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

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.List;

/**
 * A time signature, such as 4/4 or 6/8.
 *
 * <p>Two different questions are asked of a meter and it is worth keeping them
 * apart, because conflating them is what mis-bars compound time. <em>How much
 * music is in a bar</em> is {@link #quarterBeatsPerBar()}, and it is measured in
 * quarter notes because everything downstream of the beat grid is. <em>How that
 * bar divides into beats</em> is {@link #beatsPerBar()} and
 * {@link #beatUnitQuarters()}, and it is what a musician counts, what a beat
 * tracker pulses on, and what a beam or a bar line has to respect. 3/4 and 6/8
 * agree on the first question and disagree on the second; that disagreement is
 * the entire difference between them.
 *
 * @param numerator   beats per bar as written
 * @param denominator note value that gets one beat, as a power of two
 */
public record TimeSignature(int numerator, int denominator) {

    public static final TimeSignature FOUR_FOUR = new TimeSignature(4, 4);
    public static final TimeSignature THREE_FOUR = new TimeSignature(3, 4);
    public static final TimeSignature SIX_EIGHT = new TimeSignature(6, 8);

    public TimeSignature {
        if (numerator < 1) {
            throw new IllegalArgumentException("numerator must be positive, got: " + numerator);
        }
        if (denominator < 1 || Integer.bitCount(denominator) != 1) {
            throw new IllegalArgumentException(
                    "denominator must be a positive power of two, got: " + denominator);
        }
        // Bounded so that bar-counting loops stay cheap and because nothing
        // beyond a 64th note is musically meaningful as a beat unit.
        if (denominator > 64) {
            throw new IllegalArgumentException(
                    "denominator must be at most 64, got: " + denominator);
        }
        if (numerator > 64) {
            throw new IllegalArgumentException(
                    "numerator must be at most 64, got: " + numerator);
        }
    }

    /**
     * Quarter-note beats per bar. Note this is deliberately not {@link #numerator}:
     * 6/8 has six eighth notes but three quarter-note beats, and the rest of the
     * pipeline counts in quarter notes.
     */
    public double quarterBeatsPerBar() {
        return numerator * 4.0 / denominator;
    }

    /** True for signatures conventionally felt in compound time, such as 6/8 and 12/8. */
    @JsonIgnore
    public boolean isCompound() {
        return denominator >= 8 && numerator % 3 == 0 && numerator > 3;
    }

    /**
     * Quarter notes in one counted beat: 1.0 in 4/4 and 3/4, 1.5 -- a dotted
     * quarter -- in 6/8, 0.5 in 7/8.
     *
     * <p>This does not contradict the rule that the pipeline counts quarter-note
     * beats; it is what lets that rule survive contact with compound time. A beat
     * tracker in 6/8 emits a dotted-quarter pulse, so a stage converting tracked
     * pulses to musical time needs to know that one pulse is worth 1.5 quarters.
     * Without it six pulses become two bars of 6/8 instead of three, which is
     * arithmetically consistent and musically wrong.
     */
    public double beatUnitQuarters() {
        // Exact: the denominator is a power of two no greater than 64, so the
        // quotient is a dyadic rational a double holds without rounding. That
        // matters because beatsPerBar() * beatUnitQuarters() has to equal
        // quarterBeatsPerBar() to the last bit, or a position on the final beat
        // of a bar can round into the next bar.
        return (isCompound() ? 3 : 1) * 4.0 / denominator;
    }

    /**
     * Counted beats per bar: 4 in 4/4, 3 in 3/4, 2 in 6/8, 4 in 12/8.
     *
     * <p>Deliberately not {@link #numerator}. Passing the numerator where this is
     * wanted is the mis-barring bug in miniature: it bars 6/8 every six pulses
     * rather than every two.
     */
    public int beatsPerBar() {
        return isCompound() ? numerator / 3 : numerator;
    }

