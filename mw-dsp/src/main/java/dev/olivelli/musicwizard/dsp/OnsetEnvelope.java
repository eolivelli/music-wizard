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
import java.util.Objects;

/**
 * The onset strength envelope: one number per frame saying how much the spectrum
 * just changed.
 *
 * <p>This is the input to tempo estimation and beat tracking, and its quality
 * bounds theirs. The construction follows Ellis (2007): mel-band magnitudes in
 * decibels, low-passed along time, first difference, half-wave rectified,
 * summed across bands, then high-passed and normalised.
 *
 * <p>Each step earns its place. Working in decibels makes a quiet passage
 * contribute as much as a loud one, because perceived accent tracks relative
 * rather than absolute change. Half-wave rectification keeps only increases in
 * energy, since a note ending is not an onset. Mel bands rather than raw FFT
 * bins stop a single strong partial from dominating. Subtracting a moving
 * average removes the slow swell of an arrangement, leaving the sharp local
 * changes that mark note attacks. The low-pass is the least obvious of them and
 * is documented on {@link #antiAlias}.
 *
 * @param strength   one value per frame, mean-zero and unit-variance
 * @param frameRate  frames per second
 */
public record OnsetEnvelope(double[] strength, double frameRate) {

    /**
     * Window and hop used for onset analysis, deliberately different from the
     * spectrogram used for harmony.
     *
     * <p>The hop has to be small. A beat period is not a whole number of frames,
     * so at a coarse hop successive beats land at different sub-frame positions
     * and the resulting amplitude ripple is itself periodic -- at half the beat
     * rate. That is a real periodicity in the envelope, not noise, and it defeats
     * the perceptual prior outright: measured here, a 43 fps envelope of a clean
     * 120 BPM click track estimates 60 BPM, while 86 fps and above estimate 120.
     * Ellis works at roughly 250 fps for the same reason.
     */
    public static final int ONSET_WINDOW = 1024;

    /** About 172 frames per second at the analysis rate, or 5.8 ms per frame. */
    public static final int ONSET_HOP = 128;

    private static final int MEL_BANDS = 40;
    private static final double MIN_HZ = 30;
    private static final double MAX_HZ = 8_000;

    /**
     * Pole of the band-magnitude low-pass, as a fraction of the frame rate.
     * <strong>Not the filter's corner frequency</strong>: the nominal value
     * fed to the one-pole RC discretisation is only the corner far below
     * Nyquist, and this sits near it — the measured corner is several times
     * lower. The value is defensible because the measured corner sits between
     * what the analysis window can resolve and where the interference
     * artefacts live, slightly tighter than the window rather than matched to
     * it; the cost is attacks about half a frame wider. Expressed relative to
     * the frame rate so the filter follows {@link #ONSET_HOP}. (The
     * {@code Resampler}'s identical-looking constant cuts relative to the
     * rate it decimates <em>to</em>, a much gentler filter — the analogy is
     * only in spirit.)
     */
    private static final double ANTI_ALIAS_POLE = 0.45;

    /**
     * Forward-backward passes of the one-pole. At the frame rate's Nyquist,
     * which is where the folded ripple lands, one pass leaves 0.343 of it and
     * two leave 0.118 -- 9.3 dB against 18.6.
     *
     * <p>One pass does separate the populations, so this is a choice about
     * margin rather than about correctness: it leaves the worst held note 0.015
     * under the click floor, where two leave it 0.089 under. Three is no better
     * than two and starts costing attacks. See {@link #antiAlias}.
     */
    private static final int ANTI_ALIAS_STAGES = 2;

    public OnsetEnvelope {
        Objects.requireNonNull(strength, "strength");
        if (!(frameRate > 0)) {
            throw new IllegalArgumentException("frameRate must be positive, got: " + frameRate);
        }
    }

    /**
     * Computes the envelope straight from audio, at the resolution onset
     * analysis needs. Prefer this over building the spectrogram yourself; the
     * harmony spectrogram's hop is far too coarse for rhythm.
     */
    public static OnsetEnvelope fromAudio(AudioBuffer audio) {
        Objects.requireNonNull(audio, "audio");
        return compute(Spectrogram.compute(audio, ONSET_WINDOW, ONSET_HOP));
    }

    /**
     * A recording's summed envelope and its {@link #pulseRegister}, the two
     * readings {@link BeatTracker} takes of one transform.
     *
     * @param envelope      the envelope every stage downstream works from
     * @param pulseRegister the bass register, for the octave decision alone
     */
    public record Both(OnsetEnvelope envelope, OnsetEnvelope pulseRegister) {}

