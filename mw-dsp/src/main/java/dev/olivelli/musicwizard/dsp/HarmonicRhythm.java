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

import java.util.Arrays;
import java.util.Objects;

/**
 * How periodically the harmony changes, as evidence about the beat.
 *
 * <p>The onset envelope cannot always name the beat. On {@code bossa-cm.mp3}
 * the comping pattern's accents sit three eighths apart, so the envelope's
 * strongest periodicity is at three eighths of a bar and every window seeds
 * there — #231, and not an octave error, so the perceptual prior cannot touch
 * it. What that pulse cannot do is <em>bar</em> the recording: the chords turn
 * once a bar, and no whole number of three-eighth pulses makes a bar. Harmonic
 * change is the one periodicity the comping cannot corrupt, because it is read
 * from what the notes are rather than from when they are struck.
 *
 * <p>So this measures the recording's harmonic rhythm — the autocorrelation of
 * frame-to-frame chroma change — and answers, for a candidate beat period,
 * whether some whole number of those beats spans a lag the harmony repeats at.
 * {@link TempoEstimator} multiplies the answer into a candidate's score.
 *
 * <p><b>It can favour a beat over its subharmonics, and never the reverse.</b>
 * A half note's lags are a subset of its quarter's — {@code k·2p} is
 * {@code (2k)·p} — so no support the half can find is closed to the quarter,
 * while the quarter can reach lags the half cannot: where the harmonic period
 * is an odd multiple of the beat, the half genuinely cannot bar the music, and
 * is floored. A waltz whose harmony turns every three quarters is the honest
 * case of that. Where the harmonic period is even in beats the two are close
 * to tied, the choice falls to the envelope and the prior as before — and on
 * the bossa it is the <em>envelope</em> that prefers the half, by more than the
 * prior and this factor together prefer the quarter, because the comping
 * states halves of the bar more consistently than quarters. The recording
 * lands at half its true rate: the right family, barred commensurately with
 * the music, where the three-eighths pulse could never be. The remaining
 * halving is exactly what {@code analyze --tempo} corrects, and what #139
 * parked a recorded pulse unit for.
 *
 * <p>Computed once for the whole recording and applied identically in every
 * analysis window, for #305's reason: which pulse can bar the music is a
 * property of the recording, and a per-window answer would let the windows
 * disagree about it.
 */
public final class HarmonicRhythm {

    /**
     * The longest lag read, in seconds. It covers a two-bar harmonic cycle
     * down to about 96 BPM in four, and a one-bar cycle far below that.
     *
     * <p>Harmonic rhythm slower than this exists, but a support read there
     * would be over so few repeats of the cycle that it measures the piece's
     * form rather than its bars.
     */
    private static final double MAX_LAG_SECONDS = 5.0;

    /**
     * The shortest lag read as harmonic rhythm, in seconds. Below this,
     * frame-to-frame chroma change is articulation rather than harmony — a bar
     * shorter than 0.8 s is 300 BPM in four.
     */
    private static final double MIN_LAG_SECONDS = 0.8;

    /**
     * How strong the strongest harmonic periodicity must be before this
     * measurement is allowed to weigh candidates at all, as normalised
     * autocorrelation.
     *
     * <p>Below it every candidate gets the same answer, which is abstention.
     * Measured over the corpus: {@code bm-blues-slow.mp3}, whose harmony moves
     * too slowly to measure, reads 0.095; {@code jazz-251-c-140.mp3}, whose
     * changes are real but drift, reads 0.166; the rest sit at 0.17 to 0.64,
     * including the one-chord vamp, whose comping riff is itself a one-bar
     * harmonic oscillation. The gate sits midway between 0.095 and 0.166. That
     * gap is one recording wide on each side, which is thinner evidence than a
     * plateau; what bounds the risk is the two recordings bracketing it: the
     * one below has no window whose seed changes under forced participation,
     * and the one above has fourteen of twenty-five seeds change and still
     * tracks a beat-for-beat identical grid.
     */
    private static final double GATE = 0.13;

    /**
     * The share of a full support that a candidate keeps when the harmony
     * cannot bar it. Not zero, because the harmonic evidence is one witness
     * and an envelope peak strong enough can still outrank it; small, because
     * a pulse that cannot bar the music should need exactly that.
     */
    static final double FLOOR = 0.05;

    private final double[] correlation;
    private final double frameRate;
    private final double strongestPeak;

