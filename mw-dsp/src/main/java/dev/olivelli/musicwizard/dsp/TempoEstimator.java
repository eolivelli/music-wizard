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

    private static final double MIN_TEMPO = 40;
    private static final double MAX_TEMPO = 240;

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
     * {@link #strength()} is the product, and that is what callers should gate
     * on. Needing both is not the same as both being enough: see the limits
     * documented on {@code strength()}.
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
         * How much to trust this reading, 0 to 1, and the only one of the three
         * numbers worth comparing against an absolute threshold.
         *
         * <p>The product rather than an average, because the two components are
         * a conjunction: material that fails either one is not rhythmic, and an
         * average would let a sustained tone's near-perfect self-similarity
         * carry it. Zero for silence.
         *
         * <p><strong>What this reliably detects is the absence of onset
         * structure, and only that.</strong> Smooth material collapses by two
         * orders of magnitude and stays collapsed: a sustained sine 0.004, a
         * crescendo 0.011, silence 0. That is the discrimination this measure
         * exists for and it is robust. Everything above that floor overlaps
         * pairwise, and the overlaps are not tuning problems:
         *
         * <ul>
         *   <li><b>Steady click tracks, 0.48 to 0.95.</b> Swept over every
         *       integer tempo from 60 to 200 BPM at 20 s the span is 0.53 to
         *       0.93, non-monotone in tempo — 105 BPM scores 0.59 against 110
         *       BPM's 0.90. The floor also depends on clip length, and rises
         *       with it: 0.48 at 10 s, 0.53 at 20 s, 0.56 at 40 s, with the
         *       worst tempo itself moving from 64 to 78 BPM. So the floor is a
         *       function of duration, not a constant.
         *   <li><b>Clicks at random intervals, 0.04 to 0.12</b> over 200 seeds —
         *       and a click track drifting by ±8%, which is real music, sits at
         *       0.12 as well. There is no threshold between arrhythmic material
         *       and rubato.
         *   <li><b>A held note with ordinary vibrato, 0.61 to 0.64.</b> 50 cents
         *       at 2 Hz scores 0.61 and outranks 17 of those 141 click tempi; at
         *       7 Hz it scores 0.64 and outranks 25, reporting 140 BPM — a third
         *       of the 420 BPM modulation rate, which is above the 240 BPM top
         *       of the search range and so cannot be reported at all, making the
         *       reading a subharmonic of a wobble rather than any beat.
         *       Sustained chords of pure sines land at 0.47. Periodic modulation
         *       of one note is a genuinely periodic train of accents, and nothing
         *       measurable in an onset envelope distinguishes it from a beat.
         *       Issue #43 has the measurements and two refuted fixes.
         * </ul>
         *
         * <p>So: gate on it to ask "is there any onset structure at all", where
         * the two populations are three decades apart. Do not gate on it to rank
         * how rhythmic material is — there is no threshold that separates
         * arrhythmic from rubato, or a vibrato pad from a metronome, and a
         * mid-range cut is wrong in both directions at once. Any threshold meant
         * for real music has to be chosen against tier-2 audio, not against
         * synthetic figures.
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
        Objects.requireNonNull(envelope, "envelope");
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
            double score = value * perceptualWeight(tempo);
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
        Objects.requireNonNull(envelope, "envelope");
        int from = Math.max(0, fromFrame);
        int to = Math.min(envelope.length(), toFrame);
        if (to - from < 8) {
            return estimate(envelope);
        }
        double[] slice = new double[to - from];
        System.arraycopy(envelope.strength(), from, slice, 0, slice.length);
        return estimate(new OnsetEnvelope(slice, envelope.frameRate()));
    }
}