    /**
     * Both readings, from one transform.
     *
     * <p><b>Built here rather than by the caller so that the spectrogram dies
     * at this method's return.</b> At the onset hop it is the largest array in
     * the pipeline — hundreds of megabytes on a long recording, where the
     * envelopes are a few hundred kilobytes — and a caller that holds it in a
     * local to read it twice keeps it live for the whole transcription, which
     * on the phone (#236) is an out-of-memory rather than a nicety.
     */
    public static Both bothFromAudio(AudioBuffer audio) {
        Objects.requireNonNull(audio, "audio");
        Spectrogram spectrogram = Spectrogram.compute(audio, ONSET_WINDOW, ONSET_HOP);
        return new Both(compute(spectrogram), pulseRegister(spectrogram));
    }

    /**
     * Computes the envelope from a spectrogram.
     *
     * <p><b>Not composable over slices.</b> The band floor is a share of the
     * loudest band in the spectrogram it is given, so the envelope of a slice is
     * not the slice of the envelope -- a passage taken on its own is floored
     * against its own peak. Give this the whole recording and slice the result,
     * which is what every caller does.
     */
    public static OnsetEnvelope compute(Spectrogram spectrogram) {
        return compute(spectrogram, MEL_BANDS);
    }

    /**
     * The same envelope read from the lowest bands alone: the register the kick
     * and the bass state the pulse in.
     *
     * <p>It is not a better onset envelope and must not be used as one — a mix's
     * rhythm lives across the spectrum, and reading only the bottom of it loses
     * most of the attacks. What it is for is a question the summed envelope
     * cannot answer, because summing forty bands throws the answer away: whether
     * the instruments that state a pulse play on every beat of a candidate grid
     * or on every second one. See {@link MarkedPulse}.
     *
     * <p>The ceiling is the top of the bass register rather than a band count,
     * so it survives a change to {@link #MEL_BANDS}; a fundamental above it
     * belongs to an instrument that ornaments the pulse rather than states it.
     */
    public static OnsetEnvelope pulseRegister(Spectrogram spectrogram) {
        Objects.requireNonNull(spectrogram, "spectrogram");
        return compute(spectrogram, pulseRegisterBands(spectrogram));
    }

    /** Top of the register {@link #pulseRegister} reads, in hertz. */
    private static final double PULSE_REGISTER_MAX_HZ = 300;

    /** How many mel bands sit under {@link #PULSE_REGISTER_MAX_HZ}. */
    private static int pulseRegisterBands(Spectrogram spectrogram) {
        double minMel = hzToMel(MIN_HZ);
        double maxMel = hzToMel(Math.min(MAX_HZ, spectrogram.sampleRate() / 2.0));
        int bands = 0;
        for (int band = 0; band < MEL_BANDS; band++) {
            // The band's upper edge, which is where toMelDecibels puts it.
            double mel = minMel + (maxMel - minMel) * (band + 2) / (double) (MEL_BANDS + 1);
            if (melToHz(mel) <= PULSE_REGISTER_MAX_HZ) {
                bands++;
            }
        }
        return Math.max(1, bands);
    }

    private static OnsetEnvelope compute(Spectrogram spectrogram, int bands) {
        Objects.requireNonNull(spectrogram, "spectrogram");
        int frames = spectrogram.frameCount();
        if (frames < 2) {
            return new OnsetEnvelope(new double[0], spectrogram.frameRate());
        }

        double[][] melBands = toMelDecibels(spectrogram);
        antiAlias(melBands);

        // First difference, half-wave rectified, summed across bands. Only
        // increases count: a note ending is not an onset.
        double[] flux = new double[frames];
        for (int frame = 1; frame < frames; frame++) {
            double sum = 0;
            for (int band = 0; band < bands; band++) {
                double rise = melBands[frame][band] - melBands[frame - 1][band];
                if (rise > 0) {
                    sum += rise;
                }
            }
            flux[frame] = sum;
        }
        flux[0] = flux.length > 1 ? flux[1] : 0;

        subtractMovingAverage(flux, (int) Math.round(spectrogram.frameRate()));
        normalise(flux);
        return new OnsetEnvelope(flux, spectrogram.frameRate());
    }

