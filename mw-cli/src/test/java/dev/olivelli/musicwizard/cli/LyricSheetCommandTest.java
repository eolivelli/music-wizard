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

import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code analyze --lyrics} through to the written sheet, which is #308.
 *
 * <p>Runs the real pipeline over a short synthetic signal rather than planting a
 * score, because what is under test is the seam: the option has to survive the
 * transcription cache, reach the score file, and come back out of it in
 * {@code render}. A planted score would skip every step that can go wrong.
 *
 * <p>Nothing here asserts on which chords were heard. The signal is a fixture
 * and its harmony is not the subject; the assertions are about lyrics arriving
 * and about the two output files staying distinct.
 */
class LyricSheetCommandTest {

    @TempDir
    Path directory;

    private static final String LRC = """
            [ti:Test Song]
            [00:00.50]<00:00.50>one <00:01.00>two
            [00:01.50]<00:01.50>three <00:02.00>four
            """;

    /** A workspace over a couple of seconds of audio, analysed with a lyric file. */
    private Path analysed(String lrc) throws IOException {
        Path source = directory.resolve("song.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 3.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("song.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString()).exitCode())
                .isZero();

        if (lrc == null) {
            assertThat(CliRunner.run("analyze", root.toString()).exitCode()).isZero();
            return root;
        }
        Path lyrics = directory.resolve("song.lrc");
        Files.writeString(lyrics, lrc);
        CliRunner.Result analyze = CliRunner.run(
                "analyze", root.toString(), "--lyrics", lyrics.toString());
        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        return root;
    }

    @Test
    @DisplayName("the lyrics reach the score file and survive the round trip")
    void lyricsLandInTheScore() throws IOException {
        Path root = analysed(LRC);

        Score score = Workspace.open(root).readScore().orElseThrow();

        assertThat(score.lyrics().isEmpty()).isFalse();
        assertThat(score.lyrics().lines()).hasSize(2);
        assertThat(score.lyrics().text()).isEqualTo("one two\nthree four");
        // Still in seconds. Putting a syllable on a beat needs the note it is
        // sung on, which is #150.
        assertThat(score.lyrics().isQuantized()).isFalse();
    }

    @Test
    @DisplayName("render writes the sheet beside the chart, and leaves the chart alone")
    void writesBothFiles() throws IOException {
        Path root = analysed(LRC);

        CliRunner.Result render = CliRunner.run("render", root.toString(), "--no-pdf");

        assertThat(render.exitCode()).as(render.all()).isZero();
        Path chart = root.resolve("out/chords.txt");
        Path sheet = root.resolve("out/chords-lyrics.txt");
        assertThat(chart).exists();
        assertThat(sheet).exists();
        assertThat(render.out()).contains("chords-lyrics.txt");

        // The chart is a bar grid and says nothing about the words; the sheet
        // carries the words and draws no bar lines. Two layouts, not one file
        // with rows added -- and tools/score-chart.py reads every line of
        // chords.txt beginning with "|" as a bar.
        String chartText = Files.readString(chart);
        String sheetText = Files.readString(sheet);
        assertThat(chartText).doesNotContain("one two");
        assertThat(chartText.lines().filter(line -> line.startsWith("|"))).isNotEmpty();
        assertThat(sheetText).contains("one two").contains("three four");
        assertThat(sheetText.lines()).noneMatch(line -> line.startsWith("|"));
    }

    @Test
    @DisplayName("only the lyrics part can be asked for")
    void lyricsPartAlone() throws IOException {
        Path root = analysed(LRC);

        CliRunner.Result render = CliRunner.run(
                "render", root.toString(), "--parts", "lyrics", "--no-pdf");

        assertThat(render.exitCode()).as(render.all()).isZero();
        assertThat(root.resolve("out/chords-lyrics.txt")).exists();
        assertThat(root.resolve("out/chords.txt")).doesNotExist();
    }

    @Test
    @DisplayName("without --lyrics the sheet is named as unavailable, not silently skipped")
    void noLyricsIsExplained() throws IOException {
        Path root = analysed(null);

        CliRunner.Result render = CliRunner.run("render", root.toString(), "--no-pdf");

        assertThat(render.out())
                .contains("this score holds no lyrics")
                .contains("--lyrics");
        assertThat(root.resolve("out/chords-lyrics.txt")).doesNotExist();
    }

    @Test
    @DisplayName("a lyrics file that is not LRC warns and leaves the analysis standing")
    void unreadableLyricsDoNotFailTheRun() throws IOException {
        Path source = directory.resolve("plain.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 3.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("plain.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString()).exitCode())
                .isZero();
        Path lyrics = directory.resolve("plain.txt");
        Files.writeString(lyrics, "just the words\nwith no timestamps\n");

        CliRunner.Result analyze = CliRunner.run(
                "analyze", root.toString(), "--lyrics", lyrics.toString());

        // The analysis is the expensive thing and it succeeded; a mistyped lyric
        // file must not cost it.
        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.all()).contains("no [mm:ss.xx] timestamps");
        assertThat(Workspace.open(root).readScore().orElseThrow().lyrics().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a missing lyrics file warns and leaves the analysis standing")
    void missingLyricsFileDoesNotFailTheRun() throws IOException {
        Path source = directory.resolve("gone.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 3.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("gone.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString()).exitCode())
                .isZero();

        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics", directory.resolve("nowhere.lrc").toString());

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.all()).contains("lyrics file could not be read");
    }

    @Test
    @DisplayName("a later analyze without --lyrics keeps the lyrics already supplied")
    void lyricsSurviveAReanalyze() throws IOException {
        Path root = analysed(LRC);

        // Correcting the tempo is the highest-value action the README names, and
        // it must not silently discard the lyrics supplied a moment earlier.
        CliRunner.Result again = CliRunner.run("analyze", root.toString(), "--tempo", "100");

        assertThat(again.exitCode()).as(again.all()).isZero();
        assertThat(Workspace.open(root).readScore().orElseThrow().lyrics().text())
                .isEqualTo("one two\nthree four");
    }

    @Test
    @DisplayName("an offset that is not a number does not cost the analysis")
    void badOffsetDoesNotFailTheRun() throws IOException {
        Path source = directory.resolve("nan.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 3.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("nan.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString()).exitCode())
                .isZero();
        Path lyrics = directory.resolve("nan.lrc");
        Files.writeString(lyrics, "[offset:NaN]\n[00:00.50]one two\n[00:01.50]three\n");

        CliRunner.Result analyze = CliRunner.run(
                "analyze", root.toString(), "--lyrics", lyrics.toString());

        // The DSP had already run. Losing the score and its cache entry over a
        // malformed tag in a decoration is the trade this whole path avoids.
        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(root.resolve("score/score.json")).exists();
        assertThat(Workspace.open(root).readScore().orElseThrow().lyrics().isEmpty()).isFalse();
    }

    @Test
    @DisplayName("a corrupt score does not cost the analysis that would replace it")
    void corruptScoreDoesNotFailTheRun() throws IOException {
        Path root = analysed(LRC);
        Files.writeString(root.resolve("score/score.json"), "{ this is not json");

        // Unguarded, reading the previous score aborts analyze after the DSP has
        // run and before the new score is written -- leaving the bad file in
        // place and the workspace unrecoverable, since render fails the same way
        // and analyze is the only command that would overwrite it.
        CliRunner.Result again = CliRunner.run("analyze", root.toString(), "--tempo", "100");

        assertThat(again.exitCode()).as(again.all()).isZero();
        assertThat(again.all()).contains("could not be read");
        assertThat(Workspace.open(root).readScore()).isPresent();
    }

    @Test
    @DisplayName("the --parts help names lyrics as something that can be produced")
    void helpNamesTheLyricsPart() {
        CliRunner.Result help = CliRunner.run("render", "--help");

        assertThat(help.all()).contains("lyrics");
    }

    @Test
    @DisplayName("--lyrics-language reaches the score and splits words on the page")
    void languageSplitsWords() throws IOException {
        Path source = directory.resolve("it.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 3.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("it.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString()).exitCode())
                .isZero();
        Path lyrics = directory.resolve("it.lrc");
        Files.writeString(lyrics, "[00:00.50]<00:00.50>amore <00:01.50>canzone\n");

        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics", lyrics.toString(), "--lyrics-language", "it");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(Workspace.open(root).readScore().orElseThrow().lyrics().language())
                .isEqualTo("it");

        CliRunner.Result render = CliRunner.run(
                "render", root.toString(), "--parts", "lyrics", "--no-pdf");
        assertThat(render.exitCode()).as(render.all()).isZero();
        String engraved = Files.readString(root.resolve("out/chords-lyrics.ly"));
        // The syllables and a hyphen joining them. Not adjacency: a chain can
        // cross a bar line, and then the partner is on the next line after a
        // skip.
        assertThat(engraved).as("%s", engraved)
                .contains("\"a\"").contains("\"mo\"").contains("--")
                .doesNotContain("\"amore\"");
        // The text sheet still prints whole words: syllables are for the page,
        // where each one needs its own position.
        assertThat(Files.readString(root.resolve("out/chords-lyrics.txt"))).contains("amore");
    }

    @Test
    @DisplayName("a language with no patterns warns rather than silently not splitting")
    void unsupportedLanguageWarns() throws IOException {
        Path source = directory.resolve("de.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 3.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("de.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString()).exitCode())
                .isZero();
        Path lyrics = directory.resolve("de.lrc");
        Files.writeString(lyrics, "[00:00.50]<00:00.50>Liebe\n");

        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics", lyrics.toString(), "--lyrics-language", "de");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.all()).contains("no hyphenation patterns");
        assertThat(Workspace.open(root).readScore().orElseThrow().lyrics().isEmpty()).isFalse();
    }

    @Test
    @DisplayName("lyrics are not baked into the transcription cache")
    void lyricsStayOutOfTheCache() throws IOException {
        Path root = analysed(LRC);

        // A second run with different lyrics must reuse the analysis: the cache
        // key covers the recording and how it was listened to, and a corrected
        // lyric file is neither. Recomputing here would throw away minutes of
        // DSP on a real recording for a typo.
        Path other = directory.resolve("other.lrc");
        Files.writeString(other, "[00:00.50]<00:00.50>different words\n");
        CliRunner.Result second = CliRunner.run(
                "analyze", root.toString(), "--lyrics", other.toString());

        assertThat(second.exitCode()).as(second.all()).isZero();
        assertThat(second.out()).contains("reusing the cached analysis");
        assertThat(Workspace.open(root).readScore().orElseThrow().lyrics().text())
                .isEqualTo("different words");
    }
}
