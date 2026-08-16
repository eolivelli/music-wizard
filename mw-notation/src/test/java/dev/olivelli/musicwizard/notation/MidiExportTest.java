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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiFileFormat;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the exported sequence holds, checked against the format rather than
 * against the importer.
 *
 * <p>The importer lives in {@code mw-transcribe} and cannot be reached from
 * here without the notation layer depending on it, so the loop-closure test is
 * in {@code mw-it}. These are the assertions that do not need it: that the
 * events are the ones a Standard MIDI File is made of, at the ticks the score's
 * beats name.
 *
 * <p>Which is a useful division rather than a compromise. A round trip through
 * one project's own reader can be wrong in both directions at once and still
 * pass; these read the bytes.
 */
class MidiExportTest {

    /** Ticks in a quarter note, as the export writes them. */
    private static final int TPQ = MidiExport.TICKS_PER_QUARTER;

    private static final int NOTE_ON = ShortMessage.NOTE_ON;
    private static final int NOTE_OFF = ShortMessage.NOTE_OFF;

    // ------------------------------------------------------------- fixtures

    private static Note note(double onsetBeat, double beats, String spelling) {
        PitchSpelling written = PitchSpelling.parse(spelling);
        return Note.ofSeconds(onsetBeat / 2 + 0.5, beats / 2, written.midiPitch(),
                        Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats)
                .spelledAs(written);
    }

    private static Note unspelled(double onsetBeat, double beats, int midiPitch) {
        return Note.ofSeconds(onsetBeat / 2 + 0.5, beats / 2, midiPitch, Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats);
    }

    private static NoteTrack track(PartRole role, String name, Note... notes) {
        return new NoteTrack(role, name, List.of(notes), Confidence.CERTAIN);
    }

    private static Score score(TimeSignature meter, double quarterBpm, NoteTrack... tracks) {
        Score built = Score.empty(TempoMap.constant(quarterBpm, meter), 60);
        for (NoteTrack track : tracks) {
            built = built.withTrack(track);
        }
        return built;
    }

    // ---------------------------------------------------------------- shape

    @Test
    @DisplayName("the tempo map and the meter go on a track of their own")
    void conductorTrackHoldsNoNotes() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        Sequence sequence = MidiExport.toSequence(
                score(TimeSignature.FOUR_FOUR, 120, voice).withMetadata("A Piece", null));

