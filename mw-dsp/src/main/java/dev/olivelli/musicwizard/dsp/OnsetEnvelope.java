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
     * <p>At {@link #ONSET_HOP} that corner is 17.5 Hz, and the useful
     * comparison is against what the analysis window can deliver. Measured by
     * amplitude-modulating a tone at 10% depth and reading the band series back
     * at eight times the hop rate -- so the measurement cannot itself alias --
     * with a windowed transform at the modulation rate, the window's own
     * envelope corner is <b>23 to 29 Hz</b> across carriers from 220 Hz to
     * 3 kHz, read on the mel band the tone actually lands in: 28.4 at 440 Hz,
     * 28.8 at 660, 24.2 at 1 kHz, 23.2 at 2 kHz.
     *
     * <p>Read on any other band it is not that at all -- the neighbours of
     * those four give 20.6, 57.1, 21.8 and 23.4 -- so this is a loose quantity
     * and quoting it to three figures, as two earlier versions of this comment
     * did, claims a determinacy it does not have.
     *
     * <p>So the filter is slightly <em>tighter</em> than the window, not
     * matched to it. That is worth stating the right way round, because it
     * names the cost: between 17.5 Hz and the low twenties there is genuine
     * envelope detail being attenuated, which is why attacks come out about
     * half a frame wider and why peakiness falls a little. What it buys is that the filter
     * is well clear of the interference region -- on a held 440 Hz note with
     * eight partials, the bands carrying the note put their energy at the
     * partial differences, 440 Hz and up, and three to five orders of magnitude
     * less in amplitude anywhere between 1 and 340 Hz. (In amplitude, since
     * these are decibel series; six to ten orders in power, which is the same
     * statement.) The topmost bands, 60 to 80 dB down and carrying only
     * leakage, are not so clean -- there the low rates come within about an
     * order of magnitude of the 440 Hz line rather than five.
     *
     * <p>An earlier version of this comment claimed the two corners nearly
     * coincided, at 18.7 Hz against 17.5. That figure came from an unwindowed
     * transform at 50% modulation depth, where leakage flattered it; it does
     * not reproduce. The argument survives the correction but it is a weaker
     * and more honest one -- the value is defensible because it sits between
     * what the window can resolve and where the artefacts live, not because it
     * lands on a landmark.
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
     * <p>Non-finite samples are left alone rather than filtered through.
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
     * <p>So each unbroken run of finite frames is filtered as a series in its
     * own right, and the non-finite frames between runs are left alone. That is
     * the only arrangement of this that survived measurement, and it took three
     * attempts to find, so the two that failed are worth recording:
     *
     * <ul>
     *   <li><b>Skip any band containing a non-finite value.</b> One bad audio
     *       sample reaches all forty bands, so this disabled the filter for the
     *       whole recording and silently restored the bug -- 0.751 again.
     *   <li><b>Hold the last finite value across the run, then restore it.</b>
     *       This removes the step at the far edge, but the frames after a run
     *       have been filtered against a stale value and ramp back over about
     *       ten of them, which is an accent. Swept over hole position, length
     *       and carrier against a swelling sine -- material with nothing
     *       rhythmic in it anywhere -- it reached <b>0.743</b> against a click
     *       floor of 0.751, a margin of 0.008.
     * </ul>
     *
     * <p>Filtering per run has no such edge, because the recursion never sees
     * outside the run it is filtering. Sampled at 1200 points drawn uniformly
     * from holes of 1 to 5 seconds beginning between 1 and 11 seconds, against
     * carriers from 220 Hz to 3 kHz, its worst point is <b>0.533</b> and it
     * beats doing no filtering at all at 48% of them.
     *
     * <p>It does not beat it everywhere, and that is worth stating rather than
     * rounding off: this is still worse than not filtering at <b>33% to 45%</b>
     * of those points and by up to <b>0.32 to 0.40</b>, across four independent
     * draws from the family, and its worst point is above the 0.47 that no
     * filtering reaches. The spread across draws is mostly how ties are counted
     * -- the low figure comes from ignoring differences under 0.05 -- so take
     * the wide end.
     *
     * <p>Those figures are drawn rather than gridded, deliberately. An evenly
     * spaced grid over the same family gave 0.504, 27% and 0.188 -- every one of
     * them flattering, because a grid samples a smooth artefact at its own
     * spacing rather than where it is worst. Three reviewers re-drew this and
     * all three found it worse than the grid said; that is the pattern to
     * expect from any single sample of this residue, including these.
     *
     * <p>And the family itself is not general, which is the shape of claim that
     * has now twice been mistaken for one. A hole that begins at
     * {@code t = 0} and covers most of the clip -- leaving one short run at the
     * end -- reaches <b>0.780, above the click floor</b>, and at 87.31 Hz it is
     * over the floor at 33 of 36 lengths tried between 14 and 17.5 seconds of
     * 20, not at a handful of them. Filtering per run neither causes
     * that nor fixes it: a leading hole leaves exactly one run, so this and the
     * scheme it replaced agree there to four decimal places. The honest summary
     * is that per-run is the best of the three everywhere measured and that no
     * variant is safe against an arbitrary hole.
     *
     * <p>None of this is reachable from a real recording -- a dropout is
     * digital silence, not NaN, and silence is filtered like anything else.
     * Since issue #61 it is not reachable through the pipeline at all:
     * {@code AudioBuffer} and {@code Spectrogram} both reject non-finite
     * values, and {@link #toMelDecibels} floors the band sum before the
     * logarithm, so a spectrogram that gets this far has finite bands by
     * contract. This branch survives because {@code antiAlias} is called
     * directly with hand-built bands, which is where its behaviour is pinned;
     * whether that is worth keeping is issue #76, not a question this file
     * answers on its own.
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
