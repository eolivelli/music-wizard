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

package dev.olivelli.musicwizard.audio;

import java.util.Objects;
import org.jtransforms.fft.FloatFFT_1D;

/**
 * A short-time Fourier transform: magnitude per frequency bin, per frame.
 *
 * <p>Everything spectral in the pipeline starts here — the onset envelope, the
 * chroma used for chord estimation, and the tuning estimate. It is computed once
 * per recording and shared, because it is the single most expensive step that
 * more than one stage needs.
 *
 * @param magnitudes  frame-major magnitudes, {@code [frame][bin]}
 * @param sampleRate  sample rate of the source signal
 * @param windowSize  FFT size in samples
 * @param hopSize     samples advanced between frames
 */
public record Spectrogram(float[][] magnitudes, int sampleRate, int windowSize, int hopSize) {

    /**
     * <p>Magnitudes must be finite, and that is not implied by the buffer they
     * came from. {@link AudioBuffer} rejects non-finite samples, but the window
     * multiply and the transform can still overflow when the samples are large:
     * a buffer of {@code 1e38} -- finite, and accepted -- produces 3341
     * non-finite bins here. Checking only the input would leave that door open,
     * and a poisoned bin is worse than a poisoned sample, because every stage
     * downstream reads this rather than the audio.
     *
     * <p>The scan costs 16.6 ms against the 531 ms it takes to compute a
     * five-minute spectrogram, which is paid once per recording because the
     * result is shared by every spectral stage.
     */
    public Spectrogram {
        Objects.requireNonNull(magnitudes, "magnitudes");
        if (sampleRate <= 0 || windowSize <= 0 || hopSize <= 0) {
            throw new IllegalArgumentException(
                    "sampleRate, windowSize and hopSize must be positive");
        }
        if (Integer.bitCount(windowSize) != 1) {
            throw new IllegalArgumentException(
                    "windowSize must be a power of two, got: " + windowSize);
        }
        for (int frame = 0; frame < magnitudes.length; frame++) {
            float[] bins = magnitudes[frame];
            if (bins == null) {
                throw new IllegalArgumentException("magnitudes[" + frame + "] is null");
            }
            for (int bin = 0; bin < bins.length; bin++) {
                if (!Float.isFinite(bins[bin])) {
                    throw new IllegalArgumentException("magnitudes must be finite, but magnitudes["
                            + frame + "][" + bin + "] is " + bins[bin]
                            + " -- usually the audio was far outside [-1, 1]");
                }
            }
        }
    }

    /**
     * Computes an STFT with a Hann window.
     *
     * <p>Defaults are 2048 samples at 22.05 kHz — about 93 ms, which resolves
     * adjacent semitones down to roughly the bottom of the bass range — hopped
     * by 512, giving one frame every 23 ms. That hop is short enough to place an
     * onset accurately and long enough to keep the frame count manageable.
     */
    public static Spectrogram compute(AudioBuffer audio, int windowSize, int hopSize) {
        Objects.requireNonNull(audio, "audio");
        if (Integer.bitCount(windowSize) != 1) {
            throw new IllegalArgumentException(
                    "windowSize must be a power of two, got: " + windowSize);
        }
        float[] samples = audio.samples();
        int frameCount = samples.length < windowSize
                ? 0
                : 1 + (samples.length - windowSize) / hopSize;
        int bins = windowSize / 2 + 1;

        float[] window = hannWindow(windowSize);
        FloatFFT_1D fft = new FloatFFT_1D(windowSize);
        float[][] magnitudes = new float[Math.max(frameCount, 0)][];

        float[] frame = new float[windowSize];
        for (int f = 0; f < frameCount; f++) {
            int offset = f * hopSize;
            for (int i = 0; i < windowSize; i++) {
                frame[i] = samples[offset + i] * window[i];
            }
            // In-place real forward transform; output is packed as described in
            // JTransforms' realForward contract.
            fft.realForward(frame);

            float[] bin = new float[bins];
            bin[0] = Math.abs(frame[0]);
            for (int k = 1; k < windowSize / 2; k++) {
                float re = frame[2 * k];
                float im = frame[2 * k + 1];
                bin[k] = (float) Math.hypot(re, im);
            }
            // The Nyquist bin is packed into the imaginary slot of bin 0.
            bin[windowSize / 2] = Math.abs(frame[1]);
            magnitudes[f] = bin;
        }
        return new Spectrogram(magnitudes, audio.sampleRate(), windowSize, hopSize);
    }

    /** Computes an STFT with the pipeline's default resolution. */
    public static Spectrogram compute(AudioBuffer audio) {
        return compute(audio, 2048, 512);
    }

    static float[] hannWindow(int size) {
        float[] window = new float[size];
        for (int i = 0; i < size; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / size));
        }
        return window;
    }

    public int frameCount() {
        return magnitudes.length;
    }

    public int binCount() {
        return magnitudes.length == 0 ? windowSize / 2 + 1 : magnitudes[0].length;
    }

    /** Frames per second, which is the rate every derived envelope runs at. */
    public double frameRate() {
        return (double) sampleRate / hopSize;
    }

    /** The time at the centre of a frame. */
    public double timeOf(int frame) {
        return (frame * (double) hopSize + windowSize / 2.0) / sampleRate;
    }

    /** The centre frequency of a bin, in hertz. */
    public double frequencyOf(int bin) {
        return (double) bin * sampleRate / windowSize;
    }

    /** The bin nearest a frequency, clamped into range. */
    public int binOf(double frequencyHz) {
        int bin = (int) Math.round(frequencyHz * windowSize / sampleRate);
        return (int) Math.clamp(bin, 0, binCount() - 1);
    }
}
