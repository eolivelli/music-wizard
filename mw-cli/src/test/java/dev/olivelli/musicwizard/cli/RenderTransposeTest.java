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

import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code render --transpose} produces, which is #129.
 *
 * <p>The flag was accepted, layered into the config, echoed back, and read by
 * nothing: every file was written, the command exited 0, and a singer was handed
 * a chart in the key it was recorded in. A chart that is wrong and looks right is
 * the failure this project is most expensive at, so what these assert is that the
 * chord symbols, the key line and the spelling all move together.
 *
 * <p>Everything here runs with {@code --no-pdf}; the fast suite must not shell
 * out to LilyPond, and none of these assertions is about engraving.
 */
class RenderTransposeTest {

    @TempDir
    Path directory;

    @Nested
    @DisplayName("the chart that comes out")
    class TheChart {

        @Test
        @DisplayName("C major up three is an E flat chart headed E flat major")
        void chordsAndKeyMoveTogether() throws IOException {
            Path workspace = cMajorWorkspace("song");

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "3", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            String text = Files.readString(workspace.resolve("out/chords.txt"));
            assertThat(text).contains("Key    Eb major");
            assertThat(text).contains("| Eb").contains("| Bb").contains("| Cm").contains("| Ab");
            // Flats, not the D sharp major the arithmetic alone would give.
            assertThat(text).doesNotContain("D#").doesNotContain("A#").doesNotContain("G#");
        }

        @Test
        @DisplayName("the engraved source moves with the text, not only the printed copy")
        void everyEmitterSeesTheSameScore() throws IOException {
            Path workspace = cMajorWorkspace("song");

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "3", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(Files.readString(workspace.resolve("out/chords.ly")))
                    .contains("ees").contains("bes").contains("aes").contains("c1:m")
                    .doesNotContain("dis").doesNotContain("ais");
            // The copy printed to the terminal is a second call to the chart
            // renderer, so it can disagree with the file and once would have.
            assertThat(render.out()).contains("Eb").doesNotContain("D#");
        }

        @Test
        @DisplayName("the header names the shift and the keys either side of it")
        void theShiftIsAnnounced() {
            Path workspace = cMajorWorkspace("song");

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "3", "--no-pdf");

            assertThat(render.out()).contains("Transpose  +3 semitones, C major to Eb major");
        }

        @Test
        @DisplayName("a shift of one semitone is not announced in the plural")
        void oneSemitoneReadsAsOne() {
            Path workspace = cMajorWorkspace("song");

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "-1", "--no-pdf");

