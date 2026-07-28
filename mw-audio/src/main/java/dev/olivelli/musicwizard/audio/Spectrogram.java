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
 * chroma used for chord estimation, and the tuning estimate. It is the single
 * most expensive step any of them takes.
 *
 * <p>It is <em>not</em> shared between them, though, whatever this comment used
 * to claim: harmony wants 4096/1024 for frequency resolution and onsets want
 * 1024/128 for time resolution, so a run computes two of these and neither is
 * at the default resolution.
 *
 * @param magnitudes  frame-major magnitudes, {@code [frame][bin]}
 * @param sampleRate  sample rate of the source signal
 * @param windowSize  FFT size in samples
 * @param hopSize     samples advanced between frames
 */
public record Spectrogram(float[][] magnitudes, int sampleRate, int windowSize, int hopSize) {

    /**
     * Validates the sizes, then the magnitudes' structure and finiteness.
     *
     * <p>Every frame must be present, {@code windowSize / 2 + 1} bins wide, and
     * finite. What that buys is a contract the stages downstream can rely on
     * without each re-deriving it: chroma, the tuning estimate and the onset
     * envelope all index straight into {@code magnitudes[frame][bin]}, and
     * {@link #binOf}, {@link #frequencyOf} and {@link #binCount} all assume the
     * width the transform produces.
     *
     * <p>The width is checked against {@code windowSize} rather than against
     * the first row, because agreeing with each other is not the property
     * anything depends on. A three-frame spectrogram of five bins each is
     * perfectly rectangular and still wrong: {@code binOf(440)} returns bin 4,
     * whose centre frequency is 43 Hz, so a caller asking for the 440 Hz bin
     * gets a confident wrong answer rather than the named exception this check
     * exists to give them. Zero-width frames fall out of the same clause; they
     * previously failed as {@code IllegalArgumentException: 0 > -1} from inside
     * {@code Math.clamp}, three call levels down.
     *
     * <p>The finiteness half is not implied by the buffer the magnitudes came
     * from. {@link AudioBuffer} rejects non-finite samples, but the window
     * multiply and the transform can still overflow: {@code compute} first
     * produces a non-finite bin at a sample magnitude around {@code 3.3e35} at
     * the default resolution. That is thirty-five orders above full scale and
     * will not fire on audio -- so treat it as the contract being stated rather
     * than a bug being caught. The structural clauses are the ones that will
     * ever fire, on a hand-built spectrogram.
     *
     * <p>The scan is one pass over the magnitudes, and a run pays it twice --
     * as the <em>first</em> construction in the process, which is the one that
     * costs. Over five minutes at 22.05 kHz, four fresh JVMs each, timing the
     * first {@code new Spectrogram} and then four more in the same JVM:
     *
     * <pre>
     *   resolution        values     first        thereafter
     *   4096/1024 harmony  13.2 M   16.3-16.6 ms   5.8-6.4 ms
     *   1024/128  onsets   26.5 M   24.9-25.7 ms  12.7-13.5 ms
     * </pre>
     *
     * <p>Against <b>370 to 479 ms</b> and <b>720 to 843 ms</b> to compute those
     * two, so the check is 3% to 4% of the transform it guards either way.
     *
     * <p>Both columns are quoted because they answer different questions, and
     * conflating them is how this paragraph has been wrong twice. The first
     * column is what a recording pays; the second is what a
     * {@code checkStructure()} called repeatedly would cost, which is what
     * issue #79 needs. An earlier draft quoted the second column and described
     * it as the first, and the draft before that explained the resulting 3.7x
     * gap as a difference between resolutions.
     *
     * <p>It was not one. The onset resolution really does scan twice the data,
     * and the two columns are far enough apart that no repeat of one resolution
     * overlaps the other. But the <em>default</em> resolution scans 13,238,900
     * values against harmony's 13,228,344 -- 0.08% apart -- so any gap measured
     * between those two is method, not resolution, and that was the pair the
     * 3.7x was drawn from.
     *
     * <p>Like {@link AudioBuffer}, this validates at construction and shares
     * the array rather than copying it, so a caller that writes through
     * {@link #magnitudes()} afterwards can break any of these properties and
     * nothing will notice. That matters more here than it looks: issue #76
     * reasons from this contract about whether a downstream guard can be
     * deleted. See issue #79.
     *
     * @throws NullPointerException if {@code magnitudes} is null
     * @throws IllegalArgumentException if any of the three sizes is not
     *     positive, {@code windowSize} is not a power of two, a frame is absent
     *     or not {@code windowSize / 2 + 1} bins wide, or a bin is not finite
     */
    public Spectrogram {
        Objects.requireNonNull(magnitudes, "magnitudes");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, got: " + sampleRate);
        }
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive, got: " + windowSize);
        }
        if (hopSize <= 0) {
            throw new IllegalArgumentException("hopSize must be positive, got: " + hopSize);
        }
        if (Integer.bitCount(windowSize) != 1) {
            throw new IllegalArgumentException(
                    "windowSize must be a power of two, got: " + windowSize);
        }
        int expectedBins = windowSize / 2 + 1;
        for (int frame = 0; frame < magnitudes.length; frame++) {
            float[] bins = magnitudes[frame];
            if (bins == null) {
                throw new IllegalArgumentException("magnitudes[" + frame + "] is null");
            }
            if (bins.length != expectedBins) {
                throw new IllegalArgumentException("magnitudes[" + frame + "] has "
                        + bins.length + " bins, but windowSize " + windowSize
                        + " gives " + expectedBins);
            }
            for (int bin = 0; bin < expectedBins; bin++) {
                if (!Float.isFinite(bins[bin])) {
                    throw new IllegalArgumentException("magnitudes must be finite, but magnitudes["
                            + frame + "][" + bin + "] is " + bins[bin]
                            + " -- the transform overflowed, or the spectrogram was built by hand");
                }
            }
        }
    }

    /** The magnitudes, shared rather than copied. Do not modify; see #79. */
    public float[][] magnitudes() {
        return magnitudes;
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
