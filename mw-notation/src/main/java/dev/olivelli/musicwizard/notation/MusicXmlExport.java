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
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.audiveris.proxymusic.Attributes;
import org.audiveris.proxymusic.BarStyle;
import org.audiveris.proxymusic.BarStyleColor;
import org.audiveris.proxymusic.Barline;
import org.audiveris.proxymusic.Clef;
import org.audiveris.proxymusic.ClefSign;
import org.audiveris.proxymusic.Direction;
import org.audiveris.proxymusic.DirectionType;
import org.audiveris.proxymusic.Identification;
import org.audiveris.proxymusic.Metronome;
import org.audiveris.proxymusic.Notations;
import org.audiveris.proxymusic.Note;
import org.audiveris.proxymusic.NoteType;
import org.audiveris.proxymusic.ObjectFactory;
import org.audiveris.proxymusic.PartList;
import org.audiveris.proxymusic.PartName;
import org.audiveris.proxymusic.PerMinute;
import org.audiveris.proxymusic.Pitch;
import org.audiveris.proxymusic.Rest;
import org.audiveris.proxymusic.RightLeftMiddle;
import org.audiveris.proxymusic.ScorePart;
import org.audiveris.proxymusic.ScorePartwise;
import org.audiveris.proxymusic.Sound;
import org.audiveris.proxymusic.StartStop;
import org.audiveris.proxymusic.Step;
import org.audiveris.proxymusic.Tie;
import org.audiveris.proxymusic.Tied;
import org.audiveris.proxymusic.TiedType;
import org.audiveris.proxymusic.Time;
import org.audiveris.proxymusic.TimeModification;
import org.audiveris.proxymusic.Tuplet;
import org.audiveris.proxymusic.TypedText;
import org.audiveris.proxymusic.Work;
import org.audiveris.proxymusic.YesNo;

