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

import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exports a {@link Score} as MusicXML 4.0.
 *
 * <p><b>A sibling of {@link StaffNotation}, never a stage before it.</b>
 * LilyPond source is emitted straight from the domain model precisely because
 * the route through MusicXML and {@code musicxml2ly} is lossy, so this class is
 * not on the path to a PDF and nothing on that path may reach it. That holds by
 * construction rather than by intention: both this and {@link StaffNotation}
 * are readers of {@link StaffLayout} rather than of each other, and nothing on
 * the route to a PDF names either export. {@code ExportsAreSiblingsTest}
 * checks that mechanically — the same claim made in prose was once wrong.
 *
 * <p>Which means the two exports cannot disagree about the music. Where a held
 * note is cut by a bar line, which bars carry a tuplet bracket, which notes
 * gather into a chord and where the pickup falls are all decided once, by
 * {@link StaffLayout}; this class only spells them. See {@link StaffWriter}.
 *
 * <p><b>Written as text, with no binding library.</b> The phone links this
 * module, and Android has neither JAXB nor StAX; the text is also what a golden
 * file can describe byte for byte on every platform, which a serializer's
 * indentation is not.
 *
 * <p><b>Pitch is spelled, never computed.</b> MusicXML carries {@code <step>}
 * and {@code <alter>} explicitly, which is exactly the distinction a MIDI number
 * cannot make — 61 is both C sharp and D flat — and a spelling dropped at this
 * boundary is a spelling lost to every editor downstream. So
 * {@link PitchSpelling} is written through and the MIDI number is never
 * consulted.
 *
 * <p>What is <em>not</em> written, and why:
 *
 * <ul>
 *   <li><b>{@code <accidental>}.</b> The alteration is written as
 *       {@code <alter>}, which says what the note sounds; the accidental a
 *       reader prints follows from that and the key signature, and is what
 *       LilyPond derives from exactly the same two facts. Emitting a notated
 *       accidental as well would need this class to track which accidentals are
 *       already in force in the bar, and a second implementation of that rule
 *       is a second chance to disagree with the first.
 *   <li><b>Beams, stems, slurs, dynamics and lyrics.</b> The domain model
 *       carries none of them for a note track. A reader lays out beams from the
 *       time signature and the note values, which are both here.
 *   <li><b>Voices.</b> One voice per part, as on the LilyPond side: overlapping
 *       notes become block chords rather than separate voices. #93.
 *   <li><b>Tempo changes.</b> One metronome mark for the whole part, averaged
 *       across the tempo map, because that is what {@link StaffLayout} publishes
 *       — so a MIDI export of the same score, which writes every segment, plays
 *       a tempo this file does not print. #154.
 *   <li><b>{@code <encoding>}.</b> It carries the date the file was written,
 *       which makes a golden file fail on a day nobody changed anything.
 * </ul>
 */
public final class MusicXmlExport {

    /**
     * MusicXML divisions per quarter note, which is
     * {@link ExportGrid#PER_QUARTER} because a MIDI tick is the same figure for
     * the same reason. The derivation lives there.
     */
    static final int DIVISIONS_PER_QUARTER = ExportGrid.PER_QUARTER;

    /**
     * The MusicXML version this claims to be, in the {@code version} attribute
     * and in the DOCTYPE. A version of the format, which a reader resolving the
     * public identifier through a catalogue looks up by name.
     */
    private static final String MUSICXML_VERSION = "4.0";

    private MusicXmlExport() {
    }

