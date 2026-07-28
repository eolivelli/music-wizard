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
     * <p>Provenance is carried per segment rather than per map because a single
     * map routinely holds segments of different origin. The map built for
     * {@code --tempo} is the standing example: its lead-in is
     * {@link Provenance#DERIVED}, stretched so the first tracked pulse lands on
     * a whole pulse, and only the segment after it carries the figure the user
     * {@link Provenance#SUPPLIED}. A map-level label would have to be one or the
     * other, and a reader would be back to identifying the lead-in by its
     * position -- which is the guess this replaces.
     *
     * @param startBeat    quarter-note beat at which this segment begins
     * @param startSeconds wall-clock time at which this segment begins
     * @param beatsPerMinute quarter-note tempo within this segment
     * @param provenance   where this segment's tempo came from; never null, and
     *                     {@link Provenance#UNKNOWN} for a segment read from a
     *                     score file written before provenance was carried
     */
    public record TempoSegment(
            double startBeat, double startSeconds, double beatsPerMinute, Provenance provenance) {
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
            // Normalised rather than rejected, because this is the property that
            // an existing score.json does not have. Jackson passes null for a
            // missing creator property, and rejecting it here would make every
            // file written before this change unreadable -- which is #22, the
            // breakage this project has already paid for once.
            if (provenance == null) {
                provenance = Provenance.UNKNOWN;
            }
        }

        /**
         * A segment whose origin is not recorded.
         *
         * <p>Kept so that a caller assembling a map by hand -- a test, or a
         * stage that genuinely does not know -- says {@link Provenance#UNKNOWN}
         * by omission rather than by choosing a plausible-looking origin. A
         * producer that knows should always use the four-argument form.
         */
        public TempoSegment(double startBeat, double startSeconds, double beatsPerMinute) {
            this(startBeat, startSeconds, beatsPerMinute, Provenance.UNKNOWN);
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

    /**
     * A map with one constant tempo and one time signature from the start,
     * whose tempo came from where the caller says it did.
     */
    public static TempoMap constant(
            double beatsPerMinute, TimeSignature timeSignature, Provenance provenance) {
        Objects.requireNonNull(provenance, "provenance");
        return new TempoMap(
                List.of(new TempoSegment(0.0, 0.0, beatsPerMinute, provenance)),
                List.of(new MeterChange(0, timeSignature)));
    }

    /**
     * A map with one constant tempo and one time signature from the start.
     *
     * <p>The tempo's origin is {@link Provenance#UNKNOWN}: this factory is
     * reached both by a user correction and by a fallback default, so it cannot
     * label the value itself. A producer that knows should say so through
     * {@link #constant(double, TimeSignature, Provenance)}.
     */
    public static TempoMap constant(double beatsPerMinute, TimeSignature timeSignature) {
        return constant(beatsPerMinute, timeSignature, Provenance.UNKNOWN);
    }

    /** A constant map in common time, the default assumption for popular music. */
    public static TempoMap constant(double beatsPerMinute) {
        return constant(beatsPerMinute, TimeSignature.FOUR_FOUR);
    }

    /**
     * A map with one constant tempo given in the meter's <em>counted</em> beats
     * per minute -- what a metronome shows and what a beat tracker reports --
     * rather than in quarter notes per minute.
     *
     * <p>The two agree in every x/4 meter and differ everywhere else: 120 in 6/8
     * is 120 dotted quarters, which is 180 quarter notes. This exists so that a
     * tempo somebody typed and a tempo measured from tracked pulses describe the
     * same music. Building the first with {@link #constant(double, TimeSignature)}
     * and the second with {@link #fromBeatTimes(List, TimeSignature)} silently
     * puts them 1.5x apart in compound time.
     */
    public static TempoMap constantPulse(double pulsesPerMinute, TimeSignature timeSignature) {
        return constantPulse(pulsesPerMinute, timeSignature, Provenance.UNKNOWN);
    }

    /**
     * A constant map in counted beats per minute, whose tempo came from where
     * the caller says it did.
     *
     * <p>See {@link #constantPulse(double, TimeSignature)} for what "counted"
     * means and why it is not the same as quarter notes per minute.
     */
    public static TempoMap constantPulse(
            double pulsesPerMinute, TimeSignature timeSignature, Provenance provenance) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        Objects.requireNonNull(provenance, "provenance");
        // Validated before the conversion, not after. This number comes straight
        // from --tempo, and reporting it back multiplied names a figure the user
        // never typed: a rejected 6/8 tempo of -1 was complaining about -1.5.
        if (!Double.isFinite(pulsesPerMinute) || pulsesPerMinute <= 0) {
            throw new IllegalArgumentException(
                    "pulsesPerMinute must be finite and positive, got: " + pulsesPerMinute);
        }
        double quarterBeatsPerMinute = pulsesPerMinute * timeSignature.beatUnitQuarters();
        if (!Double.isFinite(quarterBeatsPerMinute)) {
            throw new IllegalArgumentException(
                    "pulsesPerMinute " + pulsesPerMinute + " is too large to express as a tempo"
                            + " in " + timeSignature);
        }
        return constant(quarterBeatsPerMinute, timeSignature, provenance);
    }

    /**
     * Builds a tempo map from a beat grid by fitting one tempo segment per beat
     * interval, preserving the measured timing exactly.
     *
     * <p>This is the honest conversion when beats were tracked from audio: it
     * does not pretend the performance had a constant tempo.
     *
     * <p>Each tracked pulse is taken to be one beat <em>of the given meter</em>,
     * which in compound time is a dotted quarter rather than a quarter -- see
     * {@link TimeSignature#beatUnitQuarters()}. Assuming a quarter regardless
     * bars 6/8 every three pulses instead of every two. Use
     * {@link #fromBeatTimes(List, TimeSignature, double)} for a grid tracked at
     * some other pulse, such as one tracked at half tempo.
     *
     * <p>Every segment fitted to a beat interval is {@link Provenance#MEASURED};
     * the lead-in, which no interval produced, is {@link Provenance#DERIVED}.
     */
    public static TempoMap fromBeatTimes(List<Double> beatSeconds, TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return fromBeatTimes(beatSeconds, timeSignature, timeSignature.beatUnitQuarters());
    }

    /**
     * Builds a tempo map from a beat grid whose pulse is known independently of
     * the meter.
     *
     * @param pulseQuarters quarter notes spanned by one tracked pulse: 1.0 for a
     *                      quarter-note pulse, 1.5 for the dotted-quarter pulse
     *                      of compound time, 2.0 for a grid tracked at half tempo
     */
    public static TempoMap fromBeatTimes(
            List<Double> beatSeconds, TimeSignature timeSignature, double pulseQuarters) {
        Objects.requireNonNull(beatSeconds, "beatSeconds");
        Objects.requireNonNull(timeSignature, "timeSignature");
        // Bounded, not merely positive. A pulse of 1e-320 or Double.MAX_VALUE is
        // structurally legal here and then fails several frames later with a
        // message about an inconsistent segment or an infinite tempo, which
        // describes the symptom rather than the mistake. The bounds are far wider
        // than any note value the model can name -- the longest counted beat it
        // produces is a whole note, at 4.0.
        if (!Double.isFinite(pulseQuarters)
                || pulseQuarters < 1.0 / 1024 || pulseQuarters > 1024) {
            throw new IllegalArgumentException(
                    "pulseQuarters must be finite and between 1/1024 and 1024 quarter notes,"
                            + " got: " + pulseQuarters);
        }
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
        // convert. The lead-in is therefore given a whole number of pulses -- of
        // pulses rather than of quarter beats, because a whole number of quarters
        // is not a whole number of dotted quarters, and rounding to the wrong one
        // puts the first tracked pulse a third of a beat off the grid in 6/8.
        //
        // It must be at least one whenever the first beat is after t=0. The
        // alternative -- shifting the seconds axis so the first tracked beat
        // becomes the origin -- silently misaligns the entire map from the audio
        // by up to half a beat, which is worse than the problem it solves.
        int leadInPulses = 0;
        if (firstBeat > 0) {
            double ratio = firstBeat / firstInterval;
            // Guard the cast: a pathological interval (units confusion, say
            // samples for seconds) would otherwise overflow silently into a
            // nonsensical but structurally valid map.
            long rounded = Double.isFinite(ratio) ? Math.round(Math.min(ratio, 1e6)) : 1;
            leadInPulses = (int) Math.max(1, rounded);
        }

        // Written so that a quarter-note pulse multiplies by an exact 1.0 in the
        // same association order the pulse-agnostic version used, which leaves
        // every simple-meter map bit-for-bit what it was.
        List<TempoSegment> built = new ArrayList<>(beatSeconds.size() + 1);
        if (leadInPulses > 0) {
            // Stretch or squeeze the lead-in so it lands exactly on the first
            // tracked beat rather than merely close to it. The map is then
            // anchored at (beat 0, second 0) and every tracked pulse sits on a
            // whole number of pulses from the origin.
            //
            // DERIVED, not MEASURED: no beat interval was ever this long, and
            // nothing in the recording ran at this rate. It is the rate that
            // makes the anchor come out right, and a reader asking what tempo
            // was measured must not be handed it.
            built.add(new TempoSegment(
                    0, 0.0, 60.0 * leadInPulses * pulseQuarters / firstBeat, Provenance.DERIVED));
        }
        for (int i = 0; i < beatSeconds.size() - 1; i++) {
            double start = beatSeconds.get(i);
            double interval = beatSeconds.get(i + 1) - start;
            built.add(new TempoSegment(
                    (leadInPulses + i) * pulseQuarters, start,
                    60.0 * pulseQuarters / interval, Provenance.MEASURED));
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
     * is the quotient near an integer. So take {@code r} one step under a bar
     * line {@code k*L}. The exact quotient is {@code k - g/L}, where {@code g}
     * is the gap below {@code k*L}, and the question is whether that shortfall
     * clears half the step below {@code k}: anything less rounds back up to
     * {@code k} and the floor is one too many.
     *
     * <p>Write {@code L = 2^f*s} and {@code k = 2^m*t}, with {@code s} and
     * {@code t} in {@code [1,2)}. A gap below a value is one ULP of that
     * value's binade, halved exactly when the value is itself a power of two.
     * Where {@code k} is not a power of two, the shortfall {@code g/L} is at
     * least {@code 2^(m-52)/s} against a half-step of {@code 2^(m-53)}. Where
     * {@code k} is a power of two the half-step halves as well, while the
     * shortfall halves only if {@code L} is a power of two too. Either way the
     * shortfall clears the half-step by a factor of {@code 2/s}, which exceeds
     * one because {@code s} is below two -- narrowly, at the extreme: the
     * tightest legal case is a 63/64 bar, which wins by 1.6%. So the quotient
     * rounds strictly below {@code k} and never up to it. On a bar line the
     * quotient is exactly {@code k}, and above one it exceeds {@code k}, so the
     * floor cannot fall short either.
     *
     * <p>Checked as well as argued, since an argument in a comment is the thing
     * this file has learned not to trust: 274 million boundary values across
     * every legal signature, and independently 10.8 million exact-rational
     * comparisons in review, with no disagreement, and a build carrying the
     * corrections alongside confirming they never fire.
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
        return averageTempoBetween(0.0, segments.get(segments.size() - 1).startSeconds());
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
        return averageTempoBetween(0.0, totalSeconds);
    }

    /**
     * The average tempo across the piece, ignoring a lead-in built to anchor the
     * map rather than to describe the music.
     *
     * <p>Both {@link #fromBeatTimes} and a supplied-tempo override open with a
     * {@link Provenance#DERIVED} segment whose rate is whatever it took to land
     * the first tracked pulse on a whole pulse. Nothing in the recording ran at
     * that rate -- on a short clip it is several times the real tempo -- so
     * averaging it in reports a figure the music never had. That distortion is
     * the reason {@link Score#estimatedTempo()} prefers a beat grid to the map
     * at all (#69); this removes it wherever the map says which segment is the
     * artefact.
     *
     * <p>Only <em>leading</em> derived segments are skipped, and only when what
     * follows is a real span: a map that is nothing but a lead-in, or whose
     * lead-in reaches past the end of the piece, still answers from the whole of
     * itself rather than from nothing. When no segment is labelled derived --
     * every map written before #120, and every map a caller assembled by hand --
     * this is {@link #averageTempo(double)} exactly.
     *
     * @param totalSeconds the piece's duration, which bounds the final segment
     */
    public double averageTempoIgnoringLeadIn(double totalSeconds) {
        if (!Double.isFinite(totalSeconds) || totalSeconds <= 0) {
            throw new IllegalArgumentException(
                    "totalSeconds must be finite and positive, got: " + totalSeconds);
        }
        int from = 0;
        while (from < segments.size()
                && segments.get(from).provenance() == Provenance.DERIVED) {
            from++;
        }
        if (from == 0 || from == segments.size()
                || segments.get(from).startSeconds() >= totalSeconds) {
            return averageTempoBetween(0.0, totalSeconds);
        }
        return averageTempoBetween(segments.get(from).startSeconds(), totalSeconds);
    }

    /**
     * Duration-weighted mean tempo over {@code [startSeconds, endSeconds)}.
     *
     * <p>A segment straddling either bound contributes only the part inside it,
     * so the result is the mean over the window and not over whichever segments
     * happen to overlap it.
     */
    private double averageTempoBetween(double startSeconds, double endSeconds) {
        double totalBeats = 0;
        double totalSeconds = 0;
        int contributing = 0;
        double onlyTempo = 0;
        for (int i = 0; i < segments.size(); i++) {
            double segmentStart = Math.max(segments.get(i).startSeconds(), startSeconds);
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
            contributing++;
            onlyTempo = segments.get(i).beatsPerMinute();
            totalSeconds += elapsed;
            totalBeats += elapsed / segments.get(i).secondsPerBeat();
        }
        // A mean over one value is that value, and saying so returns the stored
        // double rather than a round trip through beats and back. The division
        // is not exact: a MIDI file declaring 140 stores 140.00014000014, and
        // averaging that over its own span answers 140.00014000013996 -- a
        // number the file does not contain, reported as the number it does.
        // Nothing downstream prints enough digits to see it, but "the tempo the
        // file declared" is the one thing a DECLARED segment is for. The
        // no-argument averageTempo() has always special-cased a single-segment
        // map; this makes it hold for any window landing inside one segment.
        if (contributing == 1) {
            return onlyTempo;
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
