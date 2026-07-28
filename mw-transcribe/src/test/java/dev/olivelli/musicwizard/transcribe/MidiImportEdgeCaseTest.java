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
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The MIDI a real file contains, as opposed to the MIDI a fixture builder emits.
 *
 * <p>Every case here occurs in files people actually have: a note ended by a
 * note-on at velocity zero, the same pitch struck twice before either is
 * released, a note-on with no matching note-off, two drum kits, a frame-timed
 * file. None of them is exotic, and each of them has a wrong answer that looks
 * entirely reasonable in a passing test -- a note with a negative length, a part
 * on the wrong staff, an import that throws three stages later with a message
 * about something else.
 */
class MidiImportEdgeCaseTest {

    private static final int PPQ = 480;

    private final List<String> messages = new ArrayList<>();
    private final MidiTranscriber transcriber = new MidiTranscriber(messages::add);

    // ------------------------------------------------------------- note pairing

    @Test
    @DisplayName("a note-on at velocity zero ends the note, as half the world's files assume")
    void noteOnAtVelocityZeroIsANoteOff() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 480, 0, 60, 90);
        // The note-off spelling used by files that rely on running status.
        noteOn(track, 1440, 0, 60, 0);
        noteOn(track, 1440, 0, 64, 90);
        noteOff(track, 1920, 0, 64);

        List<Note> notes = transcriber.transcribe(sequence).tracks().get(0).notes();
        assertThat(notes).extracting(note -> note.onsetBeat().orElseThrow())
                .containsExactly(1.0, 3.0);
        assertThat(notes).extracting(note -> note.durationBeats().orElseThrow())
                .containsExactly(2.0, 1.0);
    }

    @Test
    @DisplayName("the same pitch struck twice pairs oldest-first, not newest-first")
    void overlappingSamePitchNotesPairOldestFirst() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 0, 0, 60, 90);
        noteOn(track, 480, 0, 60, 90);
        noteOff(track, 960, 0, 60);
        noteOff(track, 1440, 0, 60);

        List<Note> notes = transcriber.transcribe(sequence).tracks().get(0).notes();
        // Oldest-first gives two two-beat notes. Newest-first would give a
        // one-beat note and a three-beat one, which is also self-consistent and
        // is what a stack rather than a queue produces.
        assertThat(notes).extracting(note -> note.onsetBeat().orElseThrow())
                .containsExactly(0.0, 1.0);
        assertThat(notes).extracting(note -> note.durationBeats().orElseThrow())
                .containsExactly(2.0, 2.0);
    }

    @Test
    @DisplayName("a note with no note-off is held to the end, never left open or negative")
    void anUnterminatedNoteIsHeldToTheEnd() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track held = sequence.createTrack();
        noteOn(held, 480, 0, 60, 90);
        Track other = sequence.createTrack();
        noteOn(other, 0, 1, 48, 90);
        noteOff(other, 1920, 1, 48);

        Score score = transcriber.transcribe(sequence);
        Note note = score.tracks().get(0).notes().get(0);
        assertThat(note.onsetBeat()).contains(1.0);
        // To beat 4, the end of the piece: three beats, and positive, which is
        // the whole point. An unterminated note is the easiest way to end up
        // with a negative or infinite duration.
        assertThat(note.durationBeats()).contains(3.0);
        assertThat(note.durationSeconds()).isGreaterThan(0);
        assertThat(messages).anyMatch(message -> message.contains("no note-off"));
    }

    @Test
    @DisplayName("a note-off with nothing sounding is ignored rather than producing a note")
    void anOrphanNoteOffIsIgnored() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOff(track, 0, 0, 60);
        noteOn(track, 480, 0, 62, 90);
        noteOff(track, 960, 0, 62);

        List<Note> notes = transcriber.transcribe(sequence).tracks().get(0).notes();
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).midiPitch()).isEqualTo(62);
    }

    @Test
    @DisplayName("a zero-length note is dropped, not given an invented duration")
    void zeroLengthNotesAreDropped() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 480, 0, 60);
        // On and off at the same tick, at the very end of the piece, so the
        // note is zero-length whether it is paired with its own note-off or
        // held to the end of the sequence.
        noteOn(track, 480, 0, 67, 90);
        noteOff(track, 480, 0, 67);

        List<Note> notes = transcriber.transcribe(sequence).tracks().get(0).notes();
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).midiPitch()).isEqualTo(60);
        assertThat(messages).anyMatch(message -> message.contains("zero length"));
    }

    @Test
    @DisplayName("a half note played a tick late is still exactly two beats long")
    void aDurationIsMeasuredInTicksRatherThanBetweenTwoConvertedOnsets() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        // One tick late, which is what a human-played file looks like everywhere.
        noteOn(track, 1, 0, 60, 90);
        noteOff(track, 961, 0, 60);

        Note note = transcriber.transcribe(sequence).tracks().get(0).notes().get(0);
        // 961/480 - 1/480 is 1.9999999999999998, not 2. The onset is genuinely
        // off the grid and stays off it; the note value is not, and a quantizer
        // that later sees 1.9999999999999998 has to decide what was meant.
        assertThat(961 / 480.0 - 1 / 480.0).isNotEqualTo(2.0);
        assertThat(note.onsetBeat()).contains(1 / 480.0);
        assertThat(note.durationBeats()).contains(2.0);
    }

    @Test
    @DisplayName("a key signature at the very end of the piece spans nothing and is dropped")
    void aKeySignatureAtTheEndIsDropped() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x59, new byte[] {2, 0});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 480, 0, 60);
        // At the last tick, so it is in force for no time at all. Key rejects a
        // span that does not advance, so keeping it would fail the whole import
        // with a message about the model rather than about the file.
        meta(track, 480, 0x59, new byte[] {-3, 1});

        assertThat(transcriber.transcribe(sequence).keys())
                .singleElement()
                .satisfies(key -> assertThat(key.displayName()).isEqualTo("D major"));
    }

    // ------------------------------------------------------------------- splits

    @Test
    @DisplayName("one track carrying two channels is two parts, not one staff of both")
    void aTrackIsSplitByChannel() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        trackName(track, "Combined");
        noteOn(track, 0, 0, 72, 90);
        noteOff(track, 480, 0, 72);
        noteOn(track, 0, 1, 36, 90);
        noteOff(track, 480, 1, 36);

        List<NoteTrack> tracks = transcriber.transcribe(sequence).tracks();
        assertThat(tracks).extracting(NoteTrack::name)
                .containsExactly("Combined ch 1", "Combined ch 2");
        assertThat(tracks).allSatisfy(part -> assertThat(part.notes()).hasSize(1));
    }

    @Test
    @DisplayName("a single-channel track keeps its plain name")
    void aSingleChannelTrackIsNotSuffixed() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        trackName(track, "Combined");
        noteOn(track, 0, 3, 72, 90);
        noteOff(track, 480, 3, 72);
        assertThat(transcriber.transcribe(sequence).tracks())
                .extracting(NoteTrack::name).containsExactly("Combined");
    }

    @Test
    @DisplayName("an unnamed track is named by its position in the file")
    void anUnnamedTrackGetsAPositionalName() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 0, 0, 72, 90);
        noteOff(track, 480, 0, 72);
        assertThat(transcriber.transcribe(sequence).tracks())
                .extracting(NoteTrack::name).containsExactly("Track 1");
    }

    @Test
    @DisplayName("two parts with the same name are told apart rather than rejected")
    void duplicateNamesAreDisambiguated() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        for (int channel = 0; channel < 3; channel++) {
            Track track = sequence.createTrack();
            trackName(track, "Piano");
            noteOn(track, 0, channel, 60 + channel, 90);
            noteOff(track, 480, channel, 60 + channel);
        }
        // Score requires distinct names within OTHER, so an importer that let
        // three parts share one would fail at the very last step of a successful
        // import, with a message about the model rather than about the file.
        assertThat(transcriber.transcribe(sequence).tracks())
                .extracting(NoteTrack::name)
                .containsExactly("Piano", "Piano (2)", "Piano (3)");
    }

    // -------------------------------------------------------------------- roles

    @Test
    @DisplayName("a second drum kit joins the first rather than becoming a pitched part")
    void aSecondKitIsMergedIntoTheFirst() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        for (int i = 0; i < 2; i++) {
            Track track = sequence.createTrack();
            trackName(track, "Kit " + (i + 1));
            noteOn(track, 0, 9, 42 + i, 90);
            noteOff(track, 480, 9, 42 + i);
        }
        // A score holds at most one track per named role, and this used to
        // demote the second kit to OTHER for that reason. OTHER means an
        // unclassified *pitched* part, and every stage that asks whether a part
        // is pitched asks the role -- so a demoted conga part fed its note
        // numbers to chord estimation, and a file of nothing but two percussion
        // tracks was charted as a major triad. In General MIDI both tracks are
        // the same kit, so they are imported as the one part they describe.
        List<NoteTrack> tracks = transcriber.transcribe(sequence).tracks();
        assertThat(tracks).extracting(NoteTrack::role).containsExactly(PartRole.DRUMS);
        assertThat(tracks.getFirst().notes()).extracting(Note::midiPitch)
                .containsExactly(42, 43);
        assertThat(messages).anyMatch(message -> message.contains("Kit 2")
                && message.contains("same kit"));
    }

    @Test
    @DisplayName("a bassoon is not a bass part")
    void aBassoonIsNotABassPart() throws Exception {
        assertThat(roleOfPartNamed("Bassoon")).isEqualTo(PartRole.OTHER);
        assertThat(roleOfPartNamed("Basset Horn")).isEqualTo(PartRole.OTHER);
    }

    @Test
    @DisplayName("a bass drum routed off channel 10 is not a bass part either")
    void aBassDrumIsNotABassPart() throws Exception {
        assertThat(roleOfPartNamed("Bass Drum")).isEqualTo(PartRole.OTHER);
        assertThat(roleOfPartNamed("Bass Kick")).isEqualTo(PartRole.OTHER);
    }

    @Test
    @DisplayName("the names that do mean bass are recognised")
    void bassNamesAreRecognised() throws Exception {
        assertThat(roleOfPartNamed("Bass")).isEqualTo(PartRole.BASS);
        assertThat(roleOfPartNamed("bass")).isEqualTo(PartRole.BASS);
        assertThat(roleOfPartNamed("Fretless Bass")).isEqualTo(PartRole.BASS);
        assertThat(roleOfPartNamed("Bassline")).isEqualTo(PartRole.BASS);
    }

    /** The role given to a single non-percussion part with a given track name. */
    private PartRole roleOfPartNamed(String name) throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        trackName(track, name);
        noteOn(track, 0, 0, 40, 90);
        noteOff(track, 480, 0, 40);
        return transcriber.transcribe(sequence).tracks().get(0).role();
    }

    // ------------------------------------------------------------ malformed data

    @Test
    @DisplayName("a frame-timed file is refused rather than mis-scaled into musical time")
    void smpteFilesAreRefused() throws Exception {
        Sequence sequence = new Sequence(Sequence.SMPTE_25, 40);
        Track track = sequence.createTrack();
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 40, 0, 60);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> transcriber.transcribe(sequence))
                .withMessageContaining("SMPTE");
    }

    @Test
    @DisplayName("a type 2 file is refused, because its tracks are not simultaneous")
    void type2FilesAreRefused(@TempDir Path directory) throws Exception {
        // Hand-built, because the JDK's writer only emits types 0 and 1. Header
        // chunk declaring format 2 with one track at 480 ticks per quarter, then
        // a track holding nothing but an end-of-track event.
        byte[] file = {
            'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 2, 0, 1, 0x01, (byte) 0xE0,
            'M', 'T', 'r', 'k', 0, 0, 0, 4, 0, (byte) 0xFF, 0x2F, 0};
        Path path = directory.resolve("patterns.mid");
        Files.write(path, file);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> transcriber.transcribe(path))
                .withMessageContaining("type 2");
    }

    @Test
    @DisplayName("a file with everything at tick 0 has no music in it")
    void anEmptySequenceIsRefused() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        trackName(track, "Empty");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> transcriber.transcribe(sequence))
                .withMessageContaining("no music");
    }

    @Test
    @DisplayName("a tempo of zero microseconds per quarter is refused in its own terms")
    void aZeroTempoEventIsRefused() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x51, new byte[] {0, 0, 0});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 480, 0, 60);
        // Left alone this reaches TempoSegment as an infinite BPM and is rejected
        // there, complaining about a number the file never contained.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> transcriber.transcribe(sequence))
                .withMessageContaining("zero microseconds");
    }

    @Test
    @DisplayName("a time signature the model cannot express is skipped, not fatal")
    void anUnrepresentableTimeSignatureIsSkipped() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        // A 0/128 signature: numerator below one, denominator beyond a 64th note.
        meta(track, 0, 0x58, new byte[] {0, 7, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 480, 0, 60);
        Score score = transcriber.transcribe(sequence);
        assertThat(score.tempoMap().initialTimeSignature()).isEqualTo(TimeSignature.FOUR_FOUR);
        assertThat(messages).anyMatch(message -> message.contains("outside what the model"));
    }

    @Test
    @DisplayName("a key signature outside the circle of fifths is skipped, not spelled")
    void anUnrepresentableKeySignatureIsSkipped() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x59, new byte[] {9, 0});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 480, 0, 60);
        assertThat(transcriber.transcribe(sequence).keys()).isEmpty();
        assertThat(messages).anyMatch(message -> message.contains("not a key signature"));
    }

    @Test
    @DisplayName("control characters and runaway lengths do not reach a staff label")
    void trackNamesAreCleaned() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        // Written as escapes rather than as the bytes themselves: a source file
        // holding a raw control character is treated as binary by git, and stops
        // being diffable and reviewable.
        String raw = "Lead\u0001Vocal\u0007" + "x".repeat(500);
        meta(track, 0, 0x03, raw.getBytes(StandardCharsets.UTF_8));
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 480, 0, 60);

        // This string becomes a staff label, a LilyPond string literal and part
        // of a file name, none of which want a bell character in them.
        String imported = transcriber.transcribe(sequence).tracks().get(0).name();
        assertThat(imported.chars().anyMatch(Character::isISOControl)).isFalse();
        assertThat(imported).startsWith("Lead Vocal ");
        assertThat(imported.codePointCount(0, imported.length()))
                .isLessThanOrEqualTo(96);
    }

    @Test
    @DisplayName("a name that is not UTF-8 becomes text rather than an exception")
    void aLatinOneTrackNameIsStillReadable() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        // 0xE9 is e-acute in Latin-1 and an incomplete sequence in UTF-8.
        meta(track, 0, 0x03, new byte[] {'C', 'l', (byte) 0xE9, 's'});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 480, 0, 60);
        assertThat(transcriber.transcribe(sequence).tracks().get(0).name())
                .isEqualTo("Clés");
    }

    @Test
    @DisplayName("a directory is refused before anything tries to parse it")
    void aDirectoryIsRefused(@TempDir Path directory) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> transcriber.transcribe(directory))
                .withMessageContaining("not a readable file");
    }

    // -------------------------------------------------- round 3 review findings

    @Test
    @DisplayName("a meter dropped by a chain of displacements is still reported")
    void aReportSurvivesTheRemovalOfTheEntryItWasParkedOn() throws Exception {
        // Three changes inside bar 1, each displacing the last. The report that
        // the 3/4 never takes effect was parked on the entry the 3/4 was
        // replaced by, and when the 7/8 in turn displaced that entry the report
        // went with it -- so a meter the file declares vanished from the output
        // entirely. Ordinary resolution and ordinary meters; nothing exotic is
        // needed to lose it.
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {4, 2, 24, 8});
        meta(track, 480, 0x58, new byte[] {3, 2, 24, 8});
        meta(track, 960, 0x58, new byte[] {7, 3, 24, 8});
        meta(track, 1440, 0x58, new byte[] {5, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 16 * PPQ, 0, 60);

        Score score = transcriber.transcribe(sequence);
        assertThat(score.tempoMap().meterChanges()).containsExactly(
                new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR),
                new TempoMap.MeterChange(1, new TimeSignature(5, 4)));
        assertThat(messages).anyMatch(message ->
                message.contains("3/4") && message.contains("never takes effect"));
        assertThat(messages).anyMatch(message ->
                message.contains("7/8") && message.contains("never takes effect"));
        // And the retracted line -- the 3/4's own "it moved" report, which
        // stopped being true the moment the 7/8 displaced it -- is gone.
        assertThat(messages).noneMatch(message ->
                message.contains("3/4") && message.contains("does not fall on a bar line"));
    }

    @Test
    @DisplayName("a change half a tick past a bar line is on it, not after it")
    void theToleranceIsHalfATickWide() throws Exception {
        // 1/64 at 120 ticks per quarter puts bar lines every 7.5 ticks, so bar 7
        // begins at tick 52.5 -- half a tick below the change at tick 53. That
        // is the widest gap the tolerance admits, and it is the only kind of
        // input that exercises the step which pulls the bar count back: every
        // other fixture has its events exactly on a bar line or a whole bar
        // away, where the ceiling alone is already right.
        Sequence sequence = new Sequence(Sequence.PPQ, 120);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {1, 6, 24, 8});
        meta(track, 53, 0x58, new byte[] {4, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 480, 0, 60);

        Score score = transcriber.transcribe(sequence);
        assertThat(score.tempoMap().meterChanges()).containsExactly(
                new TempoMap.MeterChange(0, new TimeSignature(1, 64)),
                new TempoMap.MeterChange(7, TimeSignature.FOUR_FOUR));
        // Within the tolerance is on the line, so there is nothing to report.
        assertThat(messages).noneMatch(message ->
                message.contains("does not fall on a bar line"));
    }

    @Test
    @DisplayName("a bar shorter than the tolerance does not pull the change a bar early")
    void theToleranceDoesNotSwallowAWholeBar() throws Exception {
        // At 8 ticks per quarter a 1/64 bar is half a tick long, so the bar line
        // below an exactly-on-the-line change is inside the tolerance too.
        // Decrementing then puts the change one bar early -- and the same
        // tolerance calls the result "on a bar line", so nothing is reported.
        // Tick 8 is beat 1.0, which is exactly bar line 16 of a 1/64 meter.
        Sequence sequence = new Sequence(Sequence.PPQ, 8);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {1, 6, 24, 8});
        meta(track, 8, 0x58, new byte[] {3, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 64, 0, 60);

        assertThat(transcriber.transcribe(sequence).tempoMap().meterChanges())
                .containsExactly(
                        new TempoMap.MeterChange(0, new TimeSignature(1, 64)),
                        new TempoMap.MeterChange(16, TimeSignature.THREE_FOUR));
    }

    @Test
    @DisplayName("a bar line is recorded relative to the one before it, not to the origin")
    void aRecordedBarLineIsMeasuredFromThePreviousOne() throws Exception {
        // Four surviving changes, which is what it takes: the stored bar line is
        // only re-used with a non-zero base from the third change onwards, so a
        // version that dropped the base term and stored the offset alone agrees
        // with this one on every shorter file.
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {6, 3, 36, 8});
        meta(track, 3360, 0x58, new byte[] {3, 2, 24, 8});
        meta(track, 5760, 0x58, new byte[] {5, 2, 24, 8});
        meta(track, 9600, 0x58, new byte[] {4, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 12000, 0, 60);

        assertThat(transcriber.transcribe(sequence).tempoMap().meterChanges())
                .containsExactly(
                        new TempoMap.MeterChange(0, TimeSignature.SIX_EIGHT),
                        new TempoMap.MeterChange(3, TimeSignature.THREE_FOUR),
                        new TempoMap.MeterChange(4, new TimeSignature(5, 4)),
                        new TempoMap.MeterChange(6, TimeSignature.FOUR_FOUR));
    }

    @Test
    @DisplayName("a change exactly half a tick from a bar line is on it, inclusively")
    void theToleranceIsInclusiveAtItsBoundary() throws Exception {
        // The boundary has to be hit exactly in binary, which needs a power-of-
        // two resolution: at 120 ticks per quarter the distance that looks like
        // half a tick is a hair under it, so an exclusive comparison agrees with
        // an inclusive one and the two cannot be told apart.
        //
        // 3/32 at 4 ticks per quarter is 0.375 quarter beats to the bar and half
        // a tick is 0.125, both exact. Bar 1 begins at beat 0.375 and the change
        // is at tick 2, which is beat 0.5 -- exactly 0.125 away.
        Sequence sequence = new Sequence(Sequence.PPQ, 4);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {3, 5, 24, 8});
        meta(track, 2, 0x58, new byte[] {3, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 8, 0, 60);

        Score score = transcriber.transcribe(sequence);
        assertThat(score.tempoMap().meterChanges()).containsExactly(
                new TempoMap.MeterChange(0, new TimeSignature(3, 32)),
                new TempoMap.MeterChange(1, TimeSignature.THREE_FOUR));
        assertThat(messages).noneMatch(message ->
                message.contains("does not fall on a bar line"));
    }

    @Test
    @DisplayName("the bar count is never pulled back below the bar a change lands on")
    void theToleranceNeverPullsBackFromAnAlreadyZeroBarCount() throws Exception {
        // The pull-back exists to undo an overshooting ceiling, so it must not
        // run when the ceiling did not overshoot. Where a change is displaced --
        // the count is already zero -- decrementing gives minus one, and the
        // entry then lands on a bar before the one preceding it, which the tempo
        // map rejects outright.
        //
        // Reaching it needs the bar line one below to be within the tolerance
        // too, which needs a very short bar: 1/64 is 0.0625 quarter beats, so
        // bar 1 of the displaced change begins at beat 4 and the bar below it at
        // 3.9375 -- tick 1890, exactly where the last change sits.
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {4, 2, 24, 8});
        meta(track, 960, 0x58, new byte[] {1, 6, 24, 8});
        meta(track, 1890, 0x58, new byte[] {3, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 4 * PPQ, 0, 60);

        assertThat(transcriber.transcribe(sequence).tempoMap().meterChanges())
                .containsExactly(
                        new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR),
                        new TempoMap.MeterChange(1, TimeSignature.THREE_FOUR));
    }

    // -------------------------------------------------- round 2 review findings

    @Test
    @DisplayName("a change one tick into a very wide opening bar does not take the walk apart")
    void aChangeJustAfterTheOriginDoesNotRemoveTheBarZeroEntry() throws Exception {
        // 64/1 is 256 quarter beats to the bar, and 32767 is the highest tick
        // resolution a MIDI header can declare. With the "is this on a bar line"
        // tolerance denominated in bars, a millionth of a bar is wider than a
        // tick here, so an event at tick 1 counted as being on bar zero's own
        // line -- which sent the walk into the branch that removes an entry, and
        // the entry was the bar-0 one the tempo map requires to exist. The index
        // went to -1 and the import died with an IndexOutOfBoundsException,
        // which is not among the failures this class documents.
        Sequence sequence = new Sequence(Sequence.PPQ, 32767);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {64, 0, 24, 8});
        meta(track, 1, 0x58, new byte[] {3, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 32767, 0, 60);

        Score score = transcriber.transcribe(sequence);
        assertThat(score.tempoMap().meterChanges()).containsExactly(
                new TempoMap.MeterChange(0, new TimeSignature(64, 1)),
                new TempoMap.MeterChange(1, TimeSignature.THREE_FOUR));
    }

    @Test
    @DisplayName("a change after an undone one is measured from the bar line that survived")
    void undoingAChangeRestoresItsBarLineAsWellAsItsMeter() throws Exception {
        // 4/4; 3/4 mid-bar, which moves to bar 1; 4/4 again before bar 1 begins,
        // which displaces the 3/4 and restores the opening meter, so the entry
        // and the bar line measured from it both have to come out. The two
        // changes after it are what make the bar line observable: if only the
        // meter were restored and the stale bar line left behind, the last
        // change lands at bar 4 instead of bar 3 and every bar number after it
        // is wrong.
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {4, 2, 24, 8});
        meta(track, 2 * PPQ, 0x58, new byte[] {3, 2, 24, 8});
        meta(track, (long) (3.5 * PPQ), 0x58, new byte[] {4, 2, 24, 8});
        meta(track, 6 * PPQ, 0x58, new byte[] {7, 3, 24, 8});
        meta(track, 10 * PPQ, 0x58, new byte[] {5, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 16 * PPQ, 0, 60);

        assertThat(transcriber.transcribe(sequence).tempoMap().meterChanges())
                .containsExactly(
                        new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR),
                        new TempoMap.MeterChange(2, new TimeSignature(7, 8)),
                        new TempoMap.MeterChange(3, new TimeSignature(5, 4)));
    }

    @Test
    @DisplayName("a bar line recorded after a change is in quarter beats, not counted beats")
    void aRecordedBarLineIsInQuarterBeats() throws Exception {
        // The bar line stored with a change is used to place the *next* one, so
        // it needs a second change after a compound meter to be observable at
        // all. A 6/8 bar is three quarter beats and two counted beats: storing
        // the counted count puts the 5/4 a bar late.
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {6, 3, 36, 8});
        meta(track, 7 * PPQ, 0x58, new byte[] {3, 2, 24, 8});
        meta(track, 12 * PPQ, 0x58, new byte[] {5, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 20 * PPQ, 0, 60);

        assertThat(transcriber.transcribe(sequence).tempoMap().meterChanges())
                .containsExactly(
                        new TempoMap.MeterChange(0, TimeSignature.SIX_EIGHT),
                        new TempoMap.MeterChange(3, TimeSignature.THREE_FOUR),
                        new TempoMap.MeterChange(4, new TimeSignature(5, 4)));
    }

    @Test
    @DisplayName("a change that both displaces another and sits mid-bar still says it moved")
    void aSupersedingChangeStillReportsThatItMoved() throws Exception {
        // The 6/8 at beat 6 displaces the 5/4 and takes effect at beat 7, a
        // whole quarter beat later than the file puts it. An earlier draft
        // reported the move only on the non-superseding path, so this one was
        // silent -- and the gap can be a whole bar.
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {4, 2, 24, 8});
        meta(track, 2 * PPQ, 0x58, new byte[] {3, 2, 24, 8});
        meta(track, 5 * PPQ, 0x58, new byte[] {5, 2, 24, 8});
        meta(track, 6 * PPQ, 0x58, new byte[] {6, 3, 36, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 16 * PPQ, 0, 60);

        transcriber.transcribe(sequence);
        assertThat(messages).anyMatch(message ->
                message.contains("6/8") && message.contains("does not fall on a bar line"));
        // Never a negative distance: what is reported is how far the change was
        // moved, which cannot be negative, rather than how far into a bar it
        // fell, which is negative exactly in this case.
        //
        // Matched against the figure the message actually prints. An earlier
        // version of this assertion looked for "(-", which was a marker of the
        // message format this replaced -- so it was true of every possible
        // output and pinned nothing at all.
        assertThat(messages).filteredOn(message -> message.contains("quarter beats later"))
                .isNotEmpty()
                .allMatch(message -> message.matches(".* begins \\d+\\.\\d+ quarter beats later"));
    }

    @Test
    @DisplayName("two tempo events the beat axis cannot separate do not sink the file either")
    void aTempoEventIndistinguishableInBeatsIsSkipped() throws Exception {
        // The sibling of the seconds case, on the other axis. Above 2^53 a tick
        // count is no longer exactly representable, so two consecutive ticks
        // divide to the same beat; TempoMap requires strictly increasing start
        // beats, so without the skip the file is unreadable rather than slightly
        // wrong.
        Sequence sequence = new Sequence(Sequence.PPQ, 1);
        Track track = sequence.createTrack();
        long huge = 1L << 60;
        assertThat((double) huge).isEqualTo((double) (huge + 1));
        tempo(track, huge, 250_000);
        tempo(track, huge + 1, 100_000);
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 1, 0, 60);

        Score score = transcriber.transcribe(sequence);
        assertThat(score.tempoMap().segments()).hasSize(2);
        assertThat(messages).anyMatch(message ->
                message.contains("indistinguishable in time"));
    }

    // -------------------------------------------------- round 1 review findings
    @Test
    @DisplayName("two tempo events the seconds axis cannot separate do not sink the file")
    void aTempoEventIndistinguishableInTimeIsSkipped() throws Exception {
        // Far-fetched but reachable, and the failure is total rather than
        // partial: TempoMap requires strictly increasing start times, so without
        // the skip the whole import throws and the file is unreadable.
        //
        // A very long stretch at the slowest tempo a MIDI file can name puts the
        // seconds axis at 1.7e13, where one ulp is about four milliseconds. One
        // tick at the fastest nameable tempo advances it by a microsecond, which
        // is absorbed: the second and third segments start at the same instant.
        Sequence sequence = new Sequence(Sequence.PPQ, 1);
        Track track = sequence.createTrack();
        tempo(track, 0, 0xFFFFFF);
        tempo(track, 1_000_000_000_000L, 1);
        tempo(track, 1_000_000_000_001L, 500_000);
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 1, 0, 60);

        Score score = transcriber.transcribe(sequence);
        assertThat(score.tempoMap().segments()).hasSize(2);
        assertThat(messages).anyMatch(message ->
                message.contains("indistinguishable in time"));
    }


    @Test
    @DisplayName("a meter that is superseded before its bar begins leaves no change behind")
    void aSupersededMeterDoesNotBecomeAChangeToTheMeterAlreadyInForce() throws Exception {
        // 4/4, then 7/8 a fraction of a bar in, then 4/4 again a fraction later.
        // The 7/8 moves forward to bar 1 and the second 4/4 lands on bar 1 too,
        // so the file's effective meter never changes at all. Recording a change
        // from 4/4 to 4/4 is accepted by the model and answered correctly by
        // timeSignatureAtBar -- and engraved by the notation layer as a
        // redundant time signature in the middle of the piece.
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {4, 2, 24, 8});
        meta(track, 100, 0x58, new byte[] {7, 3, 24, 8});
        meta(track, 200, 0x58, new byte[] {4, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 1920, 0, 60);

        Score score = transcriber.transcribe(sequence);
        assertThat(score.tempoMap().meterChanges())
                .containsExactly(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR));
        assertThat(messages).noneMatch(message -> message.contains("the meter changes"));
        // Still said, even though the change it is about was removed. It is a
        // permanent fact, so it goes into the log outright rather than being
        // attached to any entry -- only a change's own "it moved" line belongs
        // to an entry, because only that line can stop being true.
        assertThat(messages).anyMatch(message ->
                message.contains("7/8") && message.contains("never takes effect"));
    }

    @Test
    @DisplayName("a meter that never takes effect is said to never take effect")
    void aDroppedMeterIsReportedRatherThanVanishing() throws Exception {
        // 4/4, then 3/4 half-way through bar 0, then 5/4 at the bar line. The
        // 3/4 moves forward to bar 1, where the 5/4 displaces it: it is in force
        // nowhere. Saying only "3/4 takes effect at bar 2" would be a false
        // report of a bar the score does not contain in that meter.
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        meta(track, 0, 0x58, new byte[] {4, 2, 24, 8});
        meta(track, 2 * PPQ, 0x58, new byte[] {3, 2, 24, 8});
        meta(track, 4 * PPQ, 0x58, new byte[] {5, 2, 24, 8});
        noteOn(track, 0, 0, 60, 90);
        noteOff(track, 8 * PPQ, 0, 60);

        Score score = transcriber.transcribe(sequence);
        assertThat(score.tempoMap().meterChanges()).containsExactly(
                new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR),
                new TempoMap.MeterChange(1, new TimeSignature(5, 4)));
        assertThat(messages).anyMatch(message ->
                message.contains("3/4") && message.contains("never takes effect"));
        // And no message claims a bar number the score does not hold in 3/4.
        assertThat(messages).noneMatch(message ->
                message.contains("3/4") && message.contains("taking effect at bar"));
    }

    @Test
    @DisplayName("the same pitch on two channels of one track is two notes, not one queue")
    void notePairingIsKeyedByChannelAsWellAsPitch() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        trackName(track, "Split");
        // Interleaved so that pairing by pitch alone crosses the two channels
        // over: it would give each part the other's length.
        noteOn(track, 0, 0, 60, 90);
        noteOn(track, 480, 1, 60, 90);
        noteOff(track, 960, 1, 60);
        noteOff(track, 1440, 0, 60);

        List<NoteTrack> tracks = transcriber.transcribe(sequence).tracks();
        assertThat(tracks).extracting(NoteTrack::name)
                .containsExactly("Split ch 1", "Split ch 2");
        assertThat(tracks.get(0).notes().get(0).onsetBeat()).contains(0.0);
        assertThat(tracks.get(0).notes().get(0).durationBeats()).contains(3.0);
        assertThat(tracks.get(1).notes().get(0).onsetBeat()).contains(1.0);
        assertThat(tracks.get(1).notes().get(0).durationBeats()).contains(1.0);
    }

    @Test
    @DisplayName("an emptied named track does not become the title of the piece")
    void theTitleComesFromTheConductorTrackOnly() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        // Track 0: tempo only, unnamed -- so the file has no title.
        Track conductor = sequence.createTrack();
        meta(conductor, 0, 0x51, new byte[] {0x07, (byte) 0xA1, 0x20});
        // Track 1: a part whose notes have been deleted, which is what a muted
        // or emptied track looks like in a DAW export.
        Track emptied = sequence.createTrack();
        trackName(emptied, "Piano");
        Track sounding = sequence.createTrack();
        trackName(sounding, "Bass");
        noteOn(sounding, 0, 0, 40, 90);
        noteOff(sounding, 480, 0, 40);

        Score score = transcriber.transcribe(sequence);
        assertThat(score.title()).isEmpty();
        assertThat(score.tracks()).extracting(NoteTrack::name).containsExactly("Bass");
    }

    @Test
    @DisplayName("a note dropped for being zero-length is not also reported as unterminated")
    void oneLostNoteIsReportedOnce() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track dangling = sequence.createTrack();
        // At the last tick with no note-off: both unterminated and zero-length,
        // and counting it twice overstates how much of the file was lost.
        noteOn(dangling, 480, 0, 60, 90);
        Track other = sequence.createTrack();
        noteOn(other, 0, 1, 48, 90);
        noteOff(other, 480, 1, 48);

        transcriber.transcribe(sequence);
        assertThat(messages).anyMatch(message -> message.contains("zero length"));
        assertThat(messages).noneMatch(message -> message.contains("no note-off"));
    }

    @Test
    @DisplayName("a file too large to parse within a sane heap is refused, not attempted")
    void anOversizedFileIsRefused(@TempDir Path directory) throws Exception {
        // The cap is on bytes because bytes are all that can be counted before
        // parsing, but a MIDI file expands to roughly 35 times its size in live
        // objects, so above the cap the alternative to this refusal is an
        // OutOfMemoryError thrown from inside the JDK's parser.
        Path file = directory.resolve("huge.mid");
        Files.write(file, new byte[4 * 1024 * 1024 + 1]);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> transcriber.transcribe(file))
                .withMessageContaining("refusing to read");
    }

    // -------------------------------------------------------------- resolutions

    @Test
    @DisplayName("the tick resolution comes from the file, not from a constant")
    void aFileAtAnotherResolutionImportsCorrectly() {
        // 96 ticks per quarter was standard for a decade of hardware sequencers.
        Sequence sequence = MidiFixtures.sequence(96)
                .tempo(120)
                .part("Melody", 0).note(3, 2, 60).build();
        Note note = transcriber.transcribe(sequence).tracks().get(0).notes().get(0);
        assertThat(note.onsetBeat()).contains(3.0);
        assertThat(note.durationBeats()).contains(2.0);
        assertThat(note.onsetSeconds()).isEqualTo(1.5, within(1e-12));
    }

    // ---------------------------------------------------------------- utilities

    private static void noteOn(Track track, long tick, int channel, int pitch, int velocity)
            throws InvalidMidiDataException {
        track.add(new MidiEvent(
                new ShortMessage(ShortMessage.NOTE_ON, channel, pitch, velocity), tick));
    }

    private static void noteOff(Track track, long tick, int channel, int pitch)
            throws InvalidMidiDataException {
        track.add(new MidiEvent(
                new ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), tick));
    }

    private static void trackName(Track track, String name) throws InvalidMidiDataException {
        meta(track, 0, 0x03, name.getBytes(StandardCharsets.UTF_8));
    }

    /** A tempo event, in microseconds per quarter note as the wire format holds it. */
    private static void tempo(Track track, long tick, int microsecondsPerQuarter)
            throws InvalidMidiDataException {
        meta(track, tick, 0x51, new byte[] {
            (byte) (microsecondsPerQuarter >> 16),
            (byte) (microsecondsPerQuarter >> 8),
            (byte) microsecondsPerQuarter});
    }

    private static void meta(Track track, long tick, int type, byte[] data)
            throws InvalidMidiDataException {
        track.add(new MidiEvent(new MetaMessage(type, data, data.length), tick));
    }
}
