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

    /**
     * The property outranks the config key everywhere this class asserts, and
     * surefire forwards a developer's -D straight into the fork -- exporting
     * it to run the real model must not fail unrelated assertions. Held and
     * restored around every test so no future case can forget it.
     */
    private String savedNativePathProperty;

    @org.junit.jupiter.api.BeforeEach
    void clearNativePathProperty() {
        savedNativePathProperty = System.getProperty("sherpa_onnx.native.path");
        System.clearProperty("sherpa_onnx.native.path");
    }

    @org.junit.jupiter.api.AfterEach
    void restoreNativePathProperty() {
        if (savedNativePathProperty != null) {
            System.setProperty("sherpa_onnx.native.path", savedNativePathProperty);
        }
    }

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

    /**
     * A directory the native check accepts, so the assertions after it are
     * about what THIS test varies rather than about whichever JVM library
     * path the machine happens to have.
     */
    private Path goodNativeDirectory() throws java.io.IOException {
        Path lib = directory.resolve("native");
        java.nio.file.Files.createDirectories(lib);
        java.nio.file.Files.createFile(
                lib.resolve(System.mapLibraryName("sherpa-onnx-jni")));
        return lib;
    }

    private SherpaQwen3AsrProvider provider(String nativePath, String modelDirectory) {
        return new SherpaQwen3AsrProvider(
                ModelCache.at(directory.resolve("cache"), true),
                Qwen3Models.ARCHIVE, nativePath, modelDirectory);
    }

    @Test
    @DisplayName("readiness answers for the files loading will demand")
    void readinessMirrorsLoading() throws java.io.IOException {
        String good = goodNativeDirectory().toString();
        // Library resolvable, nothing else configured: ready as far as a
        // file check can tell. Blank is unset, everywhere the keys are read.
        assertThat(provider(good, null).readinessProblem()).isEmpty();
        assertThat(provider(good, "  ").readinessProblem()).isEmpty();
        // A half-copied export names its gaps before any transcription runs.
        java.nio.file.Files.createFile(directory.resolve("conv_frontend.onnx"));
        assertThat(provider(good, directory.toString()).readinessProblem())
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
    void nativeDirectoryWithoutLibrary() {
        assertThat(provider(directory.toString(), null).readinessProblem())
                .hasValueSatisfying(problem -> assertThat(problem)
                        .contains("ml.sherpaNativePath")
                        .contains("sherpa-onnx-jni"));
    }

    @Test
    @DisplayName("a blank native path is unset, not an empty-string problem")
    void blankNativePathIsUnset() {
        // A script expanding an unset variable writes "" into the config, and
        // an empty string is not a directory that holds no library -- it is
        // no setting at all. Whether the machine can load the JNI another way
        // is the machine's business; blank itself must never be the problem
        // named, least of all with nothing after the colon.
        var problem = provider("", null).readinessProblem();
        problem.ifPresent(text ->
                assertThat(text).doesNotContain("ml.sherpaNativePath holds no"));
    }

    @Test
    @DisplayName("a blank property does not block a good config value from loading")
    void blankPropertyDoesNotOutrankTheConfigKey() throws java.io.IOException {
        // Readiness and loading must read the pair the same way: a blank
        // property with a good config key is ready, and stays ready when
        // loading installs the config value over the blank.
        System.setProperty("sherpa_onnx.native.path", "");
        assertThat(provider(goodNativeDirectory().toString(), null)
                .readinessProblem()).isEmpty();
    }
}