    /**
     * Tracked pulses in one bar, for a grid tracked at {@code pulseQuarters}
     * quarter notes to the pulse.
     *
     * <p>{@link #beatsPerBar()} is this for the counted beat, and exactly this
     * -- to the last bit, asserted over every legal signature rather than
     * argued. This exists separately because a grid need not be tracked at the
     * counted beat (#139): at half tempo a 4/4 bar takes two pulses rather than
     * four, and barring by the counted-beat count would mark a downbeat every
     * second bar. Anything that turns a pulse into a bar length comes here, so
     * that the conversion is written once and cannot be made two ways.
     *
     * @throws IllegalArgumentException if the pulse is not finite and positive,
     *         or does not divide this bar into a whole number of pulses -- a
     *         grid tracked at such a pulse could not say where a bar begins
     */
    public int pulsesPerBar(double pulseQuarters) {
        if (!Double.isFinite(pulseQuarters) || pulseQuarters <= 0) {
            throw new IllegalArgumentException(
                    "pulseQuarters must be finite and positive, got: " + pulseQuarters);
        }
        double pulses = quarterBeatsPerBar() / pulseQuarters;
        // Bounded before rounding, so that a pulse small enough to imply more
        // pulses than an int can count cannot wrap into a negative bar length.
        if (!(pulses <= Integer.MAX_VALUE)) {
            throw new IllegalArgumentException(
                    "a pulse of " + pulseQuarters + " quarter notes does not fit in a " + this
                            + " bar, which is " + quarterBeatsPerBar()
                            + " quarter notes: it would take " + pulses + " of them");
        }
        long whole = Math.round(pulses);
        // A tolerance rather than an exact comparison. Every pulse the model
        // itself produces divides its bar exactly -- both quantities are dyadic
        // rationals -- but a caller may pass a pulse that is not, and 4.0
        // divided by a computed 1.0/49 is 196.00000000000003.
        if (Math.abs(pulses - whole) > 1e-9 * Math.max(1.0, pulses)) {
            throw new IllegalArgumentException(
                    "a pulse of " + pulseQuarters + " quarter notes does not divide a " + this
                            + " bar, which is " + quarterBeatsPerBar()
                            + " quarter notes, into a whole number of pulses");
        }
        // Tested after the tolerance rather than before it, because a whole-bar
        // pulse carrying a bit of rounding -- 4.000000000000001 quarter notes in
        // 4/4 -- divides its bar into 0.9999999999999998 pulses, which is one
        // pulse and not none. Ordered the other way this threw, and said the bar
        // could not hold a pulse that is the bar.
        if (whole < 1) {
            throw new IllegalArgumentException(
                    "a pulse of " + pulseQuarters + " quarter notes does not fit in a " + this
                            + " bar, which is " + quarterBeatsPerBar()
                            + " quarter notes: it would take " + pulses + " of them");
        }
        return (int) whole;
    }

    /**
     * Converts a tempo in quarter notes per minute into this meter's counted
     * beats per minute -- the figure a metronome shows and the only one a reader
     * can act on.
     *
     * <p>The inverse of what {@link TempoMap#constantPulse(double, TimeSignature)}
     * does on the way in. Printing a stored tempo without it shows 180 for a 6/8
     * piece a musician counts at 120, which is a metronome marking 50% fast.
     */
    public double countedTempo(double quarterBeatsPerMinute) {
        return quarterBeatsPerMinute / beatUnitQuarters();
    }

    /**
     * How the bar divides into beats, counted in denominator units and summing to
     * {@link #numerator}: {@code [1,1,1,1]} for 4/4, {@code [3,3]} for 6/8,
     * {@code [3,3,3,3]} for 12/8.
     *
     * <p>This is the form a notation back end wants: it is what LilyPond's
     * {@code beatStructure} takes, provided the emitter also sets
     * {@code baseMoment} to one denominator unit, and it is why a 6/8 bar beams
     * as two groups of three eighths rather than as three quarters or six
     * eighths. Nothing emits it yet -- see #64, where the chord chart's LilyPond
     * output does not so much as name the meter.
     *
     * <p>Irregular meters get one beat per denominator unit rather than a
     * conventional asymmetric grouping: 7/8 is {@code [1,1,1,1,1,1,1]}, not
     * {@code [2,2,3]}. Both 2+2+3 and 3+2+2 occur in practice and nothing in the
     * model can currently tell them apart, so this declines to guess. See #62.
     */
    public List<Integer> beatStructure() {
        return List.copyOf(Collections.nCopies(beatsPerBar(), isCompound() ? 3 : 1));
    }

    @Override
    public String toString() {
        return numerator + "/" + denominator;
    }
}