    /**
     * How far below the recording's loudest band a band may sit before it is
     * read as silence, in amplitude. Relative rather than absolute, so it
     * means the same thing for a quiet recording as a loud one. On real audio
     * the choice sits on a decades-wide plateau; what stops the floor going
     * higher is the synthetic tempo-confidence fixtures, whose onset contrast
     * <em>is</em> the rise out of digital silence this bounds — a future
     * author finding this constant in the way should question the fixture
     * before lowering the floor. It bounds the silence artefact rather than
     * removing it; removal needs a gate on absolute level, a different
     * change.
     */
    private static final double SILENCE_FLOOR = 1e-8;

    /**
     * Maps FFT bins onto mel bands and converts to decibels.
     *
     * <p>Mel spacing matches how pitch resolution actually works: closely spaced
     * at low frequencies, coarse at high ones. Summing raw FFT bins instead
     * would let one loud high partial swamp the low-frequency evidence that
     * carries most rhythmic information.
     *
     * <p><b>The floor is relative to the recording, and it has to be.</b> A
     * decibel scale is unbounded below, so the step from digital silence to an
     * inaudible sample is a larger rise than any attack in the music above it
     * — a fade-out once produced the largest frame in a whole envelope and
     * read a tempo the recording does not play, and the same artefact sits at
     * the start of most recordings. Bounding a spike also rescales
     * everything: the envelope is normalised to unit variance, and two
     * readers take it absolutely ({@link BeatTracker}'s spacing penalty,
     * {@code DownbeatEstimator}'s accents), so a recording can track
     * differently with no window's tempo seed having moved.
     */
    private static double[][] toMelDecibels(Spectrogram spectrogram) {
        int frames = spectrogram.frameCount();
        int bins = spectrogram.binCount();

        double minMel = hzToMel(MIN_HZ);
        double maxMel = hzToMel(Math.min(MAX_HZ, spectrogram.sampleRate() / 2.0));
        int[] edges = new int[MEL_BANDS + 2];
        for (int i = 0; i < edges.length; i++) {
            double mel = minMel + (maxMel - minMel) * i / (edges.length - 1);
            edges[i] = Math.min(bins - 1, spectrogram.binOf(melToHz(mel)));
        }

        // The linear sums are staged in the array that will hold the decibels,
        // so the floor can be set from the loudest of them without walking the
        // spectrogram twice or allocating anything the caller does not get.
        double[][] out = new double[frames][MEL_BANDS];
        double loudest = 0;
        for (int frame = 0; frame < frames; frame++) {
            float[] magnitudes = spectrogram.magnitudes()[frame];
            for (int band = 0; band < MEL_BANDS; band++) {
                int from = edges[band];
                int to = Math.max(edges[band + 2], from + 1);
                double sum = 0;
                for (int bin = from; bin < to && bin < bins; bin++) {
                    sum += magnitudes[bin];
                }
                out[frame][band] = sum;
                loudest = Math.max(loudest, sum);
            }
        }
        // The absolute term keeps a silent recording finite; the relative one is
        // what bounds the rise out of silence. Whichever is larger wins, so an
        // all-zero spectrogram still maps to a constant rather than to negative
        // infinity, and every band of it is equal, so the difference is zero.
        double floor = Math.max(1e-10, loudest * SILENCE_FLOOR);
        for (double[] frameBands : out) {
            for (int band = 0; band < MEL_BANDS; band++) {
                frameBands[band] = 20 * Math.log10(Math.max(frameBands[band], floor));
            }
        }
        return out;
    }

