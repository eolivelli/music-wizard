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

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Confidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Beat tracking by dynamic programming, after Ellis (2007).
 *
 * <p>The idea is that a good beat sequence is one where beats land on onsets
 * <em>and</em> are evenly spaced, and that the best trade-off between those two
 * can be found exactly rather than greedily. Maximise
 *
 * <pre>
 *   S(B) = sum over beats of onset strength
 *        + lambda * sum over gaps of a penalty on deviating from the period
 * </pre>
 *
 * <p>with the recursion {@code D(n) = max over m of { D(m) + lambda * P(n - m) }
 * + strength(n)} and {@code P(delta) = -(log2(delta / period))^2}. Because the
 * penalty is a function of the log ratio, being 10% fast costs the same as being
 * 10% slow, which is what keeps the tracker from drifting in one direction.
 *
 * <p>Backtracking from the best final score gives the globally optimal sequence
 * for the assumed tempo — no greedy commitment, no local minima. The cost is
 * that a single tempo is assumed, so a piece that drifts is tracked in
 * overlapping windows with the tempo re-estimated in each.
 */
public final class BeatTracker {

    /**
     * Weight of the spacing penalty against onset strength.
     *
     * <p>Ellis suggests around 1. Higher makes the tracker insist on even
     * spacing and ignore evidence; lower lets it chase every syncopation.
     */
    private static final double TIGHTNESS = 1.0;

    /** Window over which one tempo is assumed, in seconds. */
    private static final double WINDOW_SECONDS = 25.0;

    private BeatTracker() {
    }

    /** Tracked beats and the tempo they imply. */
    public record Result(List<Double> beatTimes, double beatsPerMinute, Confidence confidence) {
        public Result {
            beatTimes = List.copyOf(Objects.requireNonNull(beatTimes, "beatTimes"));
            Objects.requireNonNull(confidence, "confidence");
        }

        public boolean isEmpty() {
            return beatTimes.isEmpty();
        }
    }

    /**
     * Tracks beats across a whole recording, re-estimating tempo per window so
     * that a gradual change of pace does not break the constant-tempo
     * assumption the recursion depends on.
     */
    public static Result track(OnsetEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.length() < 16 || envelope.isFlat()) {
            return new Result(List.of(), TempoEstimator.PREFERRED_TEMPO, Confidence.UNKNOWN);
        }

        int windowFrames = (int) Math.round(WINDOW_SECONDS * envelope.frameRate());
        if (envelope.length() <= windowFrames) {
            TempoEstimator.Estimate tempo = TempoEstimator.estimate(envelope);
            List<Double> beats = trackFixedTempo(envelope, tempo.beatsPerMinute(), 0, envelope.length());
            return new Result(beats, tempoOf(beats, tempo.beatsPerMinute()),
                    Confidence.clamped(tempo.strength()));
        }

        // Half-overlapping windows; each contributes only its first half, so
        // every beat comes from a window where it sits away from the edge.
        List<Double> beats = new ArrayList<>();
        double tempoSum = 0;
        double strengthSum = 0;
        int windows = 0;
        int step = windowFrames / 2;

        for (int start = 0; start < envelope.length(); start += step) {
            int end = Math.min(envelope.length(), start + windowFrames);
            if (end - start < 16) {
                break;
            }
            TempoEstimator.Estimate tempo = TempoEstimator.estimateWindow(envelope, start, end);
            List<Double> windowBeats = trackFixedTempo(
                    envelope, tempo.beatsPerMinute(), start, end);

            double acceptUntil = (start + step) / envelope.frameRate();
            boolean lastWindow = end >= envelope.length();
            for (double beat : windowBeats) {
                if ((lastWindow || beat < acceptUntil) && isNewBeat(beats, beat, tempo)) {
                    beats.add(beat);
                }
            }
            tempoSum += tempo.beatsPerMinute();
            strengthSum += tempo.strength();
            windows++;
        }

