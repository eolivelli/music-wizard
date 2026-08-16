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
 * How finely a quarter note is divided by the two exports that count time in
 * whole numbers.
 *
 * <p>MusicXML measures every duration in integer divisions of a quarter note
 * and a MIDI file measures every position in integer ticks of one. The two
 * formats want the same thing for the same reason, so the number is derived
 * once here rather than twice — one figure derived twice is two figures the day
 * one of them moves, and a MIDI file and a MusicXML file of the same score that
 * disagreed about where a triplet falls would be exactly that.
 *
 * <p>What the two do with a length that is <em>not</em> a whole number of these
 * differs, and deliberately. {@link MusicXmlExport} refuses it, because a
 * measure that does not fill its meter is imported by every scorewriter without
 * complaint and everything after it lands in the wrong bar. {@link MidiExport}
 * rounds, because a tick grid is what the format is and a position off it is
 * ordinary rather than wrong — a tempo change is at a second, not at a
 * subdivision of a beat.
 */
final class ExportGrid {

    /**
     * Divisions of a quarter note.
     *
     * <p>The number has to be divisible by every note length the notation layer
     * can produce, or the arithmetic drifts and measures stop adding up.
     *
     * <p>The two constraints, and they are the tight ones:
     *
     * <ul>
     *   <li>The shortest plain value is a 64th, a sixteenth of a quarter, so 16
     *       divides it. A dotted 64th would need 32, and {@link MetricSplitter}
     *       never produces one — its shortest metric unit <em>is</em> the 64th,
     *       so there is nothing for a dot to add half of — but 32 costs nothing.
     *   <li>A tuplet step is the awkward one. {@link TupletBar} refuses a grid
     *       whose written value is shorter than a 64th, and a triplet's written
     *       64th <em>sounds</em> for two thirds of one, so the shortest sounding
     *       length is a 96th of a whole note: 24 divisions of a quarter. A
     *       duplet in compound time goes the other way and needs 32.
     * </ul>
     *
     * <p>So the least workable value is the lowest common multiple of 24 and 32,
     * which is 96. {@value} is 96 times eight, taken for headroom rather than by
     * necessity: it leaves room for a finer grid without another format change,
     * and it is small enough that a four-thousand-bar score stays far inside
     * {@code int} — and inside the fifteen bits a MIDI file header holds a
     * resolution in, which is the tighter of the two ceilings at 32767.
     *
     * <p>The argument is not left to hold on its own. {@code ExportGridTest}
     * enumerates every length the layout can produce — every meter, every grid,
     * every note value, every tuplet step and every pickup fraction — and
     * requires each to be a whole number of these <em>exactly</em>, with no
     * tolerance. That sweep is what {@link #unitsOf} then relies on.
     */
    static final int PER_QUARTER = 768;

    private ExportGrid() {
    }

    /**
     * A length in quarter-note beats as whole grid units.
     *
     * <p><b>Exact, with no tolerance.</b> A claim about IEEE arithmetic and
     * not only the mathematics — a third of a beat is not representable, but
     * scaled to grid units every length the layout can reach lands exactly;
     * the exhaustive sweep in {@code ExportGridTest} finds not one that
     * misses.
     *
     * <p>So a length that is not a whole number of units is evidence that
     * something upstream produced a position the notation layer cannot hold, and
     * the throw says so rather than rounding it away — rounding would put the
     * measure out by exactly as much as the position was wrong.
     *
     * <p><b>Positive, too.</b> A length of zero or less is not a length, and
     * {@link LilyPondDuration#wholeNoteFraction} has always refused one; this
     * did not, so a malformed {@link
     * dev.olivelli.musicwizard.arrange.QuantizedScore} — one whose grids
     * disagree with its own tempo map, which the model permits — failed on the
     * LilyPond side with a clear message and on this one much later with a
     * different one. The one class that exists so the two exports cannot
     * disagree is the last place they should.
     *
     * <p>The upper bound is a ceiling on the answer rather than on the input:
     * beyond it {@code (long) exact} would still be right and
     * {@link Math#toIntExact} would throw a bare arithmetic error naming
     * nothing. Nothing reaches it — no caller asks about more than one bar,
     * which is at most 256 quarter beats — but a diagnostic that only holds
     * while a caller behaves is not a diagnostic.
     *
     * @throws IllegalStateException if the length is not a positive whole number
     *         of units, or is too long to count
     */
    static int unitsOf(double quarters) {
        double exact = quarters * PER_QUARTER;
        if (!Double.isFinite(exact) || exact <= 0 || exact != Math.rint(exact)
                || exact > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "a length of " + quarters + " quarter beats is " + exact + " export grid"
                            + " units, which is not a positive whole number of them; the exports"
                            + " divide a quarter note " + PER_QUARTER + " ways");
        }
        return (int) exact;
    }
}
