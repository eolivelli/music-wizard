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
 * <p>Only the advisor line is asserted. Everything else {@code doctor} prints
 * depends on the machine it runs on: whether LilyPond is installed is exactly
 * what it is there to report, and pinning it would make this suite pass or fail
 * on a property of the host rather than of the code.
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
    void reportsProvidersHonestly() {
        // FakeSeparationProvider sits on the test classpath under the id the
        // defaults configure, so the separation line takes the present branch
        // here -- the branch that becomes the ordinary case when #312 lands.
        // No ASR provider exists anywhere yet, so that line must say which
        // issue supplies it, as an expected state rather than a broken install,
        // without failing the run.
        CliRunner.Result doctor = CliRunner.run("doctor");

        assertThat(doctor.exitCode()).as(doctor.all()).isZero();
        assertThat(doctor.out())
                .contains("Separation")
                .contains("onnx-spleeter (present)")
                .contains("available: onnx-spleeter")
                .contains("Lyrics ASR")
                .contains("sherpa-qwen3")
                .contains("#314")
                .contains("Models");
    }
}
