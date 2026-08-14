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
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.ScoreJson;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.StageCache;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transcription cache, and specifically the thing it must never do: hand one
 * pipeline's answer to the other.
 *
 * <p>The two paths read the same bytes and produce entirely different scores from
 * them, so the file's digest alone does not separate them -- it is identical
 * either way. What separates them is the input kind, carried in the key.
 *
 * <p>The pair of tests below is deliberate. Showing that a poisoned audio entry
 * is <em>not</em> served proves nothing on its own, because it would also pass if
 * the cache were never consulted at all; the companion test poisons the MIDI key
 * with the same score and shows it <em>is</em> served. Only together do they say
 * that the key is what made the difference.
 */
class TranscriptionCacheTest {

    @TempDir
    Path directory;

    private Path workspaceDirectory;

    /** A score no transcriber would ever produce from the fixture. */
    private static Score poison() {
        return Score.empty(TempoMap.constant(66), 999.0);
    }

    @BeforeEach
    void importFixture() {
        Path source = MidiFixtures.write(
                MidiFixtures.fourChordSong(), directory.resolve("four.mid"));
        workspaceDirectory = directory.resolve("four.mwz");
        CliRunner.Result init = CliRunner.run(
                "init", source.toString(), "-w", workspaceDirectory.toString());
        assertThat(init.exitCode()).as(init.all()).isZero();
    }

    private Workspace workspace() {
        return Workspace.open(workspaceDirectory);
    }

    /**
     * The key a plain {@code analyze} of this workspace would use.
     *
     * <p>The advisor flag is read from the workspace's own effective config
     * rather than written as a literal, because the command computes it the same
     * way and a literal here would silently stop matching the moment the default
     * changed -- which it already did not match: hard-coding {@code true} made
     * three tests miss a cache they had just written to.
     */
    private StageCache.Key keyFor(SourceKind kind) {
        return AnalyzeCommand.transcriptionKey(kind, workspace().sourceFile(),
                kind == SourceKind.AUDIO ? AudioTranscriber.Options.defaults() : null,
                false, workspace().effectiveConfig().isLlmEnabled());
    }

    /**
     * A key with the MIDI key's components and the audio path's stage.
     *
     * <p>Not a key any run produces -- the CLI always hands the audio path its
     * options -- and constructed for exactly that reason. Comparing the two
     * <em>real</em> keys proves less than it looks: they differ in their option
     * components as well, so they stay distinct even if the kind stops being
     * carried at all, which is what an earlier version of this test failed to
     * notice. Holding every other component equal leaves the stage as the only
     * thing that can separate them.
     */
    private StageCache.Key audioKeyWithMidiComponents() {
        return AnalyzeCommand.transcriptionKey(SourceKind.AUDIO, workspace().sourceFile(),
                null, false, workspace().effectiveConfig().isLlmEnabled());
    }

    @Test
    @DisplayName("the two paths are separate stages, so their entries cannot meet")
    void keysDifferByInputKind() {
        StageCache cache = workspace().cache();
        StageCache.Key midi = keyFor(SourceKind.MIDI);
        StageCache.Key audio = audioKeyWithMidiComponents();

        assertThat(audio.stage()).isNotEqualTo(midi.stage());
        assertThat(audio.digest())
                .as("every other component is equal, so only the stage separates these")
                .isNotEqualTo(midi.digest());
        // Separate directories, so the separation survives whatever the
        // components beneath them become.
        assertThat(cache.pathFor(audio, ".json").getParent())
                .isNotEqualTo(cache.pathFor(midi, ".json").getParent());
    }

