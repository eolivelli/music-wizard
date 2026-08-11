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

import org.jtransforms.fft.FloatFFT_1D;

/**
 * The STFT Spleeter's models were trained against, and its inverse.
 *
 * <p>Frame 4096, hop 1024, periodic Hann — the parameters are the model's, not
 * ours, which is why this lives beside the provider rather than reusing
 * {@code mw-dsp}'s analysis STFT: an analysis window chosen for chroma is a
 * tuning decision, this one is a compatibility requirement, and tying them
 * together would let a chroma improvement silently break separation.
 *
 * <p>The inverse divides by the summed squared synthesis window rather than
 * assuming constant overlap-add, so the edges — where fewer frames overlap —
 * come back at the right amplitude instead of faded.
 */
final class SpleeterStft {

    static final int FRAME = 4096;
    static final int HOP = 1024;

    /** Bins the model sees; the transform itself has {@code FRAME / 2 + 1}. */
    static final int MODEL_BINS = 1024;

    static final int BINS = FRAME / 2 + 1;

    private final float[] window = new float[FRAME];
    private final FloatFFT_1D fft = new FloatFFT_1D(FRAME);

    SpleeterStft() {
        for (int i = 0; i < FRAME; i++) {
            // Periodic Hann: the denominator is FRAME, not FRAME - 1, matching
            // tf.signal's default, which is what the checkpoint was trained on.
            window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / FRAME));
        }
    }

    /**
     * Complex spectrogram, {@code [frames][2 * BINS]} with re and im interleaved.
     *
     * <p>The signal is centre-padded by half a frame before framing. Without
     * that, the first sample sits at window position zero — where a periodic
     * Hann is exactly zero — and is unrecoverable, and the next few sit under
     * near-zero overlap that turns rounding error into audible error. The
     * padding is this transform's own convention, compensated in
     * {@link #inverse}, and the masks are applied to this STFT and inverted by
     * it, so the pair is self-consistent whatever the model's own framing was.
     */
    float[][] forward(float[] samples) {
        float[] padded = new float[samples.length + FRAME];
        System.arraycopy(samples, 0, padded, FRAME / 2, samples.length);
        int frames = 1 + (samples.length + HOP - 1) / HOP;
        float[][] out = new float[frames][];
        float[] buffer = new float[FRAME];
        for (int t = 0; t < frames; t++) {
            int start = t * HOP;
            for (int i = 0; i < FRAME; i++) {
                int at = start + i;
                buffer[i] = at < padded.length ? padded[at] * window[i] : 0f;
            }
            fft.realForward(buffer);
            // Unpack JTransforms' packed layout into plain interleaved complex:
            // packed a[0] = Re[0], a[1] = Re[N/2], a[2k] = Re[k], a[2k+1] = Im[k].
            float[] spectrum = new float[2 * BINS];
            spectrum[0] = buffer[0];
            spectrum[1] = 0f;
            for (int k = 1; k < BINS - 1; k++) {
                spectrum[2 * k] = buffer[2 * k];
                spectrum[2 * k + 1] = buffer[2 * k + 1];
            }
            spectrum[2 * (BINS - 1)] = buffer[1];
            spectrum[2 * (BINS - 1) + 1] = 0f;
            out[t] = spectrum;
        }
        return out;
    }

    /** The time-domain signal, cut back to {@code length} samples. */
    float[] inverse(float[][] spectrogram, int length) {
        return inverse(t -> spectrogram[t], spectrogram.length, length);
    }

    /**
     * The same, with each frame supplied on demand.
     *
     * <p>This is what lets a caller apply a mask frame by frame instead of
     * materialising a whole masked spectrogram first — at four masked
     * spectrograms of a quarter gigabyte each on an ordinary song, the
     * difference between this and the array form was the difference between
     * finishing and {@code OutOfMemoryError}.
     */
    float[] inverse(java.util.function.IntFunction<float[]> frameAt, int frames,
                    int length) {
        float[] out = new float[(frames - 1) * HOP + FRAME];
        float[] overlap = new float[out.length];
        float[] buffer = new float[FRAME];
        for (int t = 0; t < frames; t++) {
            float[] spectrum = frameAt.apply(t);
            // Repack into JTransforms' layout for the inverse.
            buffer[0] = spectrum[0];
            buffer[1] = spectrum[2 * (BINS - 1)];
            for (int k = 1; k < BINS - 1; k++) {
                buffer[2 * k] = spectrum[2 * k];
                buffer[2 * k + 1] = spectrum[2 * k + 1];
            }
            fft.realInverse(buffer, true);
            int start = t * HOP;
            for (int i = 0; i < FRAME; i++) {
                out[start + i] += buffer[i] * window[i];
                overlap[start + i] += window[i] * window[i];
            }
        }
        // Undo the centre padding: the caller's sample i sits at FRAME/2 + i.
        float[] cut = new float[length];
        int offset = FRAME / 2;
        for (int i = 0; i < length && offset + i < out.length; i++) {
            float sum = overlap[offset + i];
            cut[i] = sum > 1e-9f ? out[offset + i] / sum : 0f;
        }
        return cut;
    }
}
