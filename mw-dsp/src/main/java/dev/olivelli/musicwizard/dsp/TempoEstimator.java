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
 * Estimates tempo by autocorrelating the onset envelope.
 *
 * <p>A rhythmic signal correlates with itself at the beat period and at every
 * multiple of it, which is exactly the problem: the raw autocorrelation peak is
 * as likely to sit at half or double the true tempo as on it. This is the
 * dominant failure of tempo estimation, far more common than being merely
 * imprecise, and it cannot be fixed by looking harder at the signal — the signal
 * genuinely is periodic at both.
 *
 * <p>The tie is broken with a perceptual prior: a log-Gaussian window centred at
 * 120 BPM, which is roughly where listeners prefer to tap. Ellis (2007) uses a
 * width of about 1.4 octaves, wide enough not to force everything toward 120 and
 * narrow enough to reject the 60 and 240 aliases.
 *
 * <p>Confidence is reported as two numbers rather than one, because two
 * independent things have to hold before a tempo reading is worth trusting and
 * they fail separately. See {@link Estimate}.
 */
public final class TempoEstimator {

    /** Where listeners prefer to tap, and so where the prior is centred. */
    public static final double PREFERRED_TEMPO = 120.0;

    /** Prior width in octaves. */
    private static final double PRIOR_WIDTH_OCTAVES = 1.4;

    /**
     * The range of rates this estimator will return, and so the range any
     * consumer correcting one of its answers must stay inside.
     *
     * <p>Package-private rather than private because {@link BeatTracker} divides
     * a subdivision out of a window's seed and must stop where the estimator
     * itself would have: a corrected rate outside this range is one no window
     * could have been seeded with in the first place.
     */
    static final double MIN_TEMPO = 40;

    /** See {@link #MIN_TEMPO}. */
    static final double MAX_TEMPO = 240;

    /**
     * The kurtosis of Gaussian noise, and so the reference point for "no
     * impulsive structure at all".
     *
     * <p>This is the only constant in the peakiness formula, and it is a
     * mathematical property of the reference distribution rather than a
     * threshold chosen to make some particular signal pass.
     */
    private static final double NOISE_KURTOSIS = 3.0;

    private TempoEstimator() {
    }

    /**
     * The estimated tempo and how strongly the envelope supports it.
     *
     * <p>Neither confidence component means anything on its own, which is why
     * both are carried. Periodicity says the envelope repeats at the winning
     * period; peakiness says the envelope is built from localised events rather
     * than a continuous wash. A sustained tone has the first without the second,
     * because a smooth envelope is self-similar at every lag — that is exactly
     * how a 440 Hz sine used to out-score a metronome. A recording of unrelated
     * bangs has the second without the first. Rhythmic material needs both, so
     * {@link #strength()} is the product. Needing both is not the same as both
     * being enough, and in practice it is not enough: read the limits on
     * {@code strength()} before gating anything on it.
     *
     * @param beatsPerMinute the estimate
     * @param periodicity    fraction of the envelope's energy at the winning
     *                       period, 0 to 1. Comparable between two readings of
     *                       the same recording, and highly sensitive to tempo
     *                       drift — a click track wandering by ±8% scores about
     *                       0.12 where a rigid one scores 0.85.
     * @param peakiness      how concentrated the envelope's departures from its
     *                       own mean are, 0 to 1: 0 when they are spread no more
     *                       thinly than noise spreads them, approaching 1 for
     *                       isolated attacks. Nearly independent of tempo and of
     *                       clip length, so it reads as a property of the
     *                       material rather than of the reading.
     */
    public record Estimate(double beatsPerMinute, double periodicity, double peakiness) {
        public Estimate {
            if (!(beatsPerMinute > 0)) {
                throw new IllegalArgumentException(
                        "beatsPerMinute must be positive, got: " + beatsPerMinute);
            }
            if (!(periodicity >= 0) || periodicity > 1) {
                throw new IllegalArgumentException(
                        "periodicity must be within 0..1, got: " + periodicity);
            }
            if (!(peakiness >= 0) || peakiness > 1) {
                throw new IllegalArgumentException(
                        "peakiness must be within 0..1, got: " + peakiness);
            }
        }

