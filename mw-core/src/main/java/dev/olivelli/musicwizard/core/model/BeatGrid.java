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

package dev.olivelli.musicwizard.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tracked beat positions, with downbeats marked.
 *
 * <p>Produced by beat tracking and consumed by everything that needs to speak in
 * musical time. Downbeat detection is markedly less reliable than beat
 * detection, so the two carry separate confidences and callers are expected to
 * check them independently.
 *
 * @param beats  the tracked beats, ordered in time and non-empty
 * @param beatConfidence     trust in the beat positions themselves
 * @param downbeatConfidence trust in which of those beats are downbeats
 */
public record BeatGrid(List<Beat> beats, Confidence beatConfidence, Confidence downbeatConfidence) {

    /**
     * One tracked beat.
     *
     * @param seconds    when the beat falls
     * @param downbeat   whether this beat begins a bar
     * @param positionInBar zero-based position within its bar, or -1 if unknown
     */
    public record Beat(double seconds, boolean downbeat, int positionInBar) {
        public Beat {
            if (!Double.isFinite(seconds) || seconds < 0) {
                throw new IllegalArgumentException("beat time must be finite and non-negative, got: " + seconds);
            }
            if (positionInBar < -1) {
                throw new IllegalArgumentException("positionInBar must be -1 or non-negative, got: " + positionInBar);
            }
            // A downbeat is by definition position 0 of its bar; letting the two
            // disagree would give downstream stages two contradictory answers.
            if (downbeat && positionInBar < 0) {
                throw new IllegalArgumentException(
                        "a downbeat is by definition position 0 of its bar, so positionInBar"
                                + " must not be unknown");
            }
            if (positionInBar >= 0 && downbeat != (positionInBar == 0)) {
                throw new IllegalArgumentException(
                        "downbeat=" + downbeat + " contradicts positionInBar=" + positionInBar);
            }
        }

        /** A beat whose position within the bar has not been determined. */
        public static Beat unphased(double seconds) {
            return new Beat(seconds, false, -1);
        }
    }

    public BeatGrid {
        Objects.requireNonNull(beats, "beats");
        Objects.requireNonNull(beatConfidence, "beatConfidence");
        Objects.requireNonNull(downbeatConfidence, "downbeatConfidence");
        if (beats.isEmpty()) {
            throw new IllegalArgumentException("a beat grid needs at least one beat");
        }
        beats = List.copyOf(beats);
        for (int i = 1; i < beats.size(); i++) {
            if (beats.get(i).seconds() <= beats.get(i - 1).seconds()) {
                throw new IllegalArgumentException(
                        "beats must strictly increase in time; beat " + i + " at "
                                + beats.get(i).seconds() + "s does not follow beat " + (i - 1)
                                + " at " + beats.get(i - 1).seconds() + "s");
            }
        }
    }

    /**
     * Builds a grid from bare beat times, assuming a fixed meter and a downbeat
     * on the first beat.
     *
     * <p>Prefer {@link #ofTimes(List, TimeSignature, Confidence)}: the count
     * wanted here is tracked pulses per bar, which is the meter's numerator only
     * in simple time. In 6/8 it is two, and passing six phases the downbeats
     * three times too slowly.
     *
     * @param beatsPerBar tracked pulses in one bar, not the meter's numerator
     */
    public static BeatGrid ofTimes(List<Double> beatSeconds, int beatsPerBar, Confidence confidence) {
        Objects.requireNonNull(beatSeconds, "beatSeconds");
        if (beatsPerBar < 1) {
            throw new IllegalArgumentException("beatsPerBar must be positive, got: " + beatsPerBar);
        }
        List<Beat> built = new ArrayList<>(beatSeconds.size());
        for (int i = 0; i < beatSeconds.size(); i++) {
            int position = i % beatsPerBar;
            built.add(new Beat(beatSeconds.get(i), position == 0, position));
        }
        return new BeatGrid(built, confidence, confidence);
    }

