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

package dev.olivelli.musicwizard.dsp;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.Spectrogram;
import java.util.List;
import java.util.Objects;

/**
 * Chroma: how much energy each of the twelve pitch classes carries, per frame.
 *
 * <p>Folding every octave onto one of twelve bins is what makes chord
 * recognition tractable — a C major chord looks the same whether it is played
 * low or high, and the same whether it is voiced in root position or inverted.
 *
 * <p>Two things matter more than the folding itself:
 *
 * <p><b>Tuning.</b> The mapping from frequency to pitch class assumes A4 = 440
 * Hz. Recordings routinely sit tens of cents away from that, and a recording 50
 * cents flat puts every pitch exactly between two bins, which destroys template
 * matching. Tuning is therefore estimated from the signal and corrected before
 * folding.
 *
 * <p><b>Beat synchrony.</b> Averaging chroma between consecutive beats is the
 * single largest accuracy improvement available short of a much better
 * front end. Chords change on beats, so beat-synchronous frames both denoise
 * the estimate and hand the chord decoder a segmentation for free.
 *
 * @param vectors   frame-major chroma, {@code [frame][12]}, C at index 0
 * @param frameRate frames per second, or 0 when frames are beat-synchronous
 */
public record Chroma(double[][] vectors, double frameRate) {

    /** Lowest pitch considered. Below this, partials outweigh fundamentals. */
    private static final double MIN_HZ = 65.4;   // C2

    /** Highest pitch considered; above this there is little chordal information. */
    private static final double MAX_HZ = 2093.0; // C7

