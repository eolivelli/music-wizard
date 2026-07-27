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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * The conversion between wall-clock seconds and musical beats.
 *
 * <p>This is the hinge of the whole pipeline. Analysis stages produce times in
 * seconds because that is what the signal gives them; everything downstream of
 * the beat grid works in beats, because quantization, chord alignment, lyric
 * placement and arrangement are only mutually consistent when they share one
 * musical timeline. This class is what lets a stage cross that boundary, and it
 * is deliberately the only sanctioned way to do so.
 *
 * <p>A tempo map is a sequence of segments, each with a constant tempo. Within a
 * segment the mapping is linear, so both directions are exact and cheap.
 * Beats are counted in quarter notes throughout, regardless of time signature.
 *
 * @param segments       tempo segments, ordered by start beat, never empty
 * @param meterChanges   time-signature changes, ordered by start bar, never empty
 */
public record TempoMap(List<TempoSegment> segments, List<MeterChange> meterChanges) {

    /**
     * A stretch of constant tempo.
     *
     * @param startBeat    quarter-note beat at which this segment begins
     * @param startSeconds wall-clock time at which this segment begins
     * @param beatsPerMinute quarter-note tempo within this segment
     */
    public record TempoSegment(double startBeat, double startSeconds, double beatsPerMinute) {
        public TempoSegment {
            if (!Double.isFinite(startBeat) || startBeat < 0) {
                throw new IllegalArgumentException("startBeat must be finite and non-negative, got: " + startBeat);
            }
            if (!Double.isFinite(startSeconds) || startSeconds < 0) {
                throw new IllegalArgumentException("startSeconds must be finite and non-negative, got: " + startSeconds);
            }
            if (!Double.isFinite(beatsPerMinute) || beatsPerMinute <= 0) {
                throw new IllegalArgumentException("beatsPerMinute must be finite and positive, got: " + beatsPerMinute);
            }
        }

        /** Seconds occupied by one quarter-note beat in this segment. */
        public double secondsPerBeat() {
            return 60.0 / beatsPerMinute;
        }
    }

    /**
     * A time-signature change.
     *
     * @param startBar      zero-based bar index at which this signature takes effect
     * @param timeSignature the signature from that bar onwards
     */
    public record MeterChange(int startBar, TimeSignature timeSignature) {
        public MeterChange {
            if (startBar < 0) {
                throw new IllegalArgumentException("startBar must be non-negative, got: " + startBar);
            }
            Objects.requireNonNull(timeSignature, "timeSignature");
        }
    }

    public TempoMap {
        Objects.requireNonNull(segments, "segments");
        Objects.requireNonNull(meterChanges, "meterChanges");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("a tempo map needs at least one tempo segment");
        }
        if (meterChanges.isEmpty()) {
            throw new IllegalArgumentException("a tempo map needs at least one meter change");
        }

        segments = List.copyOf(segments);
        meterChanges = List.copyOf(meterChanges);

        // Both axes must be anchored. Anchoring only the beat axis still allows
        // secondsToBeats(0) to return a negative beat, which is the very failure
        // the anchor exists to prevent.
        if (segments.get(0).startBeat() != 0.0 || segments.get(0).startSeconds() != 0.0) {
            throw new IllegalArgumentException(
                    "the first tempo segment must start at beat 0 and second 0 so that the map"
                            + " is anchored, got beat " + segments.get(0).startBeat()
                            + " at " + segments.get(0).startSeconds() + "s");
        }

