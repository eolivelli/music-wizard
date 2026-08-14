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
 * <p>{@link Chroma} folds the spectrum onto twelve pitch classes directly, which
 * is correct for a synthesised triad and wrong for a record. #185 measured the
 * gap on a 3:43 commercial recording: taken frame by frame, the cosine of the
 * plain chroma against a flat profile beat its cosine against the best of all
 * twenty-four triads, so "no chord" was the maximum-likelihood answer for the
 * whole song and the estimator dutifully said so for 169 consecutive seconds.
 * The cause is not the estimator. A real mix has a bass whose partials spell out
 * a triad on their own, several instruments' worth of overtones, drums, and
 * reverb, and the sum of all that across twelve pitch classes is close to flat.
 *
 * <p>This front end puts a transcription step in the way. The spectrum is
 * resampled onto a pitch-linear grid, whitened, and then explained as a
 * non-negative combination of idealised note spectra — so a low C's fifth
 * partial is attributed to the low C rather than counted as an E. Only the
 * resulting note activations are folded to chroma. {@link ChordEstimator} still
 * matches templates and decodes with Viterbi and is handed a sharper chroma to
 * do it with.
 *
 * <p>It is not, on its own, the cure for #185, and the measurement is worth
 * stating here rather than only where it was taken. Handed this chroma but left
 * with the estimator it was written for — triads only, no-chord scored as a flat
 * template, the emission sharpness left at 20 — the answer does not change at
 * all: one N.C.
 * span covering 99.9% of {@code samples/gmajorblues.mp3}, which is bit for bit
 * what plain chroma gives. A flat profile scores
 * highest exactly when a frame looks least like music, and sharpening the frame
 * does not stop that. So this stage is worth a great deal and worth it only in
 * company.
 *
 * <p>How much, and how much belongs to which change, is on
 * {@link ChordEstimator} and deliberately not restated here. That figure has now
 * been corrected in three separate review rounds and each time a copy of it was
 * left standing somewhere else; this file linking to the one place it is
 * measured is cheaper than a fourth correction. {@link #combined()} breaks down
 * what this stage contributes by register, which is the part that belongs here.
 *
 * <h2>Why a separate type rather than a mode of {@code Chroma}</h2>
 *
 * <p>Three reasons, in increasing order of how much they would have cost.
 *
 * <p>{@code Chroma} is a value — twelve numbers per frame and a frame rate — and
 * every consumer of it, {@link ChordEstimator} and {@link DownbeatEstimator},
 * wants exactly that and does not care where it came from. A mode flag would put
 * a second, much larger algorithm behind a method whose contract is "fold the
 * spectrum", and give one method two quite different sets of failure modes to
 * document.
 *
 * <p>The two paths do not want the same transform. Plain chroma is happy at
 * 4096/1024; this needs a window about four times longer, because the whole
 * method rests on separating adjacent semitones in the bass and at 4096 samples
 * over 22.05 kHz the FFT bins are 5.4 Hz apart while a semitone at C2 is 3.9 Hz.
 * A mode flag on {@code extract(Spectrogram, double)} would take a spectrogram
 * at whatever resolution the caller had lying around and quietly do a worse job.
 *
 * <p>And this produces <em>two</em> chromas, treble and bass, which have to stay
 * in step. Returned separately they can be beat-synchronised against different
 * beat grids, or one of them forgotten; returned together, {@link
 * #beatSynchronous(List)} folds both or neither.
 *
 * <p>The plain path stays exactly as it was, apart from the #77 fix to its
 * tuning estimate that this depends on. It is the fallback when this one
 * underperforms, and the tier-0 tests depend on it.
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
     * Hop as a fraction of the window.
     *
     * <p>An eighth, so the hop inherits {@link #windowSizeFor}'s rounding to a
     * power of two and is therefore <em>not</em> a constant number of
     * milliseconds: 46 ms at 11.025, 22.05 and 44.1 kHz, 64 ms at 8, 16 and 32
     * kHz, and 85 ms at 24, 48 and 96 kHz. An earlier revision of this comment
     * said "about 46 ms at any rate", which is true at three rates of nine and
     * was the same oversight as the band in {@link Chroma#beatSynchronous}: one
     * reader of {@code windowSizeFor} corrected and the other, in this file, not.
     *
     * <p>The pipeline only ever analyses at 22.05 kHz, so 46 ms is what it
     * actually runs at; the spread matters to a caller using
     * {@link #extract(AudioBuffer)} on audio at its own rate.
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
     * Both registers added together: one chroma over the whole note range.
     *
     * <p>This is what chord labelling should use, and the measurement saying so
     * was not the one expected. Scored per bar against the known twelve-bar
     * cycle of {@code samples/gmajorblues.mp3} — 711 seconds, 26 repetitions of
     * G7 G7 G7 G7 / C7 C7 G7 G7 / D7 C7 G7 D7 — the three folds give:
     *
     * <pre>
     *                    bars whose      G        C        D
     *                    root is right   recall   recall   recall
     *   treble only          42.7%         24%      67%      72%
     *   bass only            51.3%         80%       0%      26%
     *   both                 86.6%         88%      96%      68%
     *   plain chroma         58.9%         32%     100%      91%
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
     * <p>The whole is a good deal more than either part, and the reason is
     * visible in the columns: the two registers fail on different chords. The
     * treble on this recording is mostly a lead line playing a blues scale, so
     * it hears the passing notes rather than the accompaniment and finds the
     * tonic in a quarter of the bars that hold it; the bass hears the
     * accompaniment and never once finds the C7. Added together, most of both
     * errors go — and the sum beats each part by more than thirty points, which
     * is not a subtlety that could have been reasoned to.
     *
     * <p>The fourth row is there because leaving it out would flatter this one.
     * Plain chroma through the same estimator scores 58.9%, so on this recording
     * the transcription step is worth about twenty-eight points rather than the
     * whole difference; the rest belongs to {@link ChordEstimator}'s own changes,
     * whose breakdown is on that class. What the transcription buys is
     * concentrated in the tonic, 32% against 88%, and it gives a little back on
     * the D7 bars. That trade is favourable here, and there is exactly one
     * recording's evidence that it is favourable anywhere (#193).
     *
     * <p>Because the two weight functions are complementary ramps, the sum is
     * one everywhere from A0 to C6 and tapers to zero over the octave above,
     * reaching zero exactly at C7 — so this is a plain unweighted fold of the
     * note activations over the chordal range, and the register split exists for
     * the benefit of callers that want it rather than as a stage of this one.
     * The top note is therefore fitted and then discarded, which is deliberate:
     * it gives the partials of everything below it somewhere to go at the top of
     * the grid without contributing a pitch class of its own.
     *
     * <h4>Call this before {@link #beatSynchronous(List)}, not after</h4>
     *
     * <p>The two orderings are not the same computation and the difference is
     * easy to miss, because both compile and both return a plausible chroma.
     * {@link Chroma#beatSynchronous(List)} scales each span to sum to one. Fold
     * the registers first and that normalisation is applied once, to the whole;
     * beat-synchronise first and it is applied to each register separately, so
     * adding them afterwards gives every beat exactly half treble and half bass
     * — whatever the two actually held. A beat where the bass is silent then
     * counts its noise floor as loudly as the chord above it.
     *
     * <p>Measured on {@code samples/gmajorblues.mp3} before #196 changed that
     * recording's beat grid — see {@link ChordEstimator} and #232 — per-bar root
     * accuracy is 86.6% folding first and 77.7% beat-synchronising first, and
     * the per-chord recall shows why: 88/96/68 for G, C and D against 94/67/38. The wrong
     * order does not fail, it just quietly over-weights whichever register had
     * less to say — which on this recording means promoting a bass register that
     * never once names the C7 to an equal vote in every beat.
     *
     * <p>So the wrong order is refused rather than documented. A comment could
     * not have helped: both orders compile, both return a plausible chroma, and
     * four of this change's own measurement harnesses took the wrong one before
     * two of them disagreed loudly enough to be noticed. The two tests that pin
     * it — {@code foldingBeforeAndAfterAreNotTheSame} and
     * {@code refusesToFoldRegistersThatAreAlreadyBeatSynchronous} — record what
     * the difference is and that it cannot be reached.
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
