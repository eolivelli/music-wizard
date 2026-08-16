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
import java.util.stream.IntStream;

/**
 * Chroma by way of approximate transcription: what notes, played together,
 * would produce the spectrum actually observed.
 *
 * <p>{@link Chroma} folds the spectrum onto twelve pitch classes directly,
 * which is correct for a synthesised triad and wrong for a record: a real
 * mix's bass partials, overtones, drums and reverb sum to nearly flat, which
 * is how a whole song came back as one N.C. span (#185). This front end puts
 * a transcription step in the way — the spectrum is resampled onto a
 * pitch-linear grid, whitened, and explained as a non-negative combination of
 * idealised note spectra, so a low C's fifth partial is attributed to the low
 * C rather than counted as an E — and only the note activations are folded to
 * chroma. On its own it is not the cure for #185: through the old emission
 * model the answer does not change at all, and what each change is worth is
 * on {@link ChordEstimator}, deliberately not restated here.
 *
 * <p>A separate type rather than a mode of {@code Chroma}: consumers want a
 * value and not a provenance; the two paths want different transforms (this
 * one needs a window long enough to separate adjacent bass semitones); and
 * this produces <em>two</em> chromas that must stay in step —
 * {@link #beatSynchronous(List)} folds both or neither. The plain path stays
 * as it was: it is the fallback, and the tier-0 tests depend on it.
 *
 * @param treble                chroma from the notes in the chordal register
 * @param bass                  chroma from the notes below it, which is where a
 *                              root is played; see {@link #bass()} for what it
 *                              is trusted with and what it is not
 * @param tuningOffsetSemitones the tuning the analysis was run at, in the sense
 *                              of {@link Chroma#estimateTuning}
 */
public record NnlsChroma(Chroma treble, Chroma bass, double tuningOffsetSemitones) {

    /**
     * Shortest analysis window, in seconds.
     *
     * <p>Set by the bass, which is the register the method exists to clean up. A
     * semitone at C2 (65.4 Hz) spans 3.9 Hz and at A0 (27.5 Hz) spans 1.6 Hz, so
     * telling two adjacent bass notes apart needs FFT bins a few hertz apart,
     * which needs a window of a few hundred milliseconds. The cost is time
     * resolution — a third of a second smears a chord change — and that is
     * affordable here precisely because the output is averaged over a beat
     * anyway.
     */
    private static final double WINDOW_SECONDS = 0.37;

    /**
     * Hop as a fraction of the window. The hop inherits
     * {@link #windowSizeFor}'s rounding to a power of two and is therefore
     * not a constant number of milliseconds across sample rates — which
     * matters only to a caller using {@link #extract(AudioBuffer)} on audio
     * at its own rate.
     */
    private static final int HOPS_PER_WINDOW = 8;

    /** Bins per semitone on the analysis grid. */
    private static final int BINS_PER_SEMITONE = 3;

    /** Lowest pitch on the analysis grid and the lowest note modelled: A0. */
    private static final int LOWEST_MIDI = 21;

    /**
     * Highest pitch on the analysis grid: C8.
     *
     * <p>Higher than the highest note modelled, on purpose — the grid has to
     * hold the partials of the notes, not just their fundamentals.
     */
    private static final int HIGHEST_GRID_MIDI = 108;

    /**
     * Highest note modelled: C7.
     *
     * <p>Above this there is nothing chordal, and a dictionary column whose
     * fundamental is near the top of the grid has almost no partials on it, so
     * it is a poorly determined column that mostly competes with the partials of
     * real notes.
     */
    private static final int HIGHEST_NOTE_MIDI = 96;

    /**
     * Register boundaries for the treble and bass folds, in MIDI numbers.
     *
     * <p>The two ranges overlap and cross-fade rather than meeting at a line.
     * Where a bass part stops and a chord's voicing starts is a matter of
     * arrangement, not of physics, and a hard boundary would make a G2 in the
     * left hand of a piano either entirely a root or entirely a chord tone
     * depending on which side of the line the arranger put it.
     */
    private static final int CROSSFADE_LOW_MIDI = 45;   // A2, 110 Hz
    private static final int CROSSFADE_HIGH_MIDI = 57;  // A3, 220 Hz

    /** Where the treble fold tapers off again, so the top octave counts less. */
    private static final int TREBLE_ROLL_OFF_MIDI = 84; // C6

