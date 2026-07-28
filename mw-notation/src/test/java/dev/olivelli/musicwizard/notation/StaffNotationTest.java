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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden files over the generated text, plus assertions that do not depend on
 * them.
 *
 * <p>A golden file on its own proves only that the emitter has not changed, so
 * every one of these was read as music before it was recorded — engraved with
 * LilyPond and checked against what the fixture says the part plays. The
 * bar-length test below is the part that keeps proving something after that:
 * whatever the emitter does, every bar it writes must add up to its meter, and
 * LilyPond will engrave a bar that does not without saying a word.
 */
class StaffNotationTest {

    /**
     * Set {@code -Dmw.golden.update=true} to rewrite the golden files from the
     * current output. Read the diff before committing it: a golden file
     * regenerated without being read asserts nothing at all.
     */
    private static final String UPDATE_PROPERTY = "mw.golden.update";

    // ------------------------------------------------------------- fixtures

    private static PitchSpelling pitch(String name) {
        return PitchSpelling.parse(name);
    }

    /** A note with musical timing, as the quantizer would leave it. */
    private static Note note(double onsetBeat, double beats, String spelling) {
        PitchSpelling written = pitch(spelling);
        // The seconds are what a 120 BPM reading of the beats would give. They
        // are deliberately not what the emitter reads; if it ever did, these
        // tests would still pass and the beat axis would have stopped mattering.
        return Note.ofSeconds(onsetBeat / 2 + 0.5, beats / 2, written.midiPitch(),
                        Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats)
                .spelledAs(written);
    }

    /** A note the pipeline never chose a spelling for. */
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

    private static Key key(String tonic, Mode mode) {
        return Key.ofSeconds(pitch(tonic), mode, 0, 60, Confidence.CERTAIN);
    }

    // --------------------------------------------------------------- golden

    @Test
    @DisplayName("a melody in common time")
    void melodyInCommonTime() {
        // C D E F | G2 A2 | eighths through the bar | a bar with a rest in it.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 1, "C4"), note(1, 1, "D4"), note(2, 1, "E4"), note(3, 1, "F4"),
                note(4, 2, "G4"), note(6, 2, "A4"),
                note(8, 0.5, "C5"), note(8.5, 0.5, "B4"), note(9, 0.5, "A4"),
                note(9.5, 0.5, "G4"), note(10, 2, "F4"),
                note(13, 1, "E4"), note(14, 2, "C4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice)
                .withKeys(List.of(key("C4", Mode.MAJOR)))
                .withMetadata("Scale Practice", "Anonymous");

        assertGolden("melody-common-time", StaffNotation.toLilyPond(score, voice));
    }

    @Test
    @DisplayName("a note held across a bar line becomes two tied notes")
    void tieAcrossBarLine() {
        // The G starts on beat three of bar one and lasts three beats, so it is
        // written as a half note tied over the bar line to a quarter.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 2, "C4"), note(2, 3, "G4"), note(5, 1, "E4"),
                note(6, 4.5, "C4"), note(10.5, 1.5, "D4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        assertGolden("tie-across-barline", StaffNotation.toLilyPond(score, voice));
    }

    @Test
    @DisplayName("a melody in 6/8 beams in two groups of three")
    void melodyInSixEight() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 0.5, "C4"), note(0.5, 0.5, "D4"), note(1, 0.5, "E4"),
                note(1.5, 1, "F4"), note(2.5, 0.5, "G4"),
                note(3, 2, "A4"), note(5, 1, "G4"),
                note(6, 3, "F4"));
        // 120 dotted-quarter pulses a minute is 180 quarter notes a minute; the
        // mark printed over the staff has to be the first of those.
        Score score = score(TimeSignature.SIX_EIGHT, 180, voice);

