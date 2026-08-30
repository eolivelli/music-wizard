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

package dev.olivelli.musicwizard.transcribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.MusicalTime;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import java.nio.file.Path;
import java.util.List;
import javax.sound.midi.Sequence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What a MIDI file is worth once it has become a {@link Score}.
 *
 * <p>This is the one input where musical time is exact rather than estimated, so
 * the assertions here are exact too. A tolerance would hide the mistake the whole
 * class exists to avoid: a beat position that is nearly right is a beat position
 * that has been through an estimate it never needed to.
 *
 * <p>Every fixture but the smoke one starts after the origin and changes tempo
 * or meter part-way, because a piece that starts at beat 0 and never changes
 * tempo is converted correctly by a single multiplication -- which is exactly
 * the implementation that is wrong.
 */
class MidiImportTest {

    private static final MidiTranscriber TRANSCRIBER = new MidiTranscriber();

    /**
     * Seconds per quarter beat at the fixture's two tempi.
     *
     * <p>Taken through {@link MidiFixtures#storedTempo} rather than written as
     * {@code 60.0 / 140}, because a MIDI tempo event holds whole microseconds
     * per quarter note and 140 BPM is not one of them: the file stores 428571
     * microseconds, which is 140.00014 BPM. Asserting against the requested
     * tempo would fail by a part in a million, and the usual repair -- widening
     * the tolerance until it passes -- would hide every real error alongside it.
     * 100 BPM is exactly representable and needs no such care.
     */
    private static final double AT_100 = 60.0 / MidiFixtures.storedTempo(100);
    private static final double AT_140 = 60.0 / MidiFixtures.storedTempo(140);

    private static Score theFixture() {
        return TRANSCRIBER.transcribe(MidiFixtures.tempoAndMeterChange());
    }

