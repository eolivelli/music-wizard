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

import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("lyric alignment in analyze")
class AlignedLyricsTest {

    @TempDir
    Path directory;

    /** Invented syllables; only the timing is under test. */
    private static final String LRC = """
            [00:01.00]la sol mi
            [00:04.00]do re
            """;

    private Path analysed(String alignmentProvider) throws IOException {
        Path source = directory.resolve("song.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 6.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("song.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString())
                .exitCode()).isZero();
        Path descriptor = root.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                + "\nconfig:\n  ml:\n    alignmentProvider: " + alignmentProvider + "\n");
        Path lrc = directory.resolve("words.lrc");
        Files.writeString(lrc, LRC);
        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics", lrc.toString(), "--lyrics-language", "en");
        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        return root;
    }

    @Test
    @DisplayName("aligned words carry the aligner's times, offset to the line's window")
    void alignedTimesReplaceSpreadTimes() throws IOException {
        Path root = analysed("fake-cli-alignment");

        Score score = Workspace.open(root).readScore().orElseThrow();
        List<LyricWord> words = score.lyrics().lines().get(0).words();

        // The window opens half a second before the line's 1.0 s start, so the
        // fake's first word at 0.111 lands at 0.5 + 0.111. A spread word would
        // sit exactly at 1.0.
        assertThat(words.get(0).startSeconds()).isEqualTo(0.5 + 0.111);
        assertThat(words.get(1).startSeconds()).isEqualTo(0.5 + 0.222);
        assertThat(words.get(0).confidence().value()).isEqualTo(0.97);
    }

    @Test
    @DisplayName("aligned lines never overlap, whatever the aligner returns")
    void alignedLinesDoNotOverlap() throws IOException {
        // The whole-window fake pushes each line's result to both edges of
        // its window. The invariant is enforced once, at assembly, so no
        // single revert of the window head or the tail bound can break this
        // assertion -- that is the design, not a gap: the head and the bound
        // exist so the assembly guard is a no-op and the times stay genuine
        // rather than shifted. ShiftedAfterTest pins the guard directly. The
        // sheet's chord cursor, which walks line ends in order, depends on
        // the invariant asserted here.
        Path root = analysed("fake-cli-late-alignment");

        Score score = Workspace.open(root).readScore().orElseThrow();
        var lines = score.lyrics().lines();
        for (int i = 1; i < lines.size(); i++) {
            assertThat(lines.get(i).startSeconds())
                    .as("line %d must not start before line %d ended", i, i - 1)
                    .isGreaterThanOrEqualTo(lines.get(i - 1).endSeconds());
        }
    }

    @Test
    @DisplayName("lines on one moment keep their shared span, and nothing cascades")
    void sharedMomentsSurviveAlignment() throws IOException {
        // Two entries on one timestamp share a span by the model's own design
        // (#340): a second voice is sung together, not in sequence. A spacing
        // rule keyed on the predecessor's end once displaced the twin by a
        // whole line and pushed every later line off the end of the recording,
        // where the engraving drops words.
        Path source = directory.resolve("song.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 8.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("song.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString())
                .exitCode()).isZero();
        Path descriptor = root.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                + "\nconfig:\n  ml:\n    alignmentProvider: fake-cli-late-alignment\n");
        Path lrc = directory.resolve("twins.lrc");
        Files.writeString(lrc, """
                [00:01.00]la sol
                [00:01.00]mi fa
                [00:04.00]do re
                """);
        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics", lrc.toString(), "--lyrics-language", "en");
        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        // The aligner genuinely ran on the non-twin line: without this the
        // whole test passes on parsed times with no provider at all.
        assertThat(analyze.out()).contains("aligned 1 lyric lines")
                .contains("2 kept their parsed times");

        Score score = Workspace.open(root).readScore().orElseThrow();
        var lines = score.lyrics().lines();
        assertThat(lines).hasSize(3);
        // The twins keep the parser's shared span, untouched by alignment.
        assertThat(lines.get(0).startSeconds()).isEqualTo(1.0);
        assertThat(lines.get(1).startSeconds()).isEqualTo(1.0);
        assertThat(lines.get(0).endSeconds()).isEqualTo(lines.get(1).endSeconds());
        // The line after them is aligned normally and stays on the recording.
        assertThat(lines.get(2).endSeconds())
                .isLessThanOrEqualTo(8.0);
    }

    @Test
    @DisplayName("a bare re-analyze does not move aligned times")
    void reAnalyzeIsIdempotent() throws IOException {
        // Carried-forward lyrics were aligned when supplied. Re-aligning them
        // recomputes every window from the aligned times, an unbounded walk,
        // one step per analyze.
        Path root = analysed("fake-cli-alignment");
        Score first = Workspace.open(root).readScore().orElseThrow();

        CliRunner.Result again = CliRunner.run("analyze", root.toString());
        assertThat(again.exitCode()).as(again.all()).isZero();

        Score second = Workspace.open(root).readScore().orElseThrow();
        assertThat(second.lyrics().lines().get(0).words().get(0).startSeconds())
                .isEqualTo(first.lyrics().lines().get(0).words().get(0).startSeconds());
    }

    @Test
    @DisplayName("a result wholly past the tail bound keeps the parsed times, never reverses")
    void resultPastTheBoundKeepsParsedTimes() throws IOException {
        // The window hears half a second past the bound, and an aligner can
        // place the whole line there. Compressing into [firstWord, bound]
        // would then scale negatively and reverse the words; the parsed guess
        // must win instead.
        Path source = directory.resolve("song.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 6.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("song.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString())
                .exitCode()).isZero();
        Path descriptor = root.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                + "\nconfig:\n  ml:\n    alignmentProvider: fake-cli-tail-alignment\n");
        Path lrc = directory.resolve("words.lrc");
        Files.writeString(lrc, LRC);
        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics", lrc.toString(), "--lyrics-language", "en");
        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        // The aligner ran and the predicate fired -- without this, a dropped
        // services line turns this test into a silent duplicate of the
        // absent-aligner one below, still green.
        assertThat(analyze.out()).contains("kept their parsed times");

        Score score = Workspace.open(root).readScore().orElseThrow();
        var words = score.lyrics().lines().get(0).words();
        // Parsed spread times -- not a reversed compression. The times are the
        // parser's, and the word order is the LRC's: LyricLine sorts words by
        // start, so a reversal shows up as reversed text, which is the harm.
        assertThat(words.get(0).startSeconds()).isEqualTo(1.0);
        assertThat(words.stream().map(w -> w.text()).toList())
                .containsExactly("la", "sol", "mi");
    }

    @Test
    @DisplayName("an absent aligner leaves the parsed times untouched")
    void absentAlignerKeepsParsedTimes() throws IOException {
        Path root = analysed("no-such-aligner");

        Score score = Workspace.open(root).readScore().orElseThrow();
        List<LyricWord> words = score.lyrics().lines().get(0).words();

        // Spread times: the line starts where the LRC says.
        assertThat(words.get(0).startSeconds()).isEqualTo(1.0);
        assertThat(words.get(0).confidence().value()).isLessThan(0.97);
    }
}
