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
    @DisplayName("a second drum kit is demoted rather than rejected, and it is said out loud")
    void aSecondKitIsDemoted() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        for (int i = 0; i < 2; i++) {
            Track track = sequence.createTrack();
            trackName(track, "Kit " + (i + 1));
            noteOn(track, 0, 9, 42, 90);
            noteOff(track, 480, 9, 42);
        }
        // A score holds at most one track per named role, so the alternative to
        // demoting is throwing away a part or throwing away the import.
        List<NoteTrack> tracks = transcriber.transcribe(sequence).tracks();
        assertThat(tracks).extracting(NoteTrack::role)
                .containsExactly(PartRole.DRUMS, PartRole.OTHER);
        assertThat(messages).anyMatch(message -> message.contains("Kit 2")
                && message.contains("DRUMS"));
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

    private static void meta(Track track, long tick, int type, byte[] data)
            throws InvalidMidiDataException {
        track.add(new MidiEvent(new MetaMessage(type, data, data.length), tick));
    }
}
