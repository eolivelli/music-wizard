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

package dev.olivelli.musicwizard.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import dev.olivelli.musicwizard.audio.Resampler;
import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.ml.ModelCacheLocation;
import dev.olivelli.musicwizard.core.ml.ModelUnavailableException;
import dev.olivelli.musicwizard.core.ml.SeparationProvider;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Map;

/**
 * Spleeter 2stems: the voice, and the rest of the mix.
 *
 * <p>Chosen for its licence chain, not its SDR — the weights are MIT by
 * Deezer's own statement and the training data has no non-commercial corpus
 * anywhere in it, the one documented exception in a field where almost every
 * high-SDR checkpoint is NC-derived whatever its card says (#312). kuielab
 * MDX-Net is the better-separating alternative if attribution under CC BY is
 * ever worth carrying.
 *
 * <p>The models eat magnitude spectrograms and emit per-stem magnitude
 * estimates; the STFT, the soft masks and the inverse are host-side, in
 * {@link SpleeterStft}. Masks are the ratio of the estimates, applied to
 * the complex mix spectrogram, so the stems sum close to the mix. Bins above
 * the model's 1024 are given to neither stem, matching Spleeter's own default
 * mask extension.
 *
 * <p>Everything runs at the checkpoint's 44.1 kHz: input at any other rate is
 * resampled in and the stems resampled back, because the model's rate is this
 * provider's implementation detail — the SPI says so.
 */
public final class SpleeterSeparationProvider implements SeparationProvider {

    static final int MODEL_RATE = 44100;

    /** Frames per model segment, the second dimension the network was built on. */
    static final int SEGMENT_FRAMES = 512;

    private final ModelCache cache;
    private final ModelRef vocalsModel;
    private final ModelRef accompanimentModel;

    /**
     * The ServiceLoader constructor: configuration from the environment.
     *
     * <p>A provider is constructed reflectively with nothing in hand, so the
     * cache location and the offline flag come from the user's global config —
     * a per-workspace {@code ml} override does not reach a provider today,
     * which is #383.
     */
    public SpleeterSeparationProvider() {
        this(environmentCache(), SpleeterModels.VOCALS, SpleeterModels.ACCOMPANIMENT);
    }

    SpleeterSeparationProvider(ModelCache cache, ModelRef vocals, ModelRef accompaniment) {
        this.cache = cache;
        this.vocalsModel = vocals;
        this.accompanimentModel = accompaniment;
    }

    private static ModelCache environmentCache() {
        MusicWizardConfig config = new ConfigLoader().effectiveConfig(null, null);
        MusicWizardConfig.MlConfig ml = config.ml();
        Path directory = ModelCacheLocation.directoryFor(
                ml == null ? null : ml.modelCacheDirectory());
        return ModelCache.at(directory, config.isOffline());
    }

    @Override
    public String id() {
        return "onnx-spleeter";
    }

    @Override
    public int preferredSampleRate() {
        return MODEL_RATE;
    }

