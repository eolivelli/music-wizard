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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("lyric transcription in analyze")
class TranscribedLyricsTest {

    @TempDir
    Path directory;

    /**
     * Two seconds of silence, then two of a chord: one sung stretch, starting
     * away from zero, so a word's absolute time tells whether the CLI offset
     * the fake's window-relative times by the stretch's start.
     */
    private Path workspaceWithQuietIntro() throws IOException {
        int rate = SignalFactory.DEFAULT_SAMPLE_RATE;
        float[] silence = SignalFactory.silence(2.0, rate);
        float[] chord = SignalFactory.chord(SignalFactory.majorTriad(60), 2.0, rate);
        float[] samples = new float[silence.length + chord.length];
        System.arraycopy(silence, 0, samples, 0, silence.length);
        System.arraycopy(chord, 0, samples, silence.length, chord.length);
        Path source = directory.resolve("song.wav");
        SignalFactory.writeWav(source, samples, rate);
        Path root = directory.resolve("song.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString())
                .exitCode()).isZero();
        Path descriptor = root.resolve("workspace.yaml");
        // The alignment id is pinned to nothing on purpose: a fresh
        // transcription is handed to the aligner, and the default id resolves
        // to the real one -- which runs whenever a developer's model cache has
        // its model, making word times an artefact of the machine. The chain
        // itself is asserted separately, with the fake aligner.
        Files.writeString(descriptor, Files.readString(descriptor)
                + "\nconfig:\n  ml:\n    asrProvider: fake-cli-asr\n"
                + "    separationProvider: fake-cli-separation\n"
                + "    alignmentProvider: no-such-alignment\n");
        return root;
    }

    @Test
    @DisplayName("a language alone transcribes, and words land in recording time")
    void languageAloneTranscribes() throws IOException {
        Path root = workspaceWithQuietIntro();

        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics-language", "en");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).contains("transcribed")
                .contains("fake-cli-asr");
        Score score = Workspace.open(root).readScore().orElseThrow();
        assertThat(score.lyrics().isEmpty()).isFalse();
        LyricWord first = score.lyrics().lines().get(0).words().get(0);
        // The fake hears its first word 0.2 s into the window; the sung
        // stretch starts near 2.0 s (padding pulls it slightly earlier). A
        // window-relative time surviving to the score would sit at 0.2.
        assertThat(first.startSeconds()).isBetween(1.8, 2.4);
        assertThat(first.confidence().value()).isEqualTo(FakeAsrProvider.HEARD.value());
    }

    @Test
    @DisplayName("a fresh transcription is handed to the aligner")
    void freshTranscriptionIsAligned() throws IOException {
        Path root = workspaceWithQuietIntro();
        Path descriptor = root.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                .replace("alignmentProvider: no-such-alignment",
                        "alignmentProvider: fake-cli-alignment"));

        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics-language", "en");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).contains("transcribed").contains("aligned");
        Score score = Workspace.open(root).readScore().orElseThrow();
        LyricWord first = score.lyrics().lines().get(0).words().get(0);
        // The fake aligner's signature: word 1 lands 0.111 s into a window
        // opening half a second before the line, at a confidence nothing else
        // assigns. Spread times surviving to the score would carry 0.55.
        assertThat(first.confidence().value()).isEqualTo(0.97);
    }

    @Test
    @DisplayName("carried-forward lyrics are kept, not re-transcribed over")
    void carriedLyricsWin() throws IOException {
        Path root = workspaceWithQuietIntro();
        assertThat(CliRunner.run("analyze", root.toString(),
                "--lyrics-language", "en").exitCode()).isZero();
        Score before = Workspace.open(root).readScore().orElseThrow();

        CliRunner.Result again = CliRunner.run("analyze", root.toString(),
                "--lyrics-language", "en");

        assertThat(again.exitCode()).as(again.all()).isZero();
        assertThat(again.out()).contains("lyrics kept from the previous analysis");
        assertThat(Workspace.open(root).readScore().orElseThrow().lyrics())
                .isEqualTo(before.lyrics());
    }

    @Test
    @DisplayName("a language the provider does not speak degrades with a reason")
    void unspokenLanguageDegrades() throws IOException {
        Path root = workspaceWithQuietIntro();

        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics-language", "fr");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).contains("lyrics not transcribed")
                .contains("fake-cli-asr");
        assertThat(Workspace.open(root).readScore().orElseThrow()
                .lyrics().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("no provider on the classpath degrades with what is available")
    void missingProviderDegrades() throws IOException {
        Path root = workspaceWithQuietIntro();
        Path descriptor = root.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                .replace("asrProvider: fake-cli-asr", "asrProvider: no-such-asr"));

        CliRunner.Result analyze = CliRunner.run("analyze", root.toString(),
                "--lyrics-language", "en");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).contains("no ASR provider named 'no-such-asr'");
        assertThat(Workspace.open(root).readScore().orElseThrow()
                .lyrics().isEmpty()).isTrue();
    }
}
