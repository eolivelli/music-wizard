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
     *
     * <p><strong>This is not the filter's corner frequency.</strong> It is the
     * nominal frequency fed to the one-pole RC discretisation, which is only
     * the corner while it is far below Nyquist -- and 0.45 is 90% of Nyquist,
     * where it is nothing of the kind. Measured on the filter as built, two
     * forward-backward passes are 3 dB down at <b>0.102 of the frame rate</b>,
     * 6 dB down at 0.154, and 18.6 dB down at Nyquist. Model it as 0.45 and you
     * will be wrong by a factor of four.
     *
     * <p>At {@link #ONSET_HOP} that corner is 17.5 Hz, which is worth knowing
     * because of what it nearly equals: {@code sampleRate / ONSET_WINDOW} is
     * 21.5 Hz, the fastest envelope change a 1024-sample window can express at
     * all. Anything faster in the band series is interference between partials
     * rather than the note doing something, so the filter turns out to pass
     * approximately what the window can resolve and stop approximately where
     * the artefacts begin. That is a coincidence of the arithmetic rather than
     * a design, but it is why this value is defensible as something other than
     * the number that scored best.
     *
     * <p>Expressed relative to the frame rate rather than in hertz, so the
     * filter follows {@link #ONSET_HOP} instead of having to be re-tuned with
     * it. Verified at 64, 128 and 256 samples of hop, and at 44.1 kHz as well
     * as 22.05 kHz.
     *
     * <p>{@link dev.olivelli.musicwizard.audio.Resampler} uses 0.45 too, but
     * the analogy is only in the spirit: it cuts at 0.45 of the rate it is
     * about to decimate <em>to</em>, which against its source rate is a much
     * gentler filter. There is no decimation here, so this 0.45 is of the
     * current rate.
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

    /** Computes the envelope from a spectrogram. */
    public static OnsetEnvelope compute(Spectrogram spectrogram) {
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
            for (int band = 0; band < MEL_BANDS; band++) {
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
     * Maps FFT bins onto mel bands and converts to decibels.
     *
     * <p>Mel spacing matches how pitch resolution actually works: closely spaced
     * at low frequencies, coarse at high ones. Summing raw FFT bins instead
     * would let one loud high partial swamp the low-frequency evidence that
     * carries most rhythmic information.
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

        double[][] out = new double[frames][MEL_BANDS];
        for (int frame = 0; frame < frames; frame++) {
            float[] magnitudes = spectrogram.magnitudes()[frame];
            for (int band = 0; band < MEL_BANDS; band++) {
                int from = edges[band];
                int to = Math.max(edges[band + 2], from + 1);
                double sum = 0;
                for (int bin = from; bin < to && bin < bins; bin++) {
                    sum += magnitudes[bin];
                }
                // Floored before the logarithm so silence maps to a finite value
                // rather than negative infinity.
                out[frame][band] = 20 * Math.log10(Math.max(sum, 1e-10));
            }
        }
        return out;
    }

    /**
     * Low-passes each band's decibel series along time, before anything
     * differences it.
     *
     * <p>Without this a held note reads as rhythmic. A band's magnitude is not
     * the smooth amplitude envelope it is taken for: two partials that both
     * leak into the same FFT bin beat there at their difference frequency,
     * which for a harmonic note is hundreds of hertz. The analysis window sets
     * how loud that ripple is, not how fast it runs, so the band series carries
     * modulation far above the {@value #ONSET_HOP}-sample hop's 86 Hz Nyquist --
     * and it was sampled at that hop with no filter in front of it. What lands
     * in the sampled series is the folded remainder, and half-wave
     * rectification of the first difference then demodulates it into a slow,
     * genuinely periodic accent train. Measured on a held 440 Hz note with
     * eight partials, the tempo band holds about a hundred times more energy
     * sampled at 172 fps than at 1378 fps: it is an artefact of the sampling,
     * not a property of the sound.
     *
     * <p>This is the same failure {@link
     * dev.olivelli.musicwizard.audio.Resampler} already low-passes to avoid
     * when it downsamples audio. The band-magnitude envelope simply never got
     * the same treatment.
     *
     * <p>Zero phase, by filtering forwards and then backwards, for the reason
     * {@code Resampler} does it: beat tracking reads onset <em>times</em> off
     * this signal, and a one-directional filter would push every one of them
     * later by a frequency-dependent amount.
     *
     * <p>Measured over 170 held notes (C2 to C6 by minor thirds, 1 to 10
     * partials) against the 141 integer click tempi from 60 to 200 BPM, on
     * twenty-second clips:
     *
     * <pre>
     *   passes  pole     click range    worst held   margin   out-scores
     *   none        --   0.526..0.931        0.751   -0.225    72 of 141
     *   1        0.45f   0.728..0.922        0.713   +0.015     0 of 141
     *   2        0.45f   0.751..0.909        0.662   +0.089     0 of 141
     *   3        0.45f   0.732..0.893        0.679   +0.053     0 of 141
     *   4        0.45f   0.700..0.880        0.689   +0.011     0 of 141
     *   2        0.30f   0.737..0.895        0.680   +0.057     0 of 141
     *   2        0.20f   0.696..0.877        0.684   +0.012     0 of 141
     *   3        0.30f   0.688..0.875        0.708   -0.020    10 of 141
     * </pre>
     *
     * <p>Everything from one pass upwards separates the populations, so the
     * choice is about margin, and two passes at 0.45 of the frame rate has the
     * widest of them by a factor of two. Filtering harder is not better: it
     * eats into the attacks, so the click floor falls faster than the held-note
     * ceiling and the two populations converge again -- three passes at 0.30
     * has them crossing.
     *
     * <p>Filtering the linear magnitude instead of the decibels was tried and
     * is much worse -- clicks at 200 BPM collapse to 0.0, because between clicks
     * the magnitude sits on the decibel floor and smearing the impulse across
     * that floor destroys the attack. Oversampling the spectrogram and
     * decimating after the filter -- the textbook anti-alias, which removes the
     * folded components rather than the ripple that carries them -- was tried at
     * four times the hop rate and is no better at the worst point (0.718), for
     * four times the STFT cost. The ripple, not the fold, is what has to go.
     *
     * <p>Non-finite samples are held over rather than filtered through.
     * Recursive filters spread one poisoned value across the whole series, and
     * the flux loop downstream drops a non-finite rise silently because
     * {@code rise > 0} is false for NaN -- so filtering it costs the entire
     * recording rather than the frames it arrived in. Measured: a single NaN
     * sample took a 120 BPM click track from 0.865 to a flat envelope.
     *
     * <p>The blast radius is worth stating exactly, because it is larger than
     * it looks. One bad audio sample lands in every FFT bin of the eight
     * windows containing it, so it poisons all forty bands for those frames,
     * not one -- which means skipping a poisoned <em>band</em> would have
     * disabled this filter for the whole recording and quietly restored the
     * behaviour of the bug it fixes, at 0.751 for the worst held note.
     *
     * <p>So each non-finite sample takes the value of the last finite one
     * before it for the duration of the filtering -- which keeps the recursion
     * clean -- and is then put back as it was. Both halves are needed. Holding
     * alone leaves a step at the far edge of a poisoned run, and differencing a
     * step manufactures an onset that is not in the recording: measured on a
     * click track with a half-second hole, an accent at 53% of the height of a
     * real click, where the unfiltered code produced nothing at all. Restoring
     * the original makes that difference non-finite again, so the flux loop
     * drops it exactly as it always did, and the damage really does stay in the
     * frames where it arrived.
     */
    static void antiAlias(double[][] melBands) {
        if (melBands.length < 2) {
            return;
        }
        // One-pole coefficient for a cutoff expressed as a fraction of the
        // sampling rate, so it does not depend on the frame rate at all.
        double rc = 1.0 / (2 * Math.PI * ANTI_ALIAS_POLE);
        double alpha = 1.0 / (rc + 1.0);

        double[] series = new double[melBands.length];
        for (int band = 0; band < MEL_BANDS; band++) {
            int first = firstFiniteFrame(melBands, band);
            if (first < 0) {
                // Nothing to hold over, so nothing to filter either.
                continue;
            }
            double held = melBands[first][band];
            for (int frame = 0; frame < melBands.length; frame++) {
                double value = melBands[frame][band];
                if (Double.isFinite(value)) {
                    held = value;
                }
                series[frame] = held;
            }
            for (int pass = 0; pass < ANTI_ALIAS_STAGES; pass++) {
                double state = series[0];
                for (int frame = 0; frame < series.length; frame++) {
                    state += alpha * (series[frame] - state);
                    series[frame] = state;
                }
                state = series[series.length - 1];
                for (int frame = series.length - 1; frame >= 0; frame--) {
                    state += alpha * (series[frame] - state);
                    series[frame] = state;
                }
            }
            for (int frame = 0; frame < melBands.length; frame++) {
                // Only where the input was finite. Putting the held value back
                // would hand the flux a step at the far edge of a poisoned run
                // and so an onset that is not in the recording; leaving the
                // original there means the difference across that edge is
                // non-finite and gets dropped, which is what happened before
                // this filter existed.
                if (Double.isFinite(melBands[frame][band])) {
                    melBands[frame][band] = series[frame];
                }
            }
        }
    }

    /**
     * The first frame in which a band is finite, or -1 if it never is.
     *
     * <p>Needed so that a run of non-finite frames at the very start has
     * something to take its value from: holding the previous sample has no
     * previous sample to hold.
     */
    private static int firstFiniteFrame(double[][] melBands, int band) {
        for (int frame = 0; frame < melBands.length; frame++) {
            if (Double.isFinite(melBands[frame][band])) {
                return frame;
            }
        }
        return -1;
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