    private static NoteTrack named(Score score, String name) {
        return score.tracks().stream()
                .filter(track -> track.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no track called \"" + name + "\" in "
                        + score.tracks().stream().map(NoteTrack::name).toList()));
    }

    // -------------------------------------------------------- exactness of time

    @Test
    @DisplayName("note onsets in beats are the tick positions, not a conversion of them")
    void onsetsInBeatsMatchTicksExactly() {
        NoteTrack melody = named(theFixture(), "Melody");
        assertThat(melody.notes()).extracting(note -> note.onsetBeat().orElseThrow())
                .containsExactly(1.0, 2.0, 3.0, 7.0, 9.0, 10.0);
        assertThat(melody.notes()).extracting(note -> note.durationBeats().orElseThrow())
                .containsExactly(1.0, 1.0, 1.0, 2.0, 1.0, 1.0);
    }

    @Test
    @DisplayName("a MIDI file arrives quantized, so nothing downstream has to quantize it")
    void everyNoteCarriesBothAxes() {
        Score score = theFixture();
        assertThat(score.tracks()).isNotEmpty();
        for (NoteTrack track : score.tracks()) {
            assertThat(track.isQuantized()).as("%s is quantized", track.name()).isTrue();
            assertThat(track.notes()).allSatisfy(note ->
                    assertThat(note.confidence().value()).isEqualTo(1.0));
        }
    }

    @Test
    @DisplayName("a note held across a tempo change lasts as long as it is really played")
    void durationInSecondsFollowsTheTempoMapAcrossAChange() {
        // Beat 7 to beat 9, with the tempo going from 100 to 140 at beat 8. One
        // beat at each: 0.6s plus 0.4285...s. Multiplying two beats by the tempo
        // at the onset would give 1.2s, and every onset around it would still be
        // right, which is what makes this the failure nobody notices.
        Note straddling = named(theFixture(), "Melody").notes().get(3);
        assertThat(straddling.onsetBeat()).contains(7.0);
        assertThat(straddling.onsetSeconds()).isEqualTo(7 * AT_100, within(1e-12));
        assertThat(straddling.durationSeconds()).isEqualTo(AT_100 + AT_140, within(1e-12));
    }

    @Test
    @DisplayName("a quarter note is one beat wherever it falls")
    void durationInBeatsIsMeasuredInTicksRatherThanBetweenTwoConvertedOnsets() {
        // Taking the difference of two converted onsets leaves a quarter note a
        // fraction of a beat short late in a piece, and a quantizer that later
        // sees 0.99999 has to decide what was meant.
        for (Note note : named(theFixture(), "Drum Kit").notes()) {
            assertThat(note.durationBeats()).contains(0.5);
        }
    }

    @Test
    @DisplayName("the piece is as long as its last event, measured through the tempo map")
    void durationSecondsSpansTheWholePiece() {
        // 8 beats at 100 then 3 at 140: the fixture ends at beat 11.
        assertThat(theFixture().durationSeconds())
                .isEqualTo(8 * AT_100 + 3 * AT_140, within(1e-12));
    }

    // -------------------------------------------------------------------- tempo

    @Test
    @DisplayName("each MIDI tempo event becomes a tempo segment anchored where it falls")
    void tempoEventsBecomeSegments() {
        Score score = theFixture();
        assertThat(score.tempoMap().segments()).hasSize(2);
        assertThat(score.tempoMap().initialTempo()).isEqualTo(100.0);
        assertThat(score.tempoMap().tempoAtBeat(7.999)).isEqualTo(100.0);
        assertThat(score.tempoMap().tempoAtBeat(8.0)).isEqualTo(MidiFixtures.storedTempo(140));
        assertThat(score.tempoMap().segments().get(1).startBeat()).isEqualTo(8.0);
        assertThat(score.tempoMap().segments().get(1).startSeconds())
                .isEqualTo(8 * AT_100, within(1e-12));
    }

    @Test
    @DisplayName("a tempo the wire format cannot hold comes back as the one it does hold")
    void anUnrepresentableTempoIsReportedAsStoredRatherThanAsRequested() {
        // Not a defect and not repairable: a tempo event carries whole
        // microseconds per quarter note. It is here because a downstream stage
        // that prints "140.00014 BPM" on a chart, or that compares an imported
        // tempo to a typed one for equality, is relying on something untrue.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(140)
                .part("Melody", 0).note(1, 1, 60).build();
        double imported = TRANSCRIBER.transcribe(sequence).tempoMap().initialTempo();
        assertThat(imported).isNotEqualTo(140.0);
        assertThat(imported).isEqualTo(140.0, within(1e-3));
        assertThat(imported).isEqualTo(MidiFixtures.storedTempo(140));
        // 120 is a whole number of microseconds and survives exactly, which is
        // what makes the line above a fact about the format rather than about
        // this importer.
        Sequence exact = MidiFixtures.sequence()
                .tempo(120)
                .part("Melody", 0).note(1, 1, 60).build();
        assertThat(TRANSCRIBER.transcribe(exact).tempoMap().initialTempo()).isEqualTo(120.0);
        // And the figure a consumer actually reads is the stored one, not a
        // re-derivation of it: estimatedTempo answers a declared map from the
        // map, and a mean over a single segment has to come back bit-identical
        // or the pipeline reports a tempo the file does not contain.
        //
        // An end-to-end pin, not a reproduction -- whether the round trip
        // through beats and back loses a bit depends on the duration, and this
        // fixture's does not. ProvenanceTest.aSingleSegmentWindowIsExact picks
        // a duration where it does, and fails without the fix.
        assertThat(TRANSCRIBER.transcribe(sequence).estimatedTempo())
                .isEqualTo(MidiFixtures.storedTempo(140));
    }

    @Test
    @DisplayName("the third tempo segment starts where the second one carried it to")
    void secondsAccumulateAcrossMoreThanOneTempoChange() {
        // Two changes, not one. With a single change every segment's start in
        // seconds is measured from the origin, where "accumulate from the
        // previous segment" and "multiply the beat by this tempo" agree exactly
        // -- the t=0 trap one level in. Segment 2 is the first that can tell
        // them apart, and a wrong answer here is not subtle: TempoMap validates
        // that the stored tempo carries from one segment to the next, so it
        // would reject an ordinary file outright.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .tempoAt(4, 60)
                .tempoAt(8, 120)
                .part("Melody", 0).note(1, 1, 60).note(9, 1, 62).build();
        Score score = TRANSCRIBER.transcribe(sequence);
        assertThat(score.tempoMap().segments()).hasSize(3);
        // Four beats at 120 is 2s; four more at 60 is another 4s.
        assertThat(score.tempoMap().segments().get(1).startSeconds()).isEqualTo(2.0);
        assertThat(score.tempoMap().segments().get(2).startSeconds()).isEqualTo(6.0);
        assertThat(score.tempoMap().beatsToSeconds(8.0)).isEqualTo(6.0);
        // The note after the second change is placed through the whole map.
        assertThat(named(score, "Melody").notes().get(1).onsetSeconds()).isEqualTo(6.5);
    }

    @Test
    @DisplayName("a file that names no tempo is played at 120, as the specification says")
    void theDefaultTempoIsOneHundredAndTwenty() {
        Sequence bare = MidiFixtures.sequence()
                .timeSignature(4, 4)
                .part("Melody", 0).note(2, 1, 60).build();
        Score score = TRANSCRIBER.transcribe(bare);
        assertThat(score.tempoMap().initialTempo()).isEqualTo(120.0);
        // Beat 2 at 120 is one second in, which is only checkable because the
        // note is not at the origin.
        assertThat(score.tracks().get(0).notes().get(0).onsetSeconds())
                .isEqualTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("tells a tempo the file declares from the one substituted for it")
    void declaredAndSubstitutedTempiAreLabelledApart() {
        // The two produce the identical number, which is the whole difficulty:
        // 120 read from a tempo event and 120 supplied by the specification are
        // indistinguishable once the map is built. #117 had to hedge its output
        // for exactly this. The importer is the only code that knows, so it
        // records it.
        Sequence declares = MidiFixtures.sequence()
                .tempo(120)
                .part("Melody", 0).note(1, 1, 60).build();
        Sequence declaresNothing = MidiFixtures.sequence()
                .timeSignature(4, 4)
                .part("Melody", 0).note(2, 1, 60).build();

        Score declared = TRANSCRIBER.transcribe(declares);
        Score assumed = TRANSCRIBER.transcribe(declaresNothing);

        assertThat(declared.tempoMap().initialTempo())
                .as("the same figure either way, which is why the label is needed")
                .isEqualTo(assumed.tempoMap().initialTempo());
        assertThat(provenances(declared)).containsExactly(Provenance.DECLARED);
        assertThat(provenances(assumed)).containsExactly(Provenance.ASSUMED);
    }

    @Test
    @DisplayName("a file whose first tempo event is late declares only from there")
    void aLateFirstTempoEventLeavesTheOpeningAssumed() {
        // The mixed case, and the one a per-map label could not express: the
        // first four beats are played at 120 because the specification says so,
        // and the file states nothing until beat 4.
        Sequence late = MidiFixtures.sequence()
                .tempoAt(4, 60)
                .part("Melody", 0).note(4, 1, 60).build();

        assertThat(provenances(TRANSCRIBER.transcribe(late)))
                .containsExactly(Provenance.ASSUMED, Provenance.DECLARED);
    }

    private static List<Provenance> provenances(Score score) {
        return score.tempoMap().segments().stream()
                .map(TempoMap.TempoSegment::provenance).toList();
    }

    @Test
    @DisplayName("a tempo event after the start still leaves the map anchored at the origin")
    void aLateFirstTempoEventDoesNotUnanchorTheMap() {
        Sequence late = MidiFixtures.sequence()
                .tempoAt(4, 60)
                .part("Melody", 0).note(4, 1, 60).build();
        Score score = TRANSCRIBER.transcribe(late);
        // The first four beats are played at the default 120 because the file
        // says nothing about them; the map must still start at (0, 0).
        assertThat(score.tempoMap().segments().get(0).startBeat()).isEqualTo(0.0);
        assertThat(score.tempoMap().segments().get(0).startSeconds()).isEqualTo(0.0);
        assertThat(score.tempoMap().initialTempo()).isEqualTo(120.0);
        assertThat(score.tempoMap().tempoAtBeat(4.0)).isEqualTo(60.0);
        assertThat(score.tracks().get(0).notes().get(0).onsetSeconds())
                .isEqualTo(2.0, within(1e-12));
    }

    // -------------------------------------------------------------------- meter

    @Test
    @DisplayName("a 6/8 file is 6/8, and its bar is still three quarter beats")
    void compoundMeterSurvivesAsCompoundMeter() {
        Score score = TRANSCRIBER.transcribe(MidiFixtures.compoundTime());
        TimeSignature meter = score.tempoMap().initialTimeSignature();
        assertThat(meter).isEqualTo(TimeSignature.SIX_EIGHT);
        assertThat(meter.quarterBeatsPerBar()).isEqualTo(3.0);
        assertThat(meter.beatsPerBar()).isEqualTo(2);
        assertThat(meter.beatUnitQuarters()).isEqualTo(1.5);
        // Three quarter beats in, not six: reading the numerator as a count of
        // quarter beats would put this in the middle of bar 1.
        assertThat(score.tempoMap().toMusicalTime(3.0))
                .isEqualTo(new MusicalTime(1, 0.0, TimeSignature.SIX_EIGHT));
    }

    @Test
    @DisplayName("a meter change lands on the bar the file puts it in")
    void meterChangesLandOnTheRightBar() {
        Score score = theFixture();
        assertThat(score.tempoMap().meterChanges()).hasSize(2);
        assertThat(score.tempoMap().timeSignatureAtBar(0)).isEqualTo(TimeSignature.FOUR_FOUR);
        assertThat(score.tempoMap().timeSignatureAtBar(1)).isEqualTo(TimeSignature.FOUR_FOUR);
        assertThat(score.tempoMap().timeSignatureAtBar(2)).isEqualTo(TimeSignature.SIX_EIGHT);
        // Two 4/4 bars are eight quarter beats, so 6/8 begins at beat 8 and its
        // first bar runs to beat 11.
        assertThat(score.tempoMap().toMusicalTime(8.0).bar()).isEqualTo(2);
        assertThat(score.tempoMap().toMusicalTime(10.999).bar()).isEqualTo(2);
        assertThat(score.tempoMap().toMusicalTime(11.0).bar()).isEqualTo(3);
    }

    @Test
    @DisplayName("a meter change off a bar line takes effect at the next bar line")
    void aMidBarMeterChangeMovesForward() {
        // 4/4 from the start, then 3/4 declared half-way through bar 1. A bar
        // index cannot express half a bar, so the change has to move -- forward,
        // so that bar 0 and bar 1 still divide the way the file said they did.
        Sequence awkward = MidiFixtures.sequence()
                .timeSignature(4, 4)
                .timeSignatureAt(6, 3, 4)
                .part("Melody", 0).note(1, 1, 60).note(9, 1, 62).build();
        Score score = TRANSCRIBER.transcribe(awkward);
        assertThat(score.tempoMap().meterChanges()).hasSize(2);
        assertThat(score.tempoMap().meterChanges().get(1).startBar()).isEqualTo(2);
        assertThat(score.tempoMap().timeSignatureAtBar(1)).isEqualTo(TimeSignature.FOUR_FOUR);
        assertThat(score.tempoMap().timeSignatureAtBar(2)).isEqualTo(TimeSignature.THREE_FOUR);
    }

    @Test
    @DisplayName("bars before a change are counted in quarter beats, not in counted beats")
    void barsAreCountedInQuarterBeatsWhenLeavingACompoundMeter() {
        // The one place the two differ. A 6/8 bar is three quarter beats and two
        // counted beats, so two bars of it end at quarter beat 6 -- and a walk
        // that divided by beatsPerBar() would put the change three bars in
        // instead of two, and every bar number after it would be wrong. Every
        // meter change in an x/4 file agrees either way, which is why this needs
        // its own fixture.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(6, 8)
                .timeSignatureAt(6, 4, 4)
                .part("Melody", 0).note(1, 1, 60).note(7, 1, 62).build();
        Score score = TRANSCRIBER.transcribe(sequence);
        assertThat(score.tempoMap().meterChanges()).hasSize(2);
        assertThat(score.tempoMap().meterChanges().get(1).startBar()).isEqualTo(2);
        assertThat(score.tempoMap().timeSignatureAtBar(1)).isEqualTo(TimeSignature.SIX_EIGHT);
        assertThat(score.tempoMap().timeSignatureAtBar(2)).isEqualTo(TimeSignature.FOUR_FOUR);
        assertThat(score.tempoMap().toMusicalTime(6.0).bar()).isEqualTo(2);
    }

    @Test
    @DisplayName("a file that names no meter is barred in 4/4")
    void theDefaultMeterIsFourFour() {
        Sequence bare = MidiFixtures.sequence()
                .tempo(120)
                .part("Melody", 0).note(2, 1, 60).build();
        assertThat(TRANSCRIBER.transcribe(bare).tempoMap().initialTimeSignature())
                .isEqualTo(TimeSignature.FOUR_FOUR);
    }

    @Test
    @DisplayName("a meter the file states and one it does not are not the same claim")
    void aDefaultedMeterIsNotReadAsDeclared() {
        // #119: both files bar in 4/4, and the score has to say which of them
        // said so -- a defaulted 4/4 reaches the engraved bar lines.
        Sequence silent = MidiFixtures.sequence()
                .tempo(120)
                .part("Melody", 0).note(2, 1, 60).build();
        Sequence stated = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Melody", 0).note(2, 1, 60).build();

        assertThat(TRANSCRIBER.transcribe(silent).tempoMap().meterChanges().get(0).provenance())
                .isEqualTo(Provenance.ASSUMED);
        assertThat(TRANSCRIBER.transcribe(stated).tempoMap().meterChanges().get(0).provenance())
                .isEqualTo(Provenance.DECLARED);
        // Nothing on the MIDI path is an estimate, so no row of it carries one.
        assertThat(TRANSCRIBER.transcribe(stated).tempoMap().meterChanges().get(0).confidence())
                .isEmpty();
    }

    @Test
    @DisplayName("restating the meter is not a meter change")
    void arepeatedTimeSignatureDoesNotSpliceASegment() {
        Sequence repeated = MidiFixtures.sequence()
                .timeSignature(4, 4)
                .timeSignatureAt(4, 4, 4)
                .timeSignatureAt(8, 4, 4)
                .part("Melody", 0).note(1, 1, 60).build();
        assertThat(TRANSCRIBER.transcribe(repeated).tempoMap().meterChanges()).hasSize(1);
    }

    // --------------------------------------------------------------------- keys

    @Test
    @DisplayName("a key signature in the file becomes a key spanning the piece")
    void keySignaturesAreRead() {
        Score score = theFixture();
        assertThat(score.keys()).hasSize(1);
        Key key = score.keys().get(0);
        assertThat(key.displayName()).isEqualTo("D major");
        assertThat(key.keySignatureAccidentals()).isEqualTo(2);
        assertThat(key.startBeat()).contains(0.0);
        assertThat(key.endBeat()).contains(11.0);
        assertThat(key.isQuantized()).isTrue();
    }

    @ParameterizedTest(name = "{0} sharps, minor={1} is {2}")
    @DisplayName("every standard key signature round-trips back to the count it came from")
    @CsvSource({
            "-7, false, Cb major", "-6, false, Gb major", "-5, false, Db major",
            "-4, false, Ab major", "-3, false, Eb major", "-2, false, Bb major",
            "-1, false, F major", "0, false, C major", "1, false, G major",
            "2, false, D major", "3, false, A major", "4, false, E major",
            "5, false, B major", "6, false, F# major", "7, false, C# major",
            "-7, true, Ab minor", "-6, true, Eb minor", "-5, true, Bb minor",
            "-4, true, F minor", "-3, true, C minor", "-2, true, G minor",
            "-1, true, D minor", "0, true, A minor", "1, true, E minor",
            "2, true, B minor", "3, true, F# minor", "4, true, C# minor",
            "5, true, G# minor", "6, true, D# minor", "7, true, A# minor"})
    void everyKeySignatureIsSpelledCorrectly(int sharpsOrFlats, boolean minor, String expected) {
        Sequence sequence = MidiFixtures.sequence()
                .keySignature(sharpsOrFlats, minor ? Mode.MINOR : Mode.MAJOR)
                .part("Melody", 0).note(1, 1, 60).build();
        Key key = TRANSCRIBER.transcribe(sequence).keys().get(0);
        assertThat(key.displayName()).isEqualTo(expected);
        // The model derives the count back from the tonic, so this is the round
        // trip and not a restatement of the input.
        assertThat(key.keySignatureAccidentals()).isEqualTo(sharpsOrFlats);
    }

    @Test
    @DisplayName("no key signature means no key, not C major")
    void anAbsentKeySignatureIsAbsent() {
        Sequence sequence = MidiFixtures.sequence()
                .part("Melody", 0).note(1, 1, 60).build();
        assertThat(TRANSCRIBER.transcribe(sequence).keys()).isEmpty();
        assertThat(TRANSCRIBER.transcribe(sequence).primaryKey()).isEmpty();
    }

    @Test
    @DisplayName("a key change splits the piece into two spans that meet exactly")
    void aKeyChangeProducesTwoAdjacentSpans() {
        Sequence modulating = MidiFixtures.sequence()
                .tempo(120)
                .keySignature(0, Mode.MAJOR)
                .keySignatureAt(8, 3, Mode.MINOR)
                .part("Melody", 0).note(1, 1, 60).note(12, 1, 62).build();
        List<Key> keys = TRANSCRIBER.transcribe(modulating).keys();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).displayName()).isEqualTo("C major");
        assertThat(keys.get(1).displayName()).isEqualTo("F# minor");
        assertThat(keys.get(0).endBeat()).contains(8.0);
        assertThat(keys.get(1).startBeat()).contains(8.0);
        assertThat(keys.get(0).endSeconds()).isEqualTo(keys.get(1).startSeconds());
    }

    // -------------------------------------------------------------------- parts

    @Test
    @DisplayName("roles come only from the two signals a file states unambiguously")
    void rolesAreInferredOnlyWhereItIsSafe() {
        Score score = theFixture();
        assertThat(named(score, "Drum Kit").role()).isEqualTo(PartRole.DRUMS);
        assertThat(named(score, "Bass Guitar").role()).isEqualTo(PartRole.BASS);
        // Melody: no channel-10 and no "bass" in the name, so nothing is known.
        // Guessing LEAD_VOCAL here would put it on the wrong staff later.
        assertThat(named(score, "Melody").role()).isEqualTo(PartRole.OTHER);
        assertThat(score.track(PartRole.LEAD_VOCAL)).isEmpty();
    }

    @Test
    @DisplayName("pitch spelling is left to the stage that has the harmony to decide it")
    void notesCarryNoSpelling() {
        for (NoteTrack track : theFixture().tracks()) {
            assertThat(track.notes()).allSatisfy(note ->
                    assertThat(note.spelling()).isEmpty());
        }
    }

    @Test
    @DisplayName("pitch and velocity survive the import unchanged")
    void pitchesAndVelocitiesSurvive() {
        Sequence sequence = MidiFixtures.sequence()
                .part("Melody", 0)
                .note(1, 1, 61, 33)
                .note(2, 1, 127, 127)
                .note(3, 1, 0, 1)
                .build();
        assertThat(TRANSCRIBER.transcribe(sequence).tracks().get(0).notes())
                .extracting(Note::midiPitch, Note::velocity)
                .containsExactly(tuple(61, 33), tuple(127, 127), tuple(0, 1));
    }

    @Test
    @DisplayName("the sequence name becomes the title; a note track's name does not")
    void theTitleComesFromTheMetaTrack() {
        assertThat(theFixture().title()).contains("Tempo and meter change");
        Sequence unnamed = MidiFixtures.sequence()
                .part("Melody", 0).note(1, 1, 60).build();
        assertThat(TRANSCRIBER.transcribe(unnamed).title()).isEmpty();
    }

    // ------------------------------------------------------------ through a file

    @Test
    @DisplayName("a written file reads back as the same score")
    void aFileRoundTripsThroughDisk(@TempDir Path directory) {
        Path file = directory.resolve("fixture.mid");
        MidiFixtures.write(MidiFixtures.tempoAndMeterChange(), file);
        Score fromDisk = TRANSCRIBER.transcribe(file);
        Score fromMemory = theFixture();
        assertThat(fromDisk.tempoMap()).isEqualTo(fromMemory.tempoMap());
        assertThat(fromDisk.durationSeconds()).isEqualTo(fromMemory.durationSeconds());
        assertThat(fromDisk.keys()).isEqualTo(fromMemory.keys());
        assertThat(fromDisk.tracks()).isEqualTo(fromMemory.tracks());
        assertThat(fromDisk.title()).isEqualTo(fromMemory.title());
    }

    @Test
    @DisplayName("something that is not a MIDI file is refused rather than half-read")
    void aNonMidiFileIsRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("not-midi.mid");
        java.nio.file.Files.writeString(file, "this is not a MIDI file");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TRANSCRIBER.transcribe(file))
                .withMessageContaining("not a readable Standard MIDI File");
    }

    // ----------------------------------------------------------- other fixtures

    @Test
    @DisplayName("the four-chord fixture carries the progression the audio track is verified on")
    void theFourChordFixtureImports() {
        Score score = TRANSCRIBER.transcribe(MidiFixtures.fourChordSong());
        assertThat(named(score, "Bass").role()).isEqualTo(PartRole.BASS);
        assertThat(named(score, "Piano").notes()).hasSize(24);
        assertThat(named(score, "Bass").notes()).hasSize(16);
        // Chord tones sound together, so the part is not a single line.
        assertThat(named(score, "Piano").isMonophonic()).isFalse();
        assertThat(named(score, "Bass").isMonophonic()).isTrue();
    }

    @Test
    @DisplayName("the lead-in fixture puts nothing at the origin")
    void theLeadInFixtureStartsAfterTheOrigin() {
        Score score = TRANSCRIBER.transcribe(MidiFixtures.leadInAndTempoChange());
        assertThat(score.tracks()).isNotEmpty();
        for (NoteTrack track : score.tracks()) {
            assertThat(track.notes().get(0).onsetBeat().orElseThrow()).isGreaterThan(0.0);
            assertThat(track.notes().get(0).onsetSeconds()).isGreaterThan(0.0);
        }
    }

    @Test
    @DisplayName("the smoke fixture is the plain case and still imports exactly")
    void theScaleFixtureImports() {
        Score score = TRANSCRIBER.transcribe(MidiFixtures.cMajorScale());
        assertThat(score.tracks()).hasSize(1);
        assertThat(score.tracks().get(0).notes()).hasSize(8);
        assertThat(score.tracks().get(0).notes().get(0).onsetBeat()).contains(0.0);
        assertThat(score.durationSeconds()).isEqualTo(4.0, within(1e-12));
    }
}
