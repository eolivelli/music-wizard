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
 * + strength(n)} and {@code P(delta) = -(ln(delta / period))^2}. Because the
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
     * <p>Higher makes the tracker insist on even spacing and ignore evidence;
     * lower lets it chase every syncopation. This is the reference
     * implementations' value for the penalty as Ellis writes it, in natural
     * logs.
     *
     * <p><strong>It has to be read together with the base of the logarithm,
     * and that is how it was wrong.</strong> The penalty here used to be
     * {@code -(log2(gap / period))^2} weighted at 1, quoting a figure of
     * "around 1" that belongs to the natural-log form. The two differ by
     * {@code 1 / (ln 2)^2}, so the shipped penalty was <b>one forty-eighth</b>
     * of the published one — not a loose setting of the algorithm but a
     * different algorithm, which is why no constant downstream could
     * compensate.
     *
     * <p>What it cost is worth stating in the units the recursion works in.
     * The envelope is normalised to unit variance and a loud attack reads
     * several, while inserting one extra beat — two gaps of half a period
     * where there was one — cost {@code 2 * log2(1/2)^2 = 2} at the old
     * weight. So an offbeat worth two standard deviations bought its own beat,
     * and on a shuffle the swung eighth is exactly that. The tracker left the
     * grid for it and came back a beat later, and the detours that failed to
     * pair up accumulated into the 1.9% rate error of #196 — a whole extra
     * beat per twelve-bar cycle.
     *
     * <p>Both halves of that are pinned rather than described.
     * {@code BeatTrackingTest.aLouderOffbeatDoesNotBuyItselfABeat} is the
     * mechanism on a synthetic shuffle, and {@code BluesLoopIT} is the
     * consequence on the real recording: the share of intervals that are two
     * thirds of a beat rather than a beat, and the tracked rate against the
     * loop's own. Both carry their before-and-after figures.
     *
     * <p>The one thing not to read into the choice of value: it is the
     * published one and it was not tuned. A sweep over the five benchmarks of
     * {@code tools/score-samples.py} says the failure is on the low side and
     * that this is not a cliff edge, and it says no more than that — every one
     * of those recordings is a programmed loop with rigid timing, so a sweep
     * on them rewards rigidity without bound and cannot choose a value. Two of
     * the five have their best point a little below this one and two a little
     * above, by margins far smaller than the distance from the old weight.
     * Somewhere above it the tracker must stop following a human rubato, and
     * nothing here measures where.
     */
    private static final double TIGHTNESS = 100.0;

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
     * <p>Reported rather than the seed estimate, because the two can disagree:
     * the seed is one number for a window and this is what the beats in it
     * actually did. Reporting the seed would contradict the very beats returned
     * alongside it, and every stage downstream reads both.
     *
     * <p><strong>What this no longer means is that the dynamic program will
     * rescue an octave error in the seed.</strong> An earlier version of this
     * comment said it would, on the grounds that onset strength outvotes a
     * mistaken period once the spacing penalty allows the correct gap. That was
     * true only because the penalty was a forty-eighth of the published weight,
     * and it was true in only one of the two directions even then: measured on
     * clean click tracks at 90, 120 and 160 BPM, a seed at half the true rate
     * was rescued and a seed at double it was not.
     *
     * <p>The two are not mirror images, although the penalties are — halving and
     * doubling a gap both cost {@code (ln 2)^2} times the weight. What differs is
     * what the correction buys. Correcting a half-rate seed <em>adds</em> beats,
     * and each one collects an onset the seed was stepping over; correcting a
     * double-rate seed <em>removes</em> beats that were sitting between onsets
     * and were nearly free to keep.
     *
     * <p>Measured on a 120 BPM click track, as the strength a rigid grid
     * collects per beat, which is what the recursion sums: 7.06 at the true
     * period, 7.07 at twice it, 3.42 at half it, against an envelope floor of
     * −0.22 between onsets. So at the old weight, where a halving or doubling
     * cost 1.00, correcting a half-rate seed was worth 12.12 against 7.07 and
     * correcting a double-rate seed 6.06 against 6.84. That is the asymmetry,
     * and it is arithmetic rather than a property of the search window. At the
     * published weight the penalty is 48.05 and the same two corrections are
     * worth −81.97 and −40.99, so neither happens. See {@link #TIGHTNESS}, and
     * {@code BeatTrackingTest.theDynamicProgramFollowsItsSeedRatherThanFixingIt}.
     *
     * <p>An earlier version of this paragraph put the onset at 5.8, taken from a
     * single frame offset applied to every click rather than from a grid phased
     * where it collects most. All four comparisons shift with it, none of the
     * four outcomes changes, and the margins were understated — which is the
     * kind of error that survives because the conclusion it supports is right.
     *
     * <p>That is the algorithm working as designed rather than a hole opened by
     * fixing it — resolving the octave is what {@link TempoEstimator}'s
     * perceptual prior is for, and a tracker that quietly disagrees with the
     * period it was given is a tracker whose reported tempo means nothing. But
     * it does move where an octave error becomes visible, so it is worth saying
     * here rather than only where the weight is set.
     *
     * <p><strong>It is visible on one of the benchmarks, and counting it as one
     * window understates its shape.</strong> Across the five recordings' 161
     * analysis windows the seed is within 6% of the music in 133. Most of the
     * shortfall is one recording — {@code bossa-cm.mp3} is on the music in 1 of
     * its 26, the other 25 being at four thirds of it, which is #231 and is not
     * an octave error at all — and only one window in the five is: the first of
     * {@code blues-shuffle-a-106bpm.mp3}. That recording's first ten intervals
     * are consequently about two beats of the music each, and those ten are most
     * of the thirteen slips {@code tools/ScoreBeats.java} still reports for it —
     * the only benchmark whose slip count does not reach zero. So the cost of
     * this trade is small, concentrated, and is the visible residue in the one
     * row of that table that has any.
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
                double deviation = Math.log(gap / periodFrames);
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
     * Assigns bar positions to tracked beats, phasing the bars from onset
     * energy alone.
     *
     * <p>Kept for callers that have no chroma to hand, and weak for the reasons
     * {@link DownbeatEstimator} sets out: on a click track every beat carries the
     * same accent, and on real music a backbeat can carry more energy than the
     * downbeat. Prefer {@link #toBeatGrid(Result, DownbeatEstimator.Estimate)}
     * with a chroma-based estimate.
     *
     * <p>The meter is not inferred here or anywhere else — 4/4 is assumed, since
     * it covers the large majority of the material this tool targets and
     * guessing wrong is far more damaging than not guessing.
     */
    public static BeatGrid toBeatGrid(Result result, OnsetEnvelope envelope, int beatsPerBar) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(envelope, "envelope");
        requireBeats(result);
        return toBeatGrid(result,
                DownbeatEstimator.fromOnsets(result.beatTimes(), envelope, beatsPerBar));
    }

    /**
     * Assigns bar positions to tracked beats using a downbeat phase estimated
     * elsewhere.
     *
     * <p>Separating the two is what lets the phase be chosen from harmony, which
     * is a far better signal than onset energy but only exists after chroma has
     * been extracted — and chroma is extracted over these very beats. Keeping
     * the phase an input rather than something this method computes is what
     * keeps that ordering acyclic.
     */
    public static BeatGrid toBeatGrid(Result result, DownbeatEstimator.Estimate downbeat) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(downbeat, "downbeat");
        requireBeats(result);

        List<Double> times = result.beatTimes();
        int beatsPerBar = downbeat.beatsPerBar();
        List<BeatGrid.Beat> beats = new ArrayList<>(times.size());
        for (int i = 0; i < times.size(); i++) {
            int position = Math.floorMod(i - downbeat.phase(), beatsPerBar);
            beats.add(new BeatGrid.Beat(times.get(i), position == 0, position));
        }
        // Two independent doubts multiply: a phase is only as good as the beats
        // it phases, so a confident phase over shaky beats is still shaky. This
        // is also what keeps the downbeat claim from ever reading as stronger
        // than the beat claim it rests on.
        return new BeatGrid(beats, result.confidence(),
                Confidence.clamped(result.confidence().value() * downbeat.confidence().value()));
    }

    private static void requireBeats(Result result) {
        if (result.isEmpty()) {
            throw new IllegalArgumentException("cannot build a beat grid with no beats");
        }
    }
}