        assertGolden("melody-six-eight", StaffNotation.toLilyPond(score, voice));
    }

    @Test
    @DisplayName("a bass part is written in bass clef, an octave above where it sounds")
    void bassLine() {
        // No spellings at all, in a flat key: the A flats must not come out as
        // G sharps.
        NoteTrack bass = track(PartRole.BASS, "Bass",
                unspelled(0, 1, 39), unspelled(1, 1, 39), unspelled(2, 1, 44),
                unspelled(3, 1, 46), unspelled(4, 4, 32));
        Score score = score(TimeSignature.FOUR_FOUR, 120, bass)
                .withKeys(List.of(key("Eb3", Mode.MAJOR)));

        assertGolden("bass-line", StaffNotation.toLilyPond(score, bass));
    }

    @Test
    @DisplayName("a melody starting before the first downbeat gets a pickup bar")
    void pickupBar() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(3, 1, "G4"),
                note(4, 1.5, "C5"), note(5.5, 0.5, "B4"), note(6, 2, "A4"),
                note(8, 4, "G4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        assertGolden("pickup-bar", StaffNotation.toLilyPond(score, voice));
    }

    @Test
    @DisplayName("a bar nobody plays in gets one rest, whatever the meter")
    void restsAndEmptyBars() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 1, "A4"), note(2, 1, "B4"),
                note(9, 1, "C5"), note(10, 2, "D5"));
        Score score = score(TimeSignature.THREE_FOUR, 90, voice)
                .withKeys(List.of(key("A4", Mode.MINOR)));

        assertGolden("rests-and-empty-bars", StaffNotation.toLilyPond(score, voice));
    }

    @Test
    @DisplayName("a meter change is written where it takes effect")
    void meterChange() {
        TempoMap map = new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR),
                        new TempoMap.MeterChange(1, TimeSignature.THREE_FOUR)));
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 4, "C4"),
                note(4, 1, "D4"), note(5, 2, "E4"),
                note(7, 3, "F4"));
        Score score = Score.empty(map, 60).withTrack(voice);

        assertGolden("meter-change", StaffNotation.toLilyPond(score, voice));
    }

    @Test
    @DisplayName("simultaneous notes become a chord and an overlap is cut short")
    void chordsAndOverlaps() {
        NoteTrack piano = track(PartRole.PIANO_LEFT_HAND, "Piano",
                // A triad, all three notes starting together.
                note(0, 2, "C3"), note(0, 2, "E3"), note(0, 2, "G3"),
                // A pedal note the next attack cuts short: it is written as a
                // quarter, not as the half note it was transcribed as.
                note(2, 2, "C3"), note(3, 1, "B2"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, piano);

        assertGolden("chords-and-overlaps", StaffNotation.toLilyPond(score, piano));
    }

    // ------------------------------------------------------------- meaning

    @Test
    @DisplayName("every bar of every golden case fills its meter exactly")
    void everyBarFillsItsMeter() {
        for (String name : List.of("melody-common-time", "tie-across-barline",
                "melody-six-eight", "bass-line", "pickup-bar", "rests-and-empty-bars",
                "meter-change", "chords-and-overlaps")) {
            assertBarsFillTheirMeter(name, readGolden(name));
        }
    }

    @Test
    @DisplayName("a pitch is written the way it was spelled, not the way MIDI numbers suggest")
    void spellingWins() {
        // One pitch, MIDI 61, spelled both ways. Nothing but the spelling
        // differs, and the two must not print the same.
        NoteTrack sharp = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C#4"));
        NoteTrack flat = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "Db4"));
        assertThat(sharp.notes().getFirst().midiPitch())
                .isEqualTo(flat.notes().getFirst().midiPitch());

        assertThat(StaffNotation.toLilyPond(score(TimeSignature.FOUR_FOUR, 120, sharp), sharp))
                .contains("cis'1").doesNotContain("des'");
        assertThat(StaffNotation.toLilyPond(score(TimeSignature.FOUR_FOUR, 120, flat), flat))
                .contains("des'1").doesNotContain("cis'");
    }

    @Test
    @DisplayName("an unspelled pitch follows the key rather than always preferring sharps")
    void unspelledPitchesFollowTheKey() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", unspelled(0, 4, 61));
        Score noKey = score(TimeSignature.FOUR_FOUR, 120, voice);
        Score flatKey = noKey.withKeys(List.of(key("Eb3", Mode.MAJOR)));

        assertThat(StaffNotation.toLilyPond(noKey, voice)).contains("cis'1");
        assertThat(StaffNotation.toLilyPond(flatKey, voice)).contains("des'1");
    }

    @Test
    @DisplayName("a track with no musical timing is refused rather than guessed at")
    void refusesUnquantizedTracks() {
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice",
                List.of(Note.ofSeconds(0.5, 1.0, 60, Confidence.CERTAIN)), Confidence.CERTAIN);
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> StaffNotation.toLilyPond(score, voice))
                .withMessageContaining("no musical timing");
    }

    @Test
    @DisplayName("a percussion part is refused rather than engraved as pitches")
    void refusesDrums() {
        NoteTrack drums = track(PartRole.DRUMS, "Drums", note(0, 1, "C4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, drums);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> StaffNotation.toLilyPond(score, drums))
                .withMessageContaining("DrumStaff");
    }

    @Test
    @DisplayName("a part that plays nothing is still a staff")
    void emptyTrackIsOneBarOfRest() {
        NoteTrack voice = NoteTrack.empty(PartRole.LEAD_VOCAL, "Voice");
        String source = StaffNotation.toLilyPond(score(TimeSignature.THREE_FOUR, 120, voice),
                voice);

        assertThat(source).contains("R2. |");
        assertBarsFillTheirMeter("empty track", source);
    }

    @Test
    @DisplayName("music that starts in bar two is preceded by rests, not by a pickup")
    void aLaterEntryIsNotAPickup() {
        // Beat four of bar two. Calling that a pickup would move every bar line
        // in the piece by three beats.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(7, 1, "G4"), note(8, 4, "C5"));
        String source = StaffNotation.toLilyPond(score(TimeSignature.FOUR_FOUR, 120, voice), voice);

        assertThat(source).doesNotContain("\\partial").contains("R1 |");
        assertBarsFillTheirMeter("later entry", source);
    }

    @Test
    @DisplayName("parts share one pickup, so their bar lines still agree")
    void partsShareThePickupOfTheScore() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(3, 1, "G4"), note(4, 4, "C5"));
        NoteTrack bass = track(PartRole.BASS, "Bass", note(4, 4, "C3"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice, bass);

        // The bass plays nothing in the pickup, but its staff has to carry the
        // same short bar or the two parts cannot be read together.
        String bassSource = StaffNotation.toLilyPond(score, bass);
        assertThat(bassSource).contains("\\partial 4").contains("r4 |");
        assertThat(StaffNotation.toLilyPond(score, voice)).contains("\\partial 4");
        assertBarsFillTheirMeter("shared pickup", bassSource);
    }

    @Test
    @DisplayName("a beat-long note starting off the beat in 6/8 is tied, not written as one symbol")
    void aCompoundBeatIsNeverHiddenByASymbolLyingAcrossIt() {
        // Round 1 of review found this reaching the page as "c'8 d'4. e'4": a
        // dotted quarter is exactly one 6/8 beat, and starting it on the second
        // eighth lays it across the bar of the second beat, which is the only
        // thing distinguishing 6/8 from 3/4.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 0.5, "C4"), note(0.5, 1.5, "D4"), note(2, 1, "E4"));
        String source = StaffNotation.toLilyPond(score(TimeSignature.SIX_EIGHT, 180, voice), voice);

        assertThat(source).contains("c'8 d'4~ d'8 e'4 |");
        assertBarsFillTheirMeter("compound beat", source);
    }

    @Test
    @DisplayName("a chord is written as long as its longest member, not its shortest")
    void aChordIsWrittenToItsLongestMember() {
        // One staff holds one rhythm, so a chord whose members stop at different
        // times has to pick one. The long reading prints the short note still
        // sounding; the short reading would open a rest inside a held harmony.
        // Neither is true, so the choice is pinned here rather than left to drift.
        NoteTrack piano = track(PartRole.PIANO_RIGHT_HAND, "Piano",
                note(0, 4, "C4"), note(0, 4, "E4"), note(0, 2, "G4"));
        String source = StaffNotation.toLilyPond(score(TimeSignature.FOUR_FOUR, 120, piano), piano);

        assertThat(source).contains("<c' e' g'>1 |");
        assertBarsFillTheirMeter("chord", source);
    }

    @Test
    @DisplayName("a note shorter than the shortest value is dropped, not stretched")
    void dropsNotesTooShortToWrite() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 4, "C4"), note(4, 0.01, "D4"), note(4.0625, 3.9375, "E4"));
        String source = StaffNotation.toLilyPond(score(TimeSignature.FOUR_FOUR, 120, voice), voice);

        assertThat(source).doesNotContain("d'");
        assertBarsFillTheirMeter("dropped grace", source);
    }

    // --------------------------------------------------------------- helpers

    /**
     * Checks that every bar the emitter wrote holds exactly what its meter says.
     *
     * <p>Reads the emitted text the way LilyPond would: the meter from
     * {@code \time}, the short first bar from {@code \partial}, and the length of
     * each token from {@link LilyPondNotes}. This is the assertion that would
     * have caught a bar that is a sixteenth short, which LilyPond engraves
     * happily and no golden file would have questioned.
     */
    private static void assertBarsFillTheirMeter(String label, String source) {
        double barLength = 0;
        double expected = -1;
        int barNumber = 0;
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("\\time")) {
                String meter = line.substring(line.lastIndexOf(' ') + 1);
                String[] parts = meter.split("/");
                barLength = Integer.parseInt(parts[0]) * 4.0 / Integer.parseInt(parts[1]);
                expected = barLength;
            } else if (line.startsWith("\\partial")) {
                expected = LilyPondNotes.quartersOf(line.substring("\\partial ".length()));
            } else if (line.endsWith("|") && !line.startsWith("\\bar")) {
                double sum = 0;
                List<String> tokens = new ArrayList<>(LilyPondNotes.tokenize(line));
                tokens.removeLast();
                for (String token : tokens) {
                    sum += LilyPondNotes.quartersOf(token);
                }
                barNumber++;
                assertThat(sum)
                        .as("%s: bar %d (%s)", label, barNumber, line)
                        .isEqualTo(expected);
                expected = barLength;
            }
        }
        assertThat(barNumber).as("%s: no bars were written", label).isPositive();
    }

    private static void assertGolden(String name, String actual) {
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Path target = Path.of("src", "test", "resources", "golden", name + ".ly");
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, actual);
            } catch (IOException e) {
                throw new UncheckedIOException("could not update golden " + name, e);
            }
            System.err.println("updated golden file " + target.toAbsolutePath());
        }
        assertThat(actual).isEqualTo(readGolden(name));
    }

    private static String readGolden(String name) {
        String resource = "/golden/" + name + ".ly";
        try (var stream = StaffNotationTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("missing golden file " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read golden " + name, e);
        }
    }
}
