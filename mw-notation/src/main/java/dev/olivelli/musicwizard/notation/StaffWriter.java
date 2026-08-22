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

import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import java.util.Optional;

/**
 * Where {@link StaffLayout} sends a staff it has laid out.
 *
 * <p>The layout decisions — which bar a note falls in, where the meter cuts it,
 * which bars carry a tuplet bracket, where a tie is therefore needed — are made
 * once, by {@link StaffLayout}, and written by an implementation of this. There
 * are two: {@link StaffNotation} spells them as LilyPond and
 * {@link MusicXmlExport} spells them as MusicXML.
 *
 * <p><b>That is the whole point of the interface.</b> Two emitters that each
 * decided for themselves where a held note is cut by a bar line would be one
 * fact with two readers, which on this project has cost more than any algorithm:
 * #92 was exactly that, between the quantizer and the LilyPond emitter, and its
 * symptom was a triplet engraved as tied 64ths — correct arithmetic, wrong
 * music, in one output and not the other. A PDF and a MusicXML file that
 * disagree about a tie would be the same defect with a worse blast radius,
 * because only one of them is ever looked at.
 *
 * <p>The calls arrive in this order, which implementations may rely on:
 *
 * <pre>
 *   startStaff
 *     per bar:  startBar
 *               [tempo]        -- once, in the first bar emitted
 *               [pickup]       -- bar 0 only, when the music starts late
 *               body:  [unreadMelody] wholeBarRest
 *                      | ( [openTuplet] symbol+ [closeTuplet] )+
 *               endBar
 *   endStaff
 * </pre>
 *
 * <p>Every implementation is single-use and not thread-safe: it accumulates one
 * staff.
 */
interface StaffWriter {

    /**
     * Opens the staff.
     *
     * @param name  the part name, as an instrument label
     * @param clef  the clef, carrying any octave transposition
     * @param key   the key signature, or empty when the score claims no key —
     *              which is not the same as C major and must not be written as
     *              one, since a key signature is a claim about the music
     */
    void startStaff(String name, StaffClef clef, Optional<Key> key);

    /**
     * Opens one bar.
     *
     * @param index        the bar, counting the first as 0
     * @param meter        the signature in force in it
     * @param meterChanged whether it differs from the previous bar's, which is
     *                     when a time signature is printed
     */
    void startBar(int index, TimeSignature meter, boolean meterChanged);

    /**
     * The metronome mark, called at most once, inside the first bar.
     *
     * <p>In the beat the reader counts rather than in quarter notes: a 6/8 bar
     * is counted in dotted quarters, and a quarter-note figure printed over one
     * is a marking 50% fast.
     *
     * <p><b>The figure is an estimate and the mark has to say so</b>, with
     * {@link TempoMark#ESTIMATE} beside it in whatever this format's vocabulary
     * for a word next to a metronome mark is. That is the one part of the mark
     * this signature cannot carry, and it is the part that went wrong: #216
     * qualified {@link StaffNotation} and left {@link MusicXmlExport} printing
     * the same number as exact, so two goldens of one fixture disagreed about
     * how much to trust it. Passing a {@link TempoMark} instead would not have prevented it —
     * spelling the word is the implementation's work either way — so it is
     * written here, where an implementer reads what the callback means.
     *
     * @param unit      the note value the count is in
     * @param perMinute how many of them a minute holds
     */
    void tempo(NoteValue unit, long perMinute);

    /**
     * The opening bar is short: the music begins this far before the bar line.
     *
     * <p>As an exact fraction of a whole note rather than as a length in beats,
     * because a pickup entering inside a triplet is two thirds of a quarter and
     * is not a whole number of anything a double can hold. See
     * {@link TupletBar#lengthToBarLine}.
     */
    void pickup(long wholeNotesNumerator, long wholeNotesDenominator);

    /**
     * Opens a tuplet bracket: {@code actual} of the following values are played
     * in the time of {@code normal}.
     *
     * <p>Every {@link #symbol} until the matching {@link #closeTuplet} is inside
     * it, and its value is the value as <em>written</em>, which is longer than
     * it sounds for a triplet and shorter for a duplet.
     */
    void openTuplet(int actual, int normal);

    /** Closes the open bracket. */
    void closeTuplet();

    /**
     * One written symbol: a note, a chord, or a rest when {@code pitches} is
     * empty.
     *
     * @param pitches          what sounds, spelled — never a MIDI number, since a
     *                         spelling lost here is lost to every reader of the
     *                         output. Ordered low to high on the staff.
     * @param value            the note value as written
     * @param soundingQuarters how long it sounds, in quarter-note beats. Equal to
     *                         {@code value.quarters()} outside a bracket and
     *                         scaled by the ratio inside one
     * @param tied             whether it ties into the symbol that follows —
     *                         which may be in the next bar, or the next bracket
     */
    void symbol(List<PitchSpelling> pitches, NoteValue value, double soundingQuarters,
                boolean tied);

    /**
     * A bar nobody plays in, which shows one rest covering it whatever the meter.
     *
     * <p>Its length is the bar's, as an exact fraction of a whole note: a 3/4 bar
     * of silence is one rest of three quarters, not a dotted half rest.
     */
    void wholeBarRest(long wholeNotesNumerator, long wholeNotesDenominator);

    /**
     * The rest that follows opens a stretch of rest bars with placed words
     * over them: the melody stage looked there and found nothing (#602).
     *
     * <p>A rest under sung words and a rest where nobody sings are different
     * facts, and a page that draws them identically cannot be read — the
     * standing failure shape, landing in notation. The mark's text is
     * {@link #UNREAD_MELODY} in both formats, for the reason
     * {@link TempoMark#ESTIMATE} is shared: two spellings of one page must
     * not disagree about what they admit to. Called once per stretch, before
     * the {@code wholeBarRest} that opens it; the layout decides the
     * stretches, so the writers cannot dedupe them differently.
     */
    void unreadMelody();

    /** What {@link #unreadMelody}'s mark says, in every format. */
    String UNREAD_MELODY = "melody not read";

    /** Closes the bar. */
    void endBar();

    /** Closes the staff. */
    void endStaff();
}