        assertThat(sequence.getDivisionType()).isEqualTo(Sequence.PPQ);
        assertThat(sequence.getResolution()).isEqualTo(TPQ);
        assertThat(sequence.getTracks()).hasSize(2);
        // No notes on track 0, which is what lets its name be read as the title
        // of the piece rather than as the name of a part.
        assertThat(noteEvents(sequence.getTracks()[0])).isEmpty();
        assertThat(trackName(sequence.getTracks()[0])).contains("A Piece");
        assertThat(trackName(sequence.getTracks()[1])).contains("Voice");
    }

    @Test
    @DisplayName("a score with no title leaves the conductor track unnamed")
    void noTitleNoName() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        Sequence sequence = MidiExport.toSequence(score(TimeSignature.FOUR_FOUR, 120, voice));

        // Absent is absent. A file named "Untitled" claims a title the score
        // never had, and a re-import would come back holding one.
        assertThat(trackName(sequence.getTracks()[0])).isEmpty();
    }

    @Test
    @DisplayName("a note is written at the tick its beat names, and lasts its beats")
    void notesLandOnTheirBeats() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 1, "C4"), note(1.5, 0.5, "D4"), note(4, 2, "E4"));
        Sequence sequence = MidiExport.toSequence(score(TimeSignature.FOUR_FOUR, 120, voice));

        assertThat(noteEvents(sequence.getTracks()[1])).containsExactly(
                new Played(0, NOTE_ON, 0, 60),
                new Played(TPQ, NOTE_OFF, 0, 60),
                new Played(3 * TPQ / 2, NOTE_ON, 0, 62),
                new Played(2 * TPQ, NOTE_OFF, 0, 62),
                new Played(4 * TPQ, NOTE_ON, 0, 64),
                new Played(6 * TPQ, NOTE_OFF, 0, 64));
    }

    @Test
    @DisplayName("a triplet lands on a whole tick, which is why the resolution is what it is")
    void tripletsAreExact() {
        // A third of a beat is not a representable double, so this is the case
        // the resolution was chosen for: 768 ticks a quarter makes a triplet
        // eighth 256 ticks and nothing rounds.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 1.0 / 3, "C4"), note(1.0 / 3, 1.0 / 3, "D4"),
                note(2.0 / 3, 1.0 / 3, "E4"));
        Sequence sequence = MidiExport.toSequence(score(TimeSignature.FOUR_FOUR, 120, voice));

        assertThat(noteEvents(sequence.getTracks()[1])).extracting(Played::tick)
                .containsExactly(0L, 256L, 256L, 512L, 512L, 768L);
    }

    @Test
    @DisplayName("percussion goes on channel 10, which is the only thing that says it is drums")
    void percussionKeepsItsChannel() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        NoteTrack drums = track(PartRole.DRUMS, "Drums", unspelled(0, 1, 36));
        Sequence sequence = MidiExport.toSequence(
                score(TimeSignature.FOUR_FOUR, 120, voice, drums));

        assertThat(noteEvents(sequence.getTracks()[1])).allMatch(played -> played.channel() == 0);
        // Channel 9 counting from zero is General MIDI's channel 10, and it is
        // the whole of what survives a round trip about a part being percussion.
        assertThat(noteEvents(sequence.getTracks()[2])).allMatch(played -> played.channel() == 9);
    }

    @Test
    @DisplayName("pitched parts skip the percussion channel rather than landing on it")
    void pitchedPartsAvoidChannelTen() {
        List<NoteTrack> tracks = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            tracks.add(track(PartRole.OTHER, "Part " + i, unspelled(0, 1, 60 + i)));
        }
        Sequence sequence = MidiExport.toSequence(
                score(TimeSignature.FOUR_FOUR, 120, tracks.toArray(new NoteTrack[0])));

        List<Integer> channels = new ArrayList<>();
        for (int i = 1; i < sequence.getTracks().length; i++) {
            channels.add(noteEvents(sequence.getTracks()[i]).getFirst().channel());
        }
        // A pitched part on channel 10 plays as a drum kit, and re-imports as
        // one: the importer reads the channel before it reads anything else.
        assertThat(channels).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11);
    }

    @Test
    @DisplayName("a bass part is written where it sounds, not where it reads")
    void bassIsNotTransposed() {
        // PartRole.BASS reads an octave above where it sounds and the clef says
        // so. Transposing here as well would play it an octave high, and a
        // re-import would come back an octave high.
        NoteTrack bass = track(PartRole.BASS, "Bass", unspelled(0, 4, 40));
        Sequence sequence = MidiExport.toSequence(score(TimeSignature.FOUR_FOUR, 120, bass));

        assertThat(noteEvents(sequence.getTracks()[1]).getFirst().data1()).isEqualTo(40);
    }

    @Test
    @DisplayName("a tempo event holds whole microseconds per quarter note")
    void tempoIsMicrosecondsPerQuarter() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        Sequence sequence = MidiExport.toSequence(score(TimeSignature.FOUR_FOUR, 120, voice));

        byte[] data = metaData(sequence.getTracks()[0], 0x51, 0);
        int microseconds = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        assertThat(microseconds).isEqualTo(500_000);

        // 140 BPM is 428571.43 microseconds and is not representable. The file
        // stores 428571; the score comes back at 140.00014 BPM. That is what the
        // format holds, and a test that expected exactly 140 would be a test
        // asking to have its tolerance widened.
        Sequence odd = MidiExport.toSequence(score(TimeSignature.FOUR_FOUR, 140, voice));
        byte[] oddData = metaData(odd.getTracks()[0], 0x51, 0);
        int oddMicroseconds =
                ((oddData[0] & 0xFF) << 16) | ((oddData[1] & 0xFF) << 8) | (oddData[2] & 0xFF);
        assertThat(oddMicroseconds).isEqualTo(428_571);
        assertThat(60_000_000.0 / oddMicroseconds).isNotEqualTo(140.0);
    }

    @Test
    @DisplayName("a tempo change is written at the beat it happens on")
    void tempoChangesKeepTheirPlace() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"),
                note(4, 4, "D4"));
        TempoMap tempoMap = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120),
                        new TempoMap.TempoSegment(4, 2, 60)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        Score built = Score.empty(tempoMap, 60).withTrack(voice);

        List<Long> ticks = metaTicks(MidiExport.toSequence(built).getTracks()[0], 0x51);
        assertThat(ticks).containsExactly(0L, 4L * TPQ);
    }

    @Test
    @DisplayName("a meter change is written at the bar line it takes effect on")
    void meterChangesLandOnTheirBarLine() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 4, "C4"), note(4, 3, "D4"), note(7, 3, "E4"));
        Score built = score(TimeSignature.FOUR_FOUR, 120, voice);
        built = built.withTempoMap(built.tempoMap().withMeterChange(1, TimeSignature.THREE_FOUR));
        Sequence sequence = MidiExport.toSequence(built);

        // Bar 1 begins after one 4/4 bar, which is four quarters rather than the
        // three the new meter holds. Taking the new meter's bar length would put
        // the change a beat early and every bar after it in the wrong place.
        assertThat(metaTicks(sequence.getTracks()[0], 0x58)).containsExactly(0L, 4L * TPQ);
        byte[] first = metaData(sequence.getTracks()[0], 0x58, 0);
        byte[] second = metaData(sequence.getTracks()[0], 0x58, 1);
        assertThat(first[0]).isEqualTo((byte) 4);
        // The denominator is stored as a power of two: 2 means a quarter note.
        assertThat(first[1]).isEqualTo((byte) 2);
        assertThat(second[0]).isEqualTo((byte) 3);
        assertThat(second[1]).isEqualTo((byte) 2);
    }

    @Test
    @DisplayName("a 6/8 meter is written as 6/8 rather than as its quarter-beat length")
    void compoundMeterKeepsItsDenominator() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 3, "C4"));
        Sequence sequence = MidiExport.toSequence(score(TimeSignature.SIX_EIGHT, 180, voice));

        byte[] meter = metaData(sequence.getTracks()[0], 0x58, 0);
        assertThat(meter[0]).isEqualTo((byte) 6);
        assertThat(meter[1]).isEqualTo((byte) 3);
    }

    @Test
    @DisplayName("a key signature is written as a signed count of sharps and a mode")
    void keySignature() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        Score built = score(TimeSignature.FOUR_FOUR, 120, voice)
                .withKeys(List.of(Key.ofSeconds(PitchSpelling.parse("Eb3"), Mode.MAJOR,
                        0, 60, Confidence.CERTAIN)));

        byte[] key = metaData(MidiExport.toSequence(built).getTracks()[0], 0x59, 0);
        // Three flats, major. The byte is signed, which is what lets one number
        // carry both directions round the circle of fifths.
        assertThat(key[0]).isEqualTo((byte) -3);
        assertThat(key[1]).isEqualTo((byte) 0);
    }

    @Test
    @DisplayName("a minor key says so, which is the half of it the tonic cannot carry")
    void minorKeySignature() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        Score built = score(TimeSignature.FOUR_FOUR, 120, voice)
                .withKeys(List.of(Key.ofSeconds(PitchSpelling.parse("C4"), Mode.MINOR,
                        0, 60, Confidence.CERTAIN)));

        byte[] key = metaData(MidiExport.toSequence(built).getTracks()[0], 0x59, 0);
        assertThat(key[0]).isEqualTo((byte) -3);
        assertThat(key[1]).isEqualTo((byte) 1);
    }

    @Test
    @DisplayName("a note recorded at velocity zero is still struck")
    void silentNotesAreAudible() {
        Note silent = new Note(0.5, 0.5, 60, 0, Optional.empty(),
                Optional.of(0.0), Optional.of(1.0), Confidence.CERTAIN);
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(silent),
                Confidence.CERTAIN);
        Sequence sequence = MidiExport.toSequence(score(TimeSignature.FOUR_FOUR, 120, voice));

        // A note-on at velocity zero is a note-off in every reader, so writing
        // the model's zero through would delete the note rather than soften it.
        assertThat(firstVelocity(sequence.getTracks()[1])).isEqualTo(1);
    }

    @Test
    @DisplayName("a note shorter than a tick is played briefly rather than dropped")
    void subTickNotesSurviveAsOneTick() {
        // Legal input: Note only requires a positive duration, so a caller who
        // built one by hand rather than through the quantizer can reach this.
        // A note-on and a note-off at the same tick is a note every reader
        // discards -- including this project's importer -- so the floor is what
        // keeps it from vanishing. The floor was once untested.
        Note brief = new Note(0.5, 0.001, 60, 80, Optional.empty(),
                Optional.of(0.0), Optional.of(1.0 / 4000), Confidence.CERTAIN);
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(brief),
                Confidence.CERTAIN);
        Sequence sequence = MidiExport.toSequence(score(TimeSignature.FOUR_FOUR, 120, voice));

        assertThat(noteEvents(sequence.getTracks()[1])).containsExactly(
                new Played(0, NOTE_ON, 0, 60),
                new Played(1, NOTE_OFF, 0, 60));
    }

    @Test
    @DisplayName("a tempo too slow for a tempo event is refused rather than truncated")
    void aTempoTheFormatCannotHoldIsRefused() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        // Microseconds per quarter note is three bytes, so the slowest tempo a
        // file can name is 60,000,000 / 0xFFFFFF, about 3.576 BPM. TempoSegment
        // permits anything positive, so a score can carry a slower one -- and
        // truncating it to three bytes would write a tempo the score never had,
        // silently and wildly wrong. This guard was once live
        // and untested.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiExport.toSequence(
                        score(TimeSignature.FOUR_FOUR, 3.0, voice)))
                .withMessageContaining("outside what a MIDI tempo event can express");

        // Just above the boundary, which is what says the guard is at the edge
        // rather than somewhere convenient. The value is named rather than
        // bounded: reconstructing three bytes
        // and asserting the result fits in three bytes is true by construction
        // and asserts nothing. 60,000,000 microseconds a minute over 3.6 beats.
        Sequence slow = MidiExport.toSequence(score(TimeSignature.FOUR_FOUR, 3.6, voice));
        byte[] data = metaData(slow.getTracks()[0], 0x51, 0);
        int microseconds = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        assertThat(microseconds).isEqualTo(16_666_667);
    }

    @Test
    @DisplayName("an unquantized note is refused rather than placed by its seconds")
    void unquantizedNotesAreRefused() {
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice",
                List.of(Note.ofSeconds(0, 1, 60, Confidence.CERTAIN)), Confidence.CERTAIN);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiExport.toSequence(
                        score(TimeSignature.FOUR_FOUR, 120, voice)))
                .withMessageContaining("quantize before exporting MIDI");
    }

    @Test
    @DisplayName("a score with no notes is refused rather than written empty")
    void emptyScoresAreRefused() {
        Score empty = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 60);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MidiExport.toSequence(empty))
                .withMessageContaining("no MIDI to write");
    }

    @Test
    @DisplayName("the file on disk is a type 1 Standard MIDI File this JDK can read back")
    void writesAReadableFile(@TempDir Path directory) {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C4"));
        NoteTrack bass = track(PartRole.BASS, "Bass", unspelled(0, 4, 36));
        Path file = MidiExport.write(score(TimeSignature.FOUR_FOUR, 120, voice, bass),
                directory.resolve("score.mid"));

        try {
            assertThat(Files.size(file)).isPositive();
            MidiFileFormat format = MidiSystem.getMidiFileFormat(file.toFile());
            // Type 1, because type 0 flattens the parts into one track and a
            // re-import would have nothing to split them on.
            assertThat(format.getType()).isEqualTo(1);
            assertThat(format.getResolution()).isEqualTo(TPQ);
            Sequence read = MidiSystem.getSequence(file.toFile());
            assertThat(read.getTracks()).hasSize(3);
            assertThat(read.getTickLength()).isEqualTo(4L * TPQ);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InvalidMidiDataException e) {
            throw new AssertionError("the written file is not a readable MIDI file", e);
        }
    }

    // ----------------------------------------------------------------- probes

    /**
     * One note event, in the terms the format holds it.
     *
     * <p>Velocity is deliberately not a component: it would have to be repeated
     * in every expected event of every timing assertion, where it is noise.
     * {@link #firstVelocity} asks about it where it is the point.
     */
    private record Played(long tick, int command, int channel, int data1) {
    }

    /** How hard the first note of a track is struck. */
    private static int firstVelocity(Track track) {
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == NOTE_ON) {
                return message.getData2();
            }
        }
        throw new AssertionError("no note-on in this track");
    }

    private static List<Played> noteEvents(Track track) {
        List<Played> events = new ArrayList<>();
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage message
                    && (message.getCommand() == NOTE_ON || message.getCommand() == NOTE_OFF)) {
                events.add(new Played(track.get(i).getTick(), message.getCommand(),
                        message.getChannel(), message.getData1()));
            }
        }
        return events;
    }

    private static List<byte[]> metaEvents(Track track, int type) {
        List<byte[]> found = new ArrayList<>();
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == type) {
                found.add(meta.getData());
            }
        }
        return found;
    }

    private static byte[] metaData(Track track, int type, int index) {
        List<byte[]> found = metaEvents(track, type);
        assertThat(found).as("meta events of type 0x%x", type).hasSizeGreaterThan(index);
        return found.get(index);
    }

    private static List<Long> metaTicks(Track track, int type) {
        List<Long> ticks = new ArrayList<>();
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == type) {
                ticks.add(track.get(i).getTick());
            }
        }
        return ticks;
    }

    private static Optional<String> trackName(Track track) {
        List<byte[]> names = metaEvents(track, 0x03);
        return names.isEmpty() ? Optional.empty()
                : Optional.of(new String(names.getFirst(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