    /**
     * Low-passes each band's decibel series along time, before anything
     * differences it. Without this a held note reads as rhythmic: partials
     * leaking into one FFT bin beat at their difference frequency, far above
     * the hop's Nyquist, and sampling with no filter in front folds that
     * ripple into a slow, genuinely periodic accent train — an artefact of
     * the sampling, not a property of the sound, and the same failure
     * {@link dev.olivelli.musicwizard.audio.Resampler} low-passes to avoid.
     * Zero phase (forwards then backwards), because beat tracking reads onset
     * <em>times</em> off this signal.
     *
     * <p>The pass count and pole were swept over held notes against click
     * tempi: everything from one pass separates the populations, so the
     * choice is about margin, and filtering harder eats into the attacks
     * until the populations converge again. Filtering the linear magnitude
     * instead of the decibels destroys click attacks outright; oversampling
     * and decimating — the textbook anti-alias — removes the fold rather than
     * the ripple and does not help.
     *
     * <p>Non-finite samples are left alone rather than filtered through: a
     * recursive filter spreads one poisoned value across the whole series,
     * and the flux loop drops a non-finite rise silently, so filtering it
     * costs the entire recording. One bad audio sample poisons all forty
     * bands for its frames, so each unbroken run of finite frames is filtered
     * as a series in its own right — the only arrangement of three that
     * survived measurement (skipping a poisoned band disabled the filter for
     * the whole recording; holding the last finite value ramps back over
     * several frames, which is an accent). Not reachable from a real
     * recording or, since #61, through the pipeline at all; the branch
     * survives because {@code antiAlias} is pinned directly with hand-built
     * bands (#76).
     */
    static void antiAlias(double[][] melBands) {
        if (melBands.length < 2) {
            return;
        }
        // One-pole coefficient for a pole expressed as a fraction of the
        // sampling rate, so it does not depend on the frame rate at all.
        double rc = 1.0 / (2 * Math.PI * ANTI_ALIAS_POLE);
        double alpha = 1.0 / (rc + 1.0);

        double[] series = new double[melBands.length];
        for (int band = 0; band < MEL_BANDS; band++) {
            int frame = 0;
            while (frame < melBands.length) {
                while (frame < melBands.length
                        && !Double.isFinite(melBands[frame][band])) {
                    frame++;
                }
                int start = frame;
                while (frame < melBands.length
                        && Double.isFinite(melBands[frame][band])) {
                    frame++;
                }
                int length = frame - start;
                if (length < 2) {
                    // Nothing a two-tap recursion can do with one sample.
                    continue;
                }
                for (int i = 0; i < length; i++) {
                    series[i] = melBands[start + i][band];
                }
                filterInPlace(series, length, alpha);
                for (int i = 0; i < length; i++) {
                    melBands[start + i][band] = series[i];
                }
            }
        }
    }

    /** The zero-phase cascade, over the first {@code length} samples. */
    private static void filterInPlace(double[] series, int length, double alpha) {
        for (int pass = 0; pass < ANTI_ALIAS_STAGES; pass++) {
            double state = series[0];
            for (int i = 0; i < length; i++) {
                state += alpha * (series[i] - state);
                series[i] = state;
            }
            state = series[length - 1];
            for (int i = length - 1; i >= 0; i--) {
                state += alpha * (series[i] - state);
                series[i] = state;
            }
        }
    }

    /**
     * Removes the slow component by subtracting a moving average, keeping only
     * the rectified remainder.
     *
     * <p>Without this a crescendo reads as one long onset, and a dense
     * arrangement produces a high plateau that buries the individual attacks.
     */
    private static void subtractMovingAverage(double[] signal, int windowFrames) {
        int half = Math.max(1, windowFrames / 2);

        // Prefix sums, so each window average is O(1) and the whole pass is
        // linear rather than quadratic in the window size.
        double[] prefix = new double[signal.length + 1];
        for (int i = 0; i < signal.length; i++) {
            prefix[i + 1] = prefix[i] + signal[i];
        }

        double[] smoothed = new double[signal.length];
        for (int i = 0; i < signal.length; i++) {
            int from = Math.max(0, i - half);
            int to = Math.min(signal.length, i + half + 1);
            smoothed[i] = (prefix[to] - prefix[from]) / (to - from);
        }
        for (int i = 0; i < signal.length; i++) {
            signal[i] = Math.max(0, signal[i] - smoothed[i]);
        }
    }

    private static void normalise(double[] signal) {
        double mean = 0;
        for (double value : signal) {
            mean += value;
        }
        mean /= Math.max(1, signal.length);

        double variance = 0;
        for (double value : signal) {
            variance += (value - mean) * (value - mean);
        }
        double deviation = Math.sqrt(variance / Math.max(1, signal.length));
        if (deviation < 1e-12) {
            // Constant signal, such as silence. Leave it at zero rather than
            // amplifying numerical noise into apparent onsets.
            java.util.Arrays.fill(signal, 0);
            return;
        }
        for (int i = 0; i < signal.length; i++) {
            signal[i] = (signal[i] - mean) / deviation;
        }
    }

    static double hzToMel(double hz) {
        return 2595 * Math.log10(1 + hz / 700);
    }

    static double melToHz(double mel) {
        return 700 * (Math.pow(10, mel / 2595) - 1);
    }

    public int length() {
        return strength.length;
    }

    /** The time of a frame, in seconds. */
    public double timeOf(int frame) {
        return frame / frameRate;
    }

    /** The frame nearest a time. */
    public int frameOf(double seconds) {
        return (int) Math.clamp(Math.round(seconds * frameRate), 0, Math.max(0, strength.length - 1));
    }

    /** True when nothing in the signal looks like an onset. */
    public boolean isFlat() {
        for (double value : strength) {
            if (value > 1e-9) {
                return false;
            }
        }
        return true;
    }
}