        double meanStrength = windows > 0 ? strengthSum / windows : 0;
        double fallback = windows > 0 ? tempoSum / windows : TempoEstimator.PREFERRED_TEMPO;
        return new Result(beats, tempoOf(beats, fallback), Confidence.clamped(meanStrength));
    }

    /**
     * The tempo actually implied by the tracked beats, as the median interval.
     *
     * <p>Reported rather than the seed estimate, because the two can disagree.
     * The autocorrelation peak is prone to landing an octave out -- the signal
     * really is periodic at half and double the beat rate -- but the dynamic
     * program recovers the right beats anyway, since onset strength outvotes a
     * mistaken period once the spacing penalty allows the correct gap. Reporting
     * the seed would then contradict the very beats returned alongside it, and
     * every stage downstream reads both.
     *
     * <p>Median rather than mean so that one dropped or doubled beat does not
     * drag the answer.
     */
    private static double tempoOf(List<Double> beats, double fallback) {
        if (beats.size() < 2) {
            return fallback;
        }
        double[] intervals = new double[beats.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = beats.get(i + 1) - beats.get(i);
        }
        java.util.Arrays.sort(intervals);
        int middle = intervals.length / 2;
        double median = intervals.length % 2 == 1
                ? intervals[middle]
                : (intervals[middle - 1] + intervals[middle]) / 2.0;
        return median > 0 ? 60.0 / median : fallback;
    }

    /** Rejects a beat that would land on top of one already accepted. */
    private static boolean isNewBeat(List<Double> beats, double candidate,
                                     TempoEstimator.Estimate tempo) {
        if (beats.isEmpty()) {
            return true;
        }
        double last = beats.get(beats.size() - 1);
        return candidate - last > 0.4 * tempo.beatPeriodSeconds();
    }

    /**
     * The dynamic program itself, over one window at one tempo.
     *
     * @return beat times in seconds, in order
     */
    static List<Double> trackFixedTempo(OnsetEnvelope envelope, double beatsPerMinute,
                                        int fromFrame, int toFrame) {
        double frameRate = envelope.frameRate();
        double periodFrames = frameRate * 60.0 / beatsPerMinute;
        if (!(periodFrames >= 1)) {
            return List.of();
        }

        int length = toFrame - fromFrame;
        double[] strength = envelope.strength();

        double[] score = new double[length];
        int[] previous = new int[length];
        java.util.Arrays.fill(previous, -1);

        // Only predecessors within roughly half to double the period can be the
        // previous beat, which bounds the inner loop and keeps this O(n * window)
        // rather than O(n^2).
        int minGap = Math.max(1, (int) Math.floor(periodFrames * 0.5));
        int maxGap = (int) Math.ceil(periodFrames * 2.0);

        for (int n = 0; n < length; n++) {
            double best = Double.NEGATIVE_INFINITY;
            int bestPrevious = -1;

            int earliest = Math.max(0, n - maxGap);
            int latest = n - minGap;
            for (int m = earliest; m <= latest; m++) {
                double gap = n - m;
                double deviation = Math.log(gap / periodFrames) / Math.log(2);
                double candidate = score[m] - TIGHTNESS * deviation * deviation;
                if (candidate > best) {
                    best = candidate;
                    bestPrevious = m;
                }
            }
            if (bestPrevious < 0) {
                // No valid predecessor: this frame can only start a sequence.
                best = 0;
            }
            score[n] = best + strength[fromFrame + n];
            previous[n] = bestPrevious;
        }

        int end = 0;
        for (int n = 1; n < length; n++) {
            if (score[n] > score[end]) {
                end = n;
            }
        }

        // Backtrack, then reverse: the chain is discovered from the end.
        List<Double> reversed = new ArrayList<>();
        for (int n = end; n >= 0; n = previous[n]) {
            reversed.add((fromFrame + n) / frameRate);
            if (previous[n] < 0) {
                break;
            }
        }
        List<Double> beats = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            beats.add(reversed.get(i));
        }
        return beats;
    }

    /**
     * Assigns bar positions to tracked beats.
     *
     * <p>Downbeat detection is the weakest link in the whole pipeline, so this
     * does the honest minimum: it picks the phase whose beats carry the most
     * onset energy, which is right more often than not because bars tend to
     * start with an accent. It does not attempt to infer the meter — 4/4 is
     * assumed, since it covers the large majority of the material this tool
     * targets and guessing wrong is far more damaging than not guessing.
     */
    public static BeatGrid toBeatGrid(Result result, OnsetEnvelope envelope, int beatsPerBar) {
        Objects.requireNonNull(result, "result");
        if (result.isEmpty()) {
            throw new IllegalArgumentException("cannot build a beat grid with no beats");
        }
        if (beatsPerBar < 1) {
            throw new IllegalArgumentException("beatsPerBar must be positive, got: " + beatsPerBar);
        }

        List<Double> times = result.beatTimes();
        int bestPhase = 0;
        double bestEnergy = Double.NEGATIVE_INFINITY;
        for (int phase = 0; phase < beatsPerBar; phase++) {
            double energy = 0;
            for (int i = phase; i < times.size(); i += beatsPerBar) {
                energy += envelope.strength()[envelope.frameOf(times.get(i))];
            }
            if (energy > bestEnergy) {
                bestEnergy = energy;
                bestPhase = phase;
            }
        }

        List<BeatGrid.Beat> beats = new ArrayList<>(times.size());
        for (int i = 0; i < times.size(); i++) {
            int position = Math.floorMod(i - bestPhase, beatsPerBar);
            beats.add(new BeatGrid.Beat(times.get(i), position == 0, position));
        }
        // Downbeat phase is a weaker claim than the beats themselves, so it
        // carries its own lower confidence rather than borrowing theirs.
        return new BeatGrid(beats, result.confidence(),
                Confidence.clamped(result.confidence().value() * 0.6));
    }
}