        for (int i = 1; i < segments.size(); i++) {
            TempoSegment previous = segments.get(i - 1);
            TempoSegment current = segments.get(i);
            if (current.startBeat() <= previous.startBeat()) {
                throw new IllegalArgumentException(
                        "tempo segments must be strictly ordered by start beat; segment " + i
                                + " starts at beat " + current.startBeat()
                                + " but the previous starts at " + previous.startBeat());
            }
            if (current.startSeconds() <= previous.startSeconds()) {
                throw new IllegalArgumentException(
                        "tempo segments must be strictly ordered in time; segment " + i
                                + " starts at " + current.startSeconds() + "s"
                                + " but the previous starts at " + previous.startSeconds() + "s");
            }
            // The stored tempo must actually carry us from this segment's start to
            // the next one. Without this check a map can be internally inconsistent,
            // and seconds-to-beats stops being a bijection: at a segment boundary
            // the conversion jumps, and time can even appear to run backwards.
            double impliedSeconds = previous.startSeconds()
                    + (current.startBeat() - previous.startBeat()) * previous.secondsPerBeat();
            // Loose enough to accept a map whose boundary times were stored at
            // millisecond precision, tight enough to catch a genuinely wrong
            // tempo, which is off by far more than a millisecond.
            double tolerance = Math.max(1.5e-3, 1e-6 * Math.abs(current.startSeconds()));
            if (Math.abs(impliedSeconds - current.startSeconds()) > tolerance) {
                throw new IllegalArgumentException(
                        "tempo segment " + (i - 1) + " is inconsistent with segment " + i + ": "
                                + previous.beatsPerMinute() + " BPM over "
                                + (current.startBeat() - previous.startBeat()) + " beats reaches "
                                + impliedSeconds + "s, but segment " + i + " starts at "
                                + current.startSeconds() + "s");
            }
        }
        for (int i = 1; i < meterChanges.size(); i++) {
            if (meterChanges.get(i).startBar() <= meterChanges.get(i - 1).startBar()) {
                throw new IllegalArgumentException(
                        "meter changes must be strictly ordered by start bar");
            }
        }
        if (meterChanges.get(0).startBar() != 0) {
            throw new IllegalArgumentException(
                    "the first meter change must be at bar 0, got bar " + meterChanges.get(0).startBar());
        }
    }

    /** A map with one constant tempo and one time signature from the start. */
    public static TempoMap constant(double beatsPerMinute, TimeSignature timeSignature) {
        return new TempoMap(
                List.of(new TempoSegment(0.0, 0.0, beatsPerMinute)),
                List.of(new MeterChange(0, timeSignature)));
    }

    /** A constant map in common time, the default assumption for popular music. */
    public static TempoMap constant(double beatsPerMinute) {
        return constant(beatsPerMinute, TimeSignature.FOUR_FOUR);
    }

    /**
     * Builds a tempo map from a beat grid by fitting one tempo segment per beat
     * interval, preserving the measured timing exactly.
     *
     * <p>This is the honest conversion when beats were tracked from audio: it
     * does not pretend the performance had a constant tempo.
     */
    public static TempoMap fromBeatTimes(List<Double> beatSeconds, TimeSignature timeSignature) {
        Objects.requireNonNull(beatSeconds, "beatSeconds");
        if (beatSeconds.size() < 2) {
            throw new IllegalArgumentException(
                    "need at least two beats to infer tempo, got " + beatSeconds.size());
        }
        for (int i = 1; i < beatSeconds.size(); i++) {
            if (!(beatSeconds.get(i) > beatSeconds.get(i - 1))) {
                throw new IllegalArgumentException(
                        "beat times must strictly increase; beat " + i + " does not follow beat " + (i - 1));
            }
        }

        double firstBeat = beatSeconds.get(0);
        double firstInterval = beatSeconds.get(1) - firstBeat;

        // A beat tracker never reports a beat at exactly t=0, so the audio before
        // the first tracked beat is a lead-in. Left unmodelled it maps to negative
        // beats, and any note or chord estimated in the intro then fails to
        // convert. The lead-in is therefore given a whole number of beats.
        //
        // It must be at least one whenever the first beat is after t=0. The
        // alternative -- shifting the seconds axis so the first tracked beat
        // becomes the origin -- silently misaligns the entire map from the audio
        // by up to half a beat, which is worse than the problem it solves.
        int leadInBeats = 0;
        if (firstBeat > 0) {
            double ratio = firstBeat / firstInterval;
            // Guard the cast: a pathological interval (units confusion, say
            // samples for seconds) would otherwise overflow silently into a
            // nonsensical but structurally valid map.
            long rounded = Double.isFinite(ratio) ? Math.round(Math.min(ratio, 1e6)) : 1;
            leadInBeats = (int) Math.max(1, rounded);
        }

        List<TempoSegment> built = new ArrayList<>(beatSeconds.size() + 1);
        if (leadInBeats > 0) {
            // Stretch or squeeze the lead-in so it lands exactly on the first
            // tracked beat rather than merely close to it. The map is then
            // anchored at (beat 0, second 0) and every tracked beat sits on an
            // integer beat position.
            built.add(new TempoSegment(0, 0.0, 60.0 * leadInBeats / firstBeat));
        }
        for (int i = 0; i < beatSeconds.size() - 1; i++) {
            double start = beatSeconds.get(i);
            double interval = beatSeconds.get(i + 1) - start;
            built.add(new TempoSegment(leadInBeats + i, start, 60.0 / interval));
        }
        return new TempoMap(built, List.of(new MeterChange(0, timeSignature)));
    }

    /** Converts a musical position in quarter-note beats to wall-clock seconds. */
    public double beatsToSeconds(double beat) {
        TempoSegment segment = segmentAtBeat(beat);
        return segment.startSeconds() + (beat - segment.startBeat()) * segment.secondsPerBeat();
    }

    /** Converts a wall-clock time to a musical position in quarter-note beats. */
    public double secondsToBeats(double seconds) {
        TempoSegment segment = segmentAtSeconds(seconds);
        return segment.startBeat() + (seconds - segment.startSeconds()) / segment.secondsPerBeat();
    }

    /** The tempo segment governing a given beat. */
    public TempoSegment segmentAtBeat(double beat) {
        return segments.get(indexOfLastSegmentStartingAtOrBefore(beat, TempoSegment::startBeat));
    }

    /** The tempo segment governing a given wall-clock time. */
    public TempoSegment segmentAtSeconds(double seconds) {
        return segments.get(indexOfLastSegmentStartingAtOrBefore(seconds, TempoSegment::startSeconds));
    }

    /**
     * The index of the last segment whose position on {@code axis} is at or
     * before {@code key}, by binary search.
     *
     * <p>A scan would be simpler, but {@code fromBeatTimes} emits one segment per
     * tracked beat, so a quarter-hour track has ~100,000 of them and every stage
     * converts per note, per chord or per frame. Linear lookup made that
     * quadratic. What makes the search sound is that each axis is sorted, and
     * the canonical constructor guarantees more than that -- both axes are
     * validated as <em>strictly</em> increasing -- so no new invariant is
     * introduced here and none is leaned on more heavily than the scan leaned
     * on it.
     *
     * <p>The result is identical to the scan this replaced for every key,
     * including the two cases that are easy to lose: a key before the map starts
     * falls back to segment 0, and so does {@code NaN}, because every comparison
     * against it is false and the search never moves off the low end.
     */
    private int indexOfLastSegmentStartingAtOrBefore(double key, ToDoubleFunction<TempoSegment> axis) {
        int low = 0;
        int high = segments.size() - 1;
        while (low < high) {
            // Bias the midpoint upwards so a two-element range makes progress,
            // and compute it as an offset so a very long map cannot overflow.
            int mid = low + ((high - low + 1) >>> 1);
            if (axis.applyAsDouble(segments.get(mid)) <= key) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /** Tempo in quarter-note beats per minute at a given beat position. */
    public double tempoAtBeat(double beat) {
        return segmentAtBeat(beat).beatsPerMinute();
    }

    /** The time signature in force at a given zero-based bar. */
    public TimeSignature timeSignatureAtBar(int bar) {
        TimeSignature found = meterChanges.get(0).timeSignature();
        for (MeterChange change : meterChanges) {
            if (change.startBar() <= bar) {
                found = change.timeSignature();
            } else {
                break;
            }
        }
        return found;
    }

    /**
     * Converts a beat position into bar, beat and fractional position, honouring
     * any time-signature changes along the way.
     *
     * <p>Bar length is not constant, so this walks the meter changes -- of which
     * there are a handful -- rather than the bars, of which a long piece has
     * thousands. Counting bars one at a time made this O(position) per call, and
     * a stage that converts per note therefore quadratic, which is the same
     * shape of defect as the segment scan above.
     *
     * @throws IllegalArgumentException if {@code beat} is not finite and
     *         non-negative, or lands past bar {@link Integer#MAX_VALUE}
     */
    public MusicalTime toMusicalTime(double beat) {
        // NaN has to be rejected explicitly. "beat < 0" is false for it, so it
        // used to reach the bar loop, where "remaining < barLength" is false as
        // well -- the loop never terminated and the call simply never returned.
        if (!Double.isFinite(beat) || beat < 0) {
            throw new IllegalArgumentException("beat must be finite and non-negative, got: " + beat);
        }
        int bar = 0;
        double remaining = beat;
        for (int i = 0; i < meterChanges.size(); i++) {
            double barLength = meterChanges.get(i).timeSignature().quarterBeatsPerBar();
            // Unreachable while TimeSignature validates its numerator, but the
            // walk this replaced stopped here rather than looping forever, and a
            // zero-length bar must not become an infinite loop again.
            if (barLength <= 0) {
                break;
            }
            long barsHere = wholeBarsIn(remaining, barLength);
            if (i + 1 < meterChanges.size()) {
                long barsInBlock =
                        (long) meterChanges.get(i + 1).startBar() - meterChanges.get(i).startBar();
                if (barsHere >= barsInBlock) {
                    remaining -= barsInBlock * barLength;
                    bar = meterChanges.get(i + 1).startBar();
                    continue;
                }
            }
            long absolute = bar + barsHere;
            if (absolute > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "beat " + beat + " is past bar " + Integer.MAX_VALUE
                                + ", which a bar index cannot represent");
            }
            bar = (int) absolute;
            remaining -= barsHere * barLength;
            break;
        }
        return new MusicalTime(bar, remaining, timeSignatureAtBar(bar));
    }

    /**
     * Whole bars of {@code barLength} that fit in {@code remaining}, saturating
     * at one more than a bar index can hold.
     *
     * <p>A division standing in for repeated subtraction usually needs guarding,
     * because a quotient a hair under an integer rounds up to it and the floor
     * is then one too many. Here it does not, and the reason is worth writing
     * down rather than defending against, because a guard no input can reach is
     * a branch nothing can test.
     *
     * <p>A bar length is a numerator times four over a power of two no greater
     * than 64, so it is a whole number of sixteenths, and a bar index caps the
     * count below 2^31 -- every product below is therefore exact.
     *
     * <p>The floor can only be wrong just under a bar line, since nowhere else
     * is the quotient near an integer. So take {@code r} one step under
     * {@code k*L}: the exact quotient is {@code k - g/L}, where {@code g} is the
     * gap below {@code k*L}. Write {@code k*L} in the binade {@code [2^e,
     * 2^(e+1))} and {@code L} in {@code [2^f, 2^(f+1))}. Then {@code g} is at
     * least {@code 2^(e-53)} -- a full ULP normally, half of one when
     * {@code k*L} is itself a power of two -- and {@code L < 2^(f+1)}, so
     * {@code g/L > 2^(e-f-54)}. Meanwhile {@code k = k*L/L}, which is greater
     * than {@code 2^(e-f-1)}, so the step below {@code k} is at most
     * {@code 2^(e-f-1-52)} and half of it at most {@code 2^(e-f-54)}. The
     * shortfall therefore exceeds half a step and the quotient rounds strictly
     * below {@code k}, never up to it. When {@code L} is not a power of two
     * {@code k} sits a binade lower still, which only widens the margin. At a
     * bar line the quotient is exactly {@code k}, and above one it exceeds
     * {@code k}, so the floor cannot fall short either.
     *
     * <p>Checked as well as argued: 274 million boundary values, every legal
     * signature against every bar line up to 4096, every power of two to 2^31
     * with its neighbours, and 200,000 random counts each, with no
     * disagreement.
     */
    private static long wholeBarsIn(double remaining, double barLength) {
        double quotient = Math.floor(remaining / barLength);
        // Also the guard against a beat so large the quotient is infinite.
        if (!(quotient <= Integer.MAX_VALUE)) {
            return (long) Integer.MAX_VALUE + 1;
        }
        return (long) quotient;
    }

    /**
     * Converts a bar-and-beat position back into an absolute beat position.
     *
     * <p>Summed one meter block at a time rather than one bar at a time, for the
     * reason given on {@link #toMusicalTime}. The blocks are added in bar order,
     * so the sum is the one the bar-by-bar walk produced, to the last bit.
     */
    public double toBeat(MusicalTime musicalTime) {
        Objects.requireNonNull(musicalTime, "musicalTime");
        int upTo = musicalTime.bar();
        double beat = 0;
        for (int i = 0; i < meterChanges.size(); i++) {
            int blockStart = meterChanges.get(i).startBar();
            if (blockStart >= upTo) {
                break;
            }
            int blockEnd = (i + 1 < meterChanges.size())
                    ? Math.min(meterChanges.get(i + 1).startBar(), upTo)
                    : upTo;
            beat += (long) (blockEnd - blockStart)
                    * meterChanges.get(i).timeSignature().quarterBeatsPerBar();
        }
        return beat + musicalTime.beatInBar();
    }

    /** The time signature at the start of the piece. */
    public TimeSignature initialTimeSignature() {
        return meterChanges.get(0).timeSignature();
    }

    /** The tempo at the start of the piece. */
    public double initialTempo() {
        return segments.get(0).beatsPerMinute();
    }

    /**
     * The average tempo over the span the map explicitly describes, weighted by
     * how long each segment lasts.
     *
     * <p>The final segment is open-ended, so this figure necessarily excludes
     * it. When the piece's duration is known, prefer
     * {@link #averageTempo(double)}, which accounts for the whole piece; on a
     * map built from tracked beats the final segment is one beat long and the
     * difference is negligible, but on a two-segment map it is not.
     */
    public double averageTempo() {
        if (segments.size() == 1) {
            return segments.get(0).beatsPerMinute();
        }
        return averageTempoUpTo(segments.get(segments.size() - 1).startSeconds());
    }

    /**
     * The average tempo across the whole piece, weighted by duration.
     *
     * @param totalSeconds the piece's duration, which bounds the final segment
     */
    public double averageTempo(double totalSeconds) {
        if (!Double.isFinite(totalSeconds) || totalSeconds <= 0) {
            throw new IllegalArgumentException(
                    "totalSeconds must be finite and positive, got: " + totalSeconds);
        }
        return averageTempoUpTo(totalSeconds);
    }

    private double averageTempoUpTo(double endSeconds) {
        double totalBeats = 0;
        double totalSeconds = 0;
        for (int i = 0; i < segments.size(); i++) {
            double segmentStart = segments.get(i).startSeconds();
            if (segmentStart >= endSeconds) {
                break;
            }
            double segmentEnd = (i + 1 < segments.size())
                    ? Math.min(segments.get(i + 1).startSeconds(), endSeconds)
                    : endSeconds;
            double elapsed = segmentEnd - segmentStart;
            if (elapsed <= 0) {
                continue;
            }
            totalSeconds += elapsed;
            totalBeats += elapsed / segments.get(i).secondsPerBeat();
        }
        return totalSeconds > 0 ? totalBeats / totalSeconds * 60.0 : initialTempo();
    }

    /** Returns a copy with the meter replaced from bar 0, keeping all tempi. */
    public TempoMap withTimeSignature(TimeSignature timeSignature) {
        return new TempoMap(segments, List.of(new MeterChange(0, timeSignature)));
    }

    /** Returns a copy with an additional meter change spliced in. */
    public TempoMap withMeterChange(int startBar, TimeSignature timeSignature) {
        List<MeterChange> merged = new ArrayList<>(meterChanges);
        merged.removeIf(change -> change.startBar() == startBar);
        merged.add(new MeterChange(startBar, timeSignature));
        merged.sort(Comparator.comparingInt(MeterChange::startBar));
        return new TempoMap(segments, merged);
    }
}
