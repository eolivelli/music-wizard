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

import java.util.Objects;

/**
 * Idealised spectra for every note the front end can transcribe, as columns of
 * the matrix NNLS fits against.
 *
 * <p>A note is modelled as a harmonic series: energy at the fundamental and at
 * every integer multiple of it, with the {@code h}-th partial scaled by
 * {@code s^(h−1)}. That is a very crude instrument model — real partials are
 * inharmonic, their amplitudes depend on the instrument and on how hard it was
 * struck, and none of that is here. Most of it does not need to be: what the fit
 * needs to know is <em>where</em> the partials are, and every pitched instrument
 * agrees about that.
 *
 * <p>The roll-off is the exception, and is not a detail that can be roughly
 * right. It is what decides whether a note that is not sounding may be used to
 * explain the notes that are, and setting it wrong does not degrade the
 * transcription gracefully — it inverts it. See {@link #PARTIAL_ROLL_OFF}.
 *
 * <p>The method's purpose is worth being concrete about. A bass playing a low C
 * puts strong energy at C, C, G, C, E, G —
 * a major triad, spelt out by one note. Fold that to chroma directly and the
 * bass has voted for a chord nobody played; do it for a bass, a guitar and a
 * voice at once and the twelve pitch classes fill up evenly, which is the flat
 * chroma measured in #185. NNLS is asked which <em>notes</em>, played together,
 * would produce the observed spectrum, and one low C explains all six peaks
 * more cheaply than six separate notes do. The upper five are then not in the
 * chroma at all, because they were never notes.
 *
 * <p>Non-negativity is what makes "more cheaply" mean anything. Allowed negative
 * coefficients, the fit would cancel partials against each other and every note
 * would be slightly on.
 */
public final class NoteDictionary {

    /**
     * Geometric roll-off of the partial amplitudes.
     *
     * <p>0.7 is the figure the method is usually specified at and it is wrong
     * here, which took a measurement to see. The claim it rests on — that a
     * shallower roll-off is harmless because explaining a peak as a fundamental
     * costs less than explaining it as somebody else's partial — is true peak by
     * peak and false over a chord, and a chord is what this is asked about.
     *
     * <p>The arithmetic. Unit-normalised, a column with roll-off {@code s} gives
     * its fundamental weight {@code sqrt(1 − s²)} and its {@code k}-th partial
     * {@code s^(k−1)} times that. At 0.7 the fundamental carries 0.714, the
     * second partial 0.500 and the third 0.350. So a note that is really
     * sounding earns 0.714 from its own fundamental — and a note an octave
     * below it, which is not sounding at all, earns 0.500 from that note's
     * fundamental as its own second partial and 0.350 from a note a fifth above
     * as its third, for 0.850. The absent note wins.
     *
     * <p>Enumerating every note from A0 to C4 against a C4-E4-G4 triad at 0.7
     * confirms which one it is. In order: C3 0.850, C4 0.714 (the real note),
     * C2 0.536, E3 and G3 0.500, F2 and A2 0.350, E2 and G2 0.245, then G#1,
     * D#2, F1, A1 and C1 below 0.18. The winner is the
     * octave below, not something further down — a lower note has more partials
     * landing on the triad but each is worth less, and the product peaks
     * immediately.
     *
     * <p>That is not a hypothesis. Handed three pure sines at C4, E4 and G4 —
     * no partials, nothing below C4 in the signal at all — the dictionary at 0.7
     * put its single largest activation on C3, an octave below anything played,
     * and invented G#1, C2, F2, A2 and E3 besides. C4 itself did not appear.
     *
     * <p>Steeper is not simply better, though, because the error has a mirror
     * image. Too shallow and a note that is not sounding explains the ones that
     * are; too steep and the model under-predicts a real note's own upper
     * partials, so the residual is explained by further notes an octave and a
     * twelfth above it. Both are measured here — sub-harmonic leakage by the
     * share of activation the pure triad keeps on its own three notes, and
     * super-harmonic leakage by what a single harmonic A1 keeps on A1 and by how
     * its energy divides between the two registers — against per-bar root and
     * root-plus-quality accuracy on {@code samples/gmajorblues.mp3}:
     *
     * <pre>
     *   roll-off   triad keeps   A1 keeps   A1 bass    blues    blues
     *              its 3 notes   its own    / treble   root     root+quality
     *     0.70        0.310       0.720      10.62     72.0%      60.8%
     *     0.60        0.489       0.490       4.76     78.3%      75.5%
     *     0.55        0.589       0.395       3.40     83.4%      82.2%
     *     0.50        0.682       0.321       2.40     86.6%      86.3%
     *     0.45        0.764       0.268       1.87     85.4%      85.0%
     *     0.40        0.833       0.233       1.53     85.0%      85.0%
     *     0.30        0.930       0.194       1.11     85.4%      85.4%
     * </pre>
     *
     * <p>The per-bar figures in this table were measured on the beat grid as
     * it was before #196: chroma is averaged per tracked beat, so removing
     * that grid's 1.9% rate error moves them. The comparison the table is
     * for is not in doubt -- the differences down it are tens of points and
     * the anchor moved by one -- but the cells read as current and are not.
     * {@link ChordEstimator} carries the statement of this and #232 tracks
     * re-measuring them.
     *
     * <p>0.50 is taken as the setting, where accuracy peaks on both columns.
     * The peak is not sharp — everything from 0.50 down to 0.30 is within 1.6
     * points — but it is on the side of the range that also keeps a bass note in
     * the bass register, so the two considerations agree rather than having to
     * be traded.
     *
     * <p>What this costs is stated rather than buried: at 0.70 a low A kept 72%
     * of its activation on itself and ten times as much energy in the bass
     * register as in the treble, and at 0.50 those are 32% and 2.4. The
     * <em>pitch class</em> survives — which is why chord accuracy improves — but
     * the octave often does not, so {@link NnlsChroma#bass()} is a good deal
     * weaker than it was and naming a bass note from it is not yet safe (#194).
     *
     * <p>One recording sets the shape of the accuracy curve here, not the value
     * of its peak. Calibrating on a tier-2 corpus is #193.
     */
    public static final double PARTIAL_ROLL_OFF = 0.50;

