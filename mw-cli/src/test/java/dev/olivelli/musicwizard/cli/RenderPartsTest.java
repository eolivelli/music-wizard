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
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    /** A workspace holding audio, with whatever score the test needs. */
    private Path audioWorkspace(String name, ChordProgression chords) {
        return workspaceWith(name, chords, List.of());
    }

    /**
     * Imports a source and writes a score straight into the workspace.
     *
     * <p>The score is planted rather than analysed on purpose: what is under test
     * is what {@code render} does with a score, and running a transcriber to
     * obtain one would make these tests depend on the accuracy of a stage they
     * are not about.
     *
     * <p>The source is always audio, and never varied to change what
     * {@code render} says. That is the point of round 1's finding: what
     * {@code render} can produce is a property of the score, and it used to
     * re-sniff the file on disk to decide -- which failed when the file was gone
     * and lied when it had been replaced.
     */
    private Path workspaceWith(String name, ChordProgression chords, List<NoteTrack> tracks) {
        Path source = directory.resolve(name + ".wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.2, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve(name + ".mwz");
        CliRunner.Result init = CliRunner.run("init", source.toString(), "-w", root.toString());
        assertThat(init.exitCode()).as(init.all()).isZero();
        Workspace workspace = Workspace.open(root);
        Score score = Score.empty(TempoMap.constant(120), 8.0).withChords(chords);
        for (NoteTrack track : tracks) {
            score = score.withTrack(track);
        }
        workspace.writeScore(score);
        return root;
    }

    /** One part's worth of notes, so a score can hold notes and no harmony. */
    private static NoteTrack aPart(String name) {
        return new NoteTrack(PartRole.OTHER, name,
                List.of(new Note(0.0, 1.0, 60, 80, Optional.empty(),
                        Optional.of(0.0), Optional.of(1.0), Confidence.CERTAIN)),
                Confidence.CERTAIN);
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
        @DisplayName("but with parts is a missing feature, and says which")
        void notesWithoutHarmonyPointAtTheMissingStage() {
            Path workspace = workspaceWith("four", ChordProgression.empty(),
                    List.of(aPart("Piano"), aPart("Bass")));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.SOFTWARE);
            assertThat(render.out())
                    .contains("holds 2 part(s) but no chord progression")
                    .contains("(#115)");
            // The empty chart is not written at all: a file saying "(no chords
            // were found)" is a file a user has to open to learn nothing.
            assertThat(workspace.resolve("out/chords.txt")).doesNotExist();
        }

        @Test
        @DisplayName("and no parts is an empty score, not a missing feature")
        void nothingAtAllIsAnEmptyScore() {
            Path workspace = audioWorkspace("quiet", ChordProgression.empty());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.SOFTWARE);
            assertThat(render.out())
                    .contains("no chord progression and no parts either")
                    .doesNotContain("#115");
        }

        @Test
        @DisplayName("says nothing about where the score came from, because it cannot know")
        void doesNotGuessAtProvenance() {
            // A MIDI file holding only a conductor track -- a plain tempo-map
            // export -- imports to a score with no parts, and so takes the same
            // branch as an audio analysis that found nothing. Round 1's fix
            // replaced a source-file sniff with a proxy; round 2 found the proxy
            // was wrong here, on a real file, with the source present and
            // untouched. So neither message may name a source kind at all.
            Path source = MidiFixtures.write(MidiFixtures.sequence()
                    .name("Conductor")
                    .tempo(120)
                    .tempoAt(4, 60)
                    .timeSignature(4, 4)
                    .build(), directory.resolve("conductor.mid"));
            Path root = directory.resolve("conductor.mwz");
            assertThat(CliRunner.run("init", source.toString(), "-w", root.toString()).exitCode())
                    .isZero();
            assertThat(CliRunner.run("analyze", root.toString()).exitCode()).isZero();
            assertThat(Workspace.open(root).readScore().orElseThrow().tracks())
                    .as("the fixture must take the no-parts branch to discriminate")
                    .isEmpty();

            CliRunner.Result render = CliRunner.run("render", root.toString(), "--no-pdf");

            assertThat(render.out())
                    .contains("no chord progression and no parts either")
                    .doesNotContain("recording")
                    .doesNotContain("audio")
                    .doesNotContain("MIDI file");
        }

        @Test
        @DisplayName("does not announce an output directory or an engraver")
        void announcesNothingItWillNotUse() {
            Path workspace = audioWorkspace("quiet", ChordProgression.empty());

            CliRunner.Result render = CliRunner.run("render", workspace.toString());

            // Not --no-pdf: the engraver is looked up on this path in real use,
            // and announcing it before discovering there is nothing to engrave is
            // the same defect as the parts line.
            assertThat(render.out())
                    .doesNotContain("Output ")
                    .doesNotContain("Engraver");
            // The directory itself is created by init, so what must be true is
            // that nothing was put in it.
            assertThat(workspace.resolve("out")).isEmptyDirectory();
        }
    }

    @Nested
    @DisplayName("a workspace whose recording is gone")
    class WithoutItsSource {

        @Test
        @DisplayName("still renders, because the score holds what an engraver needs")
        void rendersFromTheScoreAlone() throws Exception {
            Path workspace = audioWorkspace("song", fourChords());
            // A workspace duplicates the recording, so deleting it to reclaim
            // space -- or archiving one without it -- is ordinary. render used to
            // re-sniff this file to word one message, and refused the whole run
            // when it was missing.
            Path source = Workspace.open(workspace).sourceFile();
            java.nio.file.Files.delete(source);

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(workspace.resolve("out/chords.txt")).exists();
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
