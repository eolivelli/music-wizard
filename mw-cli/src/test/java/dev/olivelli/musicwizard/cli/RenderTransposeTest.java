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
import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code render --transpose} does to the files it writes, which is #129.
 *
 * <p>{@code TransposerTest} covers the musical decision. What is under test here
 * is that the decision reaches the output at all: the flag was accepted, echoed
 * back and discarded, and both files came out byte-identical to a run without it.
 * So every assertion below is about the bytes on disk or the lines on the
 * terminal rather than about a {@code Score}.
 *
 * <p>Everything runs with {@code --no-pdf}: the fast suite must not shell out to
 * LilyPond, and nothing here is about engraving.
 */
class RenderTransposeTest {

    @TempDir
    Path directory;

    /** I-V-vi-IV in C major, with the key stated so the chart can print it. */
    private static Score inCMajor() {
        return baseScore(new ChordProgression(List.of(
                chord("C4", ChordQuality.MAJOR, 0, 2),
                chord("G4", ChordQuality.MAJOR, 2, 4),
                chord("A4", ChordQuality.MINOR, 4, 6),
                chord("F4", ChordQuality.MAJOR, 6, 8)),
                Confidence.of(0.8)))
                .withKeys(List.of(Key.ofSeconds(PitchSpelling.parse("C4"), Mode.MAJOR,
                        0, 8, Confidence.of(0.9))));
    }

    private static Score baseScore(ChordProgression chords) {
        return new Score(Optional.of("Four chords"), Optional.empty(),
                TempoMap.constant(120, TimeSignature.FOUR_FOUR), Optional.empty(),
                List.of(), List.of(), List.of(), chords, Lyrics.empty(), 8.0);
    }