    /**
     * Number of partials modelled per note.
     *
     * <p>Twelve reaches three and a half octaves above the fundamental, by which
     * point the amplitude is negligible at any roll-off this uses and the
     * partials are closer together than the grid can resolve. Partials that fall
     * off the top of the grid are dropped rather than wrapped, so a high note
     * simply has fewer of them.
     */
    private static final int PARTIALS = 12;

    /**
     * Half-width of a modelled partial, in FFT bins.
     *
     * <p>A sinusoid seen through a Hann window is not a spike. Its main lobe
     * runs from null to null over four FFT bins, so ±2 either side of the true
     * frequency, and {@link LogFrequencySpectrum#map} then spreads each of those
     * bins a further ±½ bin as it resamples. Two and a half is the sum of the
     * two.
     *
     * <p>Getting this wrong in the narrow direction is not a rounding error, it
     * is a different algorithm. Held at a constant 1.0 bin — which is what a
     * grid-shaped kernel looks like if the FFT's own resolution is forgotten —
     * the modelled peak at A1 is 2.5 log-frequency bins wide against an observed
     * peak of 6.4, so NNLS cannot cover one real peak with one note and spends
     * three adjacent ones on it instead. Deriving the width from the transform,
     * as {@link LogFrequencyAxis#fftBinWidthInBins} does, is what keeps the
     * modelled and observed peaks the same shape at every frequency.
     *
     * <p>The cost is honest rather than hidden: at 55 Hz this is ±2.1 semitones,
     * so the model genuinely cannot tell A1 from G1 by its fundamental. It is
     * not supposed to. That is what the upper partials are for — they are
     * resolved twelve times better in pitch terms, being twelve times higher in
     * frequency — and it is why fitting a whole harmonic series at once beats
     * reading off the strongest bin.
     */
    private static final double PARTIAL_HALF_WIDTH_FFT_BINS = 2.5;

    private final LogFrequencyAxis axis;
    private final int lowestMidi;
    private final int noteCount;
    private final double[][] profiles;
    private final boolean hasBackgroundColumn;

    /**
     * Builds the dictionary for a grid and a note range.
     *
     * @param axis            the grid the profiles are laid out on
     * @param lowestMidi      lowest note modelled, inclusive
     * @param highestMidi     highest note modelled, inclusive
     * @param sampleRate      sample rate the spectrum came from
     * @param windowSize      FFT size the spectrum came from; with the sample
     *                        rate this fixes how wide an observed peak is, and
     *                        therefore how wide a modelled one has to be
     * @param backgroundColumn whether to append a flat column; see
     *                        {@link #hasBackgroundColumn()}
     */
    public NoteDictionary(LogFrequencyAxis axis, int lowestMidi, int highestMidi,
                          int sampleRate, int windowSize, boolean backgroundColumn) {
        this(axis, lowestMidi, highestMidi, sampleRate, windowSize, backgroundColumn,
                PARTIAL_ROLL_OFF);
    }