    @Test
    @DisplayName("an audio result cached for this file is not served to the MIDI path")
    void anAudioEntryIsNotServedForMidi() {
        StageCache cache = workspace().cache();
        // Both: the key a real audio run would write, and the one that would
        // collide with the MIDI key if the kind were not carried.
        cache.writeText(keyFor(SourceKind.AUDIO), ".json", ScoreJson.toJson(poison()));
        cache.writeText(audioKeyWithMidiComponents(), ".json", ScoreJson.toJson(poison()));

        CliRunner.Result analyze = CliRunner.run("analyze", workspaceDirectory.toString());

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).doesNotContain("reusing the cached analysis");
        Score saved = workspace().readScore().orElseThrow();
        assertThat(saved.durationSeconds())
                .as("a poisoned audio entry was served for a MIDI import")
                .isCloseTo(18.0, within(1e-9));
        // Untouched: the MIDI run must not overwrite the other path's entry
        // either, or a workspace re-imported the other way round would silently
        // lose the analysis it already had.
        assertThat(workspace().cache().contains(keyFor(SourceKind.AUDIO), ".json")).isTrue();
    }

    @Test
    @DisplayName("a result cached under the MIDI key is served, so the cache is real")
    void aMidiEntryIsServed() {
        workspace().cache().writeText(keyFor(SourceKind.MIDI), ".json",
                ScoreJson.toJson(poison()));

        CliRunner.Result analyze = CliRunner.run("analyze", workspaceDirectory.toString());

        assertThat(analyze.out()).contains("reusing the cached analysis");
        assertThat(workspace().readScore().orElseThrow().durationSeconds())
                .as("the cache was not consulted at all, so the companion test proves nothing")
                .isCloseTo(999.0, within(1e-9));
    }

    @Test
    @DisplayName("--force recomputes and replaces the entry")
    void forceIgnoresTheCache() {
        workspace().cache().writeText(keyFor(SourceKind.MIDI), ".json",
                ScoreJson.toJson(poison()));

        CliRunner.Result analyze = CliRunner.run(
                "analyze", workspaceDirectory.toString(), "--force");

        assertThat(analyze.out())
                .doesNotContain("reusing the cached analysis")
                .contains("reading four.mid");
        assertThat(workspace().readScore().orElseThrow().durationSeconds())
                .isCloseTo(18.0, within(1e-9));
        // Replaced, not merely bypassed: leaving the stale entry behind would
        // make the next run without --force serve it again.
        assertThat(ScoreJson.fromJson(
                workspace().cache().readText(keyFor(SourceKind.MIDI), ".json").orElseThrow())
                .durationSeconds()).isCloseTo(18.0, within(1e-9));
    }

    @Test
    @DisplayName("a cache entry that cannot be read is recomputed rather than fatal")
    void anUnreadableEntryIsRecomputed() {
        workspace().cache().writeText(keyFor(SourceKind.MIDI), ".json", "{ this is not a score");

        CliRunner.Result analyze = CliRunner.run("analyze", workspaceDirectory.toString());

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.err()).contains("a cached analysis could not be read");
        assertThat(workspace().readScore().orElseThrow().durationSeconds())
                .isCloseTo(18.0, within(1e-9));
    }

    @Test
    @DisplayName("--skip-separation reaches the key and the warning from a real audio run")
    void skipSeparationIsWiredUpOnTheAudioPath() {
        // Through the CLI on real audio, because the two halves of round 3's fix
        // that a key-builder test cannot reach are the wiring: whether the flag
        // is read out of the config into the key at all, and whether the warning
        // fires on this path or only on the MIDI one. Round 4 found both mutants
        // surviving a suite of 43.
        //
        // A short synthesised WAV. It is decoded by the bundled ffsampledsp
        // natives rather than by the JDK's own provider -- an earlier version of
        // this comment said otherwise -- but they ship in the jar, so nothing is
        // downloaded and no external binary is required: the fast suite stays
        // fast and offline, which is the property that matters.
        Path source = directory.resolve("tone.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 1.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("tone.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString()).exitCode())
                .isZero();

        CliRunner.Result first = CliRunner.run("analyze", root.toString());
        CliRunner.Result second = CliRunner.run("analyze", root.toString());
        CliRunner.Result skipped = CliRunner.run(
                "analyze", root.toString(), "--skip-separation");

        assertThat(first.exitCode()).as(first.all()).isZero();
        assertThat(second.out())
                .as("the audio path must cache, or the third run proves nothing")
                .contains("reusing the cached analysis");
        // Positively, and not only by the absence of the reuse line. An absence
        // has more than one cause: round 5 showed that a mutant crashing every
        // --skip-separation run on audio passed this test, because a run that
        // dies also fails to print that it reused anything.
        assertThat(skipped.exitCode()).as(skipped.all()).isZero();
        assertThat(skipped.out())
                .as("--skip-separation did not reach the cache key from a real run")
                .doesNotContain("reusing the cached analysis")
                .contains("estimating chords");
        assertThat(skipped.err())
                .as("the audio path swallowed the option in silence")
                .contains("skipping separation changes nothing in this run");
    }

    @Test
    @DisplayName("an analysis that cannot be cached is still saved")
    void aFailedCacheWriteDoesNotLoseTheAnalysis() throws Exception {
        // A plain file where the stage directory has to go, so createDirectories
        // fails for every user including root -- a chmod would not, and this
        // suite must not depend on not being root. It stands in for the real
        // cases: a full disk, a read-only workspace, a cache/ copied in with
        // somebody else's permissions.
        StageCache.Key key = keyFor(SourceKind.MIDI);
        Path stageDirectory = workspace().cache().pathFor(key, ".json").getParent();
        java.nio.file.Files.createDirectories(stageDirectory.getParent());
        java.nio.file.Files.writeString(stageDirectory, "not a directory");

        CliRunner.Result analyze = CliRunner.run("analyze", workspaceDirectory.toString());

        // The pipeline had already run. Raising here threw away the whole
        // analysis -- on the audio path, minutes of it -- over a file nothing was
        // waiting for.
        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.err()).contains("could not be cached");
        assertThat(workspace().readScore().orElseThrow().durationSeconds())
                .isCloseTo(18.0, within(1e-9));
    }

    @Test
    @DisplayName("the audio key changes with the overrides, and the MIDI key does not")
    void keysCarryOnlyWhatTheirPathReads() {
        Path source = workspace().sourceFile();

        String plain = AnalyzeCommand.transcriptionKey(
                SourceKind.AUDIO, source, AudioTranscriber.Options.defaults(), false, true)
                .digest();
        String corrected = AnalyzeCommand.transcriptionKey(SourceKind.AUDIO, source,
                new AudioTranscriber.Options(90.0, null, null), false, true).digest();

        assertThat(corrected)
                .as("a corrected tempo produces a different audio analysis")
                .isNotEqualTo(plain);
        // Keyed although the cached analysis does not read it yet: today it is
        // read only by lyric transcription, which runs outside the cache, and
        // separation feeds the analysis itself under #8 -- a setting that
        // changes the analysis while the key does not change is how a corrected
        // run gets served the answer it was correcting.
        assertThat(AnalyzeCommand.transcriptionKey(
                SourceKind.AUDIO, source, AudioTranscriber.Options.defaults(), true, true)
                .digest())
                .as("--skip-separation will change the audio analysis under #8")
                .isNotEqualTo(plain);
        // The melody stage genuinely changes what the cached score holds, which
        // makes this the one component here that is not keyed against a future.
        assertThat(AnalyzeCommand.transcriptionKey(SourceKind.AUDIO, source,
                new AudioTranscriber.Options(null, null, null, true), false, true).digest())
                .as("--melody adds a note track to the analysis")
                .isNotEqualTo(plain);
        // The advisor is keyed on both paths: #11 advises on meter, structure
        // and spelling, all of which a symbolic import produces too.
        assertThat(AnalyzeCommand.transcriptionKey(
                SourceKind.AUDIO, source, AudioTranscriber.Options.defaults(), false, false)
                .digest())
                .as("the advisor will change the analysis under #11")
                .isNotEqualTo(plain);
        assertThat(AnalyzeCommand.transcriptionKey(SourceKind.MIDI, source, null, false, false)
                .digest())
                .as("the advisor is not an audio-only stage")
                .isNotEqualTo(AnalyzeCommand.transcriptionKey(
                        SourceKind.MIDI, source, null, false, true).digest());
        // Nothing on the MIDI path reads any of them, so keying on them would
        // miss the cache for a reason that is not a reason.
        assertThat(AnalyzeCommand.transcriptionKey(
                SourceKind.MIDI, source, null, false, true).digest())
                .isEqualTo(AnalyzeCommand.transcriptionKey(
                        SourceKind.MIDI, source,
                        new AudioTranscriber.Options(90.0, null, null), true, true).digest());
    }
}