    /**
     * One part, as a complete MusicXML document.
     *
     * <p>Tuplets are not represented, for the reason
     * {@link StaffNotation#toLilyPond(Score, NoteTrack)} gives: a plain
     * {@link Score} does not carry the grid its notes were snapped to, so a
     * triplet arrives as three onsets a third of a beat apart. Pass the
     * {@link QuantizedScore} instead where there is one.
     *
     * @throws IllegalArgumentException if the track is percussion, or holds a
     *         note that has not been quantized
     */
    public static String toMusicXml(Score score, NoteTrack track) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(track, "track");
        return document(score, List.of(track), TupletPlan.none());
    }

    /** The same, with the quantizer's per-bar grid honoured. */
    public static String toMusicXml(QuantizedScore quantized, NoteTrack track) {
        Objects.requireNonNull(quantized, "quantized");
        Objects.requireNonNull(track, "track");
        return document(quantized.score(), List.of(track), TupletPlan.of(quantized));
    }

    /**
     * Every part of the score that can go on a staff, as one document.
     *
     * <p>MusicXML holds a whole score where a LilyPond file in this project
     * holds one part, so this is the export a scorewriter is actually opened
     * with. The parts share a bar grid because {@link StaffLayout} takes the
     * span across the score rather than across a track — see its {@code
     * musicSpan} — so the measures line up without this class arranging it.
     *
     * <p>Percussion is left out rather than refused. It cannot be engraved on a
     * pitched staff (#95), and dropping the whole export because a drum track is
     * present would make the useful parts unreachable.
     *
     * @throws IllegalArgumentException if the score holds no engravable part, or
     *         one of them holds a note that has not been quantized
     */
    public static String toMusicXml(Score score) {
        Objects.requireNonNull(score, "score");
        return document(score, engravableTracks(score), TupletPlan.none());
    }

    /** The same, with the quantizer's per-bar grid honoured. */
    public static String toMusicXml(QuantizedScore quantized) {
        Objects.requireNonNull(quantized, "quantized");
        return document(quantized.score(), engravableTracks(quantized.score()),
                TupletPlan.of(quantized));
    }

    private static List<NoteTrack> engravableTracks(Score score) {
        List<NoteTrack> tracks = score.tracks().stream()
                .filter(track -> track.role() != PartRole.DRUMS)
                .toList();
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException(
                    "this score holds nothing that can go on a pitched staff, so there is no"
                            + " MusicXML to write; percussion needs a drum staff, see #95");
        }
        return tracks;
    }

    // -------------------------------------------------------------- document

    private static String document(Score score, List<NoteTrack> tracks, TupletPlan tuplets) {
        XmlWriter xml = new XmlWriter();
        xml.prolog("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.prolog("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML "
                + MUSICXML_VERSION + " Partwise//EN\""
                + " \"http://www.musicxml.org/dtds/partwise.dtd\">");
        xml.open("score-partwise", "version", MUSICXML_VERSION);
        score.title().ifPresent(title ->
                xml.open("work").element("work-title", title).close("work"));
        // "composer" rather than "artist": MusicXML's creator types are the
        // ones a score credits, and every reader shows composer at the top
        // right of the first page. The model's "artist" is whoever the
        // recording is by, which for a transcription is the closest thing to
        // a composer credit it has.
        score.artist().ifPresent(artist -> xml.open("identification")
                .element("creator", artist, "type", "composer")
                .close("identification"));

        xml.open("part-list");
        for (int i = 0; i < tracks.size(); i++) {
            xml.open("score-part", "id", partId(i))
                    .element("part-name", tracks.get(i).name())
                    .close("score-part");
        }
        xml.close("part-list");
        for (int i = 0; i < tracks.size(); i++) {
            xml.open("part", "id", partId(i));
            StaffLayout.write(score, tracks.get(i), tuplets,
                    new MusicXmlStaffWriter(xml, TempoMark.headline(score, 0)));
            xml.close("part");
        }
        xml.close("score-partwise");
        return xml.toString();
    }

    /** P1, P2, ...: an XML ID has to start with a letter, so the index alone would not be one. */
    private static String partId(int index) {
        return "P" + (index + 1);
    }

    /**
     * A length in quarter-note beats as whole MusicXML divisions.
     *
     * <p>Exact, with no tolerance, for the reason {@link ExportGrid#unitsOf}
     * gives: rounding a length the layout could not hold would put the measure
     * out by exactly as much as the length was wrong, and a measure that does
     * not fill its meter is imported without complaint.
     *
     * @throws IllegalStateException if the length is not a whole number of
     *         divisions
     */
    static int divisionsOf(double quarters) {
        return ExportGrid.unitsOf(quarters);
    }

    // ---------------------------------------------------------------- writer

    /**
     * Spells one laid-out staff as a MusicXML {@code <part>}.
     *
     * <p>Every decision has already been made; this writes measures. What it
     * keeps back is what the format needs later than the layout says it: a
     * measure is held until the next one starts, because its opening tag needs
     * the pickup that arrives after {@link #startBar}, and each note is held
     * until the next callback, because a tuplet bracket is stopped on its last
     * note and the layout says the bracket has closed only after that note has
     * gone by.
     */
    private static final class MusicXmlStaffWriter implements StaffWriter {

        /** The voice every note is in. One part, one voice: #93. */
        private static final String VOICE = "1";

        /** The bracket number every tuplet carries. Brackets never nest here. */
        private static final String TUPLET_NUMBER = "1";

        private final XmlWriter part;
        private final double quarterBeatsPerMinute;

        private StaffClef clef;
        private Optional<Key> key = Optional.empty();

        /** The body of the measure being written, or {@code null} before the first. */
        private XmlWriter measure;
        private int index;
        private int bars;
        private TimeSignature meter;

        /**
         * Whether bar 0 turned out to be a pickup, which shifts every measure
         * number down by one.
         *
         * <p>A pickup is measure 0 and the first full bar is measure 1, which is
         * how a musician counts and how MusicXML says to write it.
         */
        private boolean pickupBar;

        /** Divisions written into the current measure so far. */
        private int written;

        /** Divisions the current measure must hold, from its meter and pickup. */
        private int expected;

        /** The bracket in force as actual and normal note counts, or {@code null}. */
        private int[] tuplet;
        private boolean tupletHasNote;

        private PendingNote pending;

        /** Whether the note written next is the far end of a tie. */
        private boolean tiedFromPrevious;

        MusicXmlStaffWriter(XmlWriter part, double quarterBeatsPerMinute) {
            this.part = part;
            this.quarterBeatsPerMinute = quarterBeatsPerMinute;
        }

        @Override
        public void startStaff(String name, StaffClef staffClef, Optional<Key> staffKey) {
            this.clef = staffClef;
            this.key = staffKey;
        }

        @Override
        public void startBar(int barIndex, TimeSignature barMeter, boolean meterChanged) {
            emitMeasure();
            measure = part.child();
            index = barIndex;
            written = 0;
            expected = divisionsOf(barMeter.quarterBeatsPerBar());
            boolean first = bars == 0;
            bars++;
            if (first || meterChanged) {
                measure.open("attributes");
                if (first) {
                    measure.element("divisions", String.valueOf(DIVISIONS_PER_QUARTER));
                    keySignature();
                }
                timeSignature(barMeter);
                if (first) {
                    clefElement();
                }
                measure.close("attributes");
            }
            meter = barMeter;
        }

        @Override
        public void tempo(NoteValue unit, long perMinute) {
            measure.open("direction", "placement", "above");
            // The qualifier first, as its own direction-type -- the same word
            // LilyPond prints, from the same constant: this file and the .ly
            // are two spellings of one page, and a reader engraving this one
            // would otherwise state as exact the figure the PDF marks as an
            // estimate.
            measure.open("direction-type")
                    .element("words", TempoMark.ESTIMATE)
                    .close("direction-type");
            measure.open("direction-type").open("metronome")
                    .element("beat-unit", unit.musicXmlType());
            for (int i = 0; i < unit.dots(); i++) {
                measure.empty("beat-unit-dot");
            }
            measure.element("per-minute", String.valueOf(perMinute))
                    .close("metronome").close("direction-type");
            // <sound tempo> is quarter notes a minute by definition, whatever
            // unit the mark is printed in -- which is the trap the printed mark
            // exists to avoid in the other direction. Taken from the model's own
            // figure rather than reconstructed from the rounded mark, so that a
            // 6/8 score plays at the tempo it was transcribed at rather than at
            // whatever three times a rounded dotted-quarter count comes to.
            measure.empty("sound", "tempo", String.valueOf(Math.round(quarterBeatsPerMinute)));
            measure.close("direction");
        }

        @Override
        public void unreadMelody() {
            measure.open("direction", "placement", "above")
                    .open("direction-type")
                    .element("words", UNREAD_MELODY)
                    .close("direction-type")
                    .close("direction");
        }

        @Override
        public void pickup(long wholeNotesNumerator, long wholeNotesDenominator) {
            pickupBar = true;
            // Four quarters to a whole note. The pickup is a fraction of one
            // because a pickup inside a triplet is two thirds of a quarter and
            // no double holds that; the multiplication below is exact for every
            // fraction that reaches here, since the division is the last step.
            expected = divisionsOf(4.0 * wholeNotesNumerator / wholeNotesDenominator);
        }

        @Override
        public void openTuplet(int actual, int normal) {
            flush();
            tuplet = new int[] {actual, normal};
            tupletHasNote = false;
        }

        @Override
        public void closeTuplet() {
            if (pending != null && tuplet != null) {
                pending.tupletStop = true;
            }
            flush();
            tuplet = null;
        }

        @Override
        public void symbol(List<PitchSpelling> pitches, NoteValue value, double soundingQuarters,
                           boolean tied) {
            flush();
            int duration = divisionsOf(soundingQuarters);
            pending = new PendingNote(pitches, value, duration, tiedFromPrevious, tied,
                    tuplet, tuplet != null && !tupletHasNote);
            tupletHasNote = true;
            // A chord sounds once, however many note heads it has: only the
            // first carries the measure forward, and MusicXML says the same by
            // marking the rest with <chord/>.
            written += duration;
            tiedFromPrevious = tied;
        }

        @Override
        public void wholeBarRest(long wholeNotesNumerator, long wholeNotesDenominator) {
            flush();
            int duration = divisionsOf(4.0 * wholeNotesNumerator / wholeNotesDenominator);
            // measure="yes" is the whole-bar rest: one symbol centred in the bar
            // whatever the meter, which is what LilyPond's R means too. No
            // <type>: a measure rest has no note value, and naming one would
            // claim a 5/4 bar of silence is a symbol that does not exist.
            measure.open("note")
                    .empty("rest", "measure", "yes")
                    .element("duration", String.valueOf(duration))
                    .element("voice", VOICE)
                    .close("note");
            written += duration;
        }

        @Override
        public void endBar() {
            flush();
            if (written != expected) {
                // The failure this catches is the one MusicXML readers do not:
                // a measure that does not fill its meter is imported without
                // complaint and everything after it is in the wrong place.
                throw new IllegalStateException(
                        "measure " + number() + " of " + meter + " holds " + written
                                + " divisions where it should hold " + expected
                                + "; the layout and this export disagree about its length");
            }
        }

        @Override
        public void endStaff() {
            if (tiedFromPrevious) {
                throw new IllegalStateException(
                        "the last note of this part ties into a note that was never written;"
                                + " the layout ended mid-tie");
            }
            if (measure != null) {
                // The double bar line at the end of the piece, which is what
                // LilyPond's \bar "|." draws. On the last measure only.
                measure.open("barline", "location", "right")
                        .element("bar-style", "light-heavy")
                        .close("barline");
            }
            emitMeasure();
        }

        // ------------------------------------------------------------ pieces

        /** Writes the held measure into the part, with the number the pickup decided. */
        private void emitMeasure() {
            if (measure == null) {
                return;
            }
            // "implicit" is MusicXML's word for a bar that is not counted, which
            // is what a pickup is: the reader must not print a 0 over it.
            part.open("measure", "number", number(),
                    "implicit", pickupBar && index == 0 ? "yes" : null);
            part.append(measure);
            part.close("measure");
            measure = null;
        }

        private String number() {
            return String.valueOf(pickupBar ? index : index + 1);
        }

        /** Writes the held note, one {@code <note>} per note head. */
        private void flush() {
            if (pending == null) {
                return;
            }
            PendingNote note = pending;
            pending = null;
            for (int i = 0; i < Math.max(1, note.pitches.size()); i++) {
                measure.open("note");
                if (i > 0) {
                    measure.empty("chord");
                }
                if (note.pitches.isEmpty()) {
                    measure.empty("rest");
                } else {
                    pitch(note.pitches.get(i));
                }
                measure.element("duration", String.valueOf(note.duration));
                // Both spellings of a tie, because MusicXML has two and they
                // mean different things: <tie> is what a playback engine reads
                // and <tied> is the slur a reader draws. Stop before start,
                // which is the order the schema fixes.
                if (note.tiedIn) {
                    measure.empty("tie", "type", "stop");
                }
                if (note.tiedOut) {
                    measure.empty("tie", "type", "start");
                }
                measure.element("voice", VOICE);
                measure.element("type", note.value.musicXmlType());
                for (int dot = 0; dot < note.value.dots(); dot++) {
                    measure.empty("dot");
                }
                // Every note head inside a bracket carries the modification: it
                // is what tells a reader the written value is played shorter,
                // and a reader that saw it on only the first note of a chord
                // would play the rest of the chord long.
                if (note.tuplet != null) {
                    measure.open("time-modification")
                            .element("actual-notes", String.valueOf(note.tuplet[0]))
                            .element("normal-notes", String.valueOf(note.tuplet[1]))
                            .close("time-modification");
                }
                boolean bracket = i == 0 && (note.tupletStart || note.tupletStop);
                if (note.tiedIn || note.tiedOut || bracket) {
                    measure.open("notations");
                    if (note.tiedIn) {
                        measure.empty("tied", "type", "stop");
                    }
                    if (note.tiedOut) {
                        measure.empty("tied", "type", "start");
                    }
                    if (i == 0 && note.tupletStart) {
                        measure.empty("tuplet", "type", "start", "number", TUPLET_NUMBER);
                    }
                    if (i == 0 && note.tupletStop) {
                        measure.empty("tuplet", "type", "stop", "number", TUPLET_NUMBER);
                    }
                    measure.close("notations");
                }
                measure.close("note");
            }
        }

        /**
         * The key signature.
         *
         * <p>Zero sharps when the score claims no key, which is what the
         * LilyPond side writes as {@code c \major}: no accidental is put on the
         * page that the pipeline never decided on. The {@code <mode>} is left
         * off in that case rather than guessed, since "no key was decided" and
         * "the key is C major" are different claims and only the second one
         * names a mode.
         */
        private void keySignature() {
            measure.open("key")
                    .element("fifths", String.valueOf(
                            key.map(Key::keySignatureAccidentals).orElse(0)));
            key.ifPresent(k -> measure.element("mode", k.mode().lilyPondName()));
            measure.close("key");
        }

        /**
         * The time signature.
         *
         * <p>The numerator and denominator as the model holds them.
         * {@link TimeSignature#beatStructure()} has no MusicXML equivalent that
         * a reader acts on — beam grouping there is expressed per note, by
         * {@code <beam>} elements this does not write — so a reader beams the
         * bar from the meter, which is where the grouping came from anyway.
         */
        private void timeSignature(TimeSignature signature) {
            measure.open("time")
                    .element("beats", String.valueOf(signature.numerator()))
                    .element("beat-type", String.valueOf(signature.denominator()))
                    .close("time");
        }

        private void clefElement() {
            measure.open("clef")
                    .element("sign", clef.sign() == 'F' ? "F" : "G")
                    .element("line", String.valueOf(clef.line()));
            if (clef.octaveChange() != 0) {
                measure.element("clef-octave-change", String.valueOf(clef.octaveChange()));
            }
            measure.close("clef");
        }

        /**
         * A pitch, from the spelling and nothing else.
         *
         * <p>{@code <alter>} is the spelling's alteration rather than the
         * difference between the sounding pitch and the natural, which are the
         * same number and are arrived at differently: the first cannot lose a
         * double flat and the second cannot tell one from a natural a whole tone
         * down. The octave is the model's, which counts middle C as 4 — the same
         * convention MusicXML uses, so it passes straight through.
         */
        private void pitch(PitchSpelling spelling) {
            measure.open("pitch").element("step", spelling.letter().name());
            if (spelling.accidental().alteration() != 0) {
                measure.element("alter", String.valueOf(spelling.accidental().alteration()));
            }
            measure.element("octave", String.valueOf(spelling.octave())).close("pitch");
        }

        /** A note held back until the callback after it, which may stop its bracket. */
        private static final class PendingNote {

            final List<PitchSpelling> pitches;
            final NoteValue value;
            final int duration;
            final boolean tiedIn;
            final boolean tiedOut;
            final int[] tuplet;
            final boolean tupletStart;
            boolean tupletStop;

            PendingNote(List<PitchSpelling> pitches, NoteValue value, int duration,
                        boolean tiedIn, boolean tiedOut, int[] tuplet, boolean tupletStart) {
                this.pitches = pitches;
                this.value = value;
                this.duration = duration;
                this.tiedIn = tiedIn;
                this.tiedOut = tiedOut;
                this.tuplet = tuplet;
                this.tupletStart = tupletStart;
            }
        }
    }
}