    /**
     * Builds the dictionary with a stated roll-off.
     *
     * <p>Exists because {@link #PARTIAL_ROLL_OFF} is the one number in this
     * model that materially changes what it transcribes, and the table in its
     * javadoc had to be produced by varying it. A caller with a corpus of its
     * own should be able to reproduce that sweep rather than take the figure on
     * trust.
     *
     * @param partialRollOff geometric decay of the partial amplitudes, in (0, 1)
     */
    public NoteDictionary(LogFrequencyAxis axis, int lowestMidi, int highestMidi,
                          int sampleRate, int windowSize, boolean backgroundColumn,
                          double partialRollOff) {
        this.axis = Objects.requireNonNull(axis, "axis");
        if (!(partialRollOff > 0) || !(partialRollOff < 1)) {
            throw new IllegalArgumentException(
                    "partialRollOff must be in (0, 1), got: " + partialRollOff);
        }
        if (highestMidi < lowestMidi) {
            throw new IllegalArgumentException("highestMidi " + highestMidi
                    + " is below lowestMidi " + lowestMidi);
        }
        if (sampleRate <= 0 || windowSize <= 0) {
            throw new IllegalArgumentException("sampleRate and windowSize must be positive, got: "
                    + sampleRate + ", " + windowSize);
        }
        this.lowestMidi = lowestMidi;
        this.noteCount = highestMidi - lowestMidi + 1;
        this.hasBackgroundColumn = backgroundColumn;

        int bins = axis.binCount();
        int columns = noteCount + (backgroundColumn ? 1 : 0);
        double[][] design = new double[bins][columns];

        for (int note = 0; note < noteCount; note++) {
            double[] profile = new double[bins];
            for (int partial = 1; partial <= PARTIALS; partial++) {
                double centre = axis.binOfPlayedNote(lowestMidi + note)
                        + 12 * (Math.log(partial) / Math.log(2)) * axis.binsPerSemitone();
                if (centre < 0 || centre > bins - 1) {
                    continue;
                }
                double halfWidth = Math.max(1, PARTIAL_HALF_WIDTH_FFT_BINS
                        * axis.fftBinWidthInBins(centre, sampleRate, windowSize));
                double amplitude = Math.pow(partialRollOff, partial - 1);

                int from = Math.max(0, (int) Math.ceil(centre - halfWidth));
                int to = Math.min(bins - 1, (int) Math.floor(centre + halfWidth));
                double total = 0;
                for (int bin = from; bin <= to; bin++) {
                    total += LogFrequencyAxis.kernel(bin - centre, halfWidth);
                }
                if (total <= 0) {
                    continue;
                }
                // Each partial contributes its amplitude in total, however many
                // bins it is spread over. Otherwise a partial low on the grid --
                // where the kernel is widest -- would be worth several times one
                // high on it, purely because of the FFT's resolution.
                for (int bin = from; bin <= to; bin++) {
                    profile[bin] += amplitude * LogFrequencyAxis.kernel(bin - centre, halfWidth)
                            / total;
                }
            }
            // Unit-norm columns. NNLS compares columns by how much residual each
            // removes, which is a dot product, so a column with a larger norm
            // wins on size rather than on shape -- and a note whose partials all
            // fit on the grid has a much larger norm than one near the top whose
            // partials mostly do not. Normalising makes the comparison about
            // fit.
            double norm = 0;
            for (double value : profile) {
                norm += value * value;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int bin = 0; bin < bins; bin++) {
                    design[bin][note] = profile[bin] / norm;
                }
            }
        }

        if (backgroundColumn) {
            double flat = 1.0 / Math.sqrt(bins);
            for (int bin = 0; bin < bins; bin++) {
                design[bin][noteCount] = flat;
            }
        }
        this.profiles = design;
    }

    /**
     * The design matrix, {@code [bin][column]}, shared rather than copied.
     *
     * <p>Handed straight to {@link NonNegativeLeastSquares}, which copies it.
     */
    public double[][] design() {
        return profiles;
    }

    /**
     * Whether a flat column is appended after the notes.
     *
     * <p>It is not a note and its activation is discarded. It exists so that
     * broadband energy — cymbals, breath, reverb, the noise floor of a
     * compressed mix — has somewhere to go other than into notes. Without it,
     * NNLS must explain a raised floor with pitches, and the cheapest way to
     * raise a floor with pitches is to turn on a great many of them, which is
     * the flat chroma the whole front end is trying to avoid.
     *
     * <p>Whitening already subtracts a local mean, so much of the floor is gone
     * before the fit sees it. This catches what survives, which is the part that
     * varies faster than the whitening window.
     */
    public boolean hasBackgroundColumn() {
        return hasBackgroundColumn;
    }

    /** Number of note columns, excluding any background column. */
    public int noteCount() {
        return noteCount;
    }

    /** MIDI number of note column {@code index}. */
    public int midiOf(int index) {
        return lowestMidi + index;
    }

    /** The grid the profiles are laid out on. */
    public LogFrequencyAxis axis() {
        return axis;
    }
}
