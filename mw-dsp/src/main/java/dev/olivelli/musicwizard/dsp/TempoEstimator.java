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
 */
public final class TempoEstimator {

    /** Where listeners prefer to tap, and so where the prior is centred. */
    public static final double PREFERRED_TEMPO = 120.0;

    /** Prior width in octaves. */
    private static final double PRIOR_WIDTH_OCTAVES = 1.4;

    private static final double MIN_TEMPO = 40;
    private static final double MAX_TEMPO = 240;

    private TempoEstimator() {
    }

    /**
     * The estimated tempo and how strongly the envelope supports it.
     *
     * @param beatsPerMinute the estimate
     * @param strength       fraction of the envelope's energy at that period,
     *                       0 to 1. Zero for silence, and comparable between two
     *                       readings of the same recording. It is <em>not</em> yet
     *                       a reliable rhythmic-versus-arrhythmic discriminator:
     *                       a sustained tone can score as high as a click track,
     *                       because a smooth envelope is self-similar at every
     *                       lag. Do not gate behaviour on an absolute threshold.
     */
    public record Estimate(double beatsPerMinute, double strength) {
        public Estimate {
            if (!(beatsPerMinute > 0)) {
                throw new IllegalArgumentException(
                        "beatsPerMinute must be positive, got: " + beatsPerMinute);
            }
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
            return new Estimate(PREFERRED_TEMPO, 0);
        }

        double frameRate = envelope.frameRate();
        int minLag = (int) Math.max(1, Math.floor(frameRate * 60.0 / MAX_TEMPO));
        int maxLag = (int) Math.min(envelope.length() - 1, Math.ceil(frameRate * 60.0 / MIN_TEMPO));
        if (maxLag <= minLag) {
            return new Estimate(PREFERRED_TEMPO, 0);
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
        // Zero for silence, and useful for ranking two readings of the same
        // recording -- but see the caveat on Estimate.strength: it does not yet
        // reliably separate rhythmic material from merely self-similar material.
        double normaliser = correlation[0] > 0 ? correlation[0] : 1;
        return new Estimate(bestTempo, Math.clamp(bestRawCorrelation / normaliser, 0, 1));
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
