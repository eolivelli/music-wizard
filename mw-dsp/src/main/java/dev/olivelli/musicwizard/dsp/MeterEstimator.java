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

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import java.util.Objects;

/**
 * Reads the meter from the recording: how many tracked pulses make a bar, and
 * whether the pulse divides in two or in three.
 *
 * <p>The two questions are independent and both are needed. The bar length is
 * read from harmonic change, which {@link DownbeatEstimator} already measures
 * per beat — chords change preferentially at bar lines, so the bar length is
 * the period that change repeats at. The subdivision cannot be read there at
 * all: the tracker lands on the counted beat of a compound groove as readily
 * as on its eighths, so it is read from where the onsets fall <em>between</em>
 * pulses.
 *
 * <p>Both halves are scored the same way, as the squared magnitude of one
 * Fourier coefficient over the coefficient's own energy. That normalisation is
 * the whole reason the numbers may be compared at all: per-phase means are not
 * comparable across bar lengths, because the best of four phases beats the
 * best of three by chance (#88, #303), while this statistic has expectation
 * one at every period under a null of independent beats. The evidence is the
 * same; only the null it is read against is new.
 *
 * <p><b>4/4 is the prior and the reading must clear a margin to leave it.</b>
 * A wrong meter moves every bar line, which is why {@link BeatTracker} declined
 * to guess one at all, so the gates below are asymmetric by design: a bar
 * length other than four has to be supported on its own <em>and</em> beat the
 * four-beat bar by a margin, and where it does not the answer is 4/4 reported
 * at the confidence the evidence gave it rather than hidden.
 *
 * <p>Two things this deliberately does not read. <b>A swung eighth is not a
 * compound bar.</b> Every shuffle in {@code samples/list.txt} measures as a
 * triple subdivision and is barred in four by its ground-truth cycle, so the
 * subdivision may name a bar that has already left four and may not move one
 * that has not: 12/8 and 3/8 are reachable only through
 * {@code --time-signature} (#701). And <b>the accent is not asked about the
 * bar length</b> — its strongest periodicity on ordinary drum material is the
 * backbeat (#70), which argues for a two-beat bar on most of the corpus,
 * including recordings whose harmony names the bar unambiguously.
 *
 * <p>{@code tools/MeterSweep.java} prints the readings behind every constant
 * here, for the committed benchmarks and the local-only ones alike; read it
 * rather than this text for what they are worth.
 */
public final class MeterEstimator {

    /**
     * Tracked pulses to a bar this reads. Two, three and four are bars; six is
     * a bar the tracker has filled with the subdivision of a compound meter,
     * which is a reading about the pulse level rather than about the bar and
     * is why {@link Estimate#pulsesPerBar()} is carried beside the meter.
     */
    private static final int[] CANDIDATES = {2, 3, 4, 6};

    /** The bar length assumed when the evidence does not displace it. */
    private static final int ASSUMED = 4;

    /**
     * The periodicity at which a bar length counts as supported by the harmony.
     *
     * <p>A significance level, not a tuning: the statistic has expectation one
     * under a null of independent beats and is distributed about that null like
     * a squared pair of Gaussians, so this is roughly the one-percent point. A
     * candidate below it is not distinguishable from a recording whose harmony
     * has no period at all.
     */
    private static final double SUPPORTED = 5.0;

    /**
     * How far a bar length must beat the assumed one before it may replace it.
     *
     * <p>A ratio, since the two are the same statistic on the same series. It
     * is the margin {@link BeatTracker#toBeatGrid}'s refusal to guess is being
     * traded for, so it is deliberately wide: on the corpus every bar length
     * that is right clears it several times over, and the readings sit nowhere
     * near it.
     */
    private static final double MARGIN = 4.0;

    /**
     * Confidence in a meter the evidence does not decide.
     *
     * <p>Not the floor {@link DownbeatEstimator} reports for a phase nothing
     * supports. A phase nothing supports is one guess in four; a meter is 4/4,
     * which is most of the material this tool targets, so the assumption is
     * worth considerably more than a guess. Deliberately in the band that reads
     * "probably, but check it": correcting the meter by hand is one of the two
     * highest-value things a user can do, and a number that says not to bother
     * is what stops it.
     */
    private static final double ASSUMED_CONFIDENCE = 0.5;

