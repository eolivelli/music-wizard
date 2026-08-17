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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Whether a candidate pulse is the one the bass register states, or a
 * subdivision of it.
 *
 * <p>{@link TempoEstimator} decides the octave from the summed onset envelope
 * and the perceptual prior, and there is a texture neither can read. Where an
 * arrangement runs eighths in the hi-hat and a piano figure while the kick and
 * the side-stick mark the quarters, the envelope has an event at every eighth;
 * the quarters are louder, which is evidence for them, and
 * {@link TempoEstimator#compressAccents} exists to level exactly that — it is
 * how a recording whose <em>metre</em> is stated by accent stops arguing for
 * its own half. Levelled, the two grids look alike and the prior takes the
 * faster one (#509).
 *
 * <p>The evidence the summed envelope discards is <em>which instruments</em>
 * play on the beats it discards. A recording is normally not ambiguous about
 * this: the kick and the bass state the pulse and the cymbals and the comping
 * ornament it, so reading the bottom of the spectrum alone — the
 * {@link OnsetEnvelope#pulseRegister} — answers whether a candidate grid's
 * beats are all stated or only every second one. That question needs the
 * grid's phase, which is why it is asked of tracked beats rather than of an
 * autocorrelation, which has none.
 *
 * <p><b>It answers in one direction only.</b> Halving a grid asks whether the
 * beats the tracker found are stated, which is a question about a grid that
 * exists. Doubling one would ask whether the register states beats
 * <em>between</em> them, and that is measured by forcing the dynamic program
 * to twice the rate, where it places beats on whatever energy is there: over
 * the corpus a one-chord vamp's comping then reads as strongly as a walking
 * bass, so the two populations do not separate (measured on #353's
 * recordings and on this corpus's controls; the readings are on #509).
 *
 * <p><b>The register may restore the envelope's preference, never invent
 * one.</b> Read alone it cannot tell a doubled grid from a correct one whose
 * bass plays half as often: the beats it states are every second beat of the
 * first and every fourth beat of the second, and a two-way interleave sees the
 * same thing in both. So a halving needs the envelope's own ranking to have
 * put the half above the grid already — which is the situation this exists
 * for, where the prior overturned it — and that is what
 * {@link TempoEstimator#ranksHalfAbove} answers. A recording whose
 * autocorrelation ranks its tracked rate above the half is left alone
 * whatever its bass part looks like.
 *
 * <p>{@code tools/TempoOctave.java} prints the reading for every benchmark it
 * lists, including the commercial recordings held locally, which is where to
 * read what the gates below are worth rather than here.
 */
public final class MarkedPulse {

    /**
     * How much louder the register is on the tracked beats than between them
     * before its silences are read as evidence.
     *
     * <p>Below it the register is not stating this grid at all — a mix whose
     * bass sustains rather than articulates, or one with no low end — and the
     * parity below would be a ratio of two noise levels.
     */
    private static final double REGISTER_CONTRAST = 4.0;

    /**
     * How weak the quieter half of a grid's beats may be, against the louder
     * half, before the grid is read as a subdivision of the pulse rather than
     * the pulse.
     *
     * <p>A beat the register does not state at all reads near zero here, and a
     * pulse it states unevenly — which is ordinary, since a bass need not play
     * every beat equally — reads far above.
     *
     * <p><b>A two-way interleave cannot tell a subdivision from a bass that
     * plays once a bar</b>, since any even period puts every stated beat in
     * one half. On the corpus a kick on the bar line still leaves enough in
     * these bands on the beats between — from the snare, the bass and the rest
     * of the mix — to lift the quieter half clear of this. Where it does not,
     * what stands in front of a wrong halving is the envelope, not the other
     * gate: both of these read a sparse register the same way, and they fail
     * together.
     */
    private static final double UNSTATED_BEAT = 0.10;

    /**
     * How many of the beats in a grid's louder half the register must state
     * before the silence of the other half is evidence about anything, and
     * how loud a beat must be to count as stated, as a share of that half's
     * own mean.
     *
     * <p>A mean is a poor summary of a half that is mostly silence: one loud
     * frame among fifty makes the same mean as a bass playing quietly on all
     * of them, and the silence between the beats then reads as the strongest
     * contrast there is. Strict positivity does not separate those, because
     * the envelope is zero-mean and about a quarter of arbitrary frames are
     * positive; a level does.
     */
    private static final double STATED_BEATS = 0.40;

    /** See {@link #STATED_BEATS}. */
    private static final double STATED_LEVEL = 0.5;

    private MarkedPulse() {
    }

    /**
     * What the register says about one candidate grid, and whether the
     * envelope agrees: how much louder the register is on the grid's beats
     * than between them, how the quieter of the grid's two interleaved halves
     * compares with the louder, and whether most windows rank the half above
     * the grid on the envelope the candidates were ranked on. The two numbers
     * are {@code NaN} where there was nothing to measure, which reads as an
     * abstention.
     */
    public record Reading(double contrast, double parity, boolean envelopePrefersHalf) {

        /**
         * Whether this grid should be halved: the register states it and
         * states only every second beat of it, and the envelope's own ranking
         * had the half above it anyway.
         */
        public boolean callsForHalving() {
            return envelopePrefersHalf && contrast >= REGISTER_CONTRAST && parity <= UNSTATED_BEAT;
        }
    }

    /**
     * The register's reading of a rate over the windows the tempo seeds vote
     * in — the reading {@link BeatTracker} acts on, for an instrument that
     * wants the numbers rather than the decision.
     */
    public static Reading read(OnsetEnvelope envelope, OnsetEnvelope register, double rate) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(register, "register");
        return read(envelope, register, rate, BeatTracker.votingWindows(envelope));
    }

    /**
     * The rate halved where the register states only every second beat of it,
     * and unchanged otherwise — including whenever there is no register to
     * read, or the halved rate would leave {@link TempoEstimator}'s range.
     *
     * @param rate     the pulse the envelope and the prior settled on
     * @param envelope the onset envelope the beats are tracked on
     * @param register the same recording's {@link OnsetEnvelope#pulseRegister},
     *                 or null where the caller has none
     * @param windows  the analysis windows to measure over, as
     *                 {@code {fromFrame, toFrame}}; the median of their
     *                 readings decides, so a passage that drops the bass does
     *                 not decide the recording
     */
    static double resolveOctave(double rate, OnsetEnvelope envelope, OnsetEnvelope register,
                                List<int[]> windows) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(windows, "windows");
        if (register == null || register.length() != envelope.length()) {
            return rate;
        }
        double halved = rate / 2;
        if (!(halved >= TempoEstimator.MIN_TEMPO) || !(rate <= TempoEstimator.MAX_TEMPO)) {
            return rate;
        }
        return read(envelope, register, rate, windows).callsForHalving() ? halved : rate;
    }

    private static Reading read(OnsetEnvelope envelope, OnsetEnvelope register, double rate,
                                List<int[]> windows) {
        double periodFrames = envelope.frameRate() * 60.0 / rate;
        List<Double> contrasts = new ArrayList<>();
        List<Double> parities = new ArrayList<>();
        int preferHalf = 0;
        for (int[] window : windows) {
            List<Double> beats =
                    BeatTracker.trackFixedTempo(envelope, rate, window[0], window[1]);
            if (beats.size() < 4) {
                continue;
            }
            int[] frames = new int[beats.size()];
            for (int i = 0; i < frames.length; i++) {
                frames[i] = register.frameOf(beats.get(i));
            }
            double[] halves = halfMeans(register, frames);
            if (statedShareOfLouderHalf(register, frames, halves) < STATED_BEATS) {
                // Too few frames are carrying that half's mean for it to be a
                // bass part. Read as no evidence rather than skipped, so that a
                // recording whose register goes quiet cannot be decided by the
                // passages where it does not.
                contrasts.add(0.0);
                parities.add(1.0);
                continue;
            }
            contrasts.add(contrast(register, frames, periodFrames));
            parities.add(parity(halves));
            if (TempoEstimator.ranksHalfAbove(envelope, window[0], window[1], rate)) {
                preferHalf++;
            }
        }
        return contrasts.isEmpty()
                ? new Reading(Double.NaN, Double.NaN, false)
                : new Reading(median(contrasts), median(parities),
                        preferHalf * 2 > contrasts.size());
    }

    /**
     * How much stronger the register is on the beats than at the midpoints
     * between them. One means it is saying nothing about this grid.
     *
     * <p>Unbounded where the register is silent between the beats, which is
     * the strongest a grid can be stated rather than a degenerate case: the
     * caller has already established that the stated half is stated at its
     * median, so there is something there for the silence to be a contrast
     * with.
     */
    private static double contrast(OnsetEnvelope register, int[] frames, double periodFrames) {
        double onBeats = 0;
        double between = 0;
        for (int frame : frames) {
            onBeats += strengthAt(register, frame);
            between += strengthAt(register, (int) Math.round(frame + periodFrames / 2));
        }
        return between > 0 ? onBeats / between : Double.POSITIVE_INFINITY;
    }

    /**
     * The quieter of the grid's two interleaved halves over the louder, in the
     * register. Which half is which does not matter — the tracked grid has no
     * bar phase — so this is a ratio of the two, taken in the order that keeps
     * it within one.
     */
    private static double parity(double[] halfMeans) {
        double louder = Math.max(halfMeans[0], halfMeans[1]);
        return louder > 0 ? Math.min(halfMeans[0], halfMeans[1]) / louder : 1;
    }

    /**
     * The share of the louder half's beats the register states at a level
     * comparable with that half's own mean. See {@link #STATED_BEATS}.
     */
    private static double statedShareOfLouderHalf(OnsetEnvelope register, int[] frames,
                                                  double[] halfMeans) {
        int louder = halfMeans[0] >= halfMeans[1] ? 0 : 1;
        double level = STATED_LEVEL * halfMeans[louder];
        int stated = 0;
        int total = 0;
        for (int i = louder; i < frames.length; i += 2) {
            total++;
            if (strengthAt(register, frames[i]) >= level) {
                stated++;
            }
        }
        return total > 0 ? stated / (double) total : 0;
    }

    /** The mean register strength on the even-indexed beats and on the odd. */
    private static double[] halfMeans(OnsetEnvelope register, int[] frames) {
        double even = 0;
        double odd = 0;
        int evenCount = 0;
        int oddCount = 0;
        for (int i = 0; i < frames.length; i++) {
            double strength = strengthAt(register, frames[i]);
            if (i % 2 == 0) {
                even += strength;
                evenCount++;
            } else {
                odd += strength;
                oddCount++;
            }
        }
        return new double[] {even / evenCount, odd / oddCount};
    }

    /**
     * The register at a beat, as the strongest of the three frames around it —
     * an attack is a few frames wide and a tracked beat lands within one of it
     * — and floored at zero, since the envelope is normalised to zero mean and
     * a beat the register does not state sits at or under that mean.
     */
    private static double strengthAt(OnsetEnvelope register, int frame) {
        double strongest = 0;
        int from = Math.max(0, frame - 1);
        int to = Math.min(register.length() - 1, frame + 1);
        for (int at = from; at <= to; at++) {
            strongest = Math.max(strongest, register.strength()[at]);
        }
        return strongest;
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return sorted[sorted.length / 2];
    }
}