    private HarmonicRhythm(double[] correlation, double frameRate, double strongestPeak) {
        this.correlation = correlation;
        this.frameRate = frameRate;
        this.strongestPeak = strongestPeak;
    }

    /**
     * Measures the harmonic rhythm of a recording from frame-level chroma.
     *
     * <p>The chroma must be on its own fixed time grid, not beat-synchronous:
     * this exists to inform the beat estimate, so it cannot presuppose one.
     */
    public static HarmonicRhythm of(Chroma chroma) {
        Objects.requireNonNull(chroma, "chroma");
        if (chroma.isBeatSynchronous()) {
            throw new IllegalArgumentException(
                    "harmonic rhythm is measured before the beats exist, so the chroma"
                            + " must be on its own time grid, not beat-synchronous");
        }
        int frames = chroma.frameCount();
        double frameRate = chroma.frameRate();
        int maxLag = (int) Math.min(frames - 1, Math.ceil(frameRate * MAX_LAG_SECONDS));
        if (frames < 8 || maxLag < 2) {
            return new HarmonicRhythm(new double[0], frameRate, 0);
        }

        // Novelty: how unlike each frame's harmony is to the one before it.
        // Cosine distance of the chroma vectors, so a change of loudness alone
        // is no change of harmony.
        double[] novelty = new double[frames];
        for (int frame = 1; frame < frames; frame++) {
            double dot = 0;
            double normA = 0;
            double normB = 0;
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                double a = chroma.vectors()[frame - 1][pitchClass];
                double b = chroma.vectors()[frame][pitchClass];
                dot += a * b;
                normA += a * a;
                normB += b * b;
            }
            novelty[frame] = normA > 0 && normB > 0 ? 1 - dot / Math.sqrt(normA * normB) : 0;
        }
        // Mean-removed, or the autocorrelation is dominated by the fact that
        // novelty is one-sided rather than by when it recurs.
        double mean = Arrays.stream(novelty).average().orElse(0);
        for (int frame = 0; frame < frames; frame++) {
            novelty[frame] -= mean;
        }

        double[] correlation = new double[maxLag + 1];
        for (int lag = 0; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < frames; i++) {
                sum += novelty[i] * novelty[i + lag];
            }
            correlation[lag] = sum;
        }
        double normaliser = correlation[0] > 0 ? correlation[0] : 1;
        for (int lag = 0; lag <= maxLag; lag++) {
            correlation[lag] /= normaliser;
        }

        double strongest = 0;
        int from = (int) Math.max(1, Math.floor(MIN_LAG_SECONDS * frameRate));
        for (int lag = from + 1; lag < maxLag; lag++) {
            if (correlation[lag] > correlation[lag - 1]
                    && correlation[lag] > correlation[lag + 1]) {
                strongest = Math.max(strongest, correlation[lag]);
            }
        }
        return new HarmonicRhythm(correlation, frameRate, strongest);
    }

    /** A rhythm that supports every candidate equally, for callers without chroma. */
    public static HarmonicRhythm none() {
        return new HarmonicRhythm(new double[0], 1, 0);
    }

    /**
     * The factor a tempo candidate's score is multiplied by: near one where
     * some whole number of this period spans a lag the harmony repeats at,
     * {@link #FLOOR} where none does, and exactly one everywhere when the
     * recording's harmonic rhythm is too weak to measure.
     */
    public double supportFor(double periodSeconds) {
        // Garbage in, abstention out: NaN and sub-millisecond periods are
        // rates no tempo produces, and the identity is the only answer that
        // cannot steer a caller. The bound also keeps the loop below finite --
        // at a tiny period the multiple count would overflow before the lag
        // ever reached the cap.
        if (!(periodSeconds > 0.001)) {
            return 1;
        }
        if (strongestPeak < GATE) {
            return 1;
        }
        double best = 0;
        for (int k = 1; k * periodSeconds <= MAX_LAG_SECONDS; k++) {
            double lag = k * periodSeconds;
            if (lag < MIN_LAG_SECONDS) {
                continue;
            }
            best = Math.max(best, Math.max(0, interpolate(lag * frameRate)));
        }
        return FLOOR + (1 - FLOOR) * Math.min(1, best / strongestPeak);
    }

    private double interpolate(double lag) {
        int index = (int) Math.floor(lag);
        if (index < 0 || index >= correlation.length) {
            return 0;
        }
        if (index + 1 >= correlation.length) {
            return correlation[index];
        }
        double fraction = lag - index;
        return correlation[index] * (1 - fraction) + correlation[index + 1] * fraction;
    }
}
