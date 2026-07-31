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

/**
 * The frequency grid the NNLS front end works on: several bins per semitone,
 * equally spaced in pitch rather than in hertz.
 *
 * <p>Existing under its own name rather than as constants in two places is the
 * point of it. The mapping from the FFT and the dictionary of note profiles are
 * built independently and must land on exactly the same grid — a half-bin
 * disagreement between them shifts every partial in the dictionary off the
 * partial it is meant to explain, and NNLS answers with a plausible-looking
 * combination of the wrong notes. Both derive their geometry from here, so
 * there is one definition to be wrong rather than two to drift apart.
 *
 * <p><b>Tuning lives in the axis.</b> A recording tuned away from A440 is
 * handled by shifting this grid onto the recording's pitch, not by resampling
 * the spectrum onto a nominal one. The two are equivalent, but resampling means
 * a second interpolation over data that has already been interpolated once, and
 * every interpolation widens the peaks that the whole method depends on being
 * narrow. Shifting the grid is free: it changes one addend in
 * {@link #midiOf(double)}.
 *
 * <p>The consequence worth stating, because it is the part that surprises: with
 * the offset in the axis, a note's grid position does <em>not</em> depend on the
 * tuning. Concert-pitch MIDI {@code m} on a recording {@code o} semitones sharp
 * sounds at MIDI {@code m + o}, and this grid's bin for MIDI {@code m + o} is
 * {@code (m − lowestMidi) · binsPerSemitone} whatever {@code o} is. That is why
 * {@link NoteDictionary} can be built once and reused across recordings.
 *
 * @param lowestMidi            concert-pitch MIDI number of bin 0
 * @param binsPerSemitone       resolution; 3 is the value the method is
 *                              specified at, fine enough to separate a slightly
 *                              mistuned note from its neighbour and coarse
 *                              enough to keep the dictionary small
 * @param binCount              number of bins on the grid
 * @param tuningOffsetSemitones how far the recording sits above A440, in
 *                              semitones, in the same sense as
 *                              {@link Chroma#estimateTuning}
 */
public record LogFrequencyAxis(double lowestMidi, int binsPerSemitone, int binCount,
                               double tuningOffsetSemitones) {

    /** MIDI number of A440 itself, the anchor for every pitch-to-hertz conversion. */
    private static final double A440_MIDI = 69;

    private static final double LOG_2 = Math.log(2);

    public LogFrequencyAxis {
        if (binsPerSemitone <= 0) {
            throw new IllegalArgumentException(
                    "binsPerSemitone must be positive, got: " + binsPerSemitone);
        }
        if (binCount <= 0) {
            throw new IllegalArgumentException("binCount must be positive, got: " + binCount);
        }
        if (!Double.isFinite(lowestMidi)) {
            throw new IllegalArgumentException("lowestMidi must be finite, got: " + lowestMidi);
        }
        if (!Double.isFinite(tuningOffsetSemitones)) {
            throw new IllegalArgumentException(
                    "tuningOffsetSemitones must be finite, got: " + tuningOffsetSemitones);
        }
    }

    /**
     * The sounding MIDI pitch at a (possibly fractional) bin position.
     *
     * <p>Fractional because everything that uses this axis works between bins: a
     * partial lands where it lands, and rounding it to a bin before spreading it
     * would quantise the dictionary to a third of a semitone.
     */
    public double midiOf(double bin) {
        return lowestMidi + tuningOffsetSemitones + bin / binsPerSemitone;
    }

    /** The bin position, fractional, of a sounding MIDI pitch. */
    public double binOfMidi(double midi) {
        return (midi - lowestMidi - tuningOffsetSemitones) * binsPerSemitone;
    }

    /** The frequency at a bin position, in hertz. */
    public double frequencyOf(double bin) {
        return 440 * Math.pow(2, (midiOf(bin) - A440_MIDI) / 12);
    }

    /** The bin position of a frequency, fractional and unclamped. */
    public double binOfFrequency(double frequencyHz) {
        return binOfMidi(A440_MIDI + 12 * (Math.log(frequencyHz / 440) / LOG_2));
    }

    /**
     * The bin position of a note the player calls MIDI {@code m}, on this
     * recording.
     *
     * <p>Independent of the tuning offset, as the class comment explains: the
     * note sounds sharp and the grid is sharp by the same amount.
     */
    public double binOfPlayedNote(double midi) {
        return (midi - lowestMidi) * binsPerSemitone;
    }

    /**
     * How wide one FFT bin is on this grid, in bins, at a given position.
     *
     * <p>This is the number that decides whether the grid is telling the truth.
     * An FFT of {@code windowSize} samples has bins {@code sampleRate /
     * windowSize} hertz apart, a constant in hertz — but a semitone is not, so
     * low on the grid one FFT bin straddles several bins and high on it several
     * FFT bins fall inside one. Both the mapping and the dictionary widen their
     * kernels by this amount, which is what keeps a bass note's observed peak
     * and its modelled peak the same shape.
     */
    public double fftBinWidthInBins(double bin, int sampleRate, int windowSize) {
        double frequency = frequencyOf(bin);
        if (frequency <= 0) {
            return binCount;
        }
        // d(bin)/d(f) = binsPerSemitone * 12 / (f ln2), from bin = k log2(f).
        return (double) sampleRate / windowSize * binsPerSemitone * 12 / (frequency * LOG_2);
    }

    /**
     * The kernel both the mapping and the dictionary spread energy with: a
     * raised cosine, one at the centre and zero at {@code ±halfWidth}.
     *
     * <p>Raised cosine rather than triangular or rectangular because the
     * dictionary is matched against real peaks whose position is only known to
     * within the tuning estimate. A kernel with a corner at its peak makes the
     * fit's quality a non-smooth function of that estimate, so a tuning error of
     * a third of a bin costs more than it should; this one is flat at the top
     * and tapers to zero at both ends, so being slightly wrong is slightly
     * wrong.
     *
     * @param offset    distance from the kernel's centre, in bins
     * @param halfWidth distance at which the kernel reaches zero, in bins
     */
    public static double kernel(double offset, double halfWidth) {
        double distance = Math.abs(offset);
        if (!(halfWidth > 0) || distance >= halfWidth) {
            return 0;
        }
        return 0.5 * (1 + Math.cos(Math.PI * distance / halfWidth));
    }
}
