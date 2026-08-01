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

import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The other half of #247: freeing the phone must not disarm the desktop.
 *
 * <p>{@code mw-transcribe} declared {@code mw-ml} and never used it, so ONNX
 * Runtime reached the shaded jar transitively. #247 removed that edge, because
 * the Android app (#236) links {@code mw-transcribe} and has no use for a jar
 * of desktop natives. The obvious way to get that wrong is to remove the edge
 * and stop there: nothing would fail, no test would go red, and the shaded jar
 * would silently lose its provider runtime — a change to what the tool ships,
 * made by a change to a module that does not ship it.
 *
 * <p>So the declaration moved here rather than vanishing. This is the module
 * whose stated job is wiring everything together, and it is the one that
 * produces the artifact a desktop user runs.
 *
 * <p>Declared at {@code runtime} scope, which is why the probe below loads it
 * through this class's own classloader rather than importing anything: Maven
 * puts runtime dependencies on the test classpath but not on the compile one,
 * so a source-level reference would not build. That is the intended shape — a
 * provider is selected at run time, and
 * {@link dev.olivelli.musicwizard.core.config.MusicWizardConfig} already carries
 * the {@code ml.offline} switch that will select it.
 */
class MlRuntimeStaysOnTheDesktopTest {

    /** A class every ONNX Runtime jar has. */
    private static final String ONNX_PROBE = "ai.onnxruntime.OrtEnvironment";

    @Test
    @DisplayName("the CLI still reaches the ML runtime the transcribe module gave up")
    void theProviderRuntimeIsStillWiredIntoTheCli() {
        assertThatNoException()
                .as("mw-cli declares mw-ml directly (#247); without it the shaded"
                        + " jar loses ONNX Runtime and no other test notices")
                .isThrownBy(() -> Class.forName(ONNX_PROBE, false,
                        MlRuntimeStaysOnTheDesktopTest.class.getClassLoader()));
    }
}
