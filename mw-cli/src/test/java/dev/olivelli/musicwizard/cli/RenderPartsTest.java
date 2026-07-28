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
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code render} says about the parts it cannot produce, which is #82.
 *
 * <p>It used to announce {@code voice, piano, bass, chords}, write only the
 * chord files and exit 0, so a user had no way to tell whether three parts had
 * failed, been skipped, or never been implemented. The rule the command now
 * follows is the one it already followed for a missing LilyPond binary: emit
 * what you can, name what you cannot and why, and fail only when there was
 * nothing at all to emit.
 *
 * <p>Everything here runs with {@code --no-pdf}. The fast suite must not shell
 * out to LilyPond, and none of these assertions is about engraving.
 */
class RenderPartsTest {

    @TempDir
    Path directory;

    /** I-V-vi-IV, so a score has harmony to render without any analysis running. */
    private static ChordProgression fourChords() {
        return new ChordProgression(List.of(
                chord("C", NoteLetter.C, ChordQuality.MAJOR, 0, 2),
                chord("G", NoteLetter.G, ChordQuality.MAJOR, 2, 4),
                chord("Am", NoteLetter.A, ChordQuality.MINOR, 4, 6),
                chord("F", NoteLetter.F, ChordQuality.MAJOR, 6, 8)),
                Confidence.of(0.8));
    }

    private static Chord chord(String unused, NoteLetter letter, ChordQuality quality,
                               double from, double to) {
        return Chord.ofSeconds(new PitchSpelling(letter, Accidental.NATURAL, 4), quality,
                from, to, Confidence.of(0.8));
    }

    /** A workspace holding a MIDI file, with whatever score the test needs. */
    private Path midiWorkspace(String name, ChordProgression chords) {
        Path source = MidiFixtures.write(
                MidiFixtures.fourChordSong(), directory.resolve(name + ".mid"));
        return workspaceWith(name, source, chords);
    }

    /** A workspace holding audio, with whatever score the test needs. */
    private Path audioWorkspace(String name, ChordProgression chords) {
        Path source = directory.resolve(name + ".wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.2, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        return workspaceWith(name, source, chords);
    }

    /**
     * Imports a source and writes a score straight into the workspace.
     *
     * <p>The score is planted rather than analysed on purpose: what is under test
     * is what {@code render} does with a score, and running a transcriber to
     * obtain one would make these tests depend on the accuracy of a stage they
     * are not about.
     */
    private Path workspaceWith(String name, Path source, ChordProgression chords) {
        Path root = directory.resolve(name + ".mwz");
        CliRunner.Result init = CliRunner.run("init", source.toString(), "-w", root.toString());
        assertThat(init.exitCode()).as(init.all()).isZero();
        Workspace workspace = Workspace.open(root);
        workspace.writeScore(Score.empty(TempoMap.constant(120), 8.0).withChords(chords));
        return root;
    }

    @Nested
    @DisplayName("the parts line")
    class PartsLine {

        @Test
        @DisplayName("defaults to what is implemented, not to what is planned")
        void defaultsToTheImplementedParts() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out()).contains("Parts      chords");
            // The four-name line #82 was filed about.
            assertThat(render.out()).doesNotContain("voice, piano, bass, chords");
        }

        @Test
        @DisplayName("an unknown part is a usage error naming the ones that exist")
        void rejectsAnUnknownPart() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--parts", "drums", "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.USAGE);
            assertThat(render.err())
                    .contains("unknown part 'drums'")
                    .contains("chords");
        }
    }

    @Nested
    @DisplayName("a part that cannot be produced")
    class Unavailable {

        @Test
        @DisplayName("is named with the reason when it was asked for explicitly")
        void namesTheReason() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                    "--parts", "chords,voice,bass,piano", "--no-pdf");

            assertThat(render.out())
                    .contains("Not written:")
                    .contains("voice    melody transcription is not implemented yet (#8)")
                    .contains("bass     bass transcription is not implemented yet (#8)")
                    .contains("piano    the piano reduction is not implemented yet (#10)");
            // Chords were produced, so this run is a partial success.
            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out()).contains("chords.txt").contains("chords.ly");
        }

        @Test
        @DisplayName("makes the run fail only when nothing at all was written")
        void failsOnlyWhenNothingWasWritten() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--parts", "voice,piano", "--no-pdf");

            assertThat(render.exitCode())
                    .as("a run that produced nothing exited 0")
                    .isEqualTo(picocli.CommandLine.ExitCode.SOFTWARE);
            assertThat(render.out()).contains("Nothing could be written.");
            assertThat(workspace.resolve("out/chords.txt")).doesNotExist();
        }
    }

    @Nested
    @DisplayName("a score with no harmony")
    class NoHarmony {

        @Test
        @DisplayName("from MIDI is explained by where it came from")
        void midiIsExplainedAsSymbolic() {
            Path workspace = midiWorkspace("four", ChordProgression.empty());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.SOFTWARE);
            assertThat(render.out())
                    .contains("a MIDI file states notes, not harmony")
                    .contains("(#115)");
            // The empty chart is not written at all: a file saying "(no chords
            // were found)" is a file a user has to open to learn nothing.
            assertThat(workspace.resolve("out/chords.txt")).doesNotExist();
        }

        @Test
        @DisplayName("from audio is explained as a result, not as a missing feature")
        void audioIsExplainedAsAnEmptyResult() {
            Path workspace = audioWorkspace("quiet", ChordProgression.empty());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.SOFTWARE);
            assertThat(render.out())
                    .contains("the analysis found no harmony in this recording")
                    .doesNotContain("#115");
        }
    }

    @Nested
    @DisplayName("a score with harmony")
    class WithHarmony {

        @Test
        @DisplayName("is written, and the chart is echoed")
        void writesTheChart() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(workspace.resolve("out/chords.txt")).exists();
            assertThat(workspace.resolve("out/chords.ly")).exists();
            assertThat(render.out()).contains("| C").contains("| G").contains("| Am");
        }

        @Test
        @DisplayName("is written once even when the part is named twice")
        void doesNotRepeatARequestedPart() {
            Path workspace = audioWorkspace("song", fourChords());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--parts", "chords,chords", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out().split("chords\\.txt", -1))
                    .as("the chart was written twice")
                    .hasSize(2);
        }
    }
}
