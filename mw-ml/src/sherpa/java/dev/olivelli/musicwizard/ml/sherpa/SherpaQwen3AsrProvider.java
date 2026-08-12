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

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import dev.olivelli.musicwizard.audio.Resampler;
import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.ml.AsrProvider;
import dev.olivelli.musicwizard.core.ml.ModelCacheLocation;
import dev.olivelli.musicwizard.core.ml.ModelUnavailableException;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.ml.ModelCache;
import dev.olivelli.musicwizard.ml.ModelRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lyric transcription with Qwen3-ASR through sherpa-onnx.
 *
 * <p>Qwen3-ASR is the one open ASR family trained on singing, covers Italian
 * and English, and is Apache-2.0 on code and weights alike (#314). sherpa-onnx
 * is vendored as a source submodule and built with TTS off, because the
 * default build statically links a GPL-3.0 espeak fork — the licence flags are
 * asserted by {@code tools/check-sherpa-native.sh} against the built library's
 * symbols, not trusted from the build script.
 *
 * <p>This class exists only under the {@code sherpa} profile: a clone without
 * the submodule builds and runs everything else, and {@code doctor} reports
 * this provider as the absent capability it then is.
 *
 * <p>The model arrives as the official sherpa-onnx release archive — the 0.6B
 * checkpoint — fetched and checksummed by {@link ModelCache} like any other
 * model, then unpacked beside its note. A different model, such as a locally
 * produced 1.7B export (#396), runs via {@code ml.asrModelDirectory} instead.
 */
public final class SherpaQwen3AsrProvider implements AsrProvider {

    static final int MODEL_RATE = 16_000;

    /**
     * SPI subtag to the model's forcing vocabulary. {@link #languages()} is
     * derived from these keys, so a language cannot be declared spoken
     * without naming what to tell the model — a divergence would reach
     * {@code setOption} as null, which the JNI dereferences unchecked.
     */
    private static final java.util.SequencedMap<String, String> LANGUAGE_NAMES =
            new java.util.LinkedHashMap<>();
    static {
        LANGUAGE_NAMES.put("it", "Italian");
        LANGUAGE_NAMES.put("en", "English");
    }

    private final ModelCache cache;
    private final ModelRef archive;
    private final String nativePath;
    private final String configuredModelDirectory;

    private OfflineRecognizer recognizer;

    /** The ServiceLoader constructor: configuration from the environment (#383). */
    public SherpaQwen3AsrProvider() {
        this(environmentConfig());
    }

    private SherpaQwen3AsrProvider(MusicWizardConfig config) {
        this(ModelCache.at(
                        ModelCacheLocation.directoryFor(
                                config.ml() == null ? null : config.ml().modelCacheDirectory()),
                        config.isOffline()),
                Qwen3Models.ARCHIVE,
                config.ml() == null ? null : config.ml().sherpaNativePath(),
                config.ml() == null ? null : config.ml().asrModelDirectory());
    }

    SherpaQwen3AsrProvider(ModelCache cache, ModelRef archive, String nativePath,
                           String configuredModelDirectory) {
        this.cache = cache;
        this.archive = archive;
        this.nativePath = nativePath;
        this.configuredModelDirectory = configuredModelDirectory;
    }

    private static MusicWizardConfig environmentConfig() {
        return new ConfigLoader().effectiveConfig(null, null);
    }

    @Override
    public String id() {
        return "sherpa-qwen3";
    }

    @Override
    public List<String> languages() {
        return List.copyOf(LANGUAGE_NAMES.keySet());
    }

    @Override
    public List<LyricWord> transcribe(float[] samples, int sampleRate,
                                      String languageTag) {
        if (!languages().contains(languageTag)) {
            throw new IllegalArgumentException(
                    "language " + languageTag + " is not one of " + languages());
        }
        if (samples.length == 0) {
            return List.of();
        }
        float[] resampled = Resampler.resample(samples, sampleRate, MODEL_RATE);
        OfflineRecognizer held = recognizer(modelDirectory());
        synchronized (this) {
            OfflineStream stream = held.createStream();
            try {
                // The per-stream language hint: without it the model detects,
                // and a wrong detection produces fluent wrong words. The
                // model's forcing vocabulary is language NAMES -- upstream's
                // own example documents "Korean, Chinese, English", and probed
                // against a real recording the bare tag "it" changed nothing
                // while "Italian" did -- so the tag is mapped here, at the one
                // boundary between the SPI's vocabulary and the model's.
                stream.setOption("language", LANGUAGE_NAMES.get(languageTag));
                stream.acceptWaveform(resampled, MODEL_RATE);
                held.decode(stream);
                OfflineRecognizerResult result = held.getResult(stream);
                if (Qwen3Tokens.looksLikeALoop(result.getTokens())) {
                    // A capped decode of near-identical tokens is the decoder
                    // looping, not singing; heard nothing is the honest answer.
                    return List.of();
                }
                return Qwen3Tokens.words(result.getTokens(),
                        (double) resampled.length / MODEL_RATE);
            } finally {
                stream.release();
            }
        }
    }

    /**
     * The unpacked model directory, fetching and unpacking the archive first
     * when it is absent.
     *
     * <p>{@code ml.asrModelDirectory} outranks the archive: only the 0.6B
     * export is published, so running another size means producing the
     * directory locally (#396) and pointing here — <b>from the global config
     * layer</b>, the only one a ServiceLoader-built provider reads (#383).
     * It must already hold everything {@code build()} loads, checked file by
     * file, because a typo in the path or a half-copied export must not
     * silently fall back to the smaller published model or surface as a
     * per-stretch decode failure.
     *
     * <p>The archive is the unit the checksum covers; the unpacked tree is
     * derived from it locally, marked done only after tar succeeds, so a
     * killed unpack re-runs rather than being trusted.
     */
    private Path modelDirectory() {
        if (configuredModelDirectory != null && !configuredModelDirectory.isBlank()) {
            Path configured = Path.of(configuredModelDirectory);
            List<String> missing = new ArrayList<>();
            for (String wanted : new String[] {"conv_frontend.onnx",
                    "encoder.int8.onnx|encoder.onnx",
                    "decoder.int8.onnx|decoder.onnx",
                    "tokenizer/vocab.json"}) {
                boolean found = false;
                for (String option : wanted.split("\\|")) {
                    found |= Files.isRegularFile(configured.resolve(option));
                }
                if (!found) {
                    missing.add(wanted.replace('|', ' ').replace(" ", " or "));
                }
            }
            if (!missing.isEmpty()) {
                throw new ModelUnavailableException(
                        "ml.asrModelDirectory does not hold a Qwen3-ASR export;"
                        + " missing " + String.join(", ", missing)
                        + " under " + configured);
            }
            return configured;
        }
        Path tarball = cache.fetch(archive, System.out::println);
        Path directory = tarball.getParent().resolve(Qwen3Models.UNPACKED_DIRECTORY);
        Path marker = tarball.getParent().resolve(Qwen3Models.UNPACKED_DIRECTORY + ".ok");
        if (Files.isRegularFile(marker) && Files.isDirectory(directory)) {
            // Both, not the marker alone: a partial cache cleanup that kept
            // the marker would otherwise be trusted into a missing tree.
            return directory;
        }
        try {
            // tar as a process, the LilyPond pattern: POSIX only, like the
            // build that produces the native library this provider needs.
            Process tar = new ProcessBuilder("tar", "xjf", tarball.toString(),
                    "-C", tarball.getParent().toString())
                    .redirectErrorStream(true).start();
            String output = new String(tar.getInputStream().readAllBytes());
            int status = tar.waitFor();
            if (status != 0 || !Files.isDirectory(directory)) {
                throw new ModelUnavailableException(
                        "could not unpack " + tarball.getFileName() + ": tar exited "
                        + status + (output.isBlank() ? "" : ": " + output.strip()));
            }
            Files.writeString(marker, "unpacked from " + archive.fileName() + "\n");
            return directory;
        } catch (IOException e) {
            throw new ModelUnavailableException(
                    "could not unpack " + tarball.getFileName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException(
                    "unpacking " + tarball.getFileName() + " was interrupted", e);
        }
    }

    /** Built once per provider lifetime, like the aligner's session. */
    private synchronized OfflineRecognizer recognizer(Path modelDirectory) {
        if (recognizer == null) {
            // sherpa's LibraryUtils reads this property first; setting it from
            // config gives one discovery story (config key, then the JVM's own
            // library path) instead of asking the user to pass -D flags.
            if (nativePath != null
                    && System.getProperty("sherpa_onnx.native.path") == null) {
                System.setProperty("sherpa_onnx.native.path", nativePath);
            }
            try {
                recognizer = build(modelDirectory);
            } catch (LinkageError e) {
                // The Java classes compiled, the native did not arrive: the
                // same shape as a missing model, so it degrades the same way.
                throw new ModelUnavailableException(
                        "sherpa-onnx native library not found ("
                        + e.getMessage() + "); run tools/build-sherpa-native.sh"
                        + " and set ml.sherpaNativePath to its lib directory", e);
            } catch (RuntimeException e) {
                // A recognizer that cannot be built is a model that cannot be
                // had -- a corrupt unpack, a half-made export -- and not a
                // per-stretch decode failure: left unwrapped it was retried
                // and miscounted once per stretch and summarised as the model
                // having heard nothing.
                throw new ModelUnavailableException(
                        "the ASR model in " + modelDirectory
                        + " could not be loaded: " + e.getMessage(), e);
            }
        }
        return recognizer;
    }

    private static OfflineRecognizer build(Path modelDirectory) {
        OfflineQwen3AsrModelConfig qwen = OfflineQwen3AsrModelConfig.builder()
                .setConvFrontend(modelDirectory.resolve("conv_frontend.onnx").toString())
                .setEncoder(preferInt8(modelDirectory, "encoder"))
                .setDecoder(preferInt8(modelDirectory, "decoder"))
                .setTokenizer(modelDirectory.resolve("tokenizer").toString())
                .setHotwords("")
                .build();
        OfflineModelConfig model = OfflineModelConfig.builder()
                .setQwen3Asr(qwen)
                .setTokens("")
                .setNumThreads(Math.max(1, Runtime.getRuntime()
                        .availableProcessors() / 2))
                .setDebug(false)
                .build();
        return new OfflineRecognizer(OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(model)
                .build());
    }

    /**
     * The int8 file when the directory holds one, the plain export otherwise.
     * A supplied directory (#396) may carry either, and an export that
     * deliberately keeps fp32 must be runnable as it is. The choice is
     * per-file and implicit: a directory holding both runs the int8 file, so
     * choosing fp32 means curating a directory without the int8 one.
     */
    static String preferInt8(Path directory, String stem) {
        Path int8 = directory.resolve(stem + ".int8.onnx");
        return (Files.isRegularFile(int8) ? int8 : directory.resolve(stem + ".onnx"))
                .toString();
    }
}