        /**
         * How much to trust this reading, 0 to 1.
         *
         * <p>The product rather than an average, because the two components are
         * a conjunction: material that fails either one is not rhythmic, and an
         * average would let a sustained tone's near-perfect self-similarity
         * carry it. Zero for silence.
         *
         * <p><strong>Do not gate on this. It is an improvement in the average
         * case and it separates nothing reliably.</strong> Issue #26 reported a
         * sustained 440 Hz sine scoring 0.96 against a click track's 0.85; that
         * fixture now scores 0.004. But swept rather than sampled, sustained
         * material still reaches deep into the click-track range of 0.48 to
         * 0.95:
         *
         * <ul>
         *   <li><b>A held note with harmonics scores anywhere from 0.00 to
         *       0.75</b>, over a grid of fundamentals from C2 to C6 and 1 to 10
         *       partials, at constant amplitude with no attack and no
         *       modulation. The worst point — 440 Hz with 8 partials — out-scores
         *       72 of the 141 integer click tempi from 60 to 200 BPM. On
         *       {@code main} it out-scored 135, so this is a real improvement,
         *       but the residue is half the range rather than a tail.
         *   <li><b>A pure sine scores 0.00 to 0.47</b> depending only on pitch:
         *       0.004 at 440 Hz, 0.47 at 87 Hz, 0.20 at 262 Hz, 0.18 at 3520 Hz.
         *       A crescendo tracks it within 0.005 at every pitch. There is no
         *       "featureless material collapses" rule — the 440 Hz collapse is a
         *       coincidence of that pitch.
         *   <li><b>Held notes with vibrato, 0.61 to 0.64</b> (issue #43);
         *       <b>clicks at random intervals reach 0.12</b> over 200 seeds, as
         *       does a click track drifting ±8%, which is real music.
         *   <li><b>Material below 40 BPM scores exactly zero</b>, indistinguishable
         *       from silence: a clean 30 BPM metronome reads 0.000 with a
         *       peakiness of 0.992. So does any clip too short to hold several
         *       periods. Above 240 BPM it does not go to zero — a 300 BPM click
         *       track reads 0.82 via a subharmonic.
         * </ul>
         *
         * <p>The scatter is not noise and not tuning: partials beat at hundreds
         * of hertz, the envelope is sampled at 172 fps with no low-pass before
         * it, and the beating folds into the tempo band at a rate that depends
         * on the exact frequencies present. Neighbouring inputs therefore score
         * very differently — 440 Hz with 6 partials scores 0.57 and with 8
         * scores 0.75. Issue #49 has the diagnosis and four refuted fixes.
         *
         * <p>So the only claims this supports are the two it started with:
         * it is <b>zero for silence</b>, and it is <b>comparable between two
         * readings of the same recording</b>. Any absolute threshold — "is this
         * rhythmic", "is this arrhythmic", "warn the user" — is unsupported in
         * both directions by the measurements above.
         *
         * <p>These figures are for {@link TempoEstimator#estimate} over a whole
         * clip. {@link BeatTracker} averages a per-window strength and reports
         * materially less on recordings just past a window boundary — a clean
         * 78 BPM click track measures 0.53 here and 0.36 through
         * {@code BeatGrid.beatConfidence} at 26 seconds, because the trailing
         * one-second sliver is averaged in at full weight. See issue #47.
         */
        public double strength() {
            return periodicity * peakiness;
        }

        /** Seconds between beats at this tempo. */
        public double beatPeriodSeconds() {
            return 60.0 / beatsPerMinute;
        }
    }

    /** Estimates the global tempo of an onset envelope. */
    public static Estimate estimate(OnsetEnvelope envelope) {
        return estimate(envelope, HarmonicRhythm.none());
    }

    /**
     * Estimates the global tempo, weighing each candidate by whether the
     * recording's harmony can be barred by it.
     *
     * <p>The envelope alone cannot always name the beat: a comping pattern
     * whose accents sit three eighths apart gives the autocorrelation its
     * strongest peak there, and three eighths of a bar is not an octave of the
     * beat, so the perceptual prior cannot correct it (#231). What that pulse
     * cannot do is bar the harmony, and {@link HarmonicRhythm} measures exactly
     * that. Candidates the harmony cannot be barred by keep
     * {@link HarmonicRhythm#FLOOR} of their score; everything else is
     * unchanged, including the choice between a beat and its half, which stays
     * with the envelope and the prior.
     */
    public static Estimate estimate(OnsetEnvelope envelope, HarmonicRhythm rhythm) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(rhythm, "rhythm");
        if (envelope.length() < 8 || envelope.isFlat()) {
            // Nothing periodic to find. Report the prior with no confidence
            // rather than a confident reading of noise.
            return new Estimate(PREFERRED_TEMPO, 0, 0);
        }

        double frameRate = envelope.frameRate();
        int minLag = (int) Math.max(1, Math.floor(frameRate * 60.0 / MAX_TEMPO));
        int maxLag = (int) Math.min(envelope.length() - 1, Math.ceil(frameRate * 60.0 / MIN_TEMPO));
        if (maxLag <= minLag) {
            return new Estimate(PREFERRED_TEMPO, 0, 0);
        }

