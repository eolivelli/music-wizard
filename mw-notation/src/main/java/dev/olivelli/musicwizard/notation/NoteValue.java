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

/**
 * One note value as a musician names it: a note head of some denominator, plain
 * or dotted.
 *
 * <p>The unit every emitter in this package writes rhythm in. A length in
 * quarter beats is not a note value — five sixteenths is a perfectly good length
 * and no symbol has it — so the decision of which symbols a span becomes belongs
 * to {@link MetricSplitter} and is made once. What each emitter then does with
 * the answer differs only in spelling: LilyPond writes {@code 4.}, MusicXML
 * writes {@code <type>quarter</type><dot/>}, and both are this record read two
 * ways rather than two independent conversions from a double.
 *
 * <p>That matters more than it looks. The alternative — each emitter converting
 * a length to its own symbols — is the shape that produced #92, where the
 * quantizer's tuplet decision and the emitter's rhythm disagreed because each
 * derived it separately. Two emitters disagreeing about whether five sixteenths
 * is a dotted quarter tied to a sixteenth would be the same defect with the PDF
 * and the MusicXML disagreeing instead.
 *
 * @param denominator the note head, a power of two from 1 (a whole note) to
 *                    {@value LilyPondDuration#SHORTEST_DENOMINATOR}
 * @param dotted      whether a single dot lengthens it by half again
 */
record NoteValue(int denominator, boolean dotted) {

    /** Quarter notes in a whole note. */
    private static final double QUARTERS_PER_WHOLE = 4.0;

    /** How much longer a dotted value is than the value it dots. */
    private static final double DOT_FACTOR = 1.5;

    NoteValue {
        if (denominator < 1 || denominator > LilyPondDuration.SHORTEST_DENOMINATOR
                || Integer.bitCount(denominator) != 1) {
            throw new IllegalArgumentException(
                    "a note value is a power of two from 1 to "
                            + LilyPondDuration.SHORTEST_DENOMINATOR + ", got: " + denominator);
        }
    }

    /**
     * How long this value is in quarter-note beats, as written.
     *
     * <p>As <em>written</em>: inside a tuplet bracket a value sounds for less
     * than this, and that scaling is the bracket's business rather than the
     * symbol's. See {@link TupletBar}.
     */
    double quarters() {
        return QUARTERS_PER_WHOLE / denominator * (dotted ? DOT_FACTOR : 1);
    }

    /** Dots on the note head: one or none. */
    int dots() {
        return dotted ? 1 : 0;
    }

    /** The duration token LilyPond writes after a pitch: {@code 4}, {@code 8.}. */
    String lilyPondToken() {
        return denominator + (dotted ? "." : "");
    }

    /**
     * The MusicXML {@code note-type-value} for the note head, e.g. {@code
     * quarter} or {@code 16th}.
     *
     * <p>Dots are <em>not</em> part of this: MusicXML spells them as separate
     * {@code <dot/>} elements after {@code <type>}, where LilyPond folds them
     * into the same token. So {@link #dots()} carries that half and this carries
     * only the head, which is what the format asks for.
     *
     * <p>The names are the enumeration in the MusicXML 4.0 schema's {@code
     * note-type-value} simple type, not a construction from the number: the
     * ordinal suffixes are irregular ({@code 16th}, {@code 32nd}, {@code 64th})
     * and only a fixed list can be right about them.
     */
    String musicXmlType() {
        return switch (denominator) {
            case 1 -> "whole";
            case 2 -> "half";
            case 4 -> "quarter";
            case 8 -> "eighth";
            case 16 -> "16th";
            case 32 -> "32nd";
            case 64 -> "64th";
            // Unreachable: the constructor admits no other power of two in
            // range. Named rather than defaulted so that raising
            // SHORTEST_DENOMINATOR fails here instead of emitting a type name
            // MusicXML does not define.
            default -> throw new IllegalStateException(
                    "no MusicXML note type for a 1/" + denominator + " note");
        };
    }
}
