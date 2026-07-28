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
     *
     * <p>Finite input gives finite output. That is worth stating because it did
     * not used to be true: the interpolation subtracted two floats before
     * widening, so upsampling a signal near {@code Float.MAX_VALUE} overflowed
     * to infinity -- 1998 of 2000 output samples, from input that was entirely
     * finite. Downsampling hid it, because the low-pass runs first and shrinks
     * the values. {@link AudioDecoder} resamples before it constructs the
     * {@link AudioBuffer}, so without this the buffer's own check would have
     * been the thing reporting a fault the resampler had just introduced.
     *
     * <p>It moves ordinary output too. Against the previous float arithmetic,
     * on uniform noise: 44.1k to 16k differs on 13.6% of samples, 22.05k to
     * 44.1k on 14.3%, 44.1k to 48k on 24.5%. Tonal and music-like material
     * differs on about 1%, so those are the conservative end. 44.1k to 22.05k
     * is bit-identical, but only because the ratio is exactly two, so
     * {@code fraction} is always zero and no interpolation happens at all --
     * the same holds for 48k to 16k and any other integer ratio, and not
     * because downsampling is inherently safe.
     *
     * <p>Two roundings produce the difference, not one: {@code b - a} is
     * rounded, and then so is the result. So it is not bounded by half an ulp
     * of either operand -- that form is exceeded fourfold by ordinary
     * material, because Sterbenz makes {@code b - a} exact unless the operands
     * differ by more than a factor of two, which leaves only the cases where
     * the result's ulp is much the larger. Both quantities live below twice the
     * peak, and the bound that does hold is half an ulp of {@code 2 x peak} --
     * equivalently <b>one ulp of the peak</b>, since doubling a normal float
     * doubles its ulp. Attained but never exceeded over roughly 64 million
     * constructed pairs, including denormals, binade edges and both signs. It
     * scales with amplitude, so no single absolute figure is a bound on its
     * own:
     *
     * <pre>
     *   peak   bound = ulp(peak)   worst measured
     *   0.5    6.0e-8              3.0e-8   (-150.5 dBFS)
     *   0.9    6.0e-8              6.0e-8   (-144.5 dBFS)
     *   1.0    1.2e-7              6.0e-8   (-144.5 dBFS)  -- half a 24-bit LSB
     *   2.0    2.4e-7              1.2e-7   (-138.5 dBFS)
     * </pre>
     *
     * <p>Read the bound column, not the measured one. An earlier draft of this
     * paragraph gave "half an ulp of the peak" as the rule, having measured it
     * at 0.5, 1.0 and 2.0 -- every one of them an exact power of two, where
     * {@code 2 x peak} lands on a binade boundary and the two coincide. Off a
     * power of two it is short by exactly 2x: a normalised master peaking at
     * 0.9, which is the ordinary case, measures 6.0e-8 where that rule predicts
     * 3.0e-8. Downsampling is looser again, and not because the low-pass
     * shrinks the values -- at peak 0.9 into 16 kHz it takes the peak down by
     * a factor of 0.81, which alone would leave {@code max|b - a|} at 1.45 and
     * change nothing. It shrinks the <em>differences</em>, by 0.29, and that is
     * what carries them under the binade boundary.
     *
     * <p>Six orders below the tightest tolerance anywhere downstream of this
     * method, and double is the accurate side of the difference. Do not quote
     * the ulp count: it runs to the hundreds of thousands, because the
     * differences sit at zero crossings where an ulp is vanishingly small, and
     * being a maximum over samples it grows with the length of the signal
     * rather than converging on a bound.
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
            double a = source[Math.min(index, source.length - 1)];
            double b = source[Math.min(index + 1, source.length - 1)];
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