    /**
     * The most any reading may report.
     *
     * <p>Below certainty because the vocabulary is smaller than the question:
     * a bar of four tracked pulses is read as 4/4 whether the recording is in
     * four or in twelve-eight, so a reading can be right about the bar and
     * wrong about what to write on the page (#701).
     */
    private static final double CEILING = 0.9;

    /**
     * The shortest usable stretch of beats a reading is taken from, in bars of
     * the longest candidate.
     *
     * <p>Below it the Fourier coefficient is describing the window rather than
     * the music, and its null no longer holds.
     */
    private static final int BARS_FOR_A_READING = 4;

    private MeterEstimator() {
    }

    /**
     * The meter read from a recording, and how the tracked pulses fill its bars.
     *
     * <p>{@code pulsesPerBar} is not {@code meter.beatsPerBar()} in general:
     * a tracker that landed on the eighths of a 6/8 groove puts six pulses in
     * a bar that is counted in two, and a caller that bars on the counted beat
     * would draw bars a third of the right length.
     *
     * @param meter        the meter to bar the recording in
     * @param pulsesPerBar tracked pulses in one of its bars
     * @param confidence   trust in the meter alone, not in the beats it bars
     */
    public record Estimate(TimeSignature meter, int pulsesPerBar, Confidence confidence) {

        public Estimate {
            Objects.requireNonNull(meter, "meter");
            Objects.requireNonNull(confidence, "confidence");
            if (pulsesPerBar < 1) {
                throw new IllegalArgumentException(
                        "pulsesPerBar must be positive, got: " + pulsesPerBar);
            }
        }

        /** Quarter notes in one tracked pulse. */
        public double pulseQuarters() {
            return meter.quarterBeatsPerBar() / pulsesPerBar;
        }

        /** Whether the tracked pulse is the meter's counted beat. */
        public boolean pulseIsCountedBeat() {
            return pulsesPerBar == meter.beatsPerBar();
        }
    }

    /**
     * The statistics a reading is made of, for an instrument that wants the
     * numbers rather than the decision.
     *
     * <p>Every field has expectation one under its own null, so they may be
     * read against each other and against {@link #SUPPORTED}.
     *
     * @param atTwo       harmonic periodicity at two tracked pulses
     * @param atThree     the same at three
     * @param atFour      the same at four, which is the assumption's own score
     * @param atSix       the same at six
     * @param duple       onset periodicity at half a pulse
     * @param triple      onset periodicity at a third of a pulse
     * @param usableBeats beats novelty is defined at, which is what all of the
     *                    above are measured over
     */
    public record Reading(double atTwo, double atThree, double atFour, double atSix,
                          double duple, double triple, int usableBeats) {

        /** The harmonic periodicity at a candidate bar length. */
        public double at(int pulsesPerBar) {
            return switch (pulsesPerBar) {
                case 2 -> atTwo;
                case 3 -> atThree;
                case 4 -> atFour;
                case 6 -> atSix;
                default -> throw new IllegalArgumentException(
                        "not a candidate bar length: " + pulsesPerBar);
            };
        }

        /** Whether the onsets between pulses fall in three rather than in two. */
        public boolean subdividesInThree() {
            return triple > duple;
        }
    }

    /**
     * Reads the meter, with 4/4 as the prior.
     *
     * @param beatTimes the tracked beats, in seconds and ascending
     * @param chroma    beat-synchronous chroma over exactly those beats, as
     *                  {@link DownbeatEstimator#estimate} takes it
     * @param envelope  the onset envelope the beats were tracked from
     */
    public static Estimate estimate(List<Double> beatTimes, Chroma chroma,
                                    OnsetEnvelope envelope) {
        return decide(read(beatTimes, chroma, envelope));
    }

