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

import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        return analysed(alignmentProvider, LRC, "en", "song");
    }

    private Path analysed(String alignmentProvider, String lyrics, String language)
            throws IOException {
        return analysed(alignmentProvider, lyrics, language, "song");
    }

    /** The analyze output of the last {@link #analysed} run. */
    private String lastAnalyze = "";

    private Path analysed(String alignmentProvider, String lyrics, String language,
                          String name) throws IOException {
        Path source = directory.resolve(name + ".wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 6.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve(name + ".mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString())
                .exitCode()).isZero();
        Path descriptor = root.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                + "\nconfig:\n  ml:\n    alignmentProvider: " + alignmentProvider + "\n");
        Path lrc = directory.resolve(name + ".lrc");
        Files.writeString(lrc, lyrics);
        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics", lrc.toString(), "--lyrics-language", language);
        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        lastAnalyze = analyze.all();
        return root;
    }

    @Test
    @DisplayName("a workspace-layer alignment model directory is named as unreachable")
    void workspaceAlignmentModelDirectoryIsCalledOut() throws IOException {
        // The twin of the ASR key's warning. Without it the run reports the
        // provider not speaking the language while the model sits installed
        // in the layer the provider never reads (#383) -- a symptom pointing
        // at the wrong cause, which is worse than silence.
        Path source = directory.resolve("song.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 6.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("layered.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString())
                .exitCode()).isZero();
        Path descriptor = root.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                + "\nconfig:\n  ml:\n    alignmentProvider: fake-cli-alignment\n"
                + "    alignmentModelDirectory: " + directory.resolve("mine") + "\n");
        Path lrc = directory.resolve("layered.lrc");
        Files.writeString(lrc, LRC);

        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics", lrc.toString(), "--lyrics-language", "en");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.err())
                .contains("ml.alignmentModelDirectory is read from the global config only");
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
    @DisplayName("a word is aligned syllable by syllable where the language splits it")
    void syllablesAreMeasuredRatherThanDivided() throws IOException {
        // Invented syllables; what is under test is the granularity and the
        // hyphen chain. "lalala" is three the hyphenator chose and joins;
        // "sol-mi" is one the writer chose, so the piece that already carries a
        // hyphen must not claim another -- a second one engraves well--known.
        Path root = analysed("fake-cli-alignment", "[00:01.00]lalala sol-mi\n", "it");

        List<LyricWord> words = Workspace.open(root).readScore().orElseThrow()
                .lyrics().lines().get(0).words();

        assertThat(words).extracting(LyricWord::text)
                .containsExactly("la", "la", "la", "sol-", "mi");
        // The flag says a piece continues into the next, so a compound's own
        // break is a join like any other: everything downstream that rejoins a
        // word -- the text sheet, the harness, the engraver's all-or-nothing --
        // reads the chain and would otherwise see two words. Whether a hyphen
        // is drawn between them is the engraver's, from the text.
        assertThat(words).extracting(LyricWord::hyphenatedToNext)
                .containsExactly(true, true, false, true, false);
        // Each syllable carries its own measurement rather than a share of its
        // word's: the fake gives every token it is handed a different time.
        assertThat(words).extracting(LyricWord::startSeconds).doesNotHaveDuplicates();
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
        assertThat(analyze.out()).contains("aligned 1 lyric line with")
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
    @DisplayName("aligning leaves the file's own confidence on the parser's scale")
    void theFileKeepsTheParsersScale() throws IOException {
        // The same lyrics, aligned and not, report the same file confidence:
        // the aligner rates its own path and the file keeps the words' number.
        Path unaligned = analysed("no-such-aligner", LRC, "en", "plain");
        Path aligned = analysed("fake-cli-alignment", LRC, "en", "aligned");
        Score score = Workspace.open(aligned).readScore().orElseThrow();

        // The aligner ran, on every line, and its scale is present on the
        // words. Without this the equality below is satisfied by two failures as
        // easily as by two successes; without the count, one line left at parsed
        // times would satisfy the old aggregation too.
        assertThat(lastAnalyze).contains("aligned 2 lyric lines");
        assertThat(score.lyrics().lines().get(0).words().get(0).confidence())
                .isEqualTo(FakeAlignmentProvider.ALIGNED);
        assertThat(score.lyrics().confidence())
                .isEqualTo(Workspace.open(unaligned).readScore().orElseThrow()
                        .lyrics().confidence());
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

    @Test
    @DisplayName("analyze marks a syllable the melody moves under")
    void analyzeMarksAMelisma() throws IOException {
        // The whole wiring, from --melody to the file: a stepped tone under one
        // syllable an aligner gave the span of. Nothing else exercises the
        // measured-line gate, and a run whose melody track never arrived would
        // pass every other test in this file (#597).
        int rate = SignalFactory.DEFAULT_SAMPLE_RATE;
        float[] samples = new float[0];
        for (int midiPitch : new int[] {57, 60, 57}) {
            samples = concat(samples, tone(midiPitch, 2.0, rate));
        }
        Path source = directory.resolve("melisma.wav");
        SignalFactory.writeWav(source, samples, rate);
        Path root = directory.resolve("melisma.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString())
                .exitCode()).isZero();
        Path descriptor = root.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                + "\nconfig:\n  ml:\n    alignmentProvider: sung-fake-cli-alignment\n");
        Path lrc = directory.resolve("melisma.lrc");
        Files.writeString(lrc, "[00:00.50]aah\n");

        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(), "--melody",
                "--lyrics", lrc.toString(), "--lyrics-language", "en");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        Score score = Workspace.open(root).readScore().orElseThrow();
        assertThat(score.track(PartRole.LEAD_VOCAL)).as(analyze.all()).isPresent();
        assertThat(score.lyrics().allWords()).extracting(LyricWord::melisma)
                .as(analyze.all()).containsExactly(true);
    }

    /** A tone with partials, which the melody stage tracks and a bare sine it does not. */
    private static float[] tone(int midiPitch, double seconds, int rate) {
        double hz = SignalFactory.midiToHz(midiPitch);
        return SignalFactory.chord(new double[] {hz, 2 * hz, 3 * hz, 4 * hz}, seconds, rate);
    }

    private static float[] concat(float[] first, float[] second) {
        float[] out = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    @Test
    @DisplayName("melismas are decided only where the aligner measured the spans")
    void onlyMeasuredLinesGetMelismas() {
        // Two lines with the same run of notes under each syllable. The
        // aligner measured the first; the second kept times apportioned across
        // it, which say nothing about what is sung over what (#597).
        LyricLine measured = line(word("aaah", 0.0, 1.2));
        LyricLine kept = line(word("oooh", 4.0, 5.2));
        Score score = sung(
                List.of(note(0.0, 0.4, 60), note(0.4, 0.4, 62), note(0.8, 0.4, 64),
                        note(4.0, 0.4, 60), note(4.4, 0.4, 62), note(4.8, 0.4, 64)),
                measured, kept);
        Set<LyricLine> fromTheAligner =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        fromTheAligner.add(score.lyrics().lines().get(0));

        Lyrics decided = AnalyzeCommand.withMelismas(score, fromTheAligner);

        assertThat(decided.lines().get(0).words().get(0).melisma()).isTrue();
        assertThat(decided.lines().get(1).words().get(0).melisma()).isFalse();
    }

    private static Note note(double onsetSeconds, double durationSeconds, int midiPitch) {
        return Note.ofSeconds(onsetSeconds, durationSeconds, midiPitch, Confidence.of(0.7));
    }

    private static LyricWord word(String text, double startSeconds, double endSeconds) {
        return LyricWord.ofSeconds(text, startSeconds, endSeconds, Confidence.of(0.8));
    }

    private static LyricLine line(LyricWord... words) {
        return new LyricLine(List.of(words), Confidence.of(0.8));
    }

    private static Score sung(List<Note> notes, LyricLine... lines) {
        return new Score(Optional.empty(), Optional.empty(), TempoMap.constant(60),
                Optional.empty(), List.of(), List.of(),
                List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.of(0.7))),
                ChordProgression.empty(),
                new Lyrics(List.of(lines), "it", Confidence.of(0.8)), 30);
    }
}