    /**
     * Builds a grid from bare beat times in a known meter, one tracked pulse per
     * counted beat.
     *
     * <p>The safe form of {@link #ofTimes(List, int, Confidence)}, because the
     * meter answers the question that overload leaves to the caller: 6/8 bars
     * every two pulses, not every six.
     */
    public static BeatGrid ofTimes(
            List<Double> beatSeconds, TimeSignature timeSignature, Confidence confidence) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return ofTimes(beatSeconds, timeSignature.beatsPerBar(), confidence);
    }

    /** Just the beat times. */
    public List<Double> beatTimes() {
        return beats.stream().map(Beat::seconds).toList();
    }

    /** Just the downbeat times. */
    public List<Double> downbeatTimes() {
        return beats.stream().filter(Beat::downbeat).map(Beat::seconds).toList();
    }

    /**
     * How far from the median an interval may sit and still be counted as one
     * pulse, as a share of the median.
     *
     * <p>See {@link #steadyPulseRate()}. Read as the answer to "is this one beat
     * or is it the tracker having lost the beat", which is a question about
     * shape rather than about any recording: an interval a fifth away from
     * typical is still a beat pushed or pulled, and one half again as long is a
     * beat the tracker did not emit.
     *
     * <p><b>Chosen from the middle of a plateau, not tuned to an edge of one.</b>
     * Measured over the five tier-2 benchmarks against each recording's own
     * tempo -- taken from the lag its harmonic self-similarity peaks at, which
     * is a measurement of the music rather than of the tracker -- every
     * half-width from 0.075 to 0.30 puts all four measurable ones inside 0.13%,
     * and moves the fifth by under 0.9% of its own median. <b>The plateau spans a
     * factor of four</b>, 0.30 being four times 0.075, and that ratio is the
     * evidence that this is not fitted to five files. Swept at a step of 0.0025:
     * the worst cell inside it is 0.128% on {@code gmajorblues.mp3} at 0.165, and
     * {@code bossa-cm.mp3}'s largest excursion is +0.82% at 0.145.
     *
     * <p>Both edges have a mechanism rather than a fitted value, which is the
     * other half of the same argument.
     *
     * <ul>
     *   <li><b>Below 0.0525 the band starts excluding beats that really are
     *       beats.</b> A tracked pulse population is a few percent wide on its
     *       own: from 0.05 down to 0.0425 {@code blues-shuffle-a-106bpm.mp3}
     *       keeps 494 of its 576 intervals and reads +0.56%, which is <em>worse
     *       than the median it replaces</em> at +0.44%, and below 0.0425 it
     *       degrades further -- 465 kept and +0.76% at 0.04. One step up from
     *       that run, at 0.0525, it keeps 545 and reads +0.07%.
     *   <li><b>Above 0.30 it starts admitting a mistracked stretch.</b>
     *       {@code bossa-cm.mp3} is tracked at four thirds of its true rate
     *       (#231), so it carries a population of intervals about 4/3 of its
     *       median -- 54 of its 501 sit between 1.30x and 1.36x -- and a band
     *       reaching 1/3 lets them in: at 0.30 it is 0.17% from its median, at
     *       0.325 it is 2.3% away and at 0.35 it is 3.4% away.
     * </ul>
     */
    private static final double STEADY_BAND = 0.2;

    /**
     * Median rate of the tracked pulses, in pulses per minute.
     *
     * <p><b>How long one interval typically is, which is not the rate the grid
     * ran at.</b> Use {@link #steadyPulseRate()} to place anything counted in
     * pulses, and this only to describe a typical interval. Nothing outside
     * tests reads this today, and the reason to check before making something
     * the first reader is that {@link Score#estimatedTempo()} used to be one:
     * the median is quantised to the analysis hop, being an interval that was
     * actually observed, and a figure a bar index multiplies cannot be. See
     * {@link #steadyPulseRate()} for the measurement.
     *
     * <p>Deliberately not called a tempo. A grid holds pulses, and a pulse is a
     * quarter note only in simple time, so this is 1.5x under the quarter-note
     * tempo in 6/8 -- exactly the conflation that mis-barred compound meters in
     * the first place. Use {@link #medianTempo(TimeSignature)} for a figure
     * comparable with {@link TempoMap#tempoAtBeat(double)}.
     */
    public double medianPulseRate() {
        if (beats.size() < 2) {
            throw new IllegalStateException("cannot infer tempo from fewer than two beats");
        }
        return 60.0 / medianOf(intervals());
    }

    /**
     * Median tempo in quarter notes per minute, the unit every other tempo in the
     * model is in.
     *
     * <p>A typical interval, not a rate: see {@link #medianPulseRate()} and
     * {@link #steadyTempo(TimeSignature)}.
     */
    public double medianTempo(TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return medianPulseRate() * timeSignature.beatUnitQuarters();
    }

    /**
     * The rate the grid ran at over the pulses it tracked steadily, in pulses per
     * minute: the mean of the intervals within {@link #STEADY_BAND} of the
     * median, ignoring the rest.
     *
     * <p><b>A rate per pulse index, which is what places anything counted in
     * pulses.</b> Pulse {@code k} sits at {@code first + k * 60 / this}, and that
     * is the arithmetic a bar line, a chart cell and a metronome mark all do.
     * {@link #medianPulseRate()} answers a different question -- how long one
     * interval typically is -- and the two are equal only on an even grid.
     *
     * <p>Two things are wrong with the median here and only the first was ever
     * argued. It is not a rate. And <b>it is quantised</b>: it is one of the
     * observed intervals, and beat times come off a frame axis, so at these
     * tempos it moves in steps of about 2% and lands on the same value for
     * recordings that are not at the same tempo -- {@code gmajorblues.mp3} and
     * {@code blues-shuffle-a-106bpm.mp3} both get 105.46875 pulses a minute
     * against measured tempos of 106.0 and 105.0. A mean is not quantised,
     * because the step averages away over the intervals.
     *
     * <p><b>Why the plain mean is not the answer either, which is the whole of
     * why this is trimmed.</b> The mean interval is exactly the end-to-end rate,
     * since consecutive differences telescope, so it folds a gap where the
     * tracker missed a beat into the rate as if the music had slowed there.
     * {@code blues-shuffle-a-106bpm.mp3} holds eleven intervals longer than 1.5x
     * its median, one of them 3.79 s, and they drag the plain mean to 102.5
     * against a measured 105.0 -- which cost 28 points of the emitted chart when
     * it was tried (#200, #207). The band drops exactly those.
     *
     * <p>So this concedes neither of the two cases #205 is open for, where the
     * median and the mean each concede one. On an even 20-pulse grid at 120 BPM:
     * with one pulse dropped the doubled interval falls outside the band and the
     * answer is exactly 120, where the plain mean gives 113.68; with one spurious
     * pulse both halves fall outside and the answer is again exactly 120, where
     * the plain mean gives 126.32.
     *
     * <p>What it does not fix is a grid tracked an octave out for <em>part</em> of
     * a recording, which {@code BeatTracker.tempoOf}'s own javadoc says the
     * autocorrelation peak is prone to. Half a recording at double rate makes the
     * two rates two populations rather than a population and some outliers, and
     * the median picks the larger one where this picks whichever the median
     * lands in. Nothing here bounds that, and no benchmark exhibits it.
     *
     * @throws IllegalStateException if the grid holds fewer than two pulses,
     *                               which carry no interval to measure
     */
    public double steadyPulseRate() {
        if (beats.size() < 2) {
            throw new IllegalStateException("cannot infer tempo from fewer than two beats");
        }
        return steadyRateOf(intervals());
    }

    /**
     * The same rate, for a caller holding pulse times but no grid yet.
     *
     * <p>Exists for one caller. The transcriber reports the rate it tracked
     * before the downbeat phase is known, so there is no grid to ask -- and
     * chroma extraction runs in between, which takes seconds on a real
     * recording, so reporting it after the grid is built would pay for the
     * overload in the one currency a progress message is for. An overload rather
     * than the arithmetic written out at the call site, because two copies of a
     * rate is how one command came to print two tempos for one recording.
     *
     * <p>The list is copied once and then validated and measured off the copy, so
     * the figure returned is the rate of times that were actually checked. What
     * is checked is what a grid demands -- finite, non-negative, strictly
     * increasing -- since a caller bypassing the grid must not bypass its
     * invariant.
     *
     * @param pulseSeconds pulse times, ordered and holding at least two
     * @throws NullPointerException     if the list or any element is null
     * @throws IllegalArgumentException if there are fewer than two, or any is not
     *                                  finite and non-negative, or they do not
     *                                  strictly increase
     */
    public static double steadyPulseRate(List<Double> pulseSeconds) {
        Objects.requireNonNull(pulseSeconds, "pulseSeconds");
        // Copied before anything is checked, and the size read once, so that what
        // is measured is what was validated. A list that changes underneath then
        // yields the rate of the snapshot, which is the only contract that can be
        // implemented: noticing that a list lied would take a second read, and a
        // list that lies once can lie twice.
        double[] times = new double[pulseSeconds.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = Objects.requireNonNull(pulseSeconds.get(i), "pulseSeconds[" + i + "]");
        }
        if (times.length < 2) {
            throw new IllegalArgumentException(
                    "cannot infer tempo from fewer than two beats, got: " + times.length);
        }
        // Finiteness before ordering, matching the order a grid checks in: Beat
        // rejects a non-finite time as each beat is built, and only the canonical
        // constructor then looks at ordering. So a list holding both faults has
        // to report the non-finite one, whichever comes first in the list.
        for (double at : times) {
            if (!Double.isFinite(at) || at < 0) {
                throw new IllegalArgumentException(
                        "beat time must be finite and non-negative, got: " + at);
            }
        }
        double[] intervals = new double[times.length - 1];
        for (int i = 0; i < intervals.length; i++) {
            if (!(times[i + 1] > times[i])) {
                throw new IllegalArgumentException(
                        "beats must strictly increase in time; beat " + (i + 1) + " at "
                                + times[i + 1] + "s does not follow beat " + i
                                + " at " + times[i] + "s");
            }
            intervals[i] = times[i + 1] - times[i];
        }
        return steadyRateOf(intervals);
    }

    /**
     * The rate the grid ran at, in quarter notes per minute -- the unit every
     * other tempo in the model is in.
     *
     * <p>What {@link Score#estimatedTempo()} answers with when nothing has
     * corrected the tracked beats. See {@link #steadyPulseRate()} for why it is
     * this rather than the median or the plain mean.
     */
    public double steadyTempo(TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return steadyPulseRate() * timeSignature.beatUnitQuarters();
    }

    /** The intervals between consecutive pulses. Never empty: the caller checks. */
    private double[] intervals() {
        double[] intervals = new double[beats.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = beats.get(i + 1).seconds() - beats.get(i).seconds();
        }
        return intervals;
    }

    /** Sorts in place and returns the median. */
    private static double medianOf(double[] intervals) {
        java.util.Arrays.sort(intervals);
        int middle = intervals.length / 2;
        return intervals.length % 2 == 1
                ? intervals[middle]
                : (intervals[middle - 1] + intervals[middle]) / 2.0;
    }

    /** The one definition, so the two public forms cannot drift apart. */
    private static double steadyRateOf(double[] intervals) {
        double median = medianOf(intervals);
        double low = median * (1.0 - STEADY_BAND);
        double high = median * (1.0 + STEADY_BAND);
        double total = 0.0;
        int kept = 0;
        for (double interval : intervals) {
            if (interval >= low && interval <= high) {
                total += interval;
                kept++;
            }
        }
        // An even count takes the median between the two middle intervals, and
        // those two can both fall outside the band around their own average --
        // two intervals an order of magnitude apart do. There is nothing to
        // average then, so the median answers, which is the figure the band was
        // drawn around.
        return kept == 0 ? 60.0 / median : 60.0 * kept / total;
    }

    /** Index of the beat nearest a given time. */
    public int nearestBeatIndex(double seconds) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < beats.size(); i++) {
            double distance = Math.abs(beats.get(i).seconds() - seconds);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            } else {
                // Times are sorted, so once distance grows we are past the nearest.
                break;
            }
        }
        return best;
    }

    public int size() {
        return beats.size();
    }
}
