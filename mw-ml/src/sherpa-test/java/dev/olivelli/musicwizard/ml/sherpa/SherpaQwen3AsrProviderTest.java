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

package dev.olivelli.musicwizard.ml.sherpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.olivelli.musicwizard.core.ml.ModelUnavailableException;
import dev.olivelli.musicwizard.ml.ModelCache;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What is testable without the native library or a model: the paths that
 * refuse before either is touched.
 */
@DisplayName("the sherpa Qwen3 provider")
class SherpaQwen3AsrProviderTest {

    @TempDir
    Path directory;

    private SherpaQwen3AsrProvider provider(String modelDirectory) {
        return new SherpaQwen3AsrProvider(
                ModelCache.at(directory.resolve("cache"), true),
                Qwen3Models.ARCHIVE, null, modelDirectory);
    }

    @Test
    @DisplayName("a supplied directory without the model refuses, naming the key")
    void emptyModelDirectoryRefuses() {
        // A typo in ml.asrModelDirectory must fail, not silently fall back to
        // fetching the smaller published model.
        assertThatThrownBy(() -> provider(directory.toString())
                .transcribe(new float[16_000], 16_000, "it"))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("ml.asrModelDirectory")
                .hasMessageContaining("conv_frontend.onnx");
    }

    @Test
    @DisplayName("a language outside the map is refused before any model work")
    void unknownLanguageRefused() {
        assertThatThrownBy(() -> provider(null)
                .transcribe(new float[16_000], 16_000, "fr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fr");
    }

    @Test
    @DisplayName("no samples means no words, and nothing is fetched to say so")
    void emptySamplesShortCircuit() {
        assertThat(provider(null).transcribe(new float[0], 16_000, "en"))
                .isEmpty();
    }
}