    public NnlsChroma {
        Objects.requireNonNull(treble, "treble");
        Objects.requireNonNull(bass, "bass");
        if (!Double.isFinite(tuningOffsetSemitones)) {
            throw new IllegalArgumentException("tuningOffsetSemitones must be finite, got: "
                    + tuningOffsetSemitones);
        }
        if (treble.frameCount() != bass.frameCount()) {
            throw new IllegalArgumentException("treble has " + treble.frameCount()
                    + " frames and bass has " + bass.frameCount()
                    + "; the two registers describe the same frames");
        }
        // And at the same rate. Without this the pair can disagree about whether
        // it has been beat-synchronised, since that is exactly "frameRate == 0"
        // -- and then combined()'s guard, which asks the treble, is right by
        // luck rather than by construction. Checking it here makes the single
        // question that method asks a sound one.
        if (treble.frameRate() != bass.frameRate()) {
            throw new IllegalArgumentException("treble is at " + treble.frameRate()
                    + " frames per second and bass at " + bass.frameRate()
                    + "; the two registers describe the same frames");
        }
    }

    /**
     * Runs the whole front end over a recording: transform, tuning, grid,
     * whitening, NNLS, fold.
     */
    public static NnlsChroma extract(AudioBuffer audio) {
        Objects.requireNonNull(audio, "audio");
        int windowSize = windowSizeFor(audio.sampleRate());
        Spectrogram spectrogram =
                Spectrogram.compute(audio, windowSize, windowSize / HOPS_PER_WINDOW);
        return extract(spectrogram, Chroma.estimateTuning(spectrogram));
    }

    /**
     * Runs the front end over an already-computed transform with a known tuning.
     *
     * <p>Public so that a caller who has measured the tuning some other way — or
     * who wants to hold it fixed across a set of recordings — can say so. The
     * transform's resolution is the caller's responsibility; see
     * {@link #windowSizeFor(int)} for what this expects.
     */
    public static NnlsChroma extract(Spectrogram spectrogram, double tuningOffsetSemitones) {
        Objects.requireNonNull(spectrogram, "spectrogram");
        if (!Double.isFinite(tuningOffsetSemitones)) {
            throw new IllegalArgumentException("tuningOffsetSemitones must be finite, got: "
                    + tuningOffsetSemitones);
        }

        int binCount = (HIGHEST_GRID_MIDI - LOWEST_MIDI) * BINS_PER_SEMITONE + 1;
        LogFrequencyAxis axis = new LogFrequencyAxis(
                LOWEST_MIDI, BINS_PER_SEMITONE, binCount, tuningOffsetSemitones);
        NoteDictionary dictionary = new NoteDictionary(axis, LOWEST_MIDI, HIGHEST_NOTE_MIDI,
                spectrogram.sampleRate(), spectrogram.windowSize(), true);
        NonNegativeLeastSquares solver = new NonNegativeLeastSquares(dictionary.design());

        double[][] whitened =
                LogFrequencySpectrum.map(spectrogram, axis).whitened().bins();

        double[] trebleWeight = new double[dictionary.noteCount()];
        double[] bassWeight = new double[dictionary.noteCount()];
        for (int note = 0; note < dictionary.noteCount(); note++) {
            trebleWeight[note] = trebleWeightOf(dictionary.midiOf(note));
            bassWeight[note] = bassWeightOf(dictionary.midiOf(note));
        }

        int frames = whitened.length;
        double[][] treble = new double[frames][12];
        double[][] bass = new double[frames][12];

        // One solve per frame, and the frames are independent: the solver holds
        // no state between calls and the dictionary is immutable. Several
        // thousand solves of a 262x77 system is the expensive part of the whole
        // pipeline, and it parallelises exactly.
        IntStream.range(0, frames).parallel().forEach(frame -> {
            double[] activations = solver.solve(whitened[frame]);
            for (int note = 0; note < dictionary.noteCount(); note++) {
                double activation = activations[note];
                if (activation <= 0) {
                    continue;
                }
                // The background column, if present, sits past the notes and is
                // never read: it absorbed energy that is not a pitch, and
                // folding it into a pitch class would undo the point of it.
                int pitchClass = Math.floorMod(dictionary.midiOf(note), 12);
                treble[frame][pitchClass] += trebleWeight[note] * activation;
                bass[frame][pitchClass] += bassWeight[note] * activation;
            }
        });

        double frameRate = spectrogram.frameRate();
        return new NnlsChroma(new Chroma(treble, frameRate), new Chroma(bass, frameRate),
                tuningOffsetSemitones);
    }

    /**
     * Beat-synchronises both registers together.
     *
     * <p>The only way to fold an {@code NnlsChroma}, so that the two registers
     * cannot end up describing different spans of the same recording.
     */
    public NnlsChroma beatSynchronous(List<Double> beatTimes) {
        Objects.requireNonNull(beatTimes, "beatTimes");
        return new NnlsChroma(treble.beatSynchronous(beatTimes),
                bass.beatSynchronous(beatTimes), tuningOffsetSemitones);
    }