            assertThat(render.out()).contains("Transpose  -1 semitone, C major to B major");
        }

        @Test
        @DisplayName("a score with no detected key still moves, and says only the interval")
        void aKeylessScoreMovesToo() throws IOException {
            Path workspace = workspaceWith("keyless", fourChords(), List.of(), List.of());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "2", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out()).contains("Transpose  +2 semitones")
                    .doesNotContain("Transpose  +2 semitones,");
            assertThat(Files.readString(workspace.resolve("out/chords.txt")))
                    .contains("| D").contains("| A").contains("| Bm").contains("| G");
        }
    }

    @Nested
    @DisplayName("where the shift comes from")
    class TheRequest {

        @Test
        @DisplayName("the workspace config works exactly as the flag does")
        void theConfigLayerIsHonoured() throws IOException {
            // #129 asked for this by name: a singer's transposition is a
            // persistent preference and belongs in workspace.yaml rather than in
            // every command line.
            Path workspace = cMajorWorkspace("song");
            Workspace.open(workspace).updateConfig(new MusicWizardConfig(null, null,
                    new MusicWizardConfig.NotationConfig(null, null, -2, null, null, null, null),
                    null, null, null));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(Files.readString(workspace.resolve("out/chords.txt")))
                    .contains("Key    Bb major");
        }

        @Test
        @DisplayName("the flag beats the config, as every other flag does")
        void theFlagWins() throws IOException {
            Path workspace = cMajorWorkspace("song");
            Workspace.open(workspace).updateConfig(new MusicWizardConfig(null, null,
                    new MusicWizardConfig.NotationConfig(null, null, -2, null, null, null, null),
                    null, null, null));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "3", "--no-pdf");

            assertThat(Files.readString(workspace.resolve("out/chords.txt")))
                    .contains("Key    Eb major");
        }

        @Test
        @DisplayName("no shift leaves the chart exactly as it was")
        void theUntransposedChartIsUntouched() throws IOException {
            Path plain = cMajorWorkspace("plain");
            Path zero = cMajorWorkspace("zero");

            CliRunner.run("render", plain.toString(), "--no-pdf");
            CliRunner.Result render = CliRunner.run(
                    "render", zero.toString(), "--transpose", "0", "--no-pdf");

            assertThat(Files.readString(zero.resolve("out/chords.ly")))
                    .isEqualTo(Files.readString(plain.resolve("out/chords.ly")));
            assertThat(render.out()).doesNotContain("Transpose");
        }

        @Test
        @DisplayName("a shift past two octaves is a usage error, not a wrapped chart")
        void tooFarIsRefused() {
            // 50 typed for 5. Pitch classes repeat every twelve, so the chart it
            // would otherwise print is indistinguishable from a correct one.
            Path workspace = cMajorWorkspace("song");

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "50", "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(2);
            assertThat(render.err()).contains("50").contains("24");
            assertThat(workspace.resolve("out/chords.txt")).doesNotExist();
        }

        @Test
        @DisplayName("the most negative int is refused too, which an absolute value would not be")
        void theOneShiftAnAbsoluteValueLetsThrough() {
            // Math.abs(Integer.MIN_VALUE) is itself, and is negative, so a bound
            // written that way passes exactly this input -- and it printed a
            // chart in a key nobody asked for and exited 0.
            Path workspace = cMajorWorkspace("song");

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--transpose", String.valueOf(Integer.MIN_VALUE), "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(2);
            assertThat(workspace.resolve("out/chords.txt")).doesNotExist();
        }
    }

    @Nested
    @DisplayName("the transcription behind the chart")
    class TheWorkspace {

        @Test
        @DisplayName("is not rewritten by a transposed render")
        void theAnalysisIsLeftAlone() {
            // The same rule re-spelling follows: what key a chart is printed in
            // is a decision about the page, and a second render with no flag has
            // to give the chart back in the key that was heard.
            Path workspace = cMajorWorkspace("song");

            CliRunner.run("render", workspace.toString(), "--transpose", "3", "--no-pdf");
            Score saved = Workspace.open(workspace).readScore().orElseThrow();

            assertThat(saved.primaryKey().orElseThrow().displayName()).isEqualTo("C major");
            assertThat(saved.chords().chords().get(0).symbol()).isEqualTo("C");
        }
    }

    @Nested
    @DisplayName("a part the shift cannot move")
    class UnmovableParts {

        @Test
        @DisplayName("is left out and named, and the chart is still written")
        void theChartSurvives() throws IOException {
            // Dropping the whole run would deny the user a chart over a part
            // nothing prints yet; moving the part with one note left behind
            // would give a page correct everywhere except there.
            Path workspace = workspaceWith("song", fourChords(), cMajor(),
                    List.of(highPart()));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "12", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(Files.readString(workspace.resolve("out/chords.txt")))
                    .contains("Key    C major");
            assertThat(render.err())
                    .contains("the high part was left out")
                    .contains("0..127");
        }

        @Test
        @DisplayName("is named after the files, so the notice reads as a caveat and not a failure")
        void theNoticeFollowsTheFileList() {
            Path workspace = workspaceWith("song", fourChords(), cMajor(),
                    List.of(highPart()));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "12", "--no-pdf");

            // transcript(), not all(): all() is stdout concatenated with
            // stderr, so the notice would follow the file list in it whichever
            // was written first. Only the interleaved capture can answer this.
            assertThat(render.transcript().indexOf("was left out"))
                    .isGreaterThan(render.transcript().indexOf("chords.ly"));
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** I-V-vi-IV, spelled as the estimator spells it. */
    private static ChordProgression fourChords() {
        return new ChordProgression(List.of(
                triad("C4", ChordQuality.MAJOR, 0),
                triad("G4", ChordQuality.MAJOR, 2),
                triad("A4", ChordQuality.MINOR, 4),
                triad("F4", ChordQuality.MAJOR, 6)),
                Confidence.of(0.8));
    }

    private static Chord triad(String root, ChordQuality quality, double from) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality,
                from, from + 2, Confidence.of(0.8));
    }

    private static List<Key> cMajor() {
        return List.of(Key.ofSeconds(PitchSpelling.parse("C4"), Mode.MAJOR,
                0, 8, Confidence.of(0.6)));
    }

    /** A part holding a note an octave up would push off the keyboard. */
    private static NoteTrack highPart() {
        return new NoteTrack(PartRole.OTHER, "high",
                List.of(new Note(0.0, 1.0, 126, 80, Optional.empty(),
                        Optional.of(0.0), Optional.of(1.0), Confidence.CERTAIN)),
                Confidence.CERTAIN);
    }

    private Path cMajorWorkspace(String name) {
        return workspaceWith(name, fourChords(), cMajor(), List.of());
    }

    /**
     * Imports a source and writes a score straight into the workspace.
     *
     * <p>Planted rather than analysed, as {@code RenderPartsTest} plants its own
     * and for the same reason: what is under test is what {@code render} does
     * with a score, and analysing one would make these depend on the accuracy of
     * a stage they are not about.
     */
    private Path workspaceWith(String name, ChordProgression chords, List<Key> keys,
                               List<NoteTrack> tracks) {
        Path source = directory.resolve(name + ".wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.2, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve(name + ".mwz");
        CliRunner.Result init = CliRunner.run("init", source.toString(), "-w", root.toString());
        assertThat(init.exitCode()).as(init.all()).isZero();
        Workspace workspace = Workspace.open(root);
        Score score = Score.empty(TempoMap.constant(120), 8.0)
                .withChords(chords)
                .withKeys(keys);
        for (NoteTrack track : tracks) {
            score = score.withTrack(track);
        }
        workspace.writeScore(score);
        return root;
    }
}
