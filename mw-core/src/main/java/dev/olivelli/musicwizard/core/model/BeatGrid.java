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
     * Median rate of the tracked pulses, in pulses per minute.
     *
     * <p><b>The typical length of one interval, which is not the rate the grid
     * ran at.</b> The two coincide only on an even grid, and the difference is
     * the whole of #200: use {@link #overallPulseRate()} to place anything
     * counted in pulses, and this only to describe what one pulse typically
     * lasts. <b>Nothing outside tests reads this today</b>, and the reason to
     * check before making something the first is that it used to be read by
     * {@link Score#estimatedTempo()}, where being 1.4% out put the twenty-sixth
     * chord of a real recording in the wrong bar.
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
        double[] intervals = new double[beats.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = beats.get(i + 1).seconds() - beats.get(i).seconds();
        }
        java.util.Arrays.sort(intervals);
        int middle = intervals.length / 2;
        double median = intervals.length % 2 == 1
                ? intervals[middle]
                : (intervals[middle - 1] + intervals[middle]) / 2.0;
        return 60.0 / median;
    }

    /**
     * Median tempo in quarter notes per minute, the unit every other tempo in the
     * model is in.
     *
     * <p>The typical interval, not the rate: see {@link #medianPulseRate()} and
     * {@link #overallTempo(TimeSignature)}.
     */
    public double medianTempo(TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return medianPulseRate() * timeSignature.beatUnitQuarters();
    }

    /**
     * The rate the grid actually ran at, in pulses per minute: its whole span
     * divided by the number of pulses in it.
     *
     * <p><b>A rate per pulse index, which is what places anything counted in
     * pulses.</b> Pulse {@code k} is at {@code first + k * 60 / this}, and that
     * is exactly the arithmetic a bar line, a chart cell or a metronome does.
     * {@link #medianPulseRate()} answers a different question -- how long one
     * interval typically is -- and the two are equal only on an even grid.
     *
     * <p>The difference is not academic. On {@code samples/gmajorblues.mp3} the
     * beat tracker emits 1281 pulses over 711s, and the median interval of ten
     * of its twelve hundred-pulse windows is the same 0.563084s -- the other two
     * give 0.568889s -- because the tracker scores its pulses against one
     * periodic template and the median mostly reports that template back. The
     * pulses themselves keep up with the recording by shortening and lengthening
     * around it, and end to end they run at 0.555211s: 1.4% faster than the
     * template says. Spacing bar lines at the median therefore walks away from
     * the beats it is spacing -- over that grid's 320 downbeats it reaches 10.0s
     * of drift against 2.2s, an rms of 5.4s against 0.93s (#200).
     *
     * <p><b>What is given up, on a short grid.</b> {@link #medianPulseRate()} is
     * unmoved by a dropped or spurious pulse and this is not, because it is
     * exactly the mean interval -- the differences telescope. A dropped pulse
     * leaves the span alone and takes one interval off the count, so it costs one
     * part in {@code size() - 1} and the cost falls as the grid grows: measured,
     * 20% on a 6-pulse clip, 9.1% on 12, 5.3% on 20, 0.50% on 200, and 0.08% on
     * the 1281-pulse grid above -- against the 1.4% bias it removes there. So
     * this is the right answer for a grid long enough to place anything by, and
     * the trade is genuinely open on a clip of a few seconds.
     *
     * <p><b>What is given up whatever the length.</b> A grid that is tracked an
     * octave out for <em>part</em> of a recording, which
     * {@code BeatTracker.tempoOf}'s own javadoc says the autocorrelation peak is
     * prone to, moves this by the affected fraction and does not move the median
     * at all: 80 intervals of 0.5s followed by 40 of 0.25s give a median of 120
     * BPM, right for four fifths of the duration, and a rate of 144, right
     * nowhere. That cost is the affected fraction of the duration whatever the
     * grid's length, so unlike the dropped pulse it does not shrink, and nothing
     * measured here bounds it in general. What it is bounded by on
     * {@code gmajorblues.mp3} is that the recording contains no such stretch:
     * <b>8 of its 1280 intervals are under 0.6x the median and the longest run
     * of consecutive ones is 2, spanning 0.563s of 710.7s.</b> That is the
     * statistic on the short side, which is the side a double-time stretch is
     * on; round 2 of review found this bounded by the longest interval instead,
     * which rules out the opposite mistake and not this one. #205 records the
     * statistic that would concede neither this nor the dropped pulse, and why
     * one recording is not enough evidence to choose it.
     *
     * @throws IllegalStateException if the grid holds fewer than two pulses,
     *                               which carry no interval to measure
     */
    public double overallPulseRate() {
        if (beats.size() < 2) {
            throw new IllegalStateException("cannot infer tempo from fewer than two beats");
        }
        double span = beats.get(beats.size() - 1).seconds() - beats.get(0).seconds();
        return 60.0 * (beats.size() - 1) / span;
    }

    /**
     * The rate the grid actually ran at, in quarter notes per minute -- the unit
     * every other tempo in the model is in.
     *
     * <p>What {@link Score#estimatedTempo()} answers with when nothing has
     * corrected the tracked beats. See {@link #overallPulseRate()} for why it is
     * this rather than the median.
     */
    public double overallTempo(TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return overallPulseRate() * timeSignature.beatUnitQuarters();
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