    /**
     * Chroma from the bass register, which is where the root of a chord is.
     *
     * <p>Not what a chord is labelled <em>from</em> — {@link #combined()} has
     * the measurement saying so, and this register alone is much worse. What it
     * is used for is the one question the fold cannot answer, which of a chord's
     * own notes is its root, where it is a prior over roots rather than another
     * template to match — {@code ChordEstimator.BASS_ROOT_WEIGHT} carries what
     * that is worth. Naming a bass note outright is what slash chords and
     * inversions will need (#194).
     */
    @Override
    public Chroma bass() {
        return bass;
    }

    /**
     * Both registers added together: one chroma over the whole note range,
     * which is what chord labelling should use. The whole is far more than
     * either part because the two registers fail on different chords —
     * measured, the sum beats each register alone by tens of points of root
     * accuracy, which is not a subtlety that could have been reasoned to; the
     * per-change breakdown is on {@link ChordEstimator} (#232 tracks
     * re-measuring, #193 the corpus evidence). The two weight functions are
     * complementary ramps, so this is a plain unweighted fold over the
     * chordal range; the top note is fitted and discarded, giving the
     * partials below it somewhere to go.
     *
     * <p><b>Call this before {@link #beatSynchronous(List)}, not after.</b>
     * Beat-synchronising normalises each span to sum to one; applied per
     * register, adding them afterwards gives every beat exactly half treble
     * and half bass whatever the two held, so a silent bass votes its noise
     * floor at full strength. Both orders compile and both return a plausible
     * chroma, so the wrong one is refused rather than documented —
     * {@code foldingBeforeAndAfterAreNotTheSame} and
     * {@code refusesToFoldRegistersThatAreAlreadyBeatSynchronous} pin it.
     *
     * @throws IllegalStateException if the registers have already been
     *     beat-synchronised, since folding them then is almost certainly not
     *     what the caller means
     */
    public Chroma combined() {
        if (treble.isBeatSynchronous()) {
            throw new IllegalStateException(
                    "the registers are already beat-synchronous, so adding them now would give"
                            + " each an equal share of every beat whatever it held;"
                            + " call combined() first and beatSynchronous() on the result");
        }
        double[][] out = new double[treble.frameCount()][12];
        for (int frame = 0; frame < out.length; frame++) {
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                out[frame][pitchClass] =
                        treble.vectors()[frame][pitchClass] + bass.vectors()[frame][pitchClass];
            }
        }
        // The frame rate of either register; the constructor has already
        // established that they describe the same frames.
        return new Chroma(out, treble.frameRate());
    }

    /**
     * The FFT size this front end wants at a given sample rate.
     *
     * <p>The smallest power of two spanning {@link #WINDOW_SECONDS}, which is
     * 8192 at the pipeline's 22.05 kHz analysis rate and 16384 at 44.1 kHz.
     * Exposed because {@link #extract(Spectrogram, double)} cannot check what it
     * was given: a transform at half this resolution is not an error, it just
     * quietly cannot separate the bass notes the method is about.
     */
    public static int windowSizeFor(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, got: " + sampleRate);
        }
        int size = 1;
        while (size < sampleRate * WINDOW_SECONDS) {
            size <<= 1;
        }
        return size;
    }

    /** How much a note counts towards the treble chroma, in 0 to 1. */
    private static double trebleWeightOf(int midi) {
        if (midi <= CROSSFADE_LOW_MIDI || midi > HIGHEST_NOTE_MIDI) {
            return 0;
        }
        if (midi < CROSSFADE_HIGH_MIDI) {
            return ramp(midi, CROSSFADE_LOW_MIDI, CROSSFADE_HIGH_MIDI);
        }
        if (midi <= TREBLE_ROLL_OFF_MIDI) {
            return 1;
        }
        return 1 - ramp(midi, TREBLE_ROLL_OFF_MIDI, HIGHEST_NOTE_MIDI);
    }

    /** How much a note counts towards the bass chroma, in 0 to 1. */
    private static double bassWeightOf(int midi) {
        if (midi <= CROSSFADE_LOW_MIDI) {
            return 1;
        }
        if (midi >= CROSSFADE_HIGH_MIDI) {
            return 0;
        }
        return 1 - ramp(midi, CROSSFADE_LOW_MIDI, CROSSFADE_HIGH_MIDI);
    }

    /** A raised-cosine ramp from 0 at {@code from} to 1 at {@code to}. */
    private static double ramp(double value, double from, double to) {
        double position = (value - from) / (to - from);
        return 0.5 - 0.5 * Math.cos(Math.PI * Math.clamp(position, 0, 1));
    }
}
