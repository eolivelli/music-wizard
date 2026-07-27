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

/**
 * Sample-rate conversion.
 *
 * <p>Downsampling low-passes first. Skipping that step folds everything above
 * the new Nyquist limit back down into the audible range as aliases, and those
 * aliases land on arbitrary frequencies — which is fatal here, because chroma
 * estimation reads energy at specific pitches and cannot tell a real partial
 * from a folded one.
 */
public final class Resampler {

    private Resampler() {
    }

    /**
     * Resamples a signal, low-pass filtering first when downsampling.
     *
     * <p>Interpolation is linear. It is not the best kernel available, but the
     * error it leaves is broadband and small relative to the spectral resolution
     * every stage downstream actually uses, whereas aliasing is neither.
     */
    public static float[] resample(float[] samples, int fromRate, int toRate) {
        if (fromRate <= 0 || toRate <= 0) {
            throw new IllegalArgumentException(
                    "sample rates must be positive, got " + fromRate + " and " + toRate);
        }
        if (fromRate == toRate || samples.length == 0) {
            return samples;
        }

        float[] source = samples;
        if (toRate < fromRate) {
            // Cut just under the new Nyquist limit, leaving a little transition
            // room so the filter's roll-off does not eat the top of the band.
            source = lowPass(samples, fromRate, toRate * 0.45);
        }

        double ratio = (double) toRate / fromRate;
        int outputLength = (int) Math.floor(source.length * ratio);
        if (outputLength <= 0) {
            return new float[0];
        }
        float[] out = new float[outputLength];
        double step = (double) fromRate / toRate;

        for (int i = 0; i < outputLength; i++) {
            double position = i * step;
            int index = (int) position;
            double fraction = position - index;
            float a = source[Math.min(index, source.length - 1)];
            float b = source[Math.min(index + 1, source.length - 1)];
            out[i] = (float) (a + (b - a) * fraction);
        }
        return out;
    }

    /**
     * A zero-phase low-pass, run forwards and then backwards.
     *
     * <p>Filtering in both directions cancels the phase shift a single pass
     * introduces. That matters more than it might seem: onset detection reads
     * times off this signal, and a one-directional filter would smear every
     * onset later by a frequency-dependent amount.
     */
    static float[] lowPass(float[] samples, int sampleRate, double cutoffHz) {
        if (cutoffHz >= sampleRate / 2.0) {
            return samples;
        }
        // One-pole smoothing coefficient for the requested cutoff.
        double rc = 1.0 / (2 * Math.PI * cutoffHz);
        double dt = 1.0 / sampleRate;
        double alpha = dt / (rc + dt);

        float[] forward = new float[samples.length];
        double state = samples.length > 0 ? samples[0] : 0;
        for (int i = 0; i < samples.length; i++) {
            state += alpha * (samples[i] - state);
            forward[i] = (float) state;
        }

        float[] out = new float[samples.length];
        state = samples.length > 0 ? forward[forward.length - 1] : 0;
        for (int i = samples.length - 1; i >= 0; i--) {
            state += alpha * (forward[i] - state);
            out[i] = (float) state;
        }
        return out;
    }
}
