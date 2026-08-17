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
 * <p>The prior cannot do it alone, because two adjacent octaves differ by well
 * under a factor of two under it while an accented beat's autocorrelation can
 * prefer the half by more than that. So candidates are ranked on the envelope
 * with its accents' range compressed — {@link #compressAccents} — and the
 * confidence reported alongside is still read from the envelope itself.
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
     *                       drift — a gently wandering click track scores a
     *                       small fraction of a rigid one.
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
         * How much to trust this reading, 0 to 1. The product rather than an
         * average because the components are a conjunction — an average would
         * let a sustained tone's near-perfect self-similarity carry it.
         *
         * <p><strong>Do not gate on this; it separates nothing
         * reliably.</strong> Swept rather than sampled, sustained material
         * still reaches deep into the click-track range: held notes with
         * harmonics span most of it depending on the exact partials, a pure
         * sine's score depends only on its pitch, vibrato and drifting or
         * random clicks land mid-range, and material below the minimum tempo
         * scores exactly zero while a fast track survives via a subharmonic.
         * The scatter is partial-beating folding into the tempo band (#49 has
         * the diagnosis and four refuted fixes). The only supported claims:
         * zero for silence, and comparable between two readings of the same
         * recording. Per-window averaging also under-reports near a window
         * boundary (#47).
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
     * with the envelope and the prior — see {@link #compressAccents} for what
     * the envelope contributes to that choice.
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
        double[] searchCorrelation =
                autocorrelate(compressAccents(envelope.strength()), maxLag + 1);

        // Search over tempo, not over integer lag. A beat period is almost
        // never a whole number of frames, so sampling only integer lags
        // misaligns the fundamental while landing
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
            double value = interpolate(searchCorrelation, lag);
            double score = value * perceptualWeight(tempo);
            // Only a score that is in the running is weighed. A negative
            // correlation is already a non-candidate, and multiplying it by a
            // support below one would raise it: the floor exists to demote,
            // and on a negative score it promotes -- an envelope of one attack
            // over a quiet floor could then land on the very candidate the
            // harmony cannot bar, because it was floored.
            if (score > 0) {
                score *= rhythm.supportFor(60.0 / tempo);
            }
            if (score > bestScore) {
                bestScore = score;
                bestTempo = tempo;
                // Read from the envelope itself, not from the compressed copy
                // the candidates were ranked on: periodicity is reported as a
                // share of the envelope's own energy, and every figure quoted
                // for it is a measurement of that envelope.
                bestRawCorrelation = interpolate(correlation, lag);
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
     * How many standard deviations of the envelope an accent may contribute to
     * the candidate search before it is held level with the others.
     *
     * <p>It has to be low enough to level the accents themselves rather than
     * to trim outliers above them — the loud beats are exactly what it
     * levels.
     *
     * <p>The two ends fail differently. Low enough, the ceiling starts
     * levelling the quiet frames between beats too, and other recordings
     * begin to move; high enough, the loudest accents come back through and
     * the correction lapses, window by window before it goes altogether.
     * {@code tools/TempoOctave.java} takes the ceiling as an argument and
     * prints, per window, where its reproduction and the estimator disagree
     * — individual windows move on both sides well before any whole-recording
     * reading does, so that printout, not an interval quoted here, is the
     * measurement to nudge this constant against.
     *
     * <p>Expressed in deviations because {@link OnsetEnvelope} normalises to
     * unit variance over the recording, so this is a share of the envelope's own
     * scale rather than a level in any absolute unit. It is not a bound on how
     * large a frame can be: the largest frame of most recordings here is tens of
     * deviations and sits in the lead-in, which is the silence artefact
     * {@code OnsetEnvelope.SILENCE_FLOOR} documents rather than an attack.
     */
    private static final double ACCENT_CEILING = 3.0;

    /**
     * The envelope with its loudest accents held level, as the candidates are
     * ranked on.
     *
     * <p><b>Why the ranking does not read the envelope directly.</b> Metre is
     * accent alternation: a bar states its beats at unequal strengths, and that
     * is what a listener hears the bar by. Autocorrelation reads it as evidence
     * for the half. Write {@code A} and {@code a} for the strengths a strong and
     * a weak beat contribute and {@code r} for {@code A/a}: the beat's own lag
     * pairs each strong beat with a weak one and collects {@code A·a}, while the
     * half's lag pairs like with like and collects {@code (A²+a²)/2}. Their ratio
     * is {@code (r + 1/r)/2}, which is 1 only when the beats are equally loud and
     * grows without bound as the accent deepens. <b>So the more clearly a
     * recording states its metre, the harder its own autocorrelation argues for
     * half the beat</b> — and the perceptual prior separates two adjacent octaves
     * by well under a factor of two, so it cannot answer an accent ratio of 2 or
     * more (#349).
     *
     * <p><b>A ceiling rather than a compression curve, and the difference is
     * measured rather than aesthetic.</b> Any monotone map that shrinks {@code r}
     * shrinks the bias — a root, a log, a power — and every one of them corrects
     * the same recording here. They differ in what else they do: a curve that
     * shrinks the top also <em>raises the floor</em>, and the quiet frames
     * between the beats are where a subdivision lives. On
     * {@code cm-blues-68-95.mp3}, whose 6/8 groove states every eighth quietly,
     * a square root promotes the eighth to the tracked pulse; a ceiling leaves
     * that recording's reading alone, because it never touches the floor. The
     * bias this exists to remove is at the loud end, so the correction belongs
     * at the loud end.
     *
     * <p>What a ceiling shares with the curves is a cost near the top of the
     * tempo range: a click track has no accent to level, but flattening its
     * peaks still changes their shape, and where a click's beat and its half are
     * all but tied that moves a few of them onto the half (#44). Measured, it is
     * the same tempi either way, so it is the cost of correlating a modified
     * envelope at all rather than of this choice within it.
     *
     * <p><b>The mean is deliberately not removed afterwards.</b> Levelling does
     * not preserve a zero mean, and an autocorrelation of a signal with a mean
     * adds the same positive constant at every lag, which under the perceptual
     * prior drags the winner toward 120 BPM — so removing it looks like the
     * tidy thing to do. Two measurements say otherwise. The constant a ceiling
     * leaves is a fraction of a percent of the compressed variance, where a
     * curve leaves tens of times more; and the estimate is taken over a
     * <em>window</em>, whose slice of a mean-zero envelope has a mean of its
     * own that this method has no business removing — done here it moved two
     * recordings' grids by a bar each, which is a change to windowing rather
     * than to the accent bias this is about. A curve needs the removal and
     * cannot have it cheaply; that is one more reason the correction is a
     * ceiling (#353).
     */
    private static double[] compressAccents(double[] signal) {
        double[] out = new double[signal.length];
        for (int i = 0; i < signal.length; i++) {
            out[i] = Math.clamp(signal[i], -ACCENT_CEILING, ACCENT_CEILING);
        }
        return out;
    }

    /**
     * Whether the envelope's own ranking puts half of a rate above it, over one
     * window — the comparison {@link #estimate} makes before the prior and the
     * harmonic support are multiplied in.
     *
     * <p>{@link MarkedPulse} asks this because the register may only restore a
     * preference the envelope had, never invent one: a recording whose
     * autocorrelation ranks its tracked rate above the half is one where the
     * prior did not overturn anything, and a sparse register saying otherwise
     * is saying something about the bass part rather than about the beat.
     *
     * <p>False where the window is too short to hold the longer lag, since
     * there is then no comparison to make.
     */
    static boolean ranksHalfAbove(OnsetEnvelope envelope, int fromFrame, int toFrame,
                                  double beatsPerMinute) {
        int from = Math.max(0, fromFrame);
        int to = Math.min(envelope.length(), toFrame);
        double frameRate = envelope.frameRate();
        double halfLag = frameRate * 60.0 / (beatsPerMinute / 2);
        if (!(beatsPerMinute > 0) || to - from <= halfLag + 1) {
            return false;
        }
        double[] slice = new double[to - from];
        System.arraycopy(envelope.strength(), from, slice, 0, slice.length);
        double[] ranked = autocorrelate(compressAccents(slice), (int) Math.ceil(halfLag) + 1);
        return interpolate(ranked, halfLag) > interpolate(ranked, halfLag / 2);
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

    /**
     * Unnormalised autocorrelation up to a maximum lag.
     *
     * <p>Direct rather than by FFT: the longest lag searched is one beat at
     * {@link #MIN_TEMPO}, a second and a half of envelope, so the quadratic
     * cost is small and the code stays obvious.
     */
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
