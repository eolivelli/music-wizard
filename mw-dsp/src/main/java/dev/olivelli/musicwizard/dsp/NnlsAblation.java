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
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * {@link PitchClassAblation} over the same fit {@link NnlsChroma} folds: the
 * same grid, the same dictionary, the same whitened spectrum, refitted with one
 * pitch class's columns deleted.
 *
 * <p>Held per frame until {@link #beatSynchronous(List)}, then per beat, so a
 * chord span the estimator has decided on can be re-explained in one solve
 * rather than one per frame. The span's spectrum is the mean of its frames,
 * which is also what makes the measurement cheap enough to run where the
 * quality is decided (#208): thirteen solves a span, against one a frame for
 * the front end itself.
 */
public final class NnlsAblation implements PitchClassAblation {

    /**
     * Squared residual below which a span is reported as explaining nothing.
     *
     * <p>Relative to the observation's own energy, and it is division by zero
     * that is being avoided rather than a small number: a silent span fits
     * perfectly, so every deletion costs nothing and the ratio is 0/0.
     */
    private static final double RESIDUAL_FLOOR = 1e-12;

    /** Summed whitened spectra, {@code [frame or span][bin]}. */
    private final double[][] spectra;

    /** How many frames each entry of {@link #spectra} sums. */
    private final int[] frames;

    private final double frameRate;

    /** The design with each pitch class deleted, and at index 12 the full one. */
    private final double[][][] designs;

    private final NonNegativeLeastSquares[] solvers;

    private NnlsAblation(double[][] spectra, int[] frames, double frameRate,
                         double[][][] designs, NonNegativeLeastSquares[] solvers) {
        this.spectra = spectra;
        this.frames = frames;
        this.frameRate = frameRate;
        this.designs = designs;
        this.solvers = solvers;
    }

    /**
     * Builds the ablation for a transform whose tuning is known.
     *
     * <p>Takes the same transform and tuning as
     * {@link NnlsChroma#extract(Spectrogram, double)} and rebuilds the same
     * model from them, so what it deletes is what the chroma was folded from.
     * Handed a different transform it measures a different fit, silently.
     */
    public static NnlsAblation extract(Spectrogram spectrogram, double tuningOffsetSemitones) {
        Objects.requireNonNull(spectrogram, "spectrogram");
        if (!Double.isFinite(tuningOffsetSemitones)) {
            throw new IllegalArgumentException("tuningOffsetSemitones must be finite, got: "
                    + tuningOffsetSemitones);
        }
        LogFrequencyAxis axis = NnlsChroma.analysisAxis(tuningOffsetSemitones);
        NoteDictionary dictionary = NnlsChroma.analysisDictionary(axis, spectrogram);
        double[][] whitened = LogFrequencySpectrum.map(spectrogram, axis).whitened().bins();

        double[][] full = dictionary.design();
        int bins = axis.binCount();
        int columns = full[0].length;
        double[][][] designs = new double[13][][];
        designs[12] = full;
        for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
            double[][] design = new double[bins][columns];
            for (int bin = 0; bin < bins; bin++) {
                for (int column = 0; column < columns; column++) {
                    // The background column is past the notes and stays: it is
                    // not a pitch, and deleting it would change what every
                    // ablation is measured against.
                    boolean deleted = column < dictionary.noteCount()
                            && Math.floorMod(dictionary.midiOf(column), 12) == pitchClass;
                    design[bin][column] = deleted ? 0 : full[bin][column];
                }
            }
            designs[pitchClass] = design;
        }
        NonNegativeLeastSquares[] solvers = new NonNegativeLeastSquares[13];
        for (int i = 0; i < 13; i++) {
            solvers[i] = new NonNegativeLeastSquares(designs[i]);
        }

        int[] frames = new int[whitened.length];
        java.util.Arrays.fill(frames, 1);
        return new NnlsAblation(whitened, frames, spectrogram.frameRate(), designs, solvers);
    }

    /**
     * Sums the frames of each inter-beat span, so that span indices here are
     * the span indices of a {@link Chroma#beatSynchronous(List)} taken over the
     * same beats.
     *
     * <p>Summed rather than normalised, unlike the chroma fold: the fit is
     * positively homogeneous, so scaling a span's spectrum scales its residuals
     * with it and leaves every ratio this reports unchanged — while normalising
     * per beat first would give a beat that fell silent the same weight in a
     * span as one that did not.
     */
    public NnlsAblation beatSynchronous(List<Double> beatTimes) {
        Objects.requireNonNull(beatTimes, "beatTimes");
        if (beatTimes.size() < 2 || frameRate <= 0 || spectra.length == 0) {
            return this;
        }
        int spans = beatTimes.size() - 1;
        int bins = spectra[0].length;
        double[][] out = new double[spans][bins];
        int[] counts = new int[spans];
        for (int span = 0; span < spans; span++) {
            // The same mapping Chroma.beatSynchronous uses, so that span i here
            // and span i there cover the same frames.
            int from = (int) Math.floor(beatTimes.get(span) * frameRate);
            int to = (int) Math.ceil(beatTimes.get(span + 1) * frameRate);
            from = Math.clamp(from, 0, Math.max(0, spectra.length - 1));
            to = Math.clamp(to, from + 1, spectra.length);
            for (int frame = from; frame < to; frame++) {
                for (int bin = 0; bin < bins; bin++) {
                    out[span][bin] += spectra[frame][bin];
                }
                counts[span] += frames[frame];
            }
        }
        return new NnlsAblation(out, counts, 0, designs, solvers);
    }

    /**
     * Number of analysis frames, or of spans once
     * {@link #beatSynchronous(List)} has been applied.
     */
    @Override
    public int spanCount() {
        return spectra.length;
    }

    @Override
    public double[] significanceOver(int fromSpan, int toSpan) {
        if (fromSpan < 0 || toSpan > spectra.length || fromSpan >= toSpan) {
            throw new IndexOutOfBoundsException("spans [" + fromSpan + ", " + toSpan
                    + ") are not inside the " + spectra.length + " this covers");
        }
        double[] out = new double[12];
        int bins = spectra[0].length;
        double[] mean = new double[bins];
        int counted = 0;
        for (int span = fromSpan; span < toSpan; span++) {
            for (int bin = 0; bin < bins; bin++) {
                mean[bin] += spectra[span][bin];
            }
            counted += frames[span];
        }
        double energy = 0;
        for (int bin = 0; bin < bins; bin++) {
            mean[bin] /= Math.max(1, counted);
            energy += mean[bin] * mean[bin];
        }

        double[] residual = new double[13];
        IntStream.range(0, 13).parallel().forEach(i -> residual[i] = residualOf(i, mean));
        double base = residual[12];
        if (!(base > RESIDUAL_FLOOR * energy)) {
            // Nothing is left over for a pitch class to be the only explanation
            // of, so no pitch class is one.
            return out;
        }
        for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
            out[pitchClass] = Math.max(0, (residual[pitchClass] - base) / base);
        }
        return out;
    }

    /** Squared residual of the fit against design {@code index}. */
    private double residualOf(int index, double[] observation) {
        double[][] design = designs[index];
        double[] activations = solvers[index].solve(observation);
        double squared = 0;
        for (int bin = 0; bin < design.length; bin++) {
            double predicted = 0;
            double[] row = design[bin];
            for (int column = 0; column < row.length; column++) {
                predicted += row[column] * activations[column];
            }
            double error = observation[bin] - predicted;
            squared += error * error;
        }
        return squared;
    }
}