    /**
     * The statistics alone, without the decision {@link #decide} makes of them.
     *
     * <p>A reading over too few beats reports zeroes rather than a coefficient
     * of a window: {@link Reading#usableBeats()} is what says which it is.
     */
    public static Reading read(List<Double> beatTimes, Chroma chroma, OnsetEnvelope envelope) {
        Objects.requireNonNull(beatTimes, "beatTimes");
        Objects.requireNonNull(chroma, "chroma");
        Objects.requireNonNull(envelope, "envelope");

        // Novelty is only defined where a beat has a chroma span on both sides,
        // exactly as DownbeatEstimator scopes it.
        int firstBeat = 1;
        int lastBeat = beatTimes.size() - 2;
        int usable = lastBeat - firstBeat + 1;
        if (usable < BARS_FOR_A_READING * CANDIDATES[CANDIDATES.length - 1]
                || chroma.frameCount() == 0) {
            return new Reading(0, 0, 0, 0, 0, 0, Math.max(0, usable));
        }
        if (!chroma.isBeatSynchronous() || chroma.frameCount() != beatTimes.size() - 1) {
            throw new IllegalArgumentException(
                    "chroma must be beat-synchronous over these beats: expected "
                            + (beatTimes.size() - 1) + " inter-beat spans, got "
                            + chroma.frameCount()
                            + (chroma.isBeatSynchronous() ? "" : " on a fixed time grid"));
        }

        double[] novelty = DownbeatEstimator.harmonicNovelty(chroma);
        double[] within = withinPulsePhases(beatTimes, envelope);
        return new Reading(
                periodicity(novelty, firstBeat, lastBeat, 2),
                periodicity(novelty, firstBeat, lastBeat, 3),
                periodicity(novelty, firstBeat, lastBeat, 4),
                periodicity(novelty, firstBeat, lastBeat, 6),
                circularPeriodicity(within, 2),
                circularPeriodicity(within, 3),
                usable);
    }

    /**
     * The meter a reading names.
     *
     * <p>One winner and one fallback: a bar length that fails any gate returns
     * the assumption rather than the next candidate down, because a candidate
     * that only wins once the winner is disqualified was never the evidence.
     */
    public static Estimate decide(Reading reading) {
        Objects.requireNonNull(reading, "reading");
        int best = ASSUMED;
        for (int candidate : CANDIDATES) {
            if (reading.at(candidate) > reading.at(best)) {
                best = candidate;
            }
        }
        TimeSignature meter = best == ASSUMED ? null : meterAt(reading, best);
        if (meter == null || !clearsThePrior(reading, best)) {
            return new Estimate(TimeSignature.FOUR_FOUR, ASSUMED,
                    confidenceIn(reading, ASSUMED));
        }
        return new Estimate(meter, best, confidenceIn(reading, best));
    }

    /**
     * Whether a bar length other than the assumed one is worth acting on.
     *
     * <p>Three gates, and the third applies only to a candidate that divides
     * the assumption. <b>A bar of four states its own halves</b>, so a two-beat
     * periodicity is also what a four-beat bar looks like when its halves are
     * alike — which most comping is, and which is why the two-beat reading is
     * the strongest one on several recordings that are plainly in four. Nothing
     * of the kind relates three or six to four, so nothing there needs the gate.
     */
    private static boolean clearsThePrior(Reading reading, int candidate) {
        if (reading.at(candidate) < SUPPORTED) {
            return false;
        }
        if (reading.at(candidate) < MARGIN * reading.at(ASSUMED)) {
            return false;
        }
        return ASSUMED % candidate != 0 || reading.at(ASSUMED) < SUPPORTED;
    }

    /**
     * The meter a bar length and the subdivision name together, or null where
     * the pair names nothing this reads.
     *
     * <p>Six is the tracker on a subdivision rather than on the counted beat,
     * and which meter it is comes from how those six group — in two threes,
     * which is 6/8, or in three twos, which is 3/4 — read from the harmonic
     * statistic rather than from the onsets, since there is nothing below the
     * pulse left to hear.
     *
     * <p><b>The subdivision decides the two-pulse bar and nothing else.</b>
     * There it settles 6/8 against 2/4, which are different bars; at three
     * pulses it would settle 3/4 against 9/8, which are the same bar under two
     * spellings, so refusing one for the other would cost a bar axis to buy a
     * notation this cannot reach anyway. 2/4 is left to the prior, since
     * barring a four-beat bar in two draws every other bar line in the right
     * place, which is worse than drawing none of them wrong.
     */
    private static TimeSignature meterAt(Reading reading, int pulsesPerBar) {
        return switch (pulsesPerBar) {
            case 2 -> reading.subdividesInThree() ? TimeSignature.SIX_EIGHT : null;
            case 3 -> TimeSignature.THREE_FOUR;
            case 6 -> reading.atThree() > reading.atTwo()
                    ? TimeSignature.SIX_EIGHT : TimeSignature.THREE_FOUR;
            default -> null;
        };
    }