/**
 * Exports a {@link Score} as MusicXML 4.0.
 *
 * <p><b>A sibling of {@link StaffNotation}, never a stage before it.</b>
 * LilyPond source is emitted straight from the domain model precisely because
 * the route through MusicXML and {@code musicxml2ly} is lossy, so this class is
 * not on the path to a PDF and nothing on that path may reach it. That holds by
 * construction rather than by intention: both this and {@link StaffNotation}
 * are readers of {@link StaffLayout} rather than of each other, and nothing on
 * the route to a PDF names either export. {@code ExportsAreSiblingsTest} checks
 * that mechanically — round 1 of review on #151 found the same claim made in
 * prose with a {@code grep} for evidence, and the grep returned four hits the
 * claim said it would not.
 *
 * <p>Which means the two exports cannot disagree about the music. Where a held
 * note is cut by a bar line, which bars carry a tuplet bracket, which notes
 * gather into a chord and where the pickup falls are all decided once, by
 * {@link StaffLayout}; this class only spells them. See {@link StaffWriter}.
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
     * and in the DOCTYPE.
     *
     * <p>4.0 rather than proxymusic's 4.0.3, which is its own artifact version
     * and not a version of the format. See {@link #marshal}.
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
        ObjectFactory factory = new ObjectFactory();
        ScorePartwise partwise = factory.createScorePartwise();
        score.title().ifPresent(title -> {
            Work work = factory.createWork();
            work.setWorkTitle(title);
            partwise.setWork(work);
        });
        score.artist().ifPresent(artist -> {
            // "composer" rather than "artist": MusicXML's creator types are the
            // ones a score credits, and every reader shows composer at the top
            // right of the first page. The model's "artist" is whoever the
            // recording is by, which for a transcription is the closest thing to
            // a composer credit it has.
            Identification identification = factory.createIdentification();
            TypedText creator = factory.createTypedText();
            creator.setType("composer");
            creator.setValue(artist);
            identification.getCreator().add(creator);
            partwise.setIdentification(identification);
        });

        PartList partList = factory.createPartList();
        partwise.setPartList(partList);
        for (int i = 0; i < tracks.size(); i++) {
            NoteTrack track = tracks.get(i);
            ScorePart scorePart = factory.createScorePart();
            // P1, P2, ...: an XML ID has to start with a letter, so the index
            // alone would not be one.
            scorePart.setId("P" + (i + 1));
            PartName partName = factory.createPartName();
            partName.setValue(track.name());
            scorePart.setPartName(partName);
            partList.getPartGroupOrScorePart().add(scorePart);

            ScorePartwise.Part part = factory.createScorePartwisePart();
            part.setId(scorePart);
            StaffLayout.write(score, track, tuplets,
                    new MusicXmlStaffWriter(factory, part, score.estimatedTempo()));
            partwise.getPart().add(part);
        }
        return marshal(partwise);
    }

    /**
     * The document as text.
     *
     * <p>Marshalled with plain JAXB over proxymusic's generated classes rather
     * than through {@code Marshalling.marshal}, and the reason is worth stating
     * because using the library's own one-call convenience is otherwise
     * obviously right.
     *
     * <p>That call declares the document to be MusicXML <b>4.0.3</b>, in both
     * the {@code version} attribute and the DOCTYPE's public identifier. 4.0.3
     * is proxymusic's artifact version. MusicXML's are 1.0, 1.1, 2.0, 3.0, 3.1
     * and 4.0, and {@code -//Recordare//DTD MusicXML 4.0.3 Partwise//EN} names a
     * DTD that has never existed — so a reader that resolves the public
     * identifier through an XML catalogue, which is how a DTD is meant to be
     * found offline, does not find it. The version attribute exists precisely so
     * that "programs that handle later versions can distinguish earlier version
     * files reliably", which a version number the format does not use defeats.
     *
     * <p>So the two header lines are written here and everything below them is
     * still the library's. It is fifteen lines of duplication against a claim
     * about the file's own format being wrong.
     *
     * <p>Two consequences of the plain marshaller, both deliberate. It declares
     * the xlink namespace on the root element even though nothing uses it, which
     * is valid and inert. And it indents four spaces rather than two, because
     * JAXB's own formatter is not configurable that far — a golden file reads
     * the same either way.
     *
     * <p>Nothing writes an {@code <encoding>} block, which is what keeps the
     * output diffable: it carries the date the file was written <em>and</em>
     * the version of the library that wrote it, either of which makes a golden
     * file fail on a day nobody changed anything.
     */
    private static String marshal(ScorePartwise partwise) {
        partwise.setVersion(MUSICXML_VERSION);
        StringWriter text = new StringWriter();
        text.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        text.write("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML "
                + MUSICXML_VERSION + " Partwise//EN\""
                + " \"http://www.musicxml.org/dtds/partwise.dtd\">\n");
        try {
            Marshaller marshaller = Context.get().createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            // The declaration is written above, with the DOCTYPE that has to
            // follow it; JAXB would otherwise emit a second one at the top of
            // its own output, in the middle of the document.
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
            marshaller.marshal(partwise, text);
        } catch (JAXBException e) {
            // Nothing a caller can do about it and nothing it can be: the object
            // graph is built entirely above, from a score that has already been
            // validated by the model. Wrapped rather than declared so that the
            // signature of an export is not a checked exception from a binding
            // library.
            throw new IllegalStateException("could not write MusicXML for this score", e);
        }
        // A text file ends with a newline. JAXB's formatter does not add one
        // after the root element.
        text.write("\n");
        return text.toString();
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

    /**
     * The JAXB context, built once.
     *
     * <p>A {@link JAXBContext} is expensive to build, is documented thread-safe,
     * and is the only part of the marshalling that can be shared — a
     * {@link Marshaller} is not thread-safe and one is made per export.
     *
     * <p>Built here rather than taken from {@code Marshalling.getContext},
     * which round 1 of review found is a double-checked lock whose fast path
     * reads a plain {@link java.util.HashMap} outside the monitor. Two threads
     * first exporting at the same moment race on it, and a racing {@code
     * HashMap} read does not merely return the wrong answer — it can spin. A
     * class initializer costs nothing and cannot.
     */
    private static final class Context {

        private static final JAXBContext CONTEXT;
        private static final JAXBException FAILURE;

        static {
            JAXBContext built = null;
            JAXBException failure = null;
            try {
                built = JAXBContext.newInstance(ScorePartwise.class);
            } catch (JAXBException e) {
                failure = e;
            }
            CONTEXT = built;
            FAILURE = failure;
        }

        private Context() {
        }

        /**
         * The context, or a complaint that names the cause every time.
         *
         * <p>The failure is held rather than thrown from the initializer.
         * Throwing from one gives the first caller an
         * {@code ExceptionInInitializerError} carrying the reason and every
         * caller after it a bare {@code NoClassDefFoundError} carrying nothing —
         * and a broken classpath is exactly the situation where the message is
         * all anybody has. Round 2 of review.
         */
        static JAXBContext get() {
            if (CONTEXT == null) {
                throw new IllegalStateException(
                        "the MusicXML bindings are not on the classpath or cannot be read",
                        FAILURE);
            }
            return CONTEXT;
        }
    }

    // ---------------------------------------------------------------- writer

    /**
     * Spells one laid-out staff as a MusicXML {@code <part>}.
     *
     * <p>Every decision has already been made; this accumulates measures. The
     * one thing it does keep track of is state the format needs and the layout
     * does not name: which note a tuplet bracket should be stopped on, and
     * whether the note being written is the far end of a tie the previous one
     * started.
     */
    private static final class MusicXmlStaffWriter implements StaffWriter {

        /** The voice every note is in. One part, one voice: #93. */
        private static final java.lang.String VOICE = "1";

        /** The bracket number every tuplet carries. Brackets never nest here. */
        private static final int TUPLET_NUMBER = 1;

        private final ObjectFactory factory;
        private final ScorePartwise.Part part;
        private final double quarterBeatsPerMinute;

        private StaffClef clef;
        private Optional<Key> key = Optional.empty();

        private ScorePartwise.Part.Measure measure;
        private TimeSignature meter;

        /**
         * Whether bar 0 turned out to be a pickup, which shifts every measure
         * number down by one.
         *
         * <p>A pickup is measure 0 and the first full bar is measure 1, which is
         * how a musician counts and how MusicXML says to write it. Recorded
         * rather than asked of the layout because {@link #pickup} arrives after
         * {@link #startBar} has already numbered the measure — so bar 0 is
         * renumbered in place and later bars consult this.
         */
        private boolean pickupBar;

        /** Divisions written into the current measure so far. */
        private int written;

        /** Divisions the current measure must hold, from its meter and pickup. */
        private int expected;

        /**
         * The time modification in force, or {@code null} outside a bracket.
         *
         * <p>Every note inside a bracket carries one, including the second and
         * later notes of a chord: it is what tells a reader that the written
         * value is played shorter, and a reader that saw it on only the first
         * note of a chord would play the rest of the chord long.
         */
        private TimeModification timeModification;

        /**
         * The note the open bracket should be stopped on: the last one written
         * inside it.
         *
         * <p>MusicXML marks a tuplet on its first and last notes, and the layout
         * says a bracket has closed only after the last note has gone by — so
         * the stop is attached retroactively. That is possible here and not in
         * the LilyPond writer only because this one builds an object graph
         * rather than text.
         */
        private Note tupletLastNote;

        /** Whether the note written next is the far end of a tie. */
        private boolean tiedFromPrevious;

        MusicXmlStaffWriter(ObjectFactory factory, ScorePartwise.Part part,
                            double quarterBeatsPerMinute) {
            this.factory = factory;
            this.part = part;
            this.quarterBeatsPerMinute = quarterBeatsPerMinute;
        }

        @Override
        public void startStaff(java.lang.String name, StaffClef staffClef, Optional<Key> staffKey) {
            this.clef = staffClef;
            this.key = staffKey;
        }

        @Override
        public void startBar(int index, TimeSignature barMeter, boolean meterChanged) {
            measure = factory.createScorePartwisePartMeasure();
            measure.setNumber(java.lang.String.valueOf(pickupBar ? index : index + 1));
            written = 0;
            expected = divisionsOf(barMeter.quarterBeatsPerBar());
            boolean first = part.getMeasure().isEmpty();
            if (first || meterChanged) {
                Attributes attributes = factory.createAttributes();
                if (first) {
                    attributes.setDivisions(new BigDecimal(DIVISIONS_PER_QUARTER));
                    attributes.getKey().add(keySignature());
                }
                if (first || meterChanged) {
                    attributes.getTime().add(timeSignature(barMeter));
                }
                if (first) {
                    attributes.getClef().add(clefElement());
                }
                measure.getNoteOrBackupOrForward().add(attributes);
            }
            meter = barMeter;
            part.getMeasure().add(measure);
        }

        @Override
        public void tempo(NoteValue unit, long perMinute) {
            Metronome metronome = factory.createMetronome();
            metronome.setBeatUnit(unit.musicXmlType());
            for (int i = 0; i < unit.dots(); i++) {
                metronome.getBeatUnitDot().add(factory.createEmpty());
            }
            PerMinute value = factory.createPerMinute();
            value.setValue(java.lang.String.valueOf(perMinute));
            metronome.setPerMinute(value);

            DirectionType directionType = factory.createDirectionType();
            directionType.setMetronome(metronome);
            Direction direction = factory.createDirection();
            direction.getDirectionType().add(directionType);
            direction.setPlacement(org.audiveris.proxymusic.AboveBelow.ABOVE);

            // <sound tempo> is quarter notes a minute by definition, whatever
            // unit the mark is printed in -- which is the trap the printed mark
            // exists to avoid in the other direction. Taken from the model's own
            // figure rather than reconstructed from the rounded mark, so that a
            // 6/8 score plays at the tempo it was transcribed at rather than at
            // whatever three times a rounded dotted-quarter count comes to.
            Sound sound = factory.createSound();
            sound.setTempo(BigDecimal.valueOf(Math.round(quarterBeatsPerMinute)));
            direction.setSound(sound);
            measure.getNoteOrBackupOrForward().add(direction);
        }

        @Override
        public void pickup(long wholeNotesNumerator, long wholeNotesDenominator) {
            pickupBar = true;
            measure.setNumber("0");
            // "implicit" is MusicXML's word for a bar that is not counted, which
            // is what a pickup is: the reader must not print a 0 over it.
            measure.setImplicit(YesNo.YES);
            // Four quarters to a whole note. The pickup is a fraction of one
            // because a pickup inside a triplet is two thirds of a quarter and
            // no double holds that; the multiplication below is exact for every
            // fraction that reaches here, since the division is the last step.
            expected = divisionsOf(4.0 * wholeNotesNumerator / wholeNotesDenominator);
        }

        @Override
        public void openTuplet(int actual, int normal) {
            timeModification = factory.createTimeModification();
            timeModification.setActualNotes(BigInteger.valueOf(actual));
            timeModification.setNormalNotes(BigInteger.valueOf(normal));
            tupletLastNote = null;
        }

        @Override
        public void closeTuplet() {
            if (tupletLastNote != null) {
                notationsOf(tupletLastNote).getTiedOrSlurOrTuplet().add(tuplet(StartStop.STOP));
            }
            timeModification = null;
            tupletLastNote = null;
        }

        @Override
        public void symbol(List<PitchSpelling> pitches, NoteValue value, double soundingQuarters,
                           boolean tied) {
            int duration = divisionsOf(soundingQuarters);
            boolean openBracket = timeModification != null && tupletLastNote == null;
            Note first = null;
            for (int i = 0; i < Math.max(1, pitches.size()); i++) {
                Note note = factory.createNote();
                if (i > 0) {
                    note.setChord(factory.createEmpty());
                }
                if (pitches.isEmpty()) {
                    note.setRest(factory.createRest());
                } else {
                    note.setPitch(pitchOf(pitches.get(i)));
                }
                note.setDuration(new BigDecimal(duration));
                appendTies(note, tied);
                note.setVoice(VOICE);
                NoteType type = factory.createNoteType();
                type.setValue(value.musicXmlType());
                note.setType(type);
                for (int dot = 0; dot < value.dots(); dot++) {
                    note.getDot().add(factory.createEmptyPlacement());
                }
                if (timeModification != null) {
                    note.setTimeModification(timeModification);
                }
                measure.getNoteOrBackupOrForward().add(note);
                if (first == null) {
                    first = note;
                }
            }
            if (timeModification != null) {
                if (openBracket) {
                    notationsOf(first).getTiedOrSlurOrTuplet().add(tuplet(StartStop.START));
                }
                tupletLastNote = first;
            }
            // A chord sounds once, however many note heads it has: only the
            // first carries the measure forward, and MusicXML says the same by
            // marking the rest with <chord/>.
            written += duration;
            tiedFromPrevious = tied;
        }

        @Override
        public void wholeBarRest(long wholeNotesNumerator, long wholeNotesDenominator) {
            Note note = factory.createNote();
            Rest rest = factory.createRest();
            // measure="yes" is the whole-bar rest: one symbol centred in the bar
            // whatever the meter, which is what LilyPond's R means too. Without
            // it a 3/4 bar of silence would be drawn as a dotted half rest,
            // which is not how a bar of silence is written.
            rest.setMeasure(YesNo.YES);
            note.setRest(rest);
            int duration = divisionsOf(4.0 * wholeNotesNumerator / wholeNotesDenominator);
            note.setDuration(new BigDecimal(duration));
            note.setVoice(VOICE);
            // Deliberately no <type>: a measure rest has no note value, and
            // naming one would claim a 5/4 bar of silence is a symbol that does
            // not exist.
            measure.getNoteOrBackupOrForward().add(note);
            written += duration;
        }

        @Override
        public void endBar() {
            if (written != expected) {
                // The failure this catches is the one MusicXML readers do not:
                // a measure that does not fill its meter is imported without
                // complaint and everything after it is in the wrong place.
                throw new IllegalStateException(
                        "measure " + measure.getNumber() + " of " + meter + " holds " + written
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
            // The double bar line at the end of the piece, which is what
            // LilyPond's \bar "|." draws. On the last measure only.
            Barline barline = factory.createBarline();
            barline.setLocation(RightLeftMiddle.RIGHT);
            BarStyleColor style = factory.createBarStyleColor();
            style.setValue(BarStyle.LIGHT_HEAVY);
            barline.setBarStyle(style);
            part.getMeasure().getLast().getNoteOrBackupOrForward().add(barline);
        }

        // ------------------------------------------------------------ pieces

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
        private org.audiveris.proxymusic.Key keySignature() {
            org.audiveris.proxymusic.Key signature = factory.createKey();
            signature.setFifths(BigInteger.valueOf(
                    key.map(Key::keySignatureAccidentals).orElse(0)));
            key.ifPresent(k -> signature.setMode(k.mode().lilyPondName()));
            return signature;
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
        private Time timeSignature(TimeSignature signature) {
            Time time = factory.createTime();
            time.getTimeSignature().add(
                    factory.createTimeBeats(java.lang.String.valueOf(signature.numerator())));
            time.getTimeSignature().add(
                    factory.createTimeBeatType(java.lang.String.valueOf(signature.denominator())));
            return time;
        }

        private Clef clefElement() {
            Clef element = factory.createClef();
            element.setSign(clef.sign() == 'F' ? ClefSign.F : ClefSign.G);
            element.setLine(BigInteger.valueOf(clef.line()));
            if (clef.octaveChange() != 0) {
                element.setClefOctaveChange(BigInteger.valueOf(clef.octaveChange()));
            }
            return element;
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
        private Pitch pitchOf(PitchSpelling spelling) {
            Pitch pitch = factory.createPitch();
            pitch.setStep(Step.fromValue(spelling.letter().name()));
            if (spelling.accidental().alteration() != 0) {
                pitch.setAlter(new BigDecimal(spelling.accidental().alteration()));
            }
            pitch.setOctave(spelling.octave());
            return pitch;
        }

        /**
         * The tie elements for a note.
         *
         * <p>Both spellings, because MusicXML has two and they mean different
         * things: {@code <tie>} is what a playback engine reads and
         * {@code <tied>} is the slur a reader draws. A file with only the first
         * plays correctly and prints two separate notes; with only the second it
         * prints correctly and plays them twice.
         *
         * <p>Stop before start, which is the order the schema fixes and the
         * order a note in the middle of a chain of ties needs.
         */
        private void appendTies(Note note, boolean tiesOut) {
            if (tiedFromPrevious) {
                note.getTie().add(tie(StartStop.STOP));
                notationsOf(note).getTiedOrSlurOrTuplet().add(tied(TiedType.STOP));
            }
            if (tiesOut) {
                note.getTie().add(tie(StartStop.START));
                notationsOf(note).getTiedOrSlurOrTuplet().add(tied(TiedType.START));
            }
        }

        private Tie tie(StartStop type) {
            Tie element = factory.createTie();
            element.setType(type);
            return element;
        }

        private Tied tied(TiedType type) {
            Tied element = factory.createTied();
            element.setType(type);
            return element;
        }

        private Tuplet tuplet(StartStop type) {
            Tuplet element = factory.createTuplet();
            element.setType(type);
            element.setNumber(TUPLET_NUMBER);
            return element;
        }

        /** The note's {@code <notations>}, created on first use. */
        private Notations notationsOf(Note note) {
            List<Notations> notations = note.getNotations();
            if (notations.isEmpty()) {
                notations.add(factory.createNotations());
            }
            return notations.getFirst();
        }
    }
}
