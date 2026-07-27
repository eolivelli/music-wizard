/*
 * Copyright 2026 Music Wizard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.olivelli.musicwizard.dsp;

import dev.olivelli.musicwizard.core.model.Confidence;
import java.util.List;
import java.util.Objects;

/**
 * Picks which of the tracked beats begin bars.
 *
 * <p>The beats say where the pulse is; they say nothing about where the bar
 * starts, and that phase has to be chosen from other evidence. Choosing it from
 * onset energy alone — bars tend to start with an accent — is weak in two
 * different ways. On a click track every beat carries an identical click, so the
 * choice is close to arbitrary; on real music a backbeat routinely carries
 * <em>more</em> energy than the downbeat, so the heuristic is not merely
 * uninformative but sometimes actively wrong.
 *
 * <p>Harmony is the stronger signal. Chords change preferentially at bar lines
 * in popular music, so the phase whose beats coincide with harmonic change is
 * the phase that starts the bars. That is measured here as the cosine distance
 * between the beat-synchronous chroma either side of each beat, which needs no
 * chord labels at all — only the chroma the chord stage is computed from anyway.
 * Deliberately so: scoring against decoded chords would make downbeat detection
 * depend on chord estimation, which already depends on the beats.
 *
 * <p>Onset energy is kept as a weak second term rather than discarded. The two
 * are on deliberately different scales: harmonic novelty is an absolute cosine
 * distance, so a passage that genuinely holds one chord produces near-zero
 * differences between phases and the onset term decides, while a real chord
 * change produces differences an order of magnitude larger and outvotes it. The
 * gating is a property of the measure rather than a threshold to tune.
 *
 * <p>The meter is assumed, never inferred; see {@link BeatTracker#toBeatGrid}.
 */
public final class DownbeatEstimator {

    /**
     * How much weight the onset term carries against harmonic novelty.
     *
     * <p>Small on purpose. It is scaled by a phase's relative onset advantage,
     * so a pronounced accent on one phase of four contributes a few hundredths —
     * enough to decide between phases whose harmony is indistinguishable, and
     * not nearly enough to overturn a real chord change, which moves the
     * harmonic term by 0.1 or more.
     */
    private static final double ONSET_WEIGHT = 0.05;

    /**
     * The score margin over the runner-up phase that counts as a firm answer.
     *
     * <p>In cosine-distance units: a phase whose beats carry 0.05 more mean
     * harmonic change than the next best is aligned with real chord changes
     * rather than with noise.
     */
    private static final double CONFIDENT_MARGIN = 0.05;

    private DownbeatEstimator() {
    }

    /**
     * Which beat starts a bar, and how much to trust that.
     *
     * <p>Carries the meter it was estimated against, so that a phase computed
     * for one bar length cannot be applied to another.
     *
     * @param phase       index of the first beat of a bar, within {@code [0, beatsPerBar)}
     * @param beatsPerBar the assumed bar length in beats
     * @param confidence  trust in the phase alone, not in the beats it phases
     */
    public record Estimate(int phase, int beatsPerBar, Confidence confidence) {
        public Estimate {
            Objects.requireNonNull(confidence, "confidence");
            requireBeatsPerBar(beatsPerBar);
            if (phase < 0 || phase >= beatsPerBar) {
                throw new IllegalArgumentException(
                        "phase must be within [0, " + beatsPerBar + "), got: " + phase);
            }
        }
    }

    /**
     * Estimates the downbeat phase from harmonic change, with onset energy as a
     * tie-breaker.
     *
     * @param beatTimes   the tracked beats, in seconds and ascending
     * @param chroma      beat-synchronous chroma over exactly those beats, so that
     *                    {@code chroma.frameCount() == beatTimes.size() - 1}
     * @param envelope    the onset envelope the beats were tracked from
     * @param beatsPerBar the assumed bar length; not inferred
     */
    public static Estimate estimate(List<Double> beatTimes, Chroma chroma,
                                    OnsetEnvelope envelope, int beatsPerBar) {
        Objects.requireNonNull(beatTimes, "beatTimes");
        Objects.requireNonNull(chroma, "chroma");
        Objects.requireNonNull(envelope, "envelope");
        requireBeatsPerBar(beatsPerBar);
        requireBeats(beatTimes);
        // A chroma that does not line up with these beats would score the wrong
        // spans against the wrong beats and land on a plausible-looking but
        // arbitrary phase — which is the failure this class exists to remove —
        // so it is rejected rather than tolerated.
        if (!chroma.isBeatSynchronous() || chroma.frameCount() != beatTimes.size() - 1) {
            throw new IllegalArgumentException(
                    "chroma must be beat-synchronous over these beats: expected "
                            + (beatTimes.size() - 1) + " inter-beat spans, got "
                            + chroma.frameCount()
                            + (chroma.isBeatSynchronous() ? "" : " on a fixed time grid"));
        }

        // Novelty is only defined where a beat has a chroma span on both sides,
        // so the first and last beats are out of scope. Both terms are then
        // scored over that same set of beats, so neither is measured over
        // indices the other could not see.
        int firstBeat = 1;
        int lastBeat = beatTimes.size() - 2;
        if (lastBeat < firstBeat) {
            return fromOnsets(beatTimes, envelope, beatsPerBar);
        }

        double[] harmony = meanPerPhase(harmonicNovelty(chroma), firstBeat, lastBeat, beatsPerBar);
        double[] onsets = meanPerPhase(onsetStrengthPerBeat(beatTimes, envelope),
                firstBeat, lastBeat, beatsPerBar);

        double[] score = new double[beatsPerBar];
        for (int phase = 0; phase < beatsPerBar; phase++) {
            score[phase] = harmony[phase] + ONSET_WEIGHT * relativeAdvantage(onsets, phase);
        }
        return best(score, beatsPerBar);
    }