    @Override
    public Separation separate(float[][] channels, int sampleRate) {
        if (channels.length > 2) {
            // The model is stereo. Truncating silently would return a "same
            // shape" result that dropped audio; nothing upstream produces more
            // than two channels, so this is a contract error, not a case.
            throw new IllegalArgumentException(
                    "at most two channels, got " + channels.length);
        }
        for (int c = 1; c < channels.length; c++) {
            if (channels[c].length != channels[0].length) {
                // Ragged input died as an ArrayIndexOutOfBounds inside a
                // private helper; a contract error should name itself.
                throw new IllegalArgumentException(
                        "channels must be the same length, got "
                        + channels[0].length + " and " + channels[c].length);
            }
        }
        if (channels.length == 0 || channels[0].length == 0) {
            // Fresh empties, not aliases of the caller's array: the contract is
            // two stems and an untouched input, not three names for one buffer.
            return new Separation(emptyLike(channels), emptyLike(channels));
        }
        Path vocalsPath = cache.fetch(vocalsModel, System.out::println);
        Path accompanimentPath = cache.fetch(accompanimentModel, System.out::println);

        // A mono recording is sent as two equal channels, and every
        // right-hand structure below aliases the left rather than recomputing
        // a duplicate nothing reads.
        boolean mono = channels.length == 1;
        float[] left = Resampler.resample(channels[0], sampleRate, MODEL_RATE);
        float[] right = mono ? left
                : Resampler.resample(channels[1], sampleRate, MODEL_RATE);

        SpleeterStft stft = new SpleeterStft();
        float[][] leftSpec = stft.forward(left);
        float[][] rightSpec = mono ? leftSpec : stft.forward(right);

        float[][][] magnitudes = magnitudes(leftSpec, rightSpec, mono);
        float[][][] vocalsOut;
        float[][][] accompanimentOut;
        try (OrtEnvironment environment = OrtEnvironment.getEnvironment()) {
            vocalsOut = run(environment, vocalsPath, magnitudes);
            accompanimentOut = run(environment, accompanimentPath, magnitudes);
        } catch (OrtException e) {
            throw new ModelUnavailableException(
                    "ONNX Runtime could not run the separation models: " + e.getMessage(), e);
        }

        // Masks are applied frame by frame inside the inverse, so no masked
        // spectrogram is ever materialised whole.
        int modelLength = left.length;
        int originalLength = channels[0].length;
        float[][] vocals = stems(stft, leftSpec, rightSpec, vocalsOut,
                accompanimentOut, mono, modelLength, sampleRate, originalLength);
        float[][] accompaniment = stems(stft, leftSpec, rightSpec, accompanimentOut,
                vocalsOut, mono, modelLength, sampleRate, originalLength);
        return new Separation(vocals, accompaniment);
    }

    /** The caller's channel count, every channel empty — the shape, kept. */
    private static float[][] emptyLike(float[][] channels) {
        float[][] out = new float[channels.length][];
        for (int c = 0; c < out.length; c++) {
            out[c] = new float[0];
        }
        return out;
    }

    /** One stem, both channels, mask fused into the inverse. */
    private static float[][] stems(SpleeterStft stft, float[][] leftSpec,
                                   float[][] rightSpec, float[][][] stem,
                                   float[][][] other, boolean mono, int modelLength,
                                   int sampleRate, int originalLength) {
        float[] leftOut = stft.inverse(
                t -> maskedFrame(leftSpec[t], stem[0][t], other[0][t]),
                leftSpec.length, modelLength);
        if (mono) {
            return new float[][] {fitLength(
                    Resampler.resample(leftOut, MODEL_RATE, sampleRate), originalLength)};
        }
        float[] rightOut = stft.inverse(
                t -> maskedFrame(rightSpec[t], stem[1][t], other[1][t]),
                rightSpec.length, modelLength);
        return new float[][] {
                fitLength(Resampler.resample(leftOut, MODEL_RATE, sampleRate), originalLength),
                fitLength(Resampler.resample(rightOut, MODEL_RATE, sampleRate), originalLength)
        };
    }

    /** The model's input: {@code [2][segments * SEGMENT_FRAMES][MODEL_BINS]} magnitudes. */
    private static float[][][] magnitudes(float[][] leftSpec, float[][] rightSpec,
                                          boolean mono) {
        int frames = leftSpec.length;
        int padded = ((frames + SEGMENT_FRAMES - 1) / SEGMENT_FRAMES) * SEGMENT_FRAMES;
        float[][][] out = new float[2][padded][];
        float[] zero = new float[SpleeterStft.MODEL_BINS];
        for (int t = 0; t < padded; t++) {
            if (t >= frames) {
                out[0][t] = zero;
                out[1][t] = zero;
                continue;
            }
            float[] leftRow = new float[SpleeterStft.MODEL_BINS];
            for (int f = 0; f < SpleeterStft.MODEL_BINS; f++) {
                leftRow[f] = magnitude(leftSpec[t], f);
            }
            out[0][t] = leftRow;
            if (mono) {
                out[1][t] = leftRow;
            } else {
                float[] rightRow = new float[SpleeterStft.MODEL_BINS];
                for (int f = 0; f < SpleeterStft.MODEL_BINS; f++) {
                    rightRow[f] = magnitude(rightSpec[t], f);
                }
                out[1][t] = rightRow;
            }
        }
        return out;
    }

