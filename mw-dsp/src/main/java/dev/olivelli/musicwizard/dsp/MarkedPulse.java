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
 */
public final class MarkedPulse {

    /**
     * How much louder the register is on the tracked beats than between them
     * before its silences are read as evidence.
     *
     * <p>Below it the register is not stating this grid at all — a mix whose
     * bass sustains rather than articulates, or one with no low end — and the
     * parity below would be a ratio of two noise levels. Every recording in
     * the corpus quiet enough on alternate beats to reach that gate has a
     * register barely louder on the beats than between them, and the one this
     * fires on is louder by more than an order of magnitude, so the constant
     * sits in a gap rather than on a boundary. {@code tools/TempoOctave.java}
     * prints both numbers per benchmark.
     */
    private static final double REGISTER_CONTRAST = 4.0;

    /**
     * How weak the quieter half of a grid's beats may be, against the louder
     * half, before the grid is read as a subdivision of the pulse rather than
     * the pulse.
     *
     * <p>A beat the register does not state at all reads near zero here, and a
     * pulse it states unevenly — which is ordinary, since a bass need not play
     * every beat equally — reads well above. Nothing in the corpus lands
     * between.
     */
    private static final double UNSTATED_BEAT = 0.10;

    private MarkedPulse() {
    }

    /**
     * What the register says about one candidate grid: how much louder it is
     * on the grid's beats than between them, and how the quieter of the grid's
     * two interleaved halves compares with the louder. Both are {@code NaN}
     * where there was nothing to measure, which reads as an abstention.
     */
    public record Reading(double contrast, double parity) {

        /** Whether this grid is a subdivision of the pulse the register states. */
        public boolean statesOnlyEveryOtherBeat() {
            return contrast >= REGISTER_CONTRAST && parity <= UNSTATED_BEAT;
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
        return read(envelope, register, rate, windows).statesOnlyEveryOtherBeat() ? halved : rate;
    }

    private static Reading read(OnsetEnvelope envelope, OnsetEnvelope register, double rate,
                                List<int[]> windows) {
        double periodFrames = envelope.frameRate() * 60.0 / rate;
        List<Double> contrasts = new ArrayList<>();
        List<Double> parities = new ArrayList<>();
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
            contrasts.add(contrast(register, frames, periodFrames));
            parities.add(parity(register, frames));
        }
        return contrasts.isEmpty()
                ? new Reading(Double.NaN, Double.NaN)
                : new Reading(median(contrasts), median(parities));
    }

    /**
     * How much stronger the register is on the beats than at the midpoints
     * between them. One means it is saying nothing about this grid.
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
    private static double parity(OnsetEnvelope register, int[] frames) {
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
        double first = even / evenCount;
        double second = odd / oddCount;
        double louder = Math.max(first, second);
        return louder > 0 ? Math.min(first, second) / louder : 1;
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
