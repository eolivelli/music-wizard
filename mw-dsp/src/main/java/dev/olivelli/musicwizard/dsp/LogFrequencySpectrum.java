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

import dev.olivelli.musicwizard.audio.Spectrogram;
import java.util.Objects;

/**
 * An STFT magnitude spectrum resampled onto a pitch-linear grid, and optionally
 * whitened along it.
 *
 * <p>Step one of the NNLS front end. A constant-Q transform would give the same
 * thing more directly, but it is not needed: a linear map from the FFT
 * magnitudes reproduces it closely enough for a dictionary that is itself
 * idealised, and it reuses the transform the pipeline already computes rather
 * than adding a second one with its own kernels to get wrong.
 *
 * <p>The map is a resampling and is built to conserve energy <em>per FFT bin</em>
 * rather than per output bin. Each FFT bin's magnitude is spread over the output
 * bins its band covers, with weights summing to one. Normalising the other way
 * round — making each output bin an average of the FFT bins near it — is the
 * mistake that looks equivalent and is not: at the bottom of the grid several
 * output bins sit inside one FFT bin, and averaging would replicate that bin's
 * magnitude into all of them, multiplying the bass energy by the number of
 * output bins that happen to fit. The bass is the part this front end exists to
 * get right.
 *
 * <p>The kernel widens where it has to. Below roughly 200 Hz at the resolutions
 * used here an FFT bin is wider than an output bin, so a bin's energy is shared
 * between neighbours; above it, several FFT bins fall inside one output bin and
 * the kernel is a plain interpolation. {@link NoteDictionary} widens its
 * partials by the same rule, so the modelled peak and the observed peak have
 * the same width at every frequency. Without that they diverge exactly where
 * the bass lives, and NNLS explains a wide observed peak with a chord's worth of
 * narrow modelled ones.
 *
 * @param bins      frame-major magnitudes on the pitch-linear grid,
 *                  {@code [frame][bin]}
 * @param axis      the grid, including the tuning offset it was built at
 * @param frameRate frames per second
 */
public record LogFrequencySpectrum(double[][] bins, LogFrequencyAxis axis, double frameRate) {

    /**
     * Half-width of the whitening window, in semitones either side.
     *
     * <p>An octave in total. Two requirements bracket it. It has to be wide
     * relative to how far one peak is smeared, which at the bottom of the range
     * is about ±2 semitones — otherwise a peak is divided by its own spread and
     * whitening flattens the very thing it is protecting. And it has to be
     * narrow relative to the spectral envelope it removes, or it stops following
     * it.
     *
     * <p>Inside that bracket the measurement is decisive, which it was not
     * always. Swept at the shipped {@link NoteDictionary#PARTIAL_ROLL_OFF} over
     * the combined fold the pipeline uses — the frame-level margin of #185 on
     * {@code samples/gmajorblues.mp3} and on a second full mix, and per-bar
     * accuracy on the first against its known cycle:
     *
     * <pre>
     *   half-width   blues     second-mix   blues     blues
     *   (semitones)  margin    margin       root      root+quality
     *        3        +0.027     +0.153     86.9%       86.0%
     *        6        +0.038     +0.172     86.6%       86.3%
     *        9        +0.042     +0.169     76.8%       76.8%
     *       12        +0.050     +0.167     71.3%       71.3%
     *       24        +0.066     +0.166     67.2%       66.6%
     *       36        +0.065     +0.168     65.9%       65.6%
     * </pre>
     *
     * <p>Six is chosen because it wins: the best margin on the second recording
     * and the best root-plus-quality accuracy on the first, with only three
     * close to it and everything from nine upward far behind. The physical
     * bracket and the measurement agree rather than having to be traded, which
     * is the comfortable case.
     *
     * <p>The mechanism for the collapse above six is legible. A note's partials
     * are 12, 7, 5 and 4 semitones apart, so a window wider than that normalises
     * them against each other and flattens the geometric roll-off
     * {@link NoteDictionary} models — and once the observed roll-off no longer
     * matches the modelled one, NNLS spends the residual on notes that are not
     * there, which is the failure {@code PARTIAL_ROLL_OFF} exists to prevent.
     *
     * <p>Worth recording, because it is the reason this javadoc was rewritten:
     * the table that stood here previously was measured at a partial roll-off of
     * 0.70 and read the other way round — wider was better on synthetic
     * fixtures and slightly worse on a recording, and six was described as
     * "giving up 5% of the best margin measured". None of that survives the
     * roll-off change. It was a table in one file made stale by a constant in
     * another, which is this project's recurring failure and was caught here by
     * review rather than by anything mechanical.
     *
     * <p>Two recordings is still not a corpus (#193).
     */
    private static final int WHITENING_HALF_WIDTH_SEMITONES = 6;

