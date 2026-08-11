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
 * {@link SpleeterStft}. Masks are the ratio of squared estimates, applied to
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
        MusicWizardConfig.MlConfig ml =
                new ConfigLoader().effectiveConfig(null, null).ml();
        Path directory = ModelCacheLocation.directoryFor(
                ml == null ? null : ml.modelCacheDirectory());
        boolean offline = ml != null && Boolean.TRUE.equals(ml.offline());
        return ModelCache.at(directory, offline);
    }

    @Override
    public String id() {
        return "onnx-spleeter";
    }

    @Override
    public Separation separate(float[][] channels, int sampleRate) {
        if (channels.length == 0 || channels[0].length == 0) {
            return new Separation(channels, channels);
        }
        Path vocalsPath = cache.fetch(vocalsModel, System.out::println);
        Path accompanimentPath = cache.fetch(accompanimentModel, System.out::println);

        // The model is stereo. A mono recording is sent as two equal channels
        // and averaged back, so the caller's shape survives the round trip.
        boolean mono = channels.length == 1;
        float[] left = Resampler.resample(channels[0], sampleRate, MODEL_RATE);
        float[] right = mono ? left
                : Resampler.resample(channels[1], sampleRate, MODEL_RATE);

        SpleeterStft stft = new SpleeterStft();
        float[][] leftSpec = stft.forward(left);
        float[][] rightSpec = stft.forward(right);

        float[][][] magnitudes = magnitudes(leftSpec, rightSpec);
        float[][][] vocalsOut;
        float[][][] accompanimentOut;
        try (OrtEnvironment environment = OrtEnvironment.getEnvironment()) {
            vocalsOut = run(environment, vocalsPath, magnitudes);
            accompanimentOut = run(environment, accompanimentPath, magnitudes);
        } catch (OrtException e) {
            throw new ModelUnavailableException(
                    "ONNX Runtime could not run the separation models: " + e.getMessage(), e);
        }

        float[][] vocalsLeft = masked(leftSpec, vocalsOut[0], accompanimentOut[0]);
        float[][] vocalsRight = masked(rightSpec, vocalsOut[1], accompanimentOut[1]);
        float[][] accLeft = masked(leftSpec, accompanimentOut[0], vocalsOut[0]);
        float[][] accRight = masked(rightSpec, accompanimentOut[1], vocalsOut[1]);

        int modelLength = left.length;
        float[][] vocals = back(stft, vocalsLeft, vocalsRight, mono, modelLength,
                sampleRate, channels[0].length);
        float[][] accompaniment = back(stft, accLeft, accRight, mono, modelLength,
                sampleRate, channels[0].length);
        return new Separation(vocals, accompaniment);
    }

    /** The model's input: {@code [2][segments * SEGMENT_FRAMES][MODEL_BINS]} magnitudes. */
    private static float[][][] magnitudes(float[][] leftSpec, float[][] rightSpec) {
        int frames = leftSpec.length;
        int padded = ((frames + SEGMENT_FRAMES - 1) / SEGMENT_FRAMES) * SEGMENT_FRAMES;
        float[][][] out = new float[2][padded][SpleeterStft.MODEL_BINS];
        for (int t = 0; t < frames; t++) {
            for (int f = 0; f < SpleeterStft.MODEL_BINS; f++) {
                out[0][t][f] = magnitude(leftSpec[t], f);
                out[1][t][f] = magnitude(rightSpec[t], f);
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
     * The complex spectrogram of one stem: the mix under this stem's soft mask.
     *
     * <p>Ratio of squared estimates, Spleeter's own separation exponent, with a
     * small floor so silence divides to silence rather than to noise. Above the
     * model's bins the mask is zero for both stems — Spleeter's default
     * extension — so the stems' sum is the mix only below ~11 kHz, and that is
     * the checkpoint's behaviour, not a defect here.
     */
    private static float[][] masked(float[][] mixSpec, float[][] stem, float[][] other) {
        int frames = mixSpec.length;
        float[][] out = new float[frames][];
        for (int t = 0; t < frames; t++) {
            float[] spectrum = new float[mixSpec[t].length];
            for (int f = 0; f < SpleeterStft.MODEL_BINS; f++) {
                float s = stem[t][f];
                float o = other[t][f];
                float mask = (s * s) / (s * s + o * o + 1e-10f);
                spectrum[2 * f] = mixSpec[t][2 * f] * mask;
                spectrum[2 * f + 1] = mixSpec[t][2 * f + 1] * mask;
            }
            out[t] = spectrum;
        }
        return out;
    }

    /** Inverse-transforms, resamples to the caller's rate, restores the shape. */
    private static float[][] back(SpleeterStft stft, float[][] leftSpec,
                                  float[][] rightSpec, boolean mono, int modelLength,
                                  int sampleRate, int originalLength) {
        float[] left = stft.inverse(leftSpec, modelLength);
        if (mono) {
            float[] out = fitLength(
                    Resampler.resample(left, MODEL_RATE, sampleRate), originalLength);
            return new float[][] {out};
        }
        float[] right = stft.inverse(rightSpec, modelLength);
        return new float[][] {
                fitLength(Resampler.resample(left, MODEL_RATE, sampleRate), originalLength),
                fitLength(Resampler.resample(right, MODEL_RATE, sampleRate), originalLength)
        };
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