    /**
     * Validates the shape and finiteness of the vectors.
     *
     * <p>{@code mw-core}'s records all reject non-finite values and this one did
     * not, which left a door open that #77 walked through: a {@code Chroma} built
     * by hand with a NaN in it produces a confident wrong tuning rather than an
     * error. {@link Spectrogram} already guarantees finite magnitudes, so the
     * extraction path cannot reach this — it is the hand-built path that could.
     *
     * @throws IllegalArgumentException if a frame is absent, is not twelve
     *     values wide, or holds a non-finite value
     */
    public Chroma {
        Objects.requireNonNull(vectors, "vectors");
        for (int frame = 0; frame < vectors.length; frame++) {
            double[] vector = vectors[frame];
            if (vector == null || vector.length != 12) {
                throw new IllegalArgumentException("vectors[" + frame + "] has "
                        + (vector == null ? "null" : vector.length + " values")
                        + ", but a chroma vector is twelve pitch classes");
            }
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                if (!Double.isFinite(vector[pitchClass])) {
                    throw new IllegalArgumentException("vectors[" + frame + "][" + pitchClass
                            + "] is " + vector[pitchClass] + "; chroma must be finite");
                }
            }
        }
    }

    /** Extracts chroma from audio, correcting for the recording's tuning. */
    public static Chroma extract(AudioBuffer audio) {
        Objects.requireNonNull(audio, "audio");
        Spectrogram spectrogram = Spectrogram.compute(audio, 4096, 1024);
        double tuningOffsetSemitones = estimateTuning(spectrogram);
        return extract(spectrogram, tuningOffsetSemitones);
    }

    /** Extracts chroma from a spectrogram with a known tuning correction. */
    public static Chroma extract(Spectrogram spectrogram, double tuningOffsetSemitones) {
        int frames = spectrogram.frameCount();
        double[][] out = new double[frames][12];

        int lowBin = Math.max(1, spectrogram.binOf(MIN_HZ));
        int highBin = Math.min(spectrogram.binCount() - 1, spectrogram.binOf(MAX_HZ));

        for (int frame = 0; frame < frames; frame++) {
            float[] magnitudes = spectrogram.magnitudes()[frame];
            for (int bin = lowBin; bin <= highBin; bin++) {
                double frequency = spectrogram.frequencyOf(bin);
                if (frequency <= 0) {
                    continue;
                }
                double semitonesFromC0 = 12 * (Math.log(frequency / 16.351625) / Math.log(2))
                        - tuningOffsetSemitones;
                int pitchClass = Math.floorMod((int) Math.round(semitonesFromC0), 12);
                // Magnitude rather than power: power lets one loud partial
                // dominate a whole frame, and chords are about which pitches are
                // present more than about how loud each one is.
                out[frame][pitchClass] += magnitudes[bin];
            }
            // Deliberately NOT normalised per frame. Normalising here would give
            // every frame an equal vote in the beat-synchronous average, so the
            // near-silent tail of a decaying note -- which is mostly noise --
            // would count as much as its attack, and a sustained chord would
            // average out to something flat. Loudness is the weighting that makes
            // the average meaningful, so it is preserved until the span is summed.
        }
        return new Chroma(out, spectrogram.frameRate());
    }

    /**
     * How wide a step {@link #estimateTuning} can answer in, in semitones.
     *
     * <p>Its answer is a histogram slot's centre, so it never lands on zero
     * unless it found no evidence at all; {@link #readsAsConcertPitch} is how
     * a caller asks whether it found any.
     */
    public static final double TUNING_RESOLUTION_SEMITONES = 0.025;

    /**
     * Whether an offset from {@link #estimateTuning} is one that estimator
     * cannot tell from concert pitch.
     *
     * <p>True of the two slots whose deviations reach zero, and of the zero
     * itself that means no evidence. Their centres are half a step either side
     * of it and every other slot's is at least a step and a half away, so the
     * comparison is made against a whole step: a centre is arithmetic on a
     * tenth and a hundredth and misses half a step by an ulp, either way.
     */
    public static boolean readsAsConcertPitch(double offsetSemitones) {
        return Math.abs(offsetSemitones) < TUNING_RESOLUTION_SEMITONES;
    }

    /**
     * Estimates how far the recording sits from A4 = 440 Hz, in semitones.
     *
     * <p>Every spectral peak is compared with the nearest equal-tempered pitch,
     * and the deviations are histogrammed. A recording in tune has them clustered
     * near zero; one recorded flat has them clustered at a consistent negative
     * offset. The mode of that distribution is the correction.
     *
     * @return the offset in semitones, within (-0.5, 0.5], quantised to
     *     {@link #TUNING_RESOLUTION_SEMITONES}
     */
    public static double estimateTuning(Spectrogram spectrogram) {
        Objects.requireNonNull(spectrogram, "spectrogram");
        int bins = (int) Math.round(1 / TUNING_RESOLUTION_SEMITONES);
        double[] histogram = new double[bins];

        int lowBin = Math.max(1, spectrogram.binOf(MIN_HZ));
        int highBin = Math.min(spectrogram.binCount() - 1, spectrogram.binOf(MAX_HZ));

        for (float[] magnitudes : spectrogram.magnitudes()) {
            for (int bin = lowBin + 1; bin < highBin - 1; bin++) {
                // Only local maxima: a bin on the flank of a peak sits at an
                // arbitrary frequency and would blur the histogram.
                if (magnitudes[bin] <= magnitudes[bin - 1] || magnitudes[bin] <= magnitudes[bin + 1]) {
                    continue;
                }
                double frequency = refinePeak(magnitudes, bin, spectrogram);
                if (frequency <= 0) {
                    continue;
                }
                double semitones = 12 * (Math.log(frequency / 16.351625) / Math.log(2));
                double deviation = semitones - Math.round(semitones);
                // A non-finite deviation would land in slot 0 and stay there:
                // (int) Math.floor(NaN) is 0, and one NaN in the histogram then
                // makes every later comparison against it false. See #77 -- a
                // single poisoned sample used to pin the answer at -0.4875
                // semitones, which reads as a recording half a semitone flat.
                if (!Double.isFinite(deviation) || !Float.isFinite(magnitudes[bin])) {
                    continue;
                }
                int slot = (int) Math.floor((deviation + 0.5) * bins);
                histogram[Math.clamp(slot, 0, bins - 1)] += magnitudes[bin];
            }
        }

        // Seeded at -1 rather than 0 so the mode has to be positively chosen.
        // Seeded at 0, a histogram whose slot 0 is not comparable -- or simply
        // one that is entirely empty -- returns slot 0 as though it had won,
        // and slot 0 is the most extreme answer the function can give.
        int best = -1;
        double total = 0;
        for (int i = 0; i < bins; i++) {
            double value = histogram[i];
            if (!Double.isFinite(value)) {
                continue;
            }
            total += value;
            if (value > 0 && (best < 0 || value > histogram[best])) {
                best = i;
            }
        }
        if (best < 0 || total <= 0) {
            // No tuning evidence. Zero means "assume A440", which is the honest
            // answer and, unlike a confident half-semitone, is also usually the
            // right one.
            return 0;
        }
        return (best + 0.5) / bins - 0.5;
    }

    /**
     * Refines a peak's frequency by parabolic interpolation over its neighbours.
     *
     * <p>Without this the estimate is quantised to the bin spacing, which at
     * this window size is coarser than the tuning deviations being measured — so
     * the histogram would show the FFT grid rather than the recording's tuning.
     */
    private static double refinePeak(float[] magnitudes, int bin, Spectrogram spectrogram) {
        double left = magnitudes[bin - 1];
        double centre = magnitudes[bin];
        double right = magnitudes[bin + 1];
        double denominator = left - 2 * centre + right;
        double offset = denominator != 0 ? 0.5 * (left - right) / denominator : 0;
        if (Math.abs(offset) > 1) {
            offset = 0;
        }
        return (bin + offset) * spectrogram.sampleRate() / (double) spectrogram.windowSize();
    }

    /**
     * Averages chroma between consecutive beats.
     *
     * <p>The largest single accuracy gain available here: chords change on
     * beats, so this both denoises the estimate and gives the decoder its
     * segmentation for free.
     *
     * <p>Three cases fold nothing and return this chroma unchanged: fewer than
     * two beats, so there is no span between them; a chroma that is already
     * beat-synchronous, which is what {@code frameRate <= 0} means; and a chroma
     * with no frames at all.
     *
     * <p>That last one is not hypothetical, and it took a window length change
     * to expose. A recording shorter than one analysis window yields zero
     * frames while the beat tracker still finds two pulses in it — and the loop
     * below then clamps {@code to} into the range {@code [from + 1, 0]}, which
     * {@link Math#clamp} rejects outright. With the old 4096-sample window that
     * combination was unreachable, because anything long enough to hold two
     * pulses was long enough to hold a frame; at {@link NnlsChroma}'s 8192 it is
     * reachable for any clip between about 0.302 and 0.371 seconds <em>at the
     * 22.05 kHz analysis rate</em>.
     *
     * <p>The width is rate-dependent and not by the factor it looks like. Only
     * the upper edge follows the window; the lower edge is the beat tracker's
     * minimum for two pulses and barely moves. Measured by sweeping length at
     * one millisecond:
     *
     * <pre>
     *   rate      window            band                 width
     *   22.05 kHz  8192 (0.372 s)   0.302 – 0.371 s      0.070 s
     *   44.1  kHz 16384 (0.372 s)   0.276 – 0.371 s      0.096 s
     *   24    kHz 16384 (0.683 s)   0.294 – 0.682 s      0.389 s
     *   48    kHz 32768 (0.683 s)   0.272 – 0.682 s      0.411 s
     * </pre>
     *
     * <p>So nearly six times as wide at 48 kHz, not twice, and 44.1 kHz is not
     * unaffected either. {@link NnlsChroma#windowSizeFor} rounds to a power of
     * two, which is why the rates pair up the way they do. The CLI only ever
     * decodes at the analysis rate; the public extract does not.
     *
     * <p>The guard is here rather than at the call site because
     * {@link NnlsChroma#beatSynchronous} reaches this same line by a different
     * route — and it is only half the fix. Returning an empty chroma stops this
     * method throwing and hands the emptiness downstream, where
     * {@link DownbeatEstimator#estimate} has to know that beats without chroma
     * is a state the pipeline can reach rather than a caller's mistake. It does.
     *
     * @param beatTimes beat instants in seconds, ascending
     * @return one chroma vector per inter-beat span, or this chroma unchanged
     *     when there is nothing to fold
     */
    public Chroma beatSynchronous(List<Double> beatTimes) {
        Objects.requireNonNull(beatTimes, "beatTimes");
        if (beatTimes.size() < 2 || frameRate <= 0 || vectors.length == 0) {
            return this;
        }
        int spans = beatTimes.size() - 1;
        double[][] out = new double[spans][12];

        for (int span = 0; span < spans; span++) {
            int[] range = spanFrames(beatTimes, span, frameRate, vectors.length);
            int from = range[0];
            int to = range[1];

            // Summing raw magnitudes weights each frame by how loud it actually
            // was, so the attack dominates and the decaying tail contributes
            // proportionally to the energy it carries.
            for (int frame = from; frame < to; frame++) {
                for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                    out[span][pitchClass] += vectors[frame][pitchClass];
                }
            }
            normalise(out[span]);
        }
        // Frame rate no longer applies: frames are now musical spans, not a
        // fixed grid, and pretending otherwise would invite a wrong conversion.
        return new Chroma(out, 0);
    }

    /**
     * The frames one inter-beat span covers, as {@code {from, to}} with
     * {@code to} exclusive and at least one frame wide.
     *
     * <p>Package-private and shared rather than restated, because
     * {@link NnlsAblation#beatSynchronous} has to fold the spectra the same
     * frames were folded into chroma: span {@code i} of the two must be span
     * {@code i} of the same recording, and two copies of this arithmetic can
     * drift apart without either being wrong on its own.
     */
    static int[] spanFrames(List<Double> beatTimes, int span, double frameRate, int frames) {
        int from = (int) Math.floor(beatTimes.get(span) * frameRate);
        int to = (int) Math.ceil(beatTimes.get(span + 1) * frameRate);
        from = Math.clamp(from, 0, Math.max(0, frames - 1));
        return new int[] {from, Math.clamp(to, from + 1, frames)};
    }

    private static void normalise(double[] vector) {
        double sum = 0;
        for (double value : vector) {
            sum += value;
        }
        if (sum <= 1e-12) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= sum;
        }
    }

    /**
     * A copy with each frame scaled to sum to one.
     *
     * <p>Useful for inspecting or displaying a single frame. Do not apply it
     * before {@link #beatSynchronous}, which relies on relative loudness to
     * weight the average.
     */
    public Chroma normalisedPerFrame() {
        double[][] out = new double[vectors.length][];
        for (int frame = 0; frame < vectors.length; frame++) {
            out[frame] = vectors[frame].clone();
            normalise(out[frame]);
        }
        return new Chroma(out, frameRate);
    }

    public int frameCount() {
        return vectors.length;
    }

    /** True when frames are beat-synchronous rather than on a fixed time grid. */
    public boolean isBeatSynchronous() {
        return frameRate == 0;
    }

    /** The dominant pitch class of a frame, which is a crude but useful summary. */
    public int strongestPitchClass(int frame) {
        double[] vector = vectors[frame];
        int best = 0;
        for (int i = 1; i < 12; i++) {
            if (vector[i] > vector[best]) {
                best = i;
            }
        }
        return best;
    }
}