        double[] correlation = autocorrelate(envelope.strength(), maxLag + 1);

        // Search over tempo, not over integer lag. A beat period is almost never
        // a whole number of frames -- 120 BPM at this frame rate is 21.53 -- so
        // sampling only integer lags misaligns the fundamental while landing
        // squarely on its double, which manufactures exactly the half-tempo
        // error the perceptual prior is meant to resolve.
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestTempo = PREFERRED_TEMPO;
        double bestRawCorrelation = 0;

        double step = 0.25;
        for (double tempo = MIN_TEMPO; tempo <= MAX_TEMPO; tempo += step) {
            double lag = frameRate * 60.0 / tempo;
            if (lag < minLag || lag > maxLag) {
                continue;
            }
            double value = interpolate(correlation, lag);
            double score = value * perceptualWeight(tempo) * rhythm.supportFor(60.0 / tempo);
            if (score > bestScore) {
                bestScore = score;
                bestTempo = tempo;
                bestRawCorrelation = value;
            }
        }

        // The fraction of the envelope's energy explained by the winning period.
        // Necessary for a trustworthy reading but nowhere near sufficient: a
        // sustained tone scores higher here than a metronome does, which is why
        // peakiness is measured alongside it.
        double normaliser = correlation[0] > 0 ? correlation[0] : 1;
        double periodicity = bestRawCorrelation / normaliser;
        if (!Double.isFinite(periodicity)) {
            // Autocorrelation squares the envelope, so samples above about 1e154
            // overflow some lags to infinity while others stay finite, and the
            // ratio comes out Infinity/Infinity. Math.clamp passes NaN straight
            // through, so without this the record constructor throws on input
            // that is merely absurd rather than malformed. Report no evidence,
            // which is what the rest of this method does with input it cannot
            // read.
            periodicity = 0;
        }
        return new Estimate(bestTempo, Math.clamp(periodicity, 0, 1),
                peakiness(envelope.strength()));
    }

    /**
     * How concentrated an envelope's departures from its own mean are, on 0 to 1.
     *
     * <p>Derived from kurtosis, read as an effective duty cycle. For a signal
     * that is off most of the time and on for a fraction {@code p} of frames,
     * the reciprocal of the kurtosis tends to {@code p} as {@code p} gets small
     * — the exact value is {@code p(1-p) / ((1-p)³ + p³)} — and small is the
     * regime that matters, since attacks are brief. Measured here, a 120 BPM
     * click track has a kurtosis of about 85, a duty cycle of 1.2% — and a beat
     * there is 86 frames, so that is one frame per beat, which is what a click
     * physically occupies. A sustained sine measures 3.0, a duty cycle of a
     * third, meaning its envelope is spread out exactly as noise would be.
     *
     * <p>Expressing that duty cycle relative to the noise value is what turns an
     * open-ended moment into a fraction, and it puts the formula's only constant
     * somewhere principled: {@link #NOISE_KURTOSIS} is a property of the Gaussian
     * distribution, not a threshold picked to make a test pass.
     *
     * <p>Two consequences of that framing worth knowing. It measures
     * concentration, not sharpness in the everyday sense: a signal that is on
     * almost always and briefly off scores identically to its inverse, because
     * kurtosis cannot tell a spike from a hole. And it floors at zero once the
     * duty cycle passes 21.1% — the root of {@code 6p² - 6p + 1} — so accents
     * occupying more than about a fifth of the gap between them read as no
     * evidence at all rather than as weak evidence. That is a fraction of the
     * repetition period, not a fixed span: 106 ms at 120 BPM, 211 ms at 60.
     * Neither is reachable through {@link OnsetEnvelope}, whose moving-average
     * subtraction and rectification leave attacks a few frames wide, but both
     * bound what this may be reused for.
     *
     * <p>Computed about the signal's own mean rather than assuming the unit
     * variance {@link OnsetEnvelope} normalises to, because
     * {@link #estimateWindow} passes a slice of an envelope normalised over the
     * whole recording, and a slice of a mean-zero signal is not mean-zero.
     */
    static double peakiness(double[] signal) {
        if (signal.length < 2) {
            return 0;
        }
        double mean = 0;
        for (double value : signal) {
            mean += value;
        }
        mean /= signal.length;

        // Deviations are scaled by the largest of them before the moments are
        // taken. Kurtosis is scale-invariant so this cannot change the answer,
        // but it earns its keep three times over: the flat-signal test becomes
        // exact rather than an absolute epsilon, which would have called a
        // correctly-shaped but very quiet signal flat; a fourth power of an
        // unbounded input cannot overflow; and a non-finite sample poisons
        // `largest` and is rejected here, rather than propagating to the record
        // constructor to be reported as an out-of-range peakiness.
        double largest = 0;
        for (double value : signal) {
            largest = Math.max(largest, Math.abs(value - mean));
        }
        if (!(largest > 0) || !Double.isFinite(largest)) {
            // Constant, such as silence -- no events at all rather than sharp
            // ones -- or malformed. Both tests are needed, and the second is not
            // the redundancy it looks like. A NaN sample poisons the mean, which
            // makes every deviation NaN, and NaN fails the first test. But
            // samples near Double.MAX_VALUE are all finite and still overflow
            // the mean to infinity, leaving `largest` infinite rather than NaN;
            // infinity passes the first test, and the scaled deviations then
            // come out Infinity/Infinity = NaN one step later.
            //
            // Without this the record constructor rejected the result as an
            // out-of-range peakiness, blaming the measure for a malformed input.
            return 0;
        }

        double secondMoment = 0;
        double fourthMoment = 0;
        for (double value : signal) {
            double scaled = (value - mean) / largest;
            double squared = scaled * scaled;
            secondMoment += squared;
            fourthMoment += squared * squared;
        }
        secondMoment /= signal.length;
        fourthMoment /= signal.length;

        // At least one scaled deviation is exactly 1, so the second moment is at
        // least 1/length and the division below is safe.
        double kurtosis = fourthMoment / (secondMoment * secondMoment);
        return Math.clamp(1 - NOISE_KURTOSIS / kurtosis, 0, 1);
    }

    /**
     * The perceptual prior: a Gaussian in log-tempo centred on 120 BPM.
     *
     * <p>Working in log space is what makes it symmetric between halving and
     * doubling, which is the whole point — 60 and 240 should be penalised
     * equally relative to 120.
     */
    static double perceptualWeight(double beatsPerMinute) {
        double octavesFromCentre = Math.log(beatsPerMinute / PREFERRED_TEMPO) / Math.log(2);
        double normalised = octavesFromCentre / PRIOR_WIDTH_OCTAVES;
        return Math.exp(-0.5 * normalised * normalised);
    }

    /**
     * Unnormalised autocorrelation up to a maximum lag.
     *
     * <p>Direct rather than by FFT: at 43 frames per second a lag of 1.5 seconds
     * is only about 65 samples, so the quadratic cost is tiny and the code stays
     * obvious.
     */
    /** Linear interpolation of the correlation at a fractional lag. */
    private static double interpolate(double[] correlation, double lag) {
        int low = (int) Math.floor(lag);
        int high = low + 1;
        if (low < 0 || high >= correlation.length) {
            return 0;
        }
        double fraction = lag - low;
        return correlation[low] + (correlation[high] - correlation[low]) * fraction;
    }

    private static double[] autocorrelate(double[] signal, int maxLag) {
        double[] out = new double[maxLag + 1];
        for (int lag = 0; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < signal.length; i++) {
                sum += signal[i] * signal[i + lag];
            }
            // Divided by the FULL length, not the overlap. Normalising by the
            // overlap looks fairer -- long lags do sum fewer terms -- but it
            // inflates them, because the terms that remain are the strongly
            // periodic ones. That inflation is enough to make the half-tempo
            // lag beat the true one even with the perceptual prior applied, and
            // half-tempo is precisely the error the prior exists to prevent.
            out[lag] = sum / signal.length;
        }
        return out;
    }

    /**
     * Estimates tempo within a window, for pieces whose tempo drifts.
     *
     * <p>The beat tracker assumes one tempo, so on a long recording it is run
     * over overlapping windows with the tempo re-estimated in each.
     */
    public static Estimate estimateWindow(OnsetEnvelope envelope, int fromFrame, int toFrame) {
        return estimateWindow(envelope, fromFrame, toFrame, HarmonicRhythm.none());
    }

    /**
     * The same, with the recording's harmonic rhythm weighed in.
     *
     * <p>The rhythm is measured over the whole recording and applied unchanged
     * in every window, deliberately: which pulse can bar the music is a
     * property of the recording, and letting windows answer it separately is
     * how a majority of misled windows outvotes a correct one (#305).
     */
    public static Estimate estimateWindow(OnsetEnvelope envelope, int fromFrame, int toFrame,
                                          HarmonicRhythm rhythm) {
        Objects.requireNonNull(envelope, "envelope");
        int from = Math.max(0, fromFrame);
        int to = Math.min(envelope.length(), toFrame);
        if (to - from < 8) {
            return estimate(envelope, rhythm);
        }
        double[] slice = new double[to - from];
        System.arraycopy(envelope.strength(), from, slice, 0, slice.length);
        return estimate(new OnsetEnvelope(slice, envelope.frameRate()), rhythm);
    }
}
