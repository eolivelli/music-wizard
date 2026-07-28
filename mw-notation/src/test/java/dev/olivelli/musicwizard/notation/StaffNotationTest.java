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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.SwingFeel;
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
import java.util.Optional;
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

    /**
     * A quantizer verdict for a score: one grid per bar, in bar order.
     *
     * <p>Built by hand rather than by running {@link
     * dev.olivelli.musicwizard.arrange.Quantizer}, so that these tests say what
     * the emitter does with a given decision rather than what the quantizer
     * happens to decide this week. The end-to-end proof that the two agree is in
     * {@code mw-it}.
     */
    private static QuantizedScore quantized(Score score, GridResolution... perBar) {
        List<BarGrid> grids = new ArrayList<>(perBar.length);
        double startBeat = 0;
        for (int bar = 0; bar < perBar.length; bar++) {
            TimeSignature meter = score.tempoMap().timeSignatureAtBar(bar);
            grids.add(new BarGrid(bar, startBeat, perBar[bar], meter));
            startBeat += meter.quarterBeatsPerBar();
        }
        return new QuantizedScore(score, grids, SwingFeel.STRAIGHT);
    }

    /** A position or length of {@code steps} triplet eighths, in quarter beats. */
    private static double thirds(double steps) {
        return steps / 3.0;
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

    @Test
    @DisplayName("a bar the quantizer put on a triplet grid is bracketed, and only where it needs it")
    void tripletEighths() {
        List<Note> notes = new ArrayList<>();
        // Bar 1: plain eighths, so the bracketed bars have something to be read
        // against.
        String[] scale = {"C4", "D4", "E4", "F4", "G4", "A4", "B4", "C5"};
        for (int i = 0; i < 8; i++) {
            notes.add(note(i * 0.5, 0.5, scale[i]));
        }
        // Bar 2: four beats of triplet eighths, which is the case #92 is about.
        String[] run = {"C4", "D4", "E4", "F4", "G4", "A4", "B4", "C5", "D5", "E5", "D5", "C5"};
        for (int i = 0; i < 12; i++) {
            notes.add(note(4 + thirds(i), thirds(1), run[i]));
        }
        // Bar 3: the same grid, subdivided in only two of its four beats. The
        // other two must come out as plain quarters -- a bracket says a beat was
        // divided in three, and printing one round a beat that was not is as
        // wrong as leaving it off the beat that was.
        notes.add(note(8, thirds(1), "C4"));
        notes.add(note(8 + thirds(1), thirds(2), "D4"));
        notes.add(note(9, 1 + thirds(1), "E4"));
        notes.add(note(10 + thirds(1), thirds(2), "F4"));
        notes.add(note(11, 1, "G4"));
        // Bar 4: a whole note on the same grid, which needs no bracket at all.
        notes.add(note(12, 4, "C4"));

        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN);
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice)
                .withKeys(List.of(key("C4", Mode.MAJOR)))
                .withMetadata("Triplet Practice", "Anonymous");
        QuantizedScore plan = quantized(score, GridResolution.HALF_BEAT,
                GridResolution.THIRD_BEAT, GridResolution.THIRD_BEAT, GridResolution.THIRD_BEAT);

        String source = StaffNotation.toLilyPond(plan, voice);
        assertThat(source).contains(
                "\\tuplet 3/2 { c'8 d'4 } e'4~ \\tuplet 3/2 { e'8 f'4 } g'4 |");
        assertGolden("triplet-eighths", source);
    }

    @Test
    @DisplayName("a duplet in compound time is bracketed the other way round")
    void compoundDuplets() {
        // 6/8 subdivides in three, so it is the halves that are the tuplet --
        // two in the time of three. A grid anchored on the quarter rather than
        // on the counted beat cannot say this at all.
        List<Note> notes = new ArrayList<>();
        String[] scale = {"C4", "D4", "E4", "F4", "G4", "A4"};
        for (int i = 0; i < 6; i++) {
            notes.add(note(i * 0.5, 0.5, scale[i]));
        }
        notes.add(note(3, 0.75, "C4"));
        notes.add(note(3.75, 0.75, "D4"));
        notes.add(note(4.5, 0.75, "E4"));
        notes.add(note(5.25, 0.75, "F4"));
        notes.add(note(6, 1.5, "A4"));
        notes.add(note(7.5, 0.75, "G4"));
        notes.add(note(8.25, 0.75, "F4"));

        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN);
        Score score = score(TimeSignature.SIX_EIGHT, 180, voice);
        QuantizedScore plan = quantized(score, GridResolution.THIRD_BEAT,
                GridResolution.HALF_BEAT, GridResolution.HALF_BEAT);

        String source = StaffNotation.toLilyPond(plan, voice);
        assertThat(source)
                .contains("\\tuplet 2/3 { c'8 d'8 } \\tuplet 2/3 { e'8 f'8 } |")
                .contains("a'4. \\tuplet 2/3 { g'8 f'8 } |");
        assertGolden("compound-duplets", source);
    }

    // ------------------------------------------------------------- meaning

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
    @DisplayName("a dotted-beat note off the beat in 3/4 is tied, exactly as it is in 4/4")
    void tripleTimeReadsNoMoreLooselyThanDuple() {
        // Round 3 of review found "c'8. d'4. e'8." reaching the page in 3/4: the
        // dotted quarter runs from beat 1.75 to beat 3.25, so beat 2 has neither
        // an onset nor an ending anywhere on the staff. 4/4 tied the identical
        // span, which is what made it a rule inconsistency rather than a
        // judgement call.
        List<Note> notes = List.of(
                note(0, 0.75, "C4"), note(0.75, 1.5, "D4"), note(2.25, 0.75, "E4"));
        NoteTrack triple = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN);

        assertThat(StaffNotation.toLilyPond(score(TimeSignature.THREE_FOUR, 120, triple), triple))
                .contains("c'8. d'16~ d'4~ d'16 e'8. |");
        assertBarsFillTheirMeter("triple time", StaffNotation.toLilyPond(
                score(TimeSignature.THREE_FOUR, 120, triple), triple));
    }

    @Test
    @DisplayName("a long note off the beat in 5/4 is tied rather than swallowing four beats")
    void aBeatIsNeverHiddenInAMeterWhoseBeatsAreNotAPowerOfTwo() {
        // Round 2 of review found this reaching the page as "c'16 d'1 e'8.":
        // a whole note starting a sixteenth after the downbeat, with beats two
        // to five nowhere on the staff. 5/4 divides straight into five beats, so
        // no unit in its tree is longer than a beat, which is why the round 1
        // fix -- which asked about the unit's size -- never fired here.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 0.25, "C4"), note(0.25, 4, "D4"), note(4.25, 0.75, "E4"));
        String source = StaffNotation.toLilyPond(score(new TimeSignature(5, 4), 120, voice), voice);

        assertThat(source).contains("c'16 d'8.~ d'2.~ d'16 e'8. |");
        assertBarsFillTheirMeter("five four", source);
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
    @DisplayName("parts share the end of the score, so a part that stops early rests to it")
    void partsShareTheEndOfTheScore() {
        // Round 7 of review found the two ends of the bar grid derived from
        // different scopes: the pickup across the score, the last bar across this
        // track alone. A bass that drops out for the outro therefore ended its
        // staff mid-system, with a final bar line drawn under the middle of the
        // parts beside it -- and every check passed, because each bar it did
        // write summed and LilyPond had nothing to complain about.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 4, "C5"), note(4, 4, "D5"), note(8, 4, "E5"), note(12, 4, "F5"));
        NoteTrack bass = track(PartRole.BASS, "Bass", note(0, 4, "C2"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice, bass);

        String bassSource = StaffNotation.toLilyPond(score, bass);
        assertThat(barCount(bassSource))
                .as("the bass staff stops before the vocal staff does")
                .isEqualTo(barCount(StaffNotation.toLilyPond(score, voice)))
                .isEqualTo(4);
        assertThat(bassSource).contains("R1 |");
        assertBarsFillTheirMeter("shared end", bassSource);
    }

    @Test
    @DisplayName("every staff of a score agrees on how long the score is, outlier or not")
    void everyStaffAgreesOnTheLengthOfTheScore() {
        // The invariant rounds 7, 8 and 9 converged on: the bar grid is a
        // function of the score, not of which part is being engraved. Round 8
        // broke it by clamping other parts' ends while never clamping this one,
        // which put the two staves of one score at 8 bars and 101.
        //
        // A note quantized a hundred bars late therefore does lengthen every
        // staff. That is the score saying the piece is a hundred bars long, and
        // every part agreeing about it beats two staves disagreeing silently.
        // Deciding the note is spurious is a quantization judgement -- #113.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C5"), note(4, 4, "D5"));
        NoteTrack strays = track(PartRole.ACCOMPANIMENT, "Keys", note(400, 4, "E4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice, strays);

        assertThat(barCount(StaffNotation.toLilyPond(score, voice)))
                .isEqualTo(barCount(StaffNotation.toLilyPond(score, strays)))
                .isEqualTo(101);
    }

    @Test
    @DisplayName("a score too long to engrave blames the part that made it long")
    void theBarCeilingNamesThePartResponsible() {
        // The message used to say "this part", which is the part being engraved
        // and, when the length comes from a sibling, the wrong one to look at.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C5"));
        NoteTrack strays = track(PartRole.ACCOMPANIMENT, "Keys", note(4_000_000, 4, "E4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice, strays);

        assertThatThrownBy(() -> StaffNotation.toLilyPond(score, voice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("engraving \"Voice\"")
                .hasMessageContaining("\"Keys\" runs that far, so look there rather than here");

        // And when the long part IS the one being engraved, the message must not
        // send the reader to another part. A single-part score is what the
        // pipeline produces today, so this is the common shape, not the exotic one.
        Score alone = score(TimeSignature.FOUR_FOUR, 120, strays);
        assertThatThrownBy(() -> StaffNotation.toLilyPond(alone, strays))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"Keys\" itself runs that far")
                .hasMessageNotContaining("look there rather than here");
    }

    @Test
    @DisplayName("the ceiling blames the right part even when two parts share a name")
    void theBarCeilingIsNotConfusedByTwoPartsOfTheSameName() {
        // A score may hold two tracks with the same name in different roles, and
        // a piano's two hands routinely do. Round 11 found the message deciding
        // whether the culprit was another part by comparing names, which makes
        // the two hands indistinguishable and sends the reader to the part being
        // engraved when the cause is in the other one.
        NoteTrack right = track(PartRole.PIANO_RIGHT_HAND, "Piano", note(0, 4, "C5"));
        NoteTrack left = track(PartRole.PIANO_LEFT_HAND, "Piano", note(4_000_000, 4, "C3"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, right, left);

        assertThatThrownBy(() -> StaffNotation.toLilyPond(score, right))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("look there rather than here")
                .hasMessageNotContaining("itself runs that far");
    }

    @Test
    @DisplayName("the bar ceiling is the length its javadoc claims it is")
    void theBarCeilingIsTheLengthItClaims() {
        // Round 10 of review found the javadoc's figure wrong by a factor of
        // sixteen, having survived nine rounds. It is the argument for the
        // number, so it is checked rather than asserted: one bar of 4/4 at 120
        // quarter-note BPM lasts two seconds.
        double hours = StaffNotation.MAX_BARS * 4 / 120.0 / 60.0;
        assertThat(hours)
                .as("the ceiling is %d bars, which is %.1f hours of 4/4 at 120 BPM",
                        StaffNotation.MAX_BARS, hours)
                .isBetween(2.0, 2.5);
    }

    @Test
    @DisplayName("a part that is only half quantized does not move the bar lines either")
    void aHalfQuantizedPartDoesNotDriveTheBarGrid() {
        // staffBlock refuses to engrave such a part outright, and round 9 found
        // it still voting on the grid with whichever of its notes happened to
        // carry a position -- a part that can never be on the page moving the bar
        // lines of the part that is.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C5"));
        NoteTrack half = new NoteTrack(PartRole.ACCOMPANIMENT, "Keys",
                List.of(note(0, 4, "E4"), note(40, 4, "G4"),
                        Note.ofSeconds(50, 1, 60, Confidence.CERTAIN)), Confidence.CERTAIN);
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice, half);

        assertThat(barCount(StaffNotation.toLilyPond(score, voice))).isEqualTo(1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StaffNotation.toLilyPond(score, half))
                .withMessageContaining("no musical timing");
    }

    @Test
    @DisplayName("a part this cannot engrave does not move the bar lines of the parts it can")
    void percussionDoesNotDriveTheBarGrid() {
        // A count-in before beat one is the most ordinary thing a drum track
        // contains, and it gave every visible staff a pickup that nothing on the
        // page justified -- plus, after the shared end landed, trailing rests.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(4, 4, "C5"));
        NoteTrack drums = track(PartRole.DRUMS, "Drums", note(2, 1, "C4"), note(20, 1, "C4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice, drums);

        String source = StaffNotation.toLilyPond(score, voice);
        assertThat(source).doesNotContain("\\partial");
        assertThat(barCount(source)).isEqualTo(2);
        assertBarsFillTheirMeter("percussion ignored", source);
    }

    @Test
    @DisplayName("music beginning exactly on the second downbeat is a bar of rest, not a pickup")
    void musicOnTheSecondDownbeatIsNotAPickup() {
        // The boundary of the pickup test, which is the value most likely to be
        // written as <= by somebody tidying it: one beat further in and every bar
        // line in the piece moves.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(4, 4, "G4"));
        String source = StaffNotation.toLilyPond(score(TimeSignature.FOUR_FOUR, 120, voice), voice);

        assertThat(source).doesNotContain("\\partial").contains("R1 |");
        assertBarsFillTheirMeter("second downbeat", source);
    }

    @Test
    @DisplayName("a note too short to write does not open a pickup bar for music that is not there")
    void aDroppedNoteDoesNotCreateAPickup() {
        // Round 4 of review found this emitting "\\partial 1*63/64" followed by
        // seven rest symbols: the pickup was measured on the notes and the music
        // on the events, and the two differ by exactly the notes the emitter
        // decided it could not write. A stray sub-64th onset at the head of a
        // transcription -- a click, a breath, a separation artefact -- is
        // ordinary input, not a corrupt score.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0.0625, 0.001, "C4"), note(4, 4, "E4"));
        String source = StaffNotation.toLilyPond(score(TimeSignature.FOUR_FOUR, 120, voice), voice);

        assertThat(source).doesNotContain("\\partial").contains("R1 |").contains("e'1 |");
        assertBarsFillTheirMeter("dropped pickup", source);
    }

    @Test
    @DisplayName("an unquantized part elsewhere in the score does not block engraving a finished one")
    void anUnquantizedSiblingTrackIsSkipped() {
        // The pickup is read from every part so that separately engraved staves
        // share a bar grid, and a score is built up in stages, so a part still in
        // seconds while another is finished is a normal intermediate state rather
        // than a reason to refuse.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(3, 1, "G4"), note(4, 4, "C5"));
        NoteTrack raw = new NoteTrack(PartRole.ACCOMPANIMENT, "Keys",
                List.of(Note.ofSeconds(0.1, 2.0, 60, Confidence.CERTAIN)), Confidence.CERTAIN);
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice, raw);

        String source = StaffNotation.toLilyPond(score, voice);
        assertThat(source).contains("\\partial 4").contains("g'4 |");
        assertBarsFillTheirMeter("unquantized sibling", source);
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

    // --------------------------------------------------------------- tuplets

    /** One bar of triplet eighths, played as the quantizer would leave them. */
    private static NoteTrack tripletBar(String... spellings) {
        List<Note> notes = new ArrayList<>(spellings.length);
        for (int i = 0; i < spellings.length; i++) {
            notes.add(note(thirds(i), thirds(1), spellings[i]));
        }
        return new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN);
    }

    @Test
    @DisplayName("the Score overload still snaps triplets away, because a Score cannot say otherwise")
    void theScoreOverloadHasNoGridToRead() {
        // The audio track produces a plain Score, and there is nothing in one to
        // distinguish three onsets a third of a beat apart from three a half
        // beat apart on a sixth-of-a-beat grid. So this overload keeps doing
        // what it did -- badly, visibly, and without inventing a bracket -- and
        // the fix is to hand the emitter the decision rather than to make it
        // guess. Pinned so that a later tidy-up cannot quietly make it guess.
        NoteTrack voice = tripletBar("C4", "D4", "E4", "F4", "G4", "A4", "B4", "C5",
                "B4", "A4", "G4", "F4");
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String withoutGrids = StaffNotation.toLilyPond(score, voice);
        assertThat(withoutGrids).doesNotContain("\\tuplet").contains("64");
        assertBarsFillTheirMeter("no grids", withoutGrids);

        String withGrids = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT), voice);
        assertThat(withGrids)
                .contains("\\tuplet 3/2 { c'8 d'8 e'8 } \\tuplet 3/2 { f'8 g'8 a'8 }")
                .doesNotContain("64");
        assertBarsFillTheirMeter("with grids", withGrids);
    }

    @Test
    @DisplayName("a triplet bar nobody actually subdivided prints as plain quarters")
    void aTripletGridWithNothingOnItGetsNoBrackets() {
        // The quantizer decodes its grids with a Viterbi pass that resists
        // changing subdivision inside a section, so a bar of plain quarters
        // between two triplet bars is published on the triplet grid. Bracketing
        // it because the grid says triplet would put a 3/2 round four quarter
        // notes, which is not what was played and not what the grid claims --
        // the grid says what the bar was measured against, not what it holds.
        // Two bars on the same grid, one subdivided and one not, so this says
        // where the bracket goes rather than only where it does not: an emitter
        // that never brackets passes half of it.
        List<Note> notes = new ArrayList<>(List.of(
                note(0, 1, "C4"), note(1, 1, "D4"), note(2, 1, "E4"), note(3, 1, "F4")));
        String[] run = {"C4", "D4", "E4", "F4", "G4", "A4", "B4", "C5", "D5", "E5", "D5", "C5"};
        for (int i = 0; i < 12; i++) {
            notes.add(note(4 + thirds(i), thirds(1), run[i]));
        }
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN);
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT, GridResolution.THIRD_BEAT), voice);
        assertThat(source)
                .contains("c'4 d'4 e'4 f'4 |")
                .contains("\\tuplet 3/2 { c'8 d'8 e'8 } \\tuplet 3/2 { f'8 g'8 a'8 }");
        assertThat(source.lines().filter(line -> line.contains("c'4 d'4 e'4 f'4")).toList())
                .as("the unsubdivided bar carries no bracket of its own")
                .allSatisfy(line -> assertThat(line).doesNotContain("\\tuplet"));
        assertBarsFillTheirMeter("unsubdivided triplet grid", source);
    }

    @Test
    @DisplayName("a note held across two triplet beats is one symbol, not two bracketed ones")
    void aNoteAcrossPlainBeatsOfATripletBarIsNotCutAtTheBrackets() {
        // \tuplet 3/2 { c4. } \tuplet 3/2 { c4.~ } is the same music and
        // unreadable. A bracket is only cut where something inside it happens.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 2, "C4"), note(2, thirds(1), "D4"), note(2 + thirds(1), thirds(2), "E4"),
                note(3, 1, "F4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT), voice);
        assertThat(source).contains("c'2 \\tuplet 3/2 { d'8 e'4 } f'4 |");
        assertBarsFillTheirMeter("held across brackets", source);
    }

    @Test
    @DisplayName("a note leaving a bracket is cut at it, exactly as one entering it is")
    void aBracketIsClosedByTheNoteThatLeavesItAsWellAsByTheOneThatEntersIt() {
        // Round 1 of review found that the cut rule was only tested from one
        // side. Every fixture that crossed a bracket boundary crossed it *into* a
        // printed bracket, so the half of the rule that closes a bracket behind a
        // note leaving it could be deleted and the whole repository stayed green
        // -- while ordinary music came out as "\tuplet 3/2 { c'8 d'8 e'2 }": a
        // triplet bracket drawn round a half note. It sums to 4/4, so no bar
        // check catches it either, and held one beat further it throws instead.
        //
        // This bar crosses a boundary in both directions. The E leaves a printed
        // bracket into a plain beat; the F enters a printed one from a plain beat.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, thirds(1), "C4"), note(thirds(1), thirds(1), "D4"),
                note(thirds(2), thirds(1) + 1, "E4"),
                note(2, 1 + thirds(1), "F4"),
                note(3 + thirds(1), thirds(2), "G4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT), voice);
        assertThat(source).contains(
                "\\tuplet 3/2 { c'8 d'8 e'8~ } e'4 f'4~ \\tuplet 3/2 { f'8 g'4 } |");
        assertBarsFillTheirMeter("cut on both sides", source);
    }

    @Test
    @DisplayName("a note held out of a bracket across two plain beats is one symbol")
    void aNoteLeavingABracketIsNotStretchedInsideIt() {
        // The same defect one beat further on, where it is no longer silent: the
        // fragment reaches four grid steps, which is longer than a bracket, and
        // there is no note value for it. A bracket cannot hold a whole bracket's
        // worth of anything, so this is the shape that turns the miss above into
        // an exception rather than a wrong page.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, thirds(1), "C4"), note(thirds(1), thirds(1), "D4"),
                note(thirds(2), thirds(1) + 3, "E4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT), voice);
        assertThat(source).contains("\\tuplet 3/2 { c'8 d'8 e'8~ } e'2. |");
        assertBarsFillTheirMeter("held out of a bracket", source);
    }

    @Test
    @DisplayName("a pickup inside a bracket fills its short bar in an odd meter too")
    void aPartialBracketInAPickupStillSumsExactly() {
        // Round 1 of review found the bar-length helper rejecting this: a
        // bracket holding fewer notes than its ratio sums a hair under its meter
        // in floating point, and LilyPond engraves it without a word. 4/4 happens
        // to round the same way, so the pickup test above did not show it; 3/2
        // does not.
        // 3/2, so a counted beat is a half note and a triplet step is two thirds
        // of a quarter. The pickup enters one step into the bar's second step,
        // which makes the first bracket a partial one, and the bar then holds a
        // whole bracket and a bracketed pair -- three different roundings, which
        // is what it takes to make the double sum miss.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(2 * 2 / 3.0, 2 / 3.0, "C4"),
                note(2, 10 / 3.0, "D4"),
                note(6, 6, "C5"));
        Score score = score(new TimeSignature(3, 2), 120, voice);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT, GridResolution.BEAT), voice);
        assertThat(source).contains("\\partial 1*7/6")
                .contains("\\tuplet 3/2 { c'4 } d'2~ \\tuplet 3/2 { d'2 r4 } |");
        assertBarsFillTheirMeter("partial bracket in a pickup", source);
    }

    @Test
    @DisplayName("triplet sixteenths are two brackets to the beat, as they are written")
    void sixthOfABeatIsTwoTripletsRatherThanOneSextuplet() {
        // GridResolution says the ratio is 3/2 at every depth, because a
        // sixteenth triplet is three sixteenths in the time of two rather than
        // six in the time of four. So the bracket holds three notes and the beat
        // holds two brackets, which is how the rhythm is printed.
        List<Note> notes = new ArrayList<>();
        String[] run = {"C4", "D4", "E4", "F4", "G4", "A4"};
        for (int i = 0; i < 6; i++) {
            notes.add(note(i / 6.0, 1 / 6.0, run[i]));
        }
        notes.add(note(1, 3, "C5"));
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN);
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.SIXTH_BEAT), voice);
        assertThat(source)
                .contains("\\tuplet 3/2 { c'16 d'16 e'16 } \\tuplet 3/2 { f'16 g'16 a'16 } c''2.");
        assertBarsFillTheirMeter("triplet sixteenths", source);
    }

    @Test
    @DisplayName("a note tied out of a triplet bracket keeps its tie across the bar line")
    void aTieLeavesABracketAndCrossesTheBarLine() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 3, "C4"),
                note(3, thirds(1), "D4"),
                // Starts on the last triplet eighth of bar one and runs into bar
                // two, which is on a binary grid: the head is a bracketed eighth
                // and the tail an ordinary half note.
                note(3 + thirds(2), thirds(1) + 2, "E4"),
                note(6, 2, "F4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT, GridResolution.HALF_BEAT), voice);
        assertThat(source)
                .contains("c'2. \\tuplet 3/2 { d'8 r8 e'8~ } |")
                .contains("e'2 f'2 |");
        assertBarsFillTheirMeter("tie out of a bracket", source);
    }

    @Test
    @DisplayName("a bar the grids say nothing about is engraved as it always was")
    void aBarWithNoPublishedGridFallsBackToTheBinaryGrid() {
        // The quantizer publishes one entry per bar a note sounds in, so a bar
        // of silence has none. It must still be a bar of rest rather than an
        // exception, because a QuantizedScore whose grids stop short is what a
        // caller building a score up in stages hands over.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, thirds(1), "C4"), note(thirds(1), thirds(2), "D4"),
                note(1, 3, "E4"), note(8, 4, "G4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT), voice);
        assertThat(source)
                .contains("\\tuplet 3/2 { c'8 d'4 } e'2. |")
                .contains("R1 |")
                .contains("g'1 |");
        assertBarsFillTheirMeter("missing grid", source);
    }

    @Test
    @DisplayName("a triplet position built by adding lengths lands on the grid, not beside it")
    void positionsAreRebuiltOnTheGridRatherThanTrusted() {
        // A third of a beat is not a representable double, so a note's offset --
        // which the quantizer computes as onset plus length -- is not the
        // position the same grid reaches by multiplication: (8 + 1/3) + 1/3 and
        // 8 + 2/3 differ in the last bit. Left alone, the second note of every
        // triplet starts an ulp after the first one ends and the emitter writes
        // a rest of a millionth of a beat between them, or fails to write the
        // bar at all.
        double first = 8 + thirds(1);
        assertThat(first + thirds(1))
                .as("the fixture is only worth running while these differ")
                .isNotEqualTo(8 + thirds(2));

        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, 8, "C4"),
                note(8, thirds(1), "D4"), note(first, thirds(1), "E4"),
                note(first + thirds(1), thirds(1), "F4"),
                note(9, 3, "G4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String source = StaffNotation.toLilyPond(quantized(score, GridResolution.HALF_BEAT,
                GridResolution.HALF_BEAT, GridResolution.THIRD_BEAT), voice);
        assertThat(source).contains("\\tuplet 3/2 { d'8 e'8 f'8 } g'2. |");
        assertBarsFillTheirMeter("accumulated positions", source);
    }

    @Test
    @DisplayName("a pickup that falls inside a triplet bracket still fills its short bar")
    void aPickupInsideABracket() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(3 + thirds(1), thirds(1), "G4"), note(3 + thirds(2), thirds(1), "A4"),
                note(4, 4, "C5"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT, GridResolution.BEAT), voice);
        // Two triplet eighths is two thirds of a quarter beat, which is a sixth
        // of a whole note and is not a whole number of 64ths -- the length-only
        // form of \partial cannot write it at all.
        assertThat(source).contains("\\partial 1*1/6").contains("\\tuplet 3/2 { g'8 a'8 } |");
        assertBarsFillTheirMeter("pickup inside a bracket", source);
    }

    @Test
    @DisplayName("a chord in a triplet keeps its pitches together under the bracket")
    void aChordInsideABracket() {
        NoteTrack piano = track(PartRole.PIANO_RIGHT_HAND, "Piano",
                note(0, thirds(1), "C4"), note(0, thirds(1), "E4"), note(0, thirds(1), "G4"),
                note(thirds(1), thirds(2), "D4"),
                note(1, 3, "C4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, piano);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT), piano);
        assertThat(source).contains("\\tuplet 3/2 { <c' e' g'>8 d'4 } c'2. |");
        assertBarsFillTheirMeter("chord in a bracket", source);
    }

    @Test
    @DisplayName("a rest inside a triplet is bracketed with the notes it shares the beat with")
    void aRestInsideABracket() {
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice",
                note(0, thirds(1), "C4"), note(thirds(2), thirds(1), "E4"),
                note(1, 3, "G4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice);

        String source = StaffNotation.toLilyPond(
                quantized(score, GridResolution.THIRD_BEAT), voice);
        assertThat(source).contains("\\tuplet 3/2 { c'8 r8 e'8 } g'2. |");
        assertBarsFillTheirMeter("rest in a bracket", source);
    }

    @Test
    @DisplayName("both overloads refuse an impossible score the same way, with the same message")
    void theTwoOverloadsFailIdentically() {
        // Round 2 of review noticed the two diverging here. A note quantized past
        // bar 2^31 makes TempoMap.toMusicalTime refuse outright, with a message
        // about bar indices -- so asking the grid where such a note falls threw
        // that instead of the emitter's own complaint, which says which part runs
        // that far and that the beat axis is probably wrong. Unreachable in
        // practice, at something like a century of music, and pinned anyway:
        // "the two overloads fail differently" is the shape this project keeps
        // paying for, and it costs one comparison to not have it.
        NoteTrack voice = track(PartRole.LEAD_VOCAL, "Voice", note(0, 4, "C5"));
        NoteTrack strays = track(PartRole.ACCOMPANIMENT, "Keys", note(1e10, 4, "E4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, voice, strays);
        QuantizedScore plan = quantized(score, GridResolution.THIRD_BEAT);

        assertThatThrownBy(() -> StaffNotation.toLilyPond(plan, voice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"Keys\" runs that far, so look there rather than here")
                .hasMessage(catchThrowable(() -> StaffNotation.toLilyPond(score, voice))
                        .getMessage());
    }

    @Test
    @DisplayName("a percussion part is refused whichever overload is asked")
    void refusesDrumsThroughTheQuantizedOverloadToo() {
        NoteTrack drums = track(PartRole.DRUMS, "Drums", note(0, 1, "C4"));
        Score score = score(TimeSignature.FOUR_FOUR, 120, drums);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> StaffNotation.toLilyPond(
                        quantized(score, GridResolution.THIRD_BEAT), drums))
                .withMessageContaining("DrumStaff");
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
     *
     * <p>Compared as exact fractions rather than as doubles, and the difference
     * is not academic: a tuplet bracket holding a partial group — which a pickup
     * inside a bracket produces — sums a bit under its meter in floating point,
     * and round 1 of review found that this helper therefore rejected output
     * LilyPond engraves without a word. Loosening the comparison would have blunted
     * the one check that survives a golden-file regeneration, so the arithmetic
     * was made exact instead.
     */
    private static void assertBarsFillTheirMeter(String label, String source) {
        LilyPondNotes.Quarters barLength = LilyPondNotes.Quarters.ZERO;
        LilyPondNotes.Quarters expected = null;
        int barNumber = 0;
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("\\time")) {
                String meter = line.substring(line.lastIndexOf(' ') + 1);
                String[] parts = meter.split("/");
                barLength = new LilyPondNotes.Quarters(Integer.parseInt(parts[0]) * 4L,
                        Integer.parseInt(parts[1]));
                expected = barLength;
            } else if (line.startsWith("\\partial")) {
                expected = LilyPondNotes.exactQuartersOf(line.substring("\\partial ".length()));
            } else if (line.endsWith("|") && !line.startsWith("\\bar")) {
                List<String> tokens = new ArrayList<>(LilyPondNotes.tokenize(line));
                tokens.removeLast();
                LilyPondNotes.Quarters sum = LilyPondNotes.quartersOfBar(tokens);
                barNumber++;
                assertThat(sum)
                        .as("%s: bar %d (%s)", label, barNumber, line)
                        .isEqualTo(expected);
                expected = barLength;
            }
        }
        assertThat(barNumber).as("%s: no bars were written", label).isPositive();
    }

    /** Bars in an emitted staff, counted by the bar checks the emitter writes. */
    private static int barCount(String source) {
        int bars = 0;
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (line.endsWith("|") && !line.startsWith("\\bar")) {
                bars++;
            }
        }
        return bars;
    }

    /**
     * Compares generated LilyPond against its golden file, having first checked
     * that it is valid music.
     *
     * <p>The bar-length check runs on the text just generated rather than on the
     * file, and that is the whole point of it living here. It used to be a test
     * of its own that read the goldens back, which meant two things: under
     * {@code -Dmw.golden.update} it validated whichever files the JUnit method
     * order happened to have regenerated by the time it ran — two of eight — and
     * a change to the class could silently move it and change which. Since a
     * generated text that both fills its bars and equals its golden proves the
     * golden fills its bars too, running it here covers strictly more and depends
     * on nothing.
     *
     * <p>It is a check on bar <em>lengths</em>, and only that. A regeneration run
     * that changes something else — a tie that stopped being emitted, a clef, a
     * spelling — rewrites the golden and says nothing, because the comparison it
     * would have failed is the one the flag suppressed. Which is why the update
     * mode's contract is to read the diff, and why it is opt-in.
     */
    private static void assertGolden(String name, String actual) {
        assertBarsFillTheirMeter(name, actual);
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Path target = goldenDirectory()
                    .orElseThrow(() -> new AssertionError(UPDATE_PROPERTY
                            + " needs the module directory, which Maven passes as -Dbasedir;"
                            + " run it under Maven rather than from an IDE working directory"))
                    .resolve(name + ".ly");
            try {
                Files.writeString(target, actual);
            } catch (IOException e) {
                throw new UncheckedIOException("could not update golden " + name, e);
            }
            System.err.println("updated golden file " + target);
        }
        assertThat(actual).isEqualTo(readGolden(name));
    }

    /**
     * The golden files as they are on disk, not as they were on the classpath.
     *
     * <p>Those differ during an update run, and the difference used to make the
     * update mode a trap: the new text was written to {@code src/test/resources}
     * and then compared against the copy {@code process-test-resources} had
     * already put in {@code target}, so a regenerating run rewrote every file and
     * failed anyway. Worse, the bar-length check was a separate test that read
     * the same stale copies, so the one check that survives regeneration was
     * skipped on exactly the run where it mattered; it now lives in
     * {@link #assertGolden} and runs on generated text instead.
     *
     * <p>Falls back to the classpath when the source tree cannot be located,
     * which is what happens outside Maven. That path is read-only; the update
     * mode refuses rather than guessing a directory, because guessing wrongly
     * creates a second golden tree somewhere nobody looks.
     */
    private static String readGolden(String name) {
        Optional<Path> onDisk = goldenDirectory().map(dir -> dir.resolve(name + ".ly"))
                .filter(Files::isRegularFile);
        if (onDisk.isPresent()) {
            try {
                return Files.readString(onDisk.get());
            } catch (IOException e) {
                throw new UncheckedIOException("could not read golden " + name, e);
            }
        }
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

    /** Where the golden files live in the source tree, when that can be known. */
    private static Optional<Path> goldenDirectory() {
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        if (basedir == null) {
            return Optional.empty();
        }
        Path directory = Path.of(basedir, "src", "test", "resources", "golden");
        return Files.isDirectory(directory) ? Optional.of(directory) : Optional.empty();
    }
}
