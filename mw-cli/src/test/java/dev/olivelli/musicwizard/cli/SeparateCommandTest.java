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

import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("the separate command")
class SeparateCommandTest {

    @TempDir
    Path directory;

    private Path workspace() throws IOException {
        Path source = directory.resolve("song.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 2.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve("song.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", root.toString())
                .exitCode()).isZero();
        return root;
    }

    @Test
    @DisplayName("an absent provider is an absent capability, not a failure")
    void absentProviderDegrades() throws IOException {
        // The absent id is configured explicitly rather than relying on the
        // default being absent, because the default is onnx-spleeter and the
        // REAL provider is on this classpath -- mw-ml is a runtime dependency
        // -- so a test that reached it would start downloading the real
        // models inside mvn test, which is the one thing the fast suite must
        // never do. The command must say what is missing and what is present,
        // and exit zero, the LilyPond pattern.
        Path root = workspace();
        Path descriptor = root.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                + "\nconfig:\n  ml:\n    separationProvider: not-on-any-classpath\n");

        CliRunner.Result result = CliRunner.run("separate", root.toString());

        assertThat(result.exitCode()).as(result.all()).isZero();
        assertThat(result.out())
                .contains("No separation provider")
                .contains("not-on-any-classpath")
                .contains("fake-cli-separation");
        assertThat(root.resolve("out").resolve("vocals.wav")).doesNotExist();
    }

    @Test
    @DisplayName("a configured provider writes both stems beside the other outputs")
    void writesStems() throws IOException, UnsupportedAudioFileException {
        Path root = workspace();
        // The workspace's own config layer names the test provider, which is
        // how a user selects one -- and the path #383 records as not reaching
        // the provider's own cache settings is not needed here, because the
        // fake loads no model.
        Path descriptor = root.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                + "\nconfig:\n  ml:\n    separationProvider: fake-cli-separation\n");

        CliRunner.Result result = CliRunner.run("separate", root.toString());

        assertThat(result.exitCode()).as(result.all()).isZero();
        Path vocals = root.resolve("out").resolve("vocals.wav");
        Path accompaniment = root.resolve("out").resolve("accompaniment.wav");
        assertThat(vocals).exists();
        assertThat(accompaniment).exists();
        // The fake returns the input as both stems, so both files carry the
        // recording's length of audio rather than being headers over nothing.
        assertThat(Files.size(vocals)).isGreaterThan(2 * 20_000L);
        assertThat(result.out()).contains("fake-cli-separation");
        // The fake declares a rate nothing records at, so the header carrying
        // it proves the command decoded at the provider's preferred rate
        // rather than the analysis rate -- the branch that keeps the model's
        // band out of the anti-alias filter.
        assertThat(javax.sound.sampled.AudioSystem
                .getAudioFileFormat(vocals.toFile()).getFormat().getSampleRate())
                .isEqualTo((float) FakeSeparationProvider.PREFERRED_RATE);
    }
}
