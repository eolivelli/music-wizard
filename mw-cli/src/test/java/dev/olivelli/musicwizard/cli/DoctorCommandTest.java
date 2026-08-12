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

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code doctor} claims about the environment.
 *
 * <p>This command exists to tell a user what will and will not work, so a claim
 * it makes that is not true is worse here than anywhere else in the tool. It
 * said an API key made "the advisor layer available" -- {@code mw-llm} holds no
 * source at all, and #11 is the issue to build it -- which is #82's defect, in
 * the one command whose entire job is answering that question.
 *
 * <p>Nothing here asserts on the machine-dependent lines: whether LilyPond is
 * installed is exactly what {@code doctor} is there to report, and pinning it
 * would make this suite pass or fail on a property of the host rather than of
 * the code.
 */
class DoctorCommandTest {

    @Test
    @DisplayName("does not claim an advisor layer is available, because there is none")
    void doesNotPromiseAnAdvisorLayer() {
        CliRunner.Result doctor = CliRunner.run("doctor");

        assertThat(doctor.exitCode()).as(doctor.all()).isZero();
        assertThat(doctor.out())
                .contains("nothing uses it yet; the advisor layer is #11")
                // Both directions: "absent (advisor layer will stay off)" implies
                // just as strongly that a key would switch something on.
                .doesNotContain("advisor layer available")
                .doesNotContain("advisor layer will stay off");
    }

    @Test
    @DisplayName("a present provider and an absent one each read as what they are")
    void eachProviderLineReadsAsWhatItIs() {
        // Separation became the present branch's ordinary case when #312
        // landed its real provider under the default id. The ASR provider
        // exists only in builds carrying the sherpa profile, so its line is
        // asserted against this build's own classpath: present as present,
        // absent as an expected state with an issue number rather than a
        // broken install. Neither may fail the run.
        CliRunner.Result doctor = CliRunner.run("doctor");

        assertThat(doctor.exitCode()).as(doctor.all()).isZero();
        assertThat(doctor.out())
                .contains("onnx-spleeter (present)")
                .contains("Lyrics ASR")
                .contains(dev.olivelli.musicwizard.core.ml.MlProviders.asrIds()
                        .contains("sherpa-qwen3")
                        ? "sherpa-qwen3 (present)"
                        : "sherpa-qwen3 -- no such provider on this classpath yet (#314)")
                .contains("Alignment")
                .contains("onnx-wav2vec2 (present)")
                .contains("fake-cli-separation")
                .contains("Models");
    }

    @Test
    @DisplayName("a configured provider the classpath has reads as present")
    void presentProviderReadsAsPresent(@TempDir Path tmp) throws IOException {
        // The branch that becomes the ordinary case when #312 lands, driven the
        // way a user would drive it: configuration naming a provider that is on
        // the classpath.
        Path global = tmp.resolve("config.yaml");
        Files.writeString(global, """
                ml:
                  separationProvider: fake-cli-separation
                """);
        var out = new java.io.ByteArrayOutputStream();
        var previous = System.out;
        System.setOut(new java.io.PrintStream(out, true));
        try {
            int exit = new picocli.CommandLine(
                    new DoctorCommand(ConfigLoader.withGlobalConfigFile(global)))
                    .execute();
            assertThat(exit).isZero();
        } finally {
            System.setOut(previous);
        }
        assertThat(out.toString())
                .contains("fake-cli-separation (present)")
                .contains("available: fake-cli-separation");
    }

    @Test
    @DisplayName("a provider that says it is not ready is printed and fails the bill of health")
    void unreadyProviderIsReported(@TempDir Path tmp) throws IOException {
        // The readiness question is the provider's own (#396): doctor holds no
        // copy of any provider's file list to fall stale, it prints whatever
        // the configured provider answers.
        Path global = tmp.resolve("config.yaml");
        Files.writeString(global, """
                ml:
                  asrProvider: fake-cli-unready-asr
                """);
        var out = new java.io.ByteArrayOutputStream();
        var previous = System.out;
        System.setOut(new java.io.PrintStream(out, true));
        try {
            int exit = new picocli.CommandLine(
                    new DoctorCommand(ConfigLoader.withGlobalConfigFile(global)))
                    .execute();
            assertThat(exit).isZero();
        } finally {
            System.setOut(previous);
        }
        assertThat(out.toString())
                .contains(FakeUnreadyAsrProvider.PROBLEM)
                .contains("some outputs will be unavailable")
                .doesNotContain("Everything needed for full output is present");
    }
}