    /**
     * Floor on the local standard deviation, relative to the frame's mean
     * magnitude.
     *
     * <p>Whitening divides, so a region of the spectrum with nothing in it would
     * otherwise be amplified without limit and arrive at NNLS looking like
     * evidence. The floor is relative to the frame rather than absolute so that
     * a quiet passage is not whitened differently from a loud one.
     */
    private static final double WHITENING_FLOOR = 1e-3;

    public LogFrequencySpectrum {
        Objects.requireNonNull(bins, "bins");
        Objects.requireNonNull(axis, "axis");
        for (int frame = 0; frame < bins.length; frame++) {
            if (bins[frame] == null || bins[frame].length != axis.binCount()) {
                throw new IllegalArgumentException("bins[" + frame + "] has "
                        + (bins[frame] == null ? "null" : bins[frame].length + " bins")
                        + ", but the axis has " + axis.binCount());
            }
        }
        if (!(frameRate >= 0) || !Double.isFinite(frameRate)) {
            throw new IllegalArgumentException("frameRate must be finite and not negative, got: "
                    + frameRate);
        }
    }

    /** Resamples an STFT onto a pitch-linear grid. */
    public static LogFrequencySpectrum map(Spectrogram spectrogram, LogFrequencyAxis axis) {
        Objects.requireNonNull(spectrogram, "spectrogram");
        Objects.requireNonNull(axis, "axis");

        int fftBins = spectrogram.binCount();
        int outputBins = axis.binCount();

        // The map depends only on the geometry, so it is built once and applied
        // to every frame. Stored as, for each FFT bin, the first output bin it
        // touches and the weights from there on -- a compressed row, because the
        // dense form is 4097 x 262 doubles for the resolution this is used at,
        // and all but a handful of entries in each row are zero.
        int[] firstOutputBin = new int[fftBins];
        double[][] weights = new double[fftBins][];

        for (int fftBin = 0; fftBin < fftBins; fftBin++) {
            double frequency = spectrogram.frequencyOf(fftBin);
            if (frequency <= 0) {
                // Bin 0 is DC: it has no pitch and its magnitude is whatever
                // offset the recording carries. Dropping it is deliberate.
                weights[fftBin] = new double[0];
                continue;
            }
            double centre = axis.binOfFrequency(frequency);
            double halfWidth = Math.max(1,
                    axis.fftBinWidthInBins(centre, spectrogram.sampleRate(),
                            spectrogram.windowSize()) / 2);

            int from = (int) Math.ceil(centre - halfWidth);
            int to = (int) Math.floor(centre + halfWidth);
            from = Math.max(from, 0);
            to = Math.min(to, outputBins - 1);
            if (to < from) {
                // Entirely outside the grid: the FFT bin is below the lowest
                // note or above the highest, and its energy is simply not part
                // of the harmonic picture.
                weights[fftBin] = new double[0];
                continue;
            }

            double[] row = new double[to - from + 1];
            double total = 0;
            for (int out = from; out <= to; out++) {
                double weight = LogFrequencyAxis.kernel(out - centre, halfWidth);
                row[out - from] = weight;
                total += weight;
            }
            if (total <= 0) {
                weights[fftBin] = new double[0];
                continue;
            }
            // Per FFT bin, not per output bin. See the class comment: this is
            // the normalisation that keeps the bass from being multiplied up.
            for (int i = 0; i < row.length; i++) {
                row[i] /= total;
            }
            firstOutputBin[fftBin] = from;
            weights[fftBin] = row;
        }

        int frames = spectrogram.frameCount();
        double[][] out = new double[frames][outputBins];
        for (int frame = 0; frame < frames; frame++) {
            float[] magnitudes = spectrogram.magnitudes()[frame];
            double[] target = out[frame];
            for (int fftBin = 0; fftBin < fftBins; fftBin++) {
                double magnitude = magnitudes[fftBin];
                if (magnitude == 0) {
                    continue;
                }
                double[] row = weights[fftBin];
                int base = firstOutputBin[fftBin];
                for (int i = 0; i < row.length; i++) {
                    target[base + i] += magnitude * row[i];
                }
            }
        }
        return new LogFrequencySpectrum(out, axis, spectrogram.frameRate());
    }

