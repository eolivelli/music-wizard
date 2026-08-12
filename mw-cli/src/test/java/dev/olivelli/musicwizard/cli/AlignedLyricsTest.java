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
        // The late fake places words in the last fifth of each window, so with
        // independent windows line 1's result runs past line 2's parsed start
        // and the two collide -- an early-times fake never makes windows
        // interact, and this test passed with the fix reverted until review
        // caught it. The sheet's chord cursor, which walks line ends in order,
        // depends on the invariant asserted here.
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
    @DisplayName("a bare re-analyze does not move aligned times")
    void reAnalyzeIsIdempotent() throws IOException {
        // Carried-forward lyrics were aligned when supplied. Re-aligning them
        // recomputes every window from the aligned times, which review
        // measured as an unbounded walk toward zero, one step per analyze.
        Path root = analysed("fake-cli-alignment");
        Score first = Workspace.open(root).readScore().orElseThrow();

        CliRunner.Result again = CliRunner.run("analyze", root.toString());
        assertThat(again.exitCode()).as(again.all()).isZero();

        Score second = Workspace.open(root).readScore().orElseThrow();
        assertThat(second.lyrics().lines().get(0).words().get(0).startSeconds())
                .isEqualTo(first.lyrics().lines().get(0).words().get(0).startSeconds());
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