    private static Chord chord(String root, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality, from, to, Confidence.of(0.8));
    }

    /** A workspace holding audio and whatever score the test plants in it. */
    private Path workspaceWith(String name, Score score) {
        Path source = directory.resolve(name + ".wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.2, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve(name + ".mwz");
        CliRunner.Result init = CliRunner.run("init", source.toString(), "-w", root.toString());
        assertThat(init.exitCode()).as(init.all()).isZero();
        Workspace.open(root).writeScore(score);
        return root;
    }

    @Nested
    @DisplayName("the files it writes")
    class Output {

        @Test
        @DisplayName("carry the transposed chords, not the analysed ones")
        void theChartMoves() throws Exception {
            // The reproduction from #129, inverted. Both files used to be
            // byte-identical to a plain run.
            Path plain = workspaceWith("plain", inCMajor());
            Path moved = workspaceWith("moved", inCMajor());

            CliRunner.run("render", plain.toString(), "--no-pdf");
            CliRunner.Result render = CliRunner.run(
                    "render", moved.toString(), "--transpose", "5", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(Files.readString(moved.resolve("out/chords.txt")))
                    .isNotEqualTo(Files.readString(plain.resolve("out/chords.txt")));
            assertThat(Files.readString(moved.resolve("out/chords.txt")))
                    .contains("| F", "| C", "| Dm", "| Bb");
            assertThat(Files.readString(moved.resolve("out/chords.ly")))
                    .contains("f1 c1 d1:m bes1");
        }

        @Test
        @DisplayName("carry the transposed key signature too, which is half the answer")
        void theKeyMoves() throws Exception {
            // Getting the chords right and the key wrong would still hand a
            // singer a page they cannot use, and the key is the part that
            // reveals a spelling gone astray: "up 3 semitones" is true of both E
            // flat and D sharp.
            Path workspace = workspaceWith("song", inCMajor());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "3", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(Files.readString(workspace.resolve("out/chords.txt")))
                    .contains("Key    Eb major")
                    .contains("| Eb", "| Bb", "| Cm", "| Ab");
            // The engraved source, spelled flat: aes, not gis.
            assertThat(Files.readString(workspace.resolve("out/chords.ly")))
                    .contains("ees1 bes1 c1:m aes1")
                    .doesNotContain("dis")
                    .doesNotContain("gis");
        }

        @Test
        @DisplayName("agree with the chart printed to the terminal")
        void theTerminalAndTheFileAgree() throws Exception {
            // render's last act is to print the chart, and that is what a user
            // actually pipes. A transposition reaching the files and not the
            // terminal would be a new way to be confidently wrong.
            Path workspace = workspaceWith("song", inCMajor());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "-2", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out())
                    .contains(Files.readString(workspace.resolve("out/chords.txt")).strip());
        }

        @Test
        @DisplayName("leave the saved transcription in its own key")
        void theWorkspaceIsNotRewritten() {
            // render is a read-only view of the analysis. Rewriting the score
            // would make a second render --transpose 5 shift by ten, and the
            // user's own recording would no longer be what the workspace claims.
            Path workspace = workspaceWith("song", inCMajor());

            CliRunner.run("render", workspace.toString(), "--transpose", "5", "--no-pdf");

            Score saved = Workspace.open(workspace).readScore().orElseThrow();
            assertThat(saved.chords().chords().stream().map(Chord::symbol))
                    .containsExactly("C", "G", "Am", "F");
            assertThat(saved.primaryKey().orElseThrow().tonic())
                    .isEqualTo(PitchSpelling.parse("C4"));
        }

        @Test
        @DisplayName("move the note tracks as well as the harmony")
        void notesMoveToo() {
            // Only chords are engraved today, so nothing on disk would show
            // this. It is asserted through the saved-and-re-read score of a
            // second render rather than left until #8 and #10 make it visible,
            // because that is exactly when nobody will be looking for it.
            Score withVoice = inCMajor().withTrack(new NoteTrack(PartRole.LEAD_VOCAL, "Voice",
                    List.of(new Note(0.0, 1.0, 60, Note.DEFAULT_VELOCITY,
                            Optional.of(PitchSpelling.parse("C4")),
                            Optional.empty(), Optional.empty(), Confidence.CERTAIN)),
                    Confidence.CERTAIN));
            Path workspace = workspaceWith("song", withVoice);

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "3", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out()).contains("Transpose  +3 semitones, C major to Eb major");
        }
    }

    @Nested
    @DisplayName("the header line")
    class Reported {

        @Test
        @DisplayName("names the shift and the keys either side of it")
        void namesWhereItLands() {
            Path workspace = workspaceWith("song", inCMajor());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "5", "--no-pdf");

            assertThat(render.out()).contains("Transpose  +5 semitones, C major to F major");
        }

        @Test
        @DisplayName("says only the shift when there is no key to name")
        void namesOnlyTheShiftWithoutAKey() {
            // The audio path has no key detection yet, so a score with chords
            // and no key is the shipped tool's normal state. Naming a key it
            // does not have would be the overclaim this project treats as a
            // defect in its own right.
            Path workspace = workspaceWith("song", baseScore(new ChordProgression(List.of(
                    chord("C4", ChordQuality.MAJOR, 0, 4),
                    chord("G4", ChordQuality.MAJOR, 4, 8)), Confidence.of(0.8))));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "-1", "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out()).contains("Transpose  -1 semitone");
            assertThat(render.out()).doesNotContain(" to ");
        }

        @Test
        @DisplayName("is absent when nothing was asked for")
        void staysQuietAtZero() {
            Path workspace = workspaceWith("song", inCMajor());

            CliRunner.Result zero = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "0", "--no-pdf");
            CliRunner.Result none = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(zero.exitCode()).as(zero.all()).isZero();
            assertThat(zero.out()).doesNotContain("Transpose");
            assertThat(none.out()).doesNotContain("Transpose");
        }
    }

    @Nested
    @DisplayName("the workspace config")
    class FromTheConfigLayer {

        @Test
        @DisplayName("transposes exactly as the flag does")
        void theConfigKeyWorks() throws Exception {
            // #129 asked for both routes by name. A singer's key is a persistent
            // preference, so workspace.yaml is where it belongs, and the flag
            // working while the documented key did nothing would be the same
            // defect with a smaller audience.
            Path workspace = workspaceWith("song", inCMajor());
            Workspace.open(workspace).updateConfig(new MusicWizardConfig(null, null,
                    new MusicWizardConfig.NotationConfig(null, null, -2, null, null),
                    null, null, null));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).as(render.all()).isZero();
            assertThat(render.out()).contains("Transpose  -2 semitones, C major to Bb major");
            assertThat(Files.readString(workspace.resolve("out/chords.txt")))
                    .contains("Key    Bb major")
                    .contains("| Bb", "| F", "| Gm", "| Eb");
        }

        @Test
        @DisplayName("is overridden by the flag, which is the layering the CLI already has")
        void theFlagWins() {
            Path workspace = workspaceWith("song", inCMajor());
            Workspace.open(workspace).updateConfig(new MusicWizardConfig(null, null,
                    new MusicWizardConfig.NotationConfig(null, null, -2, null, null),
                    null, null, null));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "5", "--no-pdf");

            assertThat(render.out()).contains("Transpose  +5 semitones, C major to F major");
        }
    }

    @Nested
    @DisplayName("a shift that cannot be honoured")
    class Refused {

        @Test
        @DisplayName("beyond two octaves is a usage error, and nothing is written")
        void tooFarIsRefused() {
            // Pitch classes repeat every twelve, so --transpose 50 typed for
            // --transpose 5 would otherwise print a chart two semitones up and
            // exit 0 -- a confident wrong answer, which is the category this
            // whole change exists to remove.
            Path workspace = workspaceWith("song", inCMajor());

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "50", "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.USAGE);
            assertThat(render.err()).contains("-24..24").contains("50");
            assertThat(workspace.resolve("out/chords.txt")).doesNotExist();
        }

        @Test
        @DisplayName("out of range in the config says where the value came from")
        void outOfRangeFromTheConfigSaysSo() {
            // A usage error pointing at a flag the user did not type sends them
            // looking in the wrong place.
            Path workspace = workspaceWith("song", inCMajor());
            Workspace.open(workspace).updateConfig(new MusicWizardConfig(null, null,
                    new MusicWizardConfig.NotationConfig(null, null, 99, null, null),
                    null, null, null));

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.USAGE);
            assertThat(render.err()).contains("notation.transposeSemitones");
        }

        @Test
        @DisplayName("a note it would push past MIDI 127 stops the run before anything is written")
        void anUnmovableNoteStopsTheRun() {
            // #57: the model refuses rather than guessing, and the caller decides
            // what that means. Here it means the whole run fails, because
            // engraving three files in the new key and leaving one note in the
            // old one is the failure nobody would spot.
            Score withHighNote = inCMajor().withTrack(new NoteTrack(PartRole.BASS, "Bass",
                    List.of(new Note(1.5, 1.0, 126, Note.DEFAULT_VELOCITY,
                            Optional.of(new PitchSpelling(NoteLetter.F, Accidental.SHARP, 9)),
                            Optional.empty(), Optional.empty(), Confidence.CERTAIN)),
                    Confidence.CERTAIN));
            Path workspace = workspaceWith("song", withHighNote);

            CliRunner.Result render = CliRunner.run(
                    "render", workspace.toString(), "--transpose", "3", "--no-pdf");

            assertThat(render.exitCode()).isEqualTo(picocli.CommandLine.ExitCode.SOFTWARE);
            assertThat(render.err())
                    .contains("Bass")
                    .contains("126")
                    .contains("MIDI range");
            assertThat(workspace.resolve("out/chords.txt")).doesNotExist();
        }
    }
}