    /**
     * How far the evidence backs a bar length, from the floor to the ceiling.
     *
     * <p>Two things have to hold and they fail separately: the length has to
     * carry periodicity at all, and it has to carry more of it than the best
     * of the others by the margin leaving the prior costs. Multiplied, so that
     * either failing brings the number down. A four-beat bar held against a
     * stronger rival therefore reports near the floor, which is the honest
     * reading of an assumption that survived contrary evidence.
     */
    private static Confidence confidenceIn(Reading reading, int chosen) {
        double rival = 0;
        for (int candidate : CANDIDATES) {
            if (candidate != chosen) {
                rival = Math.max(rival, reading.at(candidate));
            }
        }
        double observed = Math.clamp(reading.at(chosen) / SUPPORTED, 0, 1);
        double separation = Math.clamp(
                reading.at(chosen) / (MARGIN * Math.max(rival, SUPPORTED)), 0, 1);
        return Confidence.clamped(
                ASSUMED_CONFIDENCE + (CEILING - ASSUMED_CONFIDENCE) * observed * separation);
    }

    /**
     * How strongly a per-beat series repeats at a period, against a null of
     * independent beats.
     *
     * <p>The squared magnitude of the series' Fourier coefficient at that
     * period over the series' own energy. Comparable across periods because
     * that ratio has expectation one at every one of them: the coefficient at
     * a period of two is real and the others are complex, and the two arrive at
     * the same expectation by different routes.
     */
    static double periodicity(double[] perBeat, int from, int to, int period) {
        double mean = 0;
        for (int i = from; i <= to; i++) {
            mean += perBeat[i];
        }
        mean /= to - from + 1;
        double real = 0;
        double imaginary = 0;
        double energy = 0;
        for (int i = from; i <= to; i++) {
            double centred = perBeat[i] - mean;
            double angle = -2 * Math.PI * i / period;
            real += centred * Math.cos(angle);
            imaginary += centred * Math.sin(angle);
            energy += centred * centred;
        }
        return energy > 0 ? (real * real + imaginary * imaginary) / energy : 0;
    }

    /**
     * The same statistic over samples carrying their own phase, for the onsets
     * between one pulse and the next.
     *
     * @param phased alternating position within the pulse, in turns, and onset
     *               strength at it
     */
    private static double circularPeriodicity(double[] phased, int perPulse) {
        int count = phased.length / 2;
        if (count == 0) {
            return 0;
        }
        double mean = 0;
        for (int i = 0; i < count; i++) {
            mean += phased[2 * i + 1];
        }
        mean /= count;
        double real = 0;
        double imaginary = 0;
        double energy = 0;
        for (int i = 0; i < count; i++) {
            double centred = phased[2 * i + 1] - mean;
            double angle = -2 * Math.PI * perPulse * phased[2 * i];
            real += centred * Math.cos(angle);
            imaginary += centred * Math.sin(angle);
            energy += centred * centred;
        }
        return energy > 0 ? (real * real + imaginary * imaginary) / energy : 0;
    }

    /**
     * Every onset frame between two tracked pulses, as its position within the
     * pulse and its strength.
     *
     * <p>Flattened into one array as {@code position, strength} pairs, so that
     * the two coefficients read the same samples without allocating a second
     * view of them. Frames before the first pulse and after the last carry no
     * position and are left out.
     */
    private static double[] withinPulsePhases(List<Double> beatTimes, OnsetEnvelope envelope) {
        if (envelope.length() == 0 || beatTimes.size() < 2) {
            return new double[0];
        }
        double[] strength = envelope.strength();
        // One pass over the frames rather than one per pulse, so that each frame
        // contributes at most one pair however the pulses fall and the array
        // below cannot overflow.
        double[] out = new double[2 * strength.length];
        int size = 0;
        int beat = 0;
        for (int frame = 0; frame < strength.length; frame++) {
            double time = envelope.timeOf(frame);
            while (beat + 2 < beatTimes.size() && beatTimes.get(beat + 1) <= time) {
                beat++;
            }
            double from = beatTimes.get(beat);
            double to = beatTimes.get(beat + 1);
            if (time < from || time >= to) {
                continue;
            }
            out[size++] = (time - from) / (to - from);
            out[size++] = strength[frame];
        }
        double[] trimmed = new double[size];
        System.arraycopy(out, 0, trimmed, 0, size);
        return trimmed;
    }
}
