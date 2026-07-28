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
     * <p>It moves ordinary output too, and the size of that is the thing to
     * know: the difference between the two expressions is at most <b>one ulp of
     * the signal's peak</b> -- so 1.2e-7 for normalised audio, and it scales
     * with amplitude. Attained but never exceeded over roughly 64 million
     * constructed pairs, including denormals, binade edges and both signs. The
     * worst actually measured on real conversions is half that, 6.0e-8, which
     * is about -144 dBFS or half a 24-bit LSB.
     *
     * <p>For scale, the smallest threshold anywhere downstream that is in these
     * same units is {@code isEffectivelySilent}'s peak of 1e-4, three orders
     * above the bound. Double is the accurate side of the difference.
     *
     * <p>One thing worth knowing beyond the bound: the differences are common
     * rather than rare, so this is not a last-bit curiosity. On the two
     * conversions a real recording actually takes into the analysis rate,
     * measured: <b>44.1k to 22.05k is bit-identical</b> -- the step is exactly
     * 2, so {@code fraction} is always zero and no interpolation happens, which
     * holds for any integer decimation -- and <b>48k to 22.05k differs on 15%
     * of samples</b> on uniform noise, 0.9% on a triad.
     *
     * <p>Do not read a range off those two. Divergence varies from about 3% to
     * 25% over the source rates a file might declare, it is not ordered by how
     * steep the conversion is, and three successive attempts to state it as a
     * swept range were each wrong at the floor. If you need the figure for some
     * other pair, measure that pair.
     *
     * <p>Everything else about this difference -- how it distributes, how it
     * relates to the exactness of the subtraction, and why the obvious bound of
     * half an ulp of {@code |b - a|} is wrong by a factor of four -- is a
     * side-question, and it was got wrong eight times over eight review rounds
     * on PR #78, always by stating a measurement more broadly than it was
     * taken. Those drafts are in this file's history from that PR. The bound
     * above is what a caller needs; anyone who needs more should measure it
     * rather than inherit it.
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
