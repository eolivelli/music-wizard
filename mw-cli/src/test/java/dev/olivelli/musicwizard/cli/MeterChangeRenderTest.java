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

package dev.olivelli.musicwizard.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A recording barred in 3/4 and then in 6/8 at the same pulse (#888), from a
 * score in seconds as analyze writes one, through every part render engraves.
 */
class MeterChangeRenderTest {

    @TempDir
    Path directory;

    private static final double PULSE = 0.5;

    /** Two bars of 3/4 at a quarter a pulse, then 6/8 at a dotted quarter a pulse. */
    private static TempoMap heldPulse() {
        return new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0, 120, Provenance.MEASURED),
                        new TempoMap.TempoSegment(6, 3.0, 180, Provenance.MEASURED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.THREE_FOUR, Provenance.SUPPLIED),
                        new TempoMap.MeterChange(2, TimeSignature.SIX_EIGHT, Provenance.SUPPLIED)));
    }

    private static BeatGrid grid() {
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int position = i < 6 ? i % 3 : (i - 6) % 2;
            beats.add(new BeatGrid.Beat(i * PULSE, position == 0, position));
        }
        return new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9));
    }

    private static Note note(double pulse, double pulses, int midi) {
        return Note.ofSeconds(pulse * PULSE, pulses * PULSE, midi, Confidence.CERTAIN);
    }

    private static Chord chord(NoteLetter root, double fromPulse, double toPulse) {
        return Chord.ofSeconds(new PitchSpelling(root, Accidental.NATURAL, 4), ChordQuality.MAJOR,
                fromPulse * PULSE, toPulse * PULSE, Confidence.of(0.8));
    }

    private Path workspace() {
        Path source = directory.resolve("take.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.2, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("take.mwz");
        CliRunner.Result init = CliRunner.run("init", source.toString(), "-w", root.toString());
        assertThat(init.exitCode()).as(init.all()).isZero();
        NoteTrack melody = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                note(0, 2, 64), note(2, 1, 67),
                note(3, 2, 69), note(5, 1, 71),
                note(6, 1, 72), note(7, 1, 74),
                note(8, 2, 76),
                note(10, 1, 74), note(11, 1, 72)), Confidence.CERTAIN);
        Workspace.open(root).writeScore(Score.empty(heldPulse(), 6.0)
                .withBeatGrid(grid())
                .withChords(new ChordProgression(List.of(
                        chord(NoteLetter.C, 0, 6), chord(NoteLetter.G, 6, 12)),
                        Confidence.of(0.8)))
                .withTrack(melody));
        return root;
    }

    @Test
    @DisplayName("every part changes meter at the bar the map does, marked in its new beat")
    void everyPartChangesMeter() throws IOException {
        Path root = workspace();

        CliRunner.Result render = CliRunner.run("render", root.toString(),
                "--parts", "chords,lead,playable", "--no-pdf");

        assertThat(render.exitCode()).as(render.all()).isZero();
        String mark = "\\tempo \\markup { \\italic \"ca.\" } 4. = 120";
        for (String part : List.of("lead-playable", "lead")) {
            String ly = Files.readString(root.resolve("out/" + part + ".ly"));
            assertThat(ly).as(part).containsSubsequence(
                    "3/4", "4 = 120", "e'2 g'4 |", "a'2 b'4 |",
                    "6/8", mark, "c''4. d''4. |", "e''2. |", "d''4. c''4. |");
            String xml = Files.readString(root.resolve("out/" + part + ".musicxml"));
            assertThat(xml).as(part).containsSubsequence(
                    "<beats>6</beats>", "<beat-unit-dot/>", "<per-minute>120</per-minute>",
                    "<sound tempo=\"180\"/>");
        }
        String chart = Files.readString(root.resolve("out/chords.ly"));
        assertThat(chart).containsSubsequence("3/4", "c2.", "6/8", mark, "g2.");
        assertThat(Files.readString(root.resolve("out/chords.musicxml")))
                .containsSubsequence("<beats>6</beats>", "<beat-unit-dot/>",
                        "<sound tempo=\"180\"/>");
    }

    @Test
    @DisplayName("a chart that opens after the change is headed in that stretch's tempo")
    void aChartOpeningAfterTheChange() throws IOException {
        Path root = workspace();
        Workspace opened = Workspace.open(root);
        opened.writeScore(opened.readScore().orElseThrow().withChords(new ChordProgression(
                List.of(chord(NoteLetter.G, 6, 12)), Confidence.of(0.8))));

        CliRunner.Result render = CliRunner.run("render", root.toString(),
                "--parts", "chords", "--no-pdf");

        assertThat(render.exitCode()).as(render.all()).isZero();
        assertThat(Files.readString(root.resolve("out/chords.ly")))
                .contains("\\tempo \\markup { \\italic \"ca.\" } 4. = 120");
        assertThat(Files.readString(root.resolve("out/chords.txt"))).contains("Tempo  120");
    }

    @Test
    @DisplayName("analyze takes the change after the opening signature, and says where it fell")
    void analyzeTakesTheChange() {
        Path source = directory.resolve("line.wav");
        int[] pitches = {64, 67, 69, 71, 72, 74, 76, 74};
        double pulse = 0.5;
        int sampleRate = SignalFactory.DEFAULT_SAMPLE_RATE;
        float[] samples = new float[(int) (4 * pitches.length * pulse * sampleRate)];
        int at = 0;
        for (int repeat = 0; repeat < 4; repeat++) {
            for (int pitch : pitches) {
                float[] note = SignalFactory.sine(SignalFactory.midiToHz(pitch), pulse, sampleRate);
                System.arraycopy(note, 0, samples, at, Math.min(note.length, samples.length - at));
                at += note.length;
            }
        }
        SignalFactory.writeWav(source, samples, sampleRate);
        Path root = directory.resolve("line.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString()).exitCode())
                .isZero();

        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--melody", "--skip-separation", "--time-signature", "3/4,6/8@8");

        CliRunner.Result opened = CliRunner.run("analyze", root.toString(),
                "--time-signature", "6/8@3");
        assertThat(opened.exitCode()).isNotZero();
        assertThat(opened.all()).contains("takes no @SECONDS");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).contains("the counted beat held")
                .containsPattern("Meter +3/4, 6/8 from [0-9.]+s");
        TempoMap map = Workspace.open(root).readScore().orElseThrow().tempoMap();
        assertThat(map.meterChanges()).extracting(TempoMap.MeterChange::timeSignature)
                .containsExactly(TimeSignature.THREE_FOUR, TimeSignature.SIX_EIGHT);
    }

    @Test
    @DisplayName("a change without its second, or an opening with one, is refused as typed")
    void refusesMalformedChanges() {
        assertThat(AnalyzeCommand.parseMeterChanges("3/4,6/8@17.4"))
                .containsExactly(new dev.olivelli.musicwizard.transcribe.AudioTranscriber
                        .MeterChangeAt(17.4, TimeSignature.SIX_EIGHT));
        assertThat(AnalyzeCommand.parseMeter("3/4,6/8@17.4")).isEqualTo(TimeSignature.THREE_FOUR);
        assertThat(AnalyzeCommand.parseMeterChanges("6/8")).isEmpty();
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> AnalyzeCommand.parseMeterChanges("6/8@3"))
                .withMessageContaining("takes no @SECONDS");
        for (String typed : List.of("3/4,6/8", "6/8@3", "3/4,6/8@x", "3/4,")) {
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .as(typed).isThrownBy(() -> AnalyzeCommand.parseMeterChanges(typed));
        }
    }
}
