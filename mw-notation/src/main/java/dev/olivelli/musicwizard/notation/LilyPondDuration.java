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

package dev.olivelli.musicwizard.notation;

import java.util.Optional;

/**
 * Converts a length in quarter-note beats into the duration token LilyPond
 * writes after a pitch: {@code 4} for a quarter, {@code 8.} for a dotted eighth.
 *
 * <p>Only single note values are produced, undotted or singly dotted. Anything
 * else — a length of five sixteenths, a double-dotted value, a triplet — is
 * <em>not</em> a duration; it is a tie, and deciding where to put the tie is
 * {@link MetricSplitter}'s job because it needs the meter. So this returns an
 * empty optional rather than the nearest value, which would silently change the
 * music.
 *
 * <p>Double dots are deliberately excluded. They are legal notation but they are
 * also how a splitter that trusts them turns a seven-eighths note into a single
 * unreadable symbol where two tied notes show the beat; the tie is the better
 * answer often enough that supporting the symbol is not worth the cases where it
 * is taken.
 */
final class LilyPondDuration {

    /** Quarter notes in a whole note, which is the unit LilyPond names durations in. */
    private static final double QUARTERS_PER_WHOLE = 4.0;

    /** The shortest value named: a 64th note, or a sixteenth of a quarter. */
    static final int SHORTEST_DENOMINATOR = 64;

    /**
     * The length of the shortest value, in quarter-note beats.
     *
     * <p>Also the grid the emitter snaps onto, so that every length reaching
     * this class is a whole number of these.
     */
    static final double SHORTEST_QUARTERS = QUARTERS_PER_WHOLE / SHORTEST_DENOMINATOR;

    /** How much a dot adds: half again. */
    private static final double DOTTED = 1.5;

    private LilyPondDuration() {
    }

    /**
     * The token for a length, or empty when no single value has that length.
     *
     * @param quarters length in quarter-note beats
     */
    static Optional<String> of(double quarters) {
        return valueOf(quarters).map(NoteValue::lilyPondToken);
    }

    /**
     * The note value of a length, or empty when no single value has that length.
     *
     * <p>The format-independent form of {@link #of}, and the one every emitter
     * but the LilyPond one wants: a {@link NoteValue} is a note head and a dot
     * count, which MusicXML spells as two elements and LilyPond as one token.
     * Both spellings therefore rest on the same decision about which symbol a
     * length is, rather than on two conversions that could differ.
     *
     * @param quarters length in quarter-note beats
     */
    static Optional<NoteValue> valueOf(double quarters) {
        if (!Double.isFinite(quarters) || quarters <= 0) {
            return Optional.empty();
        }
        Optional<Integer> plain = denominatorOf(quarters);
        if (plain.isPresent()) {
            return plain.map(d -> new NoteValue(d, false));
        }
        return denominatorOf(quarters / DOTTED).map(d -> new NoteValue(d, true));
    }

    /**
     * True when a length is a single note value.
     *
     * <p>Reads better than {@code of(q).isPresent()} at the two call sites that
     * only ask the question.
     */
    static boolean isSingleValue(double quarters) {
        return of(quarters).isPresent();
    }

    /**
     * The LilyPond denominator for an undotted length, e.g. 8 for half a quarter.
     *
     * <p>Empty unless the length is exactly one of the named values: a power of
     * two from a whole note down to a 64th. The comparison is exact because
     * every length that gets here is dyadic — bar lengths are
     * {@code numerator * 4 / denominator} with a power-of-two denominator, and
     * the splitter only ever halves or thirds them, and a third of a dotted
     * value is dyadic too. A tolerance would only let a rounding error through.
     */
    private static Optional<Integer> denominatorOf(double quarters) {
        double denominator = QUARTERS_PER_WHOLE / quarters;
        int rounded = (int) denominator;
        if (rounded != denominator || rounded < 1 || rounded > SHORTEST_DENOMINATOR
                || Integer.bitCount(rounded) != 1) {
            return Optional.empty();
        }
        return Optional.of(rounded);
    }

    /**
     * A length written as a duration times a factor, which is what
     * {@code \partial} and a full-measure {@code R} need when the length is not
     * a single value: a 5/4 bar is {@code 1*5/4}, five whole notes' worth of
     * quarters over four.
     *
     * <p>Prefers the single value when there is one, because {@code \partial 4.}
     * is what a musician recognises and {@code \partial 1*3/8} is not.
     *
     * @throws IllegalArgumentException if the length is not a whole number of
     *         {@link #SHORTEST_QUARTERS}, since no duration can then express it
     */
    static String scaled(double quarters) {
        long[] fraction = wholeNoteFraction(quarters);
        return scaled(fraction[0], fraction[1]);
    }

    /**
     * A length in quarter beats as an exact fraction of a whole note, as
     * {@code {numerator, denominator}}.
     *
     * <p>The format-independent half of {@link #scaled(double)}. A pickup bar is
     * the case: LilyPond needs it as a {@code \partial} duration and MusicXML
     * needs only to know the bar is short, so the length is computed once here
     * and spelled by whichever emitter wants it spelled. Kept as a fraction
     * rather than a double because the tuplet case genuinely cannot be a double
     * — see {@link TupletBar#lengthToBarLine} — and one return type for both
     * keeps the two pickup paths from diverging.
     *
     * @throws IllegalArgumentException if the length is not a whole number of
     *         {@link #SHORTEST_QUARTERS}, since no duration can then express it
     */
    static long[] wholeNoteFraction(double quarters) {
        double units = quarters / SHORTEST_QUARTERS;
        long wholeUnits = Math.round(units);
        if (!Double.isFinite(quarters) || quarters <= 0 || wholeUnits != units) {
            throw new IllegalArgumentException(
                    "length " + quarters + " quarter beats is not a whole number of 1/"
                            + SHORTEST_DENOMINATOR + " notes and cannot be written as a duration");
        }
        return new long[] {wholeUnits, SHORTEST_DENOMINATOR};
    }

    /**
     * The same, from an exact fraction of a whole note.
     *
     * <p>The way in for a length that is not a whole number of 64ths, which is
     * what a pickup bar entering inside a triplet is: two thirds of a quarter is
     * {@code 1*1/6} and is nothing else. A double cannot be asked that question
     * — a third of a beat is not representable and the fraction that produced it
     * is not recoverable from it — so the caller passes the fraction it already
     * has. See {@link TupletBar#lengthToBarLine}.
     *
     * @param numerator   whole notes, as a fraction's numerator
     * @param denominator that fraction's denominator, positive
     */
    static String scaled(long numerator, long denominator) {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException(
                    "a length must be a positive fraction, got: " + numerator + "/" + denominator);
        }
        Optional<String> single = of(QUARTERS_PER_WHOLE * numerator / denominator);
        if (single.isPresent()) {
            return single.get();
        }
        // Reduced so the common cases read the way an engraver writes them:
        // 5/4 rather than 80/64. Both are accepted by LilyPond; only one is
        // readable in a golden file.
        long divisor = gcd(numerator, denominator);
        long reduced = numerator / divisor;
        long over = denominator / divisor;
        return over == 1 ? "1*" + reduced : "1*" + reduced + "/" + over;
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
