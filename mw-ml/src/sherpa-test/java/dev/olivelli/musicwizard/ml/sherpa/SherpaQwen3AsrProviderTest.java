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
    @DisplayName("int8 wins when the directory holds both; fp32 stands alone")
    void preferInt8PicksWhatExists() throws java.io.IOException {
        java.nio.file.Files.createFile(directory.resolve("decoder.onnx"));
        assertThat(SherpaQwen3AsrProvider.preferInt8(directory, "decoder"))
                .endsWith("decoder.onnx");

        java.nio.file.Files.createFile(directory.resolve("decoder.int8.onnx"));
        assertThat(SherpaQwen3AsrProvider.preferInt8(directory, "decoder"))
                .endsWith("decoder.int8.onnx");
    }

    @Test
    @DisplayName("a half-copied export names every missing file, not the first")
    void halfCopiedExportNamesEverything() throws java.io.IOException {
        java.nio.file.Files.createFile(directory.resolve("conv_frontend.onnx"));

        assertThatThrownBy(() -> provider(directory.toString())
                .transcribe(new float[16_000], 16_000, "it"))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("encoder")
                .hasMessageContaining("decoder")
                .hasMessageContaining("tokenizer/vocab.json");
    }

    @Test
    @DisplayName("no samples means no words, and nothing is fetched to say so")
    void emptySamplesShortCircuit() {
        assertThat(provider(null).transcribe(new float[0], 16_000, "en"))
                .isEmpty();
    }

    @Test
    @DisplayName("readiness answers for the files loading will demand")
    void readinessMirrorsLoading() throws java.io.IOException {
        // Nothing configured: nothing a file check can catch.
        assertThat(provider(null).readinessProblem()).isEmpty();
        // Blank is unset, everywhere the key is read.
        assertThat(provider("  ").readinessProblem()).isEmpty();
        // A half-copied export names its gaps before any transcription runs.
        java.nio.file.Files.createFile(directory.resolve("conv_frontend.onnx"));
        assertThat(provider(directory.toString()).readinessProblem())
                .hasValueSatisfying(problem -> {
                    assertThat(problem).contains("ml.asrModelDirectory")
                            .contains("encoder").contains("decoder")
                            .contains("tokenizer/vocab.json")
                            .contains("tokenizer/merges.txt")
                            .contains("tokenizer/tokenizer_config.json");
                });
    }

    @Test
    @DisplayName("a configured native directory without the library is a named problem")
    void nativeDirectoryWithoutLibrary() throws java.io.IOException {
        // The property outranks the config key at load time; the test JVM
        // must not carry one into this assertion or out of it.
        String saved = System.getProperty("sherpa_onnx.native.path");
        try {
            System.clearProperty("sherpa_onnx.native.path");
            var provider = new SherpaQwen3AsrProvider(
                    ModelCache.at(directory.resolve("cache"), true),
                    Qwen3Models.ARCHIVE, directory.toString(), null);
            assertThat(provider.readinessProblem())
                    .hasValueSatisfying(problem -> assertThat(problem)
                            .contains("ml.sherpaNativePath")
                            .contains("sherpa-onnx-jni"));
        } finally {
            if (saved != null) {
                System.setProperty("sherpa_onnx.native.path", saved);
            }
        }
    }
}
