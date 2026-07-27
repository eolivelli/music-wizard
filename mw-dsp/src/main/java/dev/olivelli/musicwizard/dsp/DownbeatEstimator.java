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
 * <p>Onset energy is kept as a weak second term rather than discarded, because
 * there are signals where the accent genuinely does mark the bar and dropping it
 * would trade one blind spot for another. It is bounded rather than weighted:
 * the onset term moves a phase's score by at most {@code ONSET_WEIGHT} either
 * way, so a phase whose mean harmonic novelty leads by more than twice that
 * cannot be overturned by any accent, however loud. Below that margin the
 * harmony is not distinguishing the phases and the accent decides. On a
 * one-chord passage the harmonic differences are a thousandth of a cosine
 * distance and the accent always decides; on a chord change per bar they are
 * tenths and it never does.
 *
 * <p>Confidence follows the same ordering, and asks three separate questions of
 * the harmony rather than one: did it decide the phase, does it prefer that
 * phase over the alternatives, and was there enough of it to mean anything. A
 * margin alone is not enough — material that changes chord often at no
 * consistent phase produces a wide margin by luck — so the three are multiplied
 * and any one of them failing brings the number down. A phase resting on onsets
 * alone is capped below anything harmony has backed.
 *
 * <p>The meter is assumed, never inferred; see {@link BeatTracker#toBeatGrid}.
 */
public final class DownbeatEstimator {

    /**
     * The bound on what the onset term can add to or take from a phase's score.
     *
     * <p>A bound rather than a scaling, which is what makes the balance between
     * the two terms something that can be stated rather than tuned: the onset
     * term stays strictly inside this much either way, so a phase whose mean
     * harmonic novelty leads by more than {@code 2 * ONSET_WEIGHT} wins whatever
     * the onsets say. Below that margin the harmony is not distinguishing the
     * phases and the accent is allowed to decide.
     */
    private static final double ONSET_WEIGHT = 0.05;

    /**
     * The onset advantage, in standard deviations, at which the term is about
     * three quarters of the way to its bound.
     *
     * <p>Standard deviations because that is the unit {@link OnsetEnvelope}
     * reports in: it is normalised to zero mean and unit variance, so the
     * difference between one phase's mean strength and the average beat's needs
     * no dividing to be interpretable. Dividing would be unsafe here in any case
     * — a mean-zero envelope makes the divisor's sign arbitrary and its
     * magnitude unbounded.
     *
     * <p>A soft knee rather than a cutoff, because the values being measured are
     * strengths sampled <em>at beats</em>, which are peaks rather than a random
     * sample of frames, and their per-phase spread runs from about 0.02 to 1.9
     * deviations across the fixtures here. A hard clamp anywhere in that range
     * would flatten two genuinely different accents onto the same score and
     * hand the tie to whichever phase happens to come first; {@code tanh} is
     * strictly monotone, so it bounds the term without ever losing the ordering.
     */
    private static final double ONSET_FULL_SCALE = 1.0;

    /**
     * The harmonic margin over the runner-up above which the answer was decided
     * by harmony alone.
     *
     * <p>Not chosen: it is the margin above which the onset term provably cannot
     * change the winner, since that term stays strictly inside
     * {@code ±ONSET_WEIGHT}.
     *
     * <p>This says <em>who</em> decided, not <em>how well</em>. It is used as one
     * factor of the confidence and not as the whole of it, because a large margin
     * is easy to come by on material that changes chord often at no consistent
     * phase — the margin is then real and the phase is still a guess.
     */
    private static final double CONFIDENT_MARGIN = 2 * ONSET_WEIGHT;

    /**
     * Bars that must be observed before harmonic evidence is taken at full
     * strength.
     *
     * <p>Two, because one bar cannot establish that anything recurs: a single
     * chord change lands on some phase by necessity, and reading that as a
     * preference would be reading the only datum as a pattern.
     */
    private static final int BARS_FOR_FULL_CONFIDENCE = 2;

    /**
     * Confidence in a phase nothing at all supports.
     *
     * <p>Not zero: one phase in four is right a quarter of the time by chance,
     * and the bars have to start somewhere. Low enough to read as "correct this
     * by hand if it matters", which for a coin-flip phase it is.
     */
    private static final double BASE_CONFIDENCE = 0.35;

    /** What harmony agreeing with the chosen phase is worth on top of that. */
    private static final double HARMONIC_CONFIDENCE = 0.5;

    /**
     * What an onset accent on the chosen phase is worth on top of that.
     *
     * <p>A fifth of what harmony is worth, so that a phase resting on onsets
     * alone can never report more than {@code 0.45} — below anything harmony has
     * backed. That ordering is the point: the onset heuristic is the one that
     * produced the bug, and it must not be able to sound sure of itself.
     */
    private static final double ONSET_CONFIDENCE = 0.1;

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

        // Novelty is only defined where a beat has a chroma span on both sides,
        // so the first and last beats are out of scope. Both terms are then
        // scored over that same set of beats, so neither is measured over
        // indices the other could not see.
        int firstBeat = 1;
        int lastBeat = beatTimes.size() - 2;
        if (lastBeat < firstBeat) {
            // Too few beats for any beat to have harmony on both sides. Checked
            // before the chroma is validated, because Chroma.beatSynchronous
            // cannot produce a beat-synchronous chroma from fewer than two beats
            // and would otherwise make a one-beat recording throw rather than
            // fall back.
            return fromOnsets(beatTimes, envelope, beatsPerBar);
        }
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

        // Zero for a phase with no beats in range, because no observed novelty is
        // exactly what it says: nothing changed there that we saw.
        double[] harmony = meanPerPhase(
                harmonicNovelty(chroma), firstBeat, lastBeat, beatsPerBar, 0);
        double[] accent = onsetAdvantage(
                onsetStrengthPerBeat(beatTimes, envelope), firstBeat, lastBeat, beatsPerBar);

        double[] score = new double[beatsPerBar];
        for (int phase = 0; phase < beatsPerBar; phase++) {
            score[phase] = harmony[phase] + onsetTerm(accent[phase]);
        }

        int phase = argMax(score);
        int scorableBeats = lastBeat - firstBeat + 1;
        double confidence = BASE_CONFIDENCE
                + HARMONIC_CONFIDENCE * harmonicAgreement(harmony, phase, scorableBeats)
                + ONSET_CONFIDENCE * Math.max(0, Math.tanh(accent[phase] / ONSET_FULL_SCALE));
        return new Estimate(phase, beatsPerBar, Confidence.clamped(confidence));
    }

    /**
     * How far the harmony backs the chosen phase, in {@code [0, 1]}.
     *
     * <p>Three things all have to hold before harmonic evidence is worth much,
     * and each of them fails on its own in a way the others do not catch:
     *
     * <ul>
     * <li><b>Harmony decided it.</b> The margin over the runner-up has to clear
     *     what an accent could have moved, or the phase was chosen by the onset
     *     term and cannot borrow the authority of evidence that did not pick it.
     *     Negative when the accent overrode the harmony, which is no credit.</li>
     * <li><b>Harmony prefers it.</b> The winning phase has to carry a share of
     *     all the harmonic change that beats what it would get by chance. Without
     *     this, material that changes chord often at no consistent phase produces
     *     a large margin by luck and reports full confidence in a guess — the
     *     margin is real, the phase is not.</li>
     * <li><b>There is enough of it.</b> One bar cannot establish that anything
     *     recurs.</li>
     * </ul>
     *
     * <p>Multiplied rather than averaged, so that any one of them failing takes
     * the confidence down rather than being outvoted by the other two.
     */
    private static double harmonicAgreement(double[] harmony, int phase, int scorableBeats) {
        int beatsPerBar = harmony.length;
        double margin = harmony[phase] - runnerUp(harmony, phase);
        double decided = Math.clamp(margin / CONFIDENT_MARGIN, 0, 1);

        double total = 0;
        for (double value : harmony) {
            total += value;
        }
        // Uniform novelty gives every phase 1/beatsPerBar, which is the share a
        // phase carries by chance and therefore worth nothing.
        double chance = 1.0 / beatsPerBar;
        double share = total > 0 ? harmony[phase] / total : chance;
        double preferred = beatsPerBar > 1
                ? Math.clamp((share - chance) / (1 - chance), 0, 1)
                : 1;

        double observed = Math.clamp(
                scorableBeats / (double) (BARS_FOR_FULL_CONFIDENCE * beatsPerBar), 0, 1);
        return decided * preferred * observed;
    }

    /**
     * The onset term's contribution to a score, bounded by {@link #ONSET_WEIGHT}.
     *
     * <p>{@code tanh} rather than a clamp, so that the bound never costs the term
     * its ordering: two accents that both exceed a hard cutoff would score
     * identically and the tie would go to whichever phase came first, which on a
     * signal with a genuine accent is a wrong answer arrived at by arithmetic
     * rather than by evidence.
     */
    private static double onsetTerm(double advantage) {
        return ONSET_WEIGHT * Math.tanh(advantage / ONSET_FULL_SCALE);
    }

    /**
     * Estimates the downbeat phase from onset energy alone.
     *
     * <p>For callers with no chroma to hand. This is the weak heuristic
     * described above, and its answers are capped at {@code 0.45} confidence
     * however pronounced the accent, because a pronounced accent on the wrong
     * beat is exactly how this heuristic fails. Prefer {@link #estimate}
     * wherever chroma is available.
     */
    public static Estimate fromOnsets(List<Double> beatTimes, OnsetEnvelope envelope,
                                      int beatsPerBar) {
        Objects.requireNonNull(beatTimes, "beatTimes");
        Objects.requireNonNull(envelope, "envelope");
        requireBeatsPerBar(beatsPerBar);
        requireBeats(beatTimes);

        double[] accent = onsetAdvantage(onsetStrengthPerBeat(beatTimes, envelope),
                0, beatTimes.size() - 1, beatsPerBar);
        int phase = argMax(accent);
        double confidence = BASE_CONFIDENCE
                + ONSET_CONFIDENCE * Math.max(0, Math.tanh(accent[phase] / ONSET_FULL_SCALE));
        return new Estimate(phase, beatsPerBar, Confidence.clamped(confidence));
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
            // An empty span is silence, not a chord change. Cosine is undefined
            // against a zero vector, and reading the undefined value as maximum
            // novelty would make a silent passage the most persuasive evidence
            // in the recording -- more persuasive than any real chord change,
            // which never reaches a full cosine distance of 1.
            double cosine = cosine(spans[beat - 1], spans[beat]);
            novelty[beat] = Double.isNaN(cosine) ? 0 : 1 - cosine;
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
     *
     * <p>A phase with no beats in range — possible only on a recording shorter
     * than two bars — takes {@code unobserved}, because what counts as "nothing
     * was seen here" differs between the two quantities. For novelty it is zero:
     * no change was observed. For onset strength it is the overall mean, since
     * the envelope is centred on zero and a zero there is an ordinary frame
     * rather than an absence. Using the wrong one gives an unobserved phase
     * either an edge or a handicap it did nothing to earn.
     */
    private static double[] meanPerPhase(double[] perBeat, int firstBeat, int lastBeat,
                                         int beatsPerBar, double unobserved) {
        double[] totals = new double[beatsPerBar];
        int[] counts = new int[beatsPerBar];
        for (int beat = firstBeat; beat <= lastBeat; beat++) {
            int phase = Math.floorMod(beat, beatsPerBar);
            totals[phase] += perBeat[beat];
            counts[phase]++;
        }
        for (int phase = 0; phase < beatsPerBar; phase++) {
            totals[phase] = counts[phase] > 0 ? totals[phase] / counts[phase] : unobserved;
        }
        return totals;
    }

    /**
     * How far each phase's onsets stand out from the average beat, in units of a
     * full accent.
     *
     * <p>A difference rather than a ratio, deliberately. {@link OnsetEnvelope} is
     * normalised to zero mean and unit variance, so its values are already in
     * standard deviations and dividing by their mean would divide by a quantity
     * whose sign is arbitrary and whose magnitude passes through zero — which
     * turns a faint accent into an unbounded score and puts a discontinuity in
     * the middle of the scale.
     *
     * @return one value per phase, in deviations and unbounded; bounding happens
     *     in {@link #onsetTerm}, so that ranking these against each other stays
     *     possible however large they get
     */
    private static double[] onsetAdvantage(double[] perBeat, int firstBeat, int lastBeat,
                                           int beatsPerBar) {
        double overall = mean(perBeat, firstBeat, lastBeat);
        double[] perPhase = meanPerPhase(perBeat, firstBeat, lastBeat, beatsPerBar, overall);
        double[] advantage = new double[beatsPerBar];
        for (int phase = 0; phase < beatsPerBar; phase++) {
            advantage[phase] = perPhase[phase] - overall;
        }
        return advantage;
    }

    private static double mean(double[] values, int from, int to) {
        double sum = 0;
        for (int i = from; i <= to; i++) {
            sum += values[i];
        }
        int count = to - from + 1;
        return count > 0 ? sum / count : 0;
    }

    /** The highest-scoring phase, earliest first so that a tie is not arbitrary. */
    private static int argMax(double[] score) {
        int best = 0;
        for (int phase = 1; phase < score.length; phase++) {
            if (score[phase] > score[best]) {
                best = phase;
            }
        }
        return best;
    }

    /**
     * The best score among the phases other than one.
     *
     * <p>Negative infinity when there is no other phase, which makes a one-beat
     * bar's margin infinite — correct, since there is no choice to get wrong.
     */
    private static double runnerUp(double[] score, int except) {
        double best = Double.NEGATIVE_INFINITY;
        for (int phase = 0; phase < score.length; phase++) {
            if (phase != except && score[phase] > best) {
                best = score[phase];
            }
        }
        return best;
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
        if (a.length != b.length) {
            // Chroma does not validate its row shapes, so a ragged one would
            // otherwise index off the end of the shorter row.
            return Double.NaN;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        // NaN rather than zero for a vector with no length: zero would mean
        // "orthogonal", which is the strongest claim this measure can make, and
        // the truth is that it has nothing to compare.
        return denominator > 0 ? dot / denominator : Double.NaN;
    }
}