    /**
     * A copy with each frame divided by the local spread of its own magnitudes.
     *
     * <p>Whitening is what stops the front end from describing the instrument
     * instead of the chord. A bright guitar and a muffled piano playing the same
     * triad have wildly different spectral envelopes and the same harmony, and
     * an unwhitened fit spends its non-negative budget matching the envelope.
     * Dividing each bin by the standard deviation of its neighbourhood removes
     * the envelope and keeps the peaks, which is exactly the part that carries
     * pitch.
     *
     * <p>It also, incidentally, does most of the work the {@code s ≈ 0.7} partial
     * roll-off in the dictionary would otherwise have to do alone: after
     * whitening, a partial's height reflects how much it stands out from its
     * surroundings rather than how much absolute energy it has.
     *
     * <p>The local mean is subtracted before dividing, and the result clamped at
     * zero. Not subtracting it leaves the noise floor in, and the noise floor of
     * a real mix is the whole problem — drums, reverb and breath put energy in
     * every bin, and NNLS handed a raised floor explains it with notes, which is
     * precisely the flat chroma that made a commercial recording come out as
     * three minutes of "no chord" (#185).
     */
    public LogFrequencySpectrum whitened() {
        return whitened(WHITENING_HALF_WIDTH_SEMITONES);
    }

    /**
     * Whitening over a stated neighbourhood, for callers exploring the trade-off
     * the default resolves.
     *
     * @param halfWidthSemitones how far either side of a bin the neighbourhood
     *     reaches; see {@link #WHITENING_HALF_WIDTH_SEMITONES} for why the value
     *     is not free
     */
    public LogFrequencySpectrum whitened(int halfWidthSemitones) {
        if (halfWidthSemitones <= 0) {
            throw new IllegalArgumentException(
                    "halfWidthSemitones must be positive, got: " + halfWidthSemitones);
        }
        int binCount = axis.binCount();
        int halfWidth = halfWidthSemitones * axis.binsPerSemitone();
        double[] window = hammingWindow(2 * halfWidth + 1);

        double[][] out = new double[bins.length][binCount];
        for (int frame = 0; frame < bins.length; frame++) {
            double[] source = bins[frame];
            double[] target = out[frame];

            double frameMean = 0;
            for (double value : source) {
                frameMean += value;
            }
            frameMean /= binCount;
            double floor = WHITENING_FLOOR * frameMean;
            if (!(floor > 0)) {
                // Nothing in the frame. Leaving it at zero rather than dividing
                // by a floor that is itself zero: silence divided by silence is
                // where an infinity gets into the fit, and NNLS is handed a
                // frame of infinities that it will happily explain with notes.
                continue;
            }

            for (int bin = 0; bin < binCount; bin++) {
                double weightSum = 0;
                double weighted = 0;
                double weightedSquares = 0;
                int from = Math.max(0, bin - halfWidth);
                int to = Math.min(binCount - 1, bin + halfWidth);
                for (int k = from; k <= to; k++) {
                    double weight = window[k - bin + halfWidth];
                    weightSum += weight;
                    weighted += weight * source[k];
                    weightedSquares += weight * source[k] * source[k];
                }
                double mean = weighted / weightSum;
                // Renormalised by the weights actually used, so a bin near
                // either end of the grid is compared with the neighbourhood it
                // has rather than with a neighbourhood half of which is missing
                // and silently counted as zero.
                double variance = Math.max(0, weightedSquares / weightSum - mean * mean);
                double deviation = Math.max(Math.sqrt(variance), floor);
                target[bin] = Math.max(0, (source[bin] - mean) / deviation);
            }
        }
        return new LogFrequencySpectrum(out, axis, frameRate);
    }

    private static double[] hammingWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (size - 1));
        }
        return window;
    }

    public int frameCount() {
        return bins.length;
    }
}