    /**
     * Estimates the downbeat phase from onset energy alone.
     *
     * <p>For callers with no chroma to hand. This is the weak heuristic
     * described above and its answers carry correspondingly low confidence;
     * prefer {@link #estimate} wherever chroma is available.
     */
    public static Estimate fromOnsets(List<Double> beatTimes, OnsetEnvelope envelope,
                                      int beatsPerBar) {
        Objects.requireNonNull(beatTimes, "beatTimes");
        Objects.requireNonNull(envelope, "envelope");
        requireBeatsPerBar(beatsPerBar);
        requireBeats(beatTimes);

        double[] onsets = meanPerPhase(onsetStrengthPerBeat(beatTimes, envelope),
                0, beatTimes.size() - 1, beatsPerBar);
        double[] score = new double[beatsPerBar];
        for (int phase = 0; phase < beatsPerBar; phase++) {
            score[phase] = ONSET_WEIGHT * relativeAdvantage(onsets, phase);
        }
        return best(score, beatsPerBar);
    }

    /**
     * How much the harmony changes at each beat, as cosine distance between the
     * beat-synchronous chroma spans either side of it.
     *
     * <p>Cosine rather than a plain difference, so that a loud bar and a quiet
     * one holding the same chord read as no change at all.
     *
     * @return one value per beat; the first and last stay at zero, having a span
     *     on one side only
     */
    static double[] harmonicNovelty(Chroma chroma) {
        double[][] spans = chroma.vectors();
        double[] novelty = new double[spans.length + 1];
        for (int beat = 1; beat < spans.length; beat++) {
            novelty[beat] = 1 - cosine(spans[beat - 1], spans[beat]);
        }
        return novelty;
    }

    /** Onset strength sampled at each beat. */
    private static double[] onsetStrengthPerBeat(List<Double> beatTimes, OnsetEnvelope envelope) {
        double[] out = new double[beatTimes.size()];
        if (envelope.length() == 0) {
            // frameOf clamps to frame 0, which does not exist here. No onset
            // evidence at all rather than an index out of bounds.
            return out;
        }
        for (int beat = 0; beat < out.length; beat++) {
            out[beat] = envelope.strength()[envelope.frameOf(beatTimes.get(beat))];
        }
        return out;
    }

    /**
     * Averages a per-beat quantity over the beats belonging to each phase.
     *
     * <p>Mean rather than sum: the phases do not in general hold the same number
     * of beats, and a sum would quietly reward whichever one happens to have an
     * extra.
     */
    private static double[] meanPerPhase(double[] perBeat, int firstBeat, int lastBeat,
                                         int beatsPerBar) {
        double[] totals = new double[beatsPerBar];
        int[] counts = new int[beatsPerBar];
        for (int beat = firstBeat; beat <= lastBeat; beat++) {
            int phase = Math.floorMod(beat, beatsPerBar);
            totals[phase] += perBeat[beat];
            counts[phase]++;
        }
        for (int phase = 0; phase < beatsPerBar; phase++) {
            totals[phase] = counts[phase] > 0 ? totals[phase] / counts[phase] : 0;
        }
        return totals;
    }

    /**
     * How far a phase sits above the average phase, as a fraction of it.
     *
     * <p>Relative rather than absolute because onset strength is in arbitrary
     * units: what carries information is that one phase is louder than the
     * others, not how loud any of them is.
     */
    private static double relativeAdvantage(double[] perPhase, int phase) {
        double sum = 0;
        for (double value : perPhase) {
            sum += value;
        }
        double mean = sum / perPhase.length;
        return mean > 0 ? perPhase[phase] / mean - 1 : 0;
    }

    /**
     * The winning phase, with a confidence taken from how far it beat the
     * runner-up.
     *
     * <p>The margin is the honest measure here: a phase that wins by a hair on a
     * signal with no harmonic structure is a guess, and saying so is what lets a
     * user know which first downbeats are worth correcting by hand.
     */
    private static Estimate best(double[] score, int beatsPerBar) {
        int bestPhase = 0;
        for (int phase = 1; phase < beatsPerBar; phase++) {
            if (score[phase] > score[bestPhase]) {
                bestPhase = phase;
            }
        }
        double runnerUp = Double.NEGATIVE_INFINITY;
        for (int phase = 0; phase < beatsPerBar; phase++) {
            if (phase != bestPhase && score[phase] > runnerUp) {
                runnerUp = score[phase];
            }
        }
        // A one-beat bar has no runner-up and no choice to make, so there is
        // nothing uncertain about the answer.
        double margin = beatsPerBar == 1 ? CONFIDENT_MARGIN : score[bestPhase] - runnerUp;
        double agreement = Math.clamp(margin / CONFIDENT_MARGIN, 0, 1);
        return new Estimate(bestPhase, beatsPerBar, Confidence.clamped(0.4 + 0.5 * agreement));
    }

    private static void requireBeatsPerBar(int beatsPerBar) {
        if (beatsPerBar < 1) {
            throw new IllegalArgumentException("beatsPerBar must be positive, got: " + beatsPerBar);
        }
    }

    private static void requireBeats(List<Double> beatTimes) {
        if (beatTimes.isEmpty()) {
            throw new IllegalArgumentException("cannot estimate a downbeat phase with no beats");
        }
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator > 0 ? dot / denominator : 0;
    }
}