    private static float magnitude(float[] spectrum, int bin) {
        float re = spectrum[2 * bin];
        float im = spectrum[2 * bin + 1];
        return (float) Math.sqrt(re * re + im * im);
    }

    /** Runs one stem model over every segment at once. */
    private static float[][][] run(OrtEnvironment environment, Path model,
                                   float[][][] magnitudes) throws OrtException {
        int padded = magnitudes[0].length;
        int segments = padded / SEGMENT_FRAMES;
        long[] shape = {2, segments, SEGMENT_FRAMES, SpleeterStft.MODEL_BINS};
        FloatBuffer input = FloatBuffer.allocate(
                2 * padded * SpleeterStft.MODEL_BINS);
        for (float[][] channel : magnitudes) {
            for (float[] frame : channel) {
                input.put(frame);
            }
        }
        input.flip();
        try (OrtSession session = environment.createSession(model.toString(),
                     new OrtSession.SessionOptions());
             OnnxTensor tensor = OnnxTensor.createTensor(environment, input, shape);
             OrtSession.Result result = session.run(Map.of("x", tensor))) {
            float[][][][] raw = (float[][][][]) result.get(0).getValue();
            float[][][] out = new float[2][padded][];
            for (int c = 0; c < 2; c++) {
                for (int s = 0; s < segments; s++) {
                    for (int t = 0; t < SEGMENT_FRAMES; t++) {
                        out[c][s * SEGMENT_FRAMES + t] = raw[c][s][t];
                    }
                }
            }
            return out;
        }
    }

    /**
     * One frame of the mix under one stem's soft mask.
     *
     * <p>Ratio of the estimates themselves, with a small floor so silence
     * divides to silence rather than to noise. Spleeter's own default squares
     * them first, which cuts harder where the band beats the voice in a bin —
     * and what the melody stage loses to separation is dominated by voice the
     * mask removed rather than by band it left behind, so the harder mask was
     * paying for the wrong thing (#503).
     * {@code tools/apportion-separation-loss.py} is what re-measures the
     * trade.
     *
     * <p>Estimates are magnitudes and a negative one is a broken checkpoint,
     * but squaring used to make that harmless and this does not: it would give
     * a mask outside zero to one, and a stem louder than the mix it came from.
     * Floored for that reason and no other.
     *
     * <p>Above the model's bins the mask is zero for both stems — Spleeter's
     * default extension ({@code mask_extension: "zeros"} in its 2stems config)
     * — so the stems' sum is the mix only below ~11 kHz, and that is the
     * checkpoint's behaviour, not a defect here.
     */
    static float[] maskedFrame(float[] mixFrame, float[] stem, float[] other) {
        float[] spectrum = new float[mixFrame.length];
        for (int f = 0; f < SpleeterStft.MODEL_BINS; f++) {
            float s = Math.max(0f, stem[f]);
            float o = Math.max(0f, other[f]);
            float mask = s / (s + o + 1e-10f);
            spectrum[2 * f] = mixFrame[2 * f] * mask;
            spectrum[2 * f + 1] = mixFrame[2 * f + 1] * mask;
        }
        return spectrum;
    }

    /** Rounding in two resamples can drift a sample or two; the contract is exact. */
    private static float[] fitLength(float[] samples, int length) {
        if (samples.length == length) {
            return samples;
        }
        float[] out = new float[length];
        System.arraycopy(samples, 0, out, 0, Math.min(samples.length, length));
        return out;
    }
}
