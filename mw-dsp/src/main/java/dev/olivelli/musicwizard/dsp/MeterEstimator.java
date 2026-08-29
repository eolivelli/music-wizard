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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Reads how many tracked pulses make a bar, and names the meter that bars them.
 *
 * <p>The bar length is read from harmonic change, which {@link DownbeatEstimator}
 * already measures per beat — chords change preferentially at bar lines, so the
 * bar is the period that change repeats at. Each candidate is scored as the
 * squared magnitude of one Fourier coefficient of that novelty over the
 * novelty's own energy, which is what makes lengths comparable at all:
 * per-phase means are not, because the best of four phases beats the best of
 * three by chance (#88, #303), while this has expectation one at every period
 * under a null of independent beats.
 *
 * <p><b>4/4 is the prior and a reading must clear a margin to leave it.</b> A
 * wrong meter moves every bar line, which is why {@link BeatTracker} declined to
 * guess one at all, so the gates are asymmetric by design and a bar length that
 * does not clear them is reported as 4/4 at the confidence the evidence gave it
 * rather than hidden.
 *
 * <p><b>The statistic cannot prefer a period over its own divisors.</b> Novelty
 * that repeats every six beats carries the same coefficient at three and at two,
 * six being a multiple of both, so where six is comparable it is six that is
 * believed: the shorter reading is implied by the longer and never the other way
 * round. Four is coprime with three and with six, so nothing of the kind relates
 * it to either.
 *
 * <p><b>A bar of two tracked pulses is the one length the harmony may not
 * choose on its own</b> (#707). Harmony moving every two beats of a four-beat
 * bar is ordinary comping and scores at period two exactly as a compound bar
 * counted in two does, so the two-pulse bar is admitted only where the pulse
 * also divides in three — which is a reading of the onset envelope's own
 * periodicity rather than of the harmony, and is the only place this class asks
 * the envelope anything. Both are needed and neither is sufficient: the corpus
 * holds two-beat comping under a pulse that divides in two, and a pulse
 * dividing in three under a four-beat bar the harmony supports.
 *
 * <p>Two things this still does not read. <b>A swung eighth is not a compound
 * bar</b>: every shuffle in {@code samples/list.txt} divides its pulse in three
 * and is barred in four by its own ground-truth cycle, so the division may
 * admit a two-pulse bar and may not promote a four-pulse one, and 12/8 and 3/8
 * stay with {@code --time-signature} (#701). And <b>the accent is not asked
 * about the bar length</b>: its strongest periodicity on ordinary drum material
 * is the backbeat (#70), which argues for a two-beat bar on most of the corpus
 * and prefers four to three on material whose harmony names three
 * unambiguously.
 *
 * <p>{@code tools/MeterSweep.java} prints the readings behind every constant
 * here, for the committed benchmarks and the local-only ones alike; read it
 * rather than this text for what they are worth.
 */
public final class MeterEstimator {

    /**
     * Tracked pulses to a bar this reads. Three and four are bars; six is a bar
     * the tracker has filled with its own subdivision, which is a reading about
     * the pulse level rather than about the bar and is why
     * {@link Estimate#pulsesPerBar()} is carried beside the meter.
     */
    private static final int[] CANDIDATES = {3, 4, 6};

    /**
     * The bar length the harmony alone may not choose, because two divides both
     * four and six and the statistic below cannot prefer a period to its own
     * divisors. It reaches {@link Estimate#pulsesPerBar()} only through
     * {@link #barsInTwo}, and it is a rival to every other length throughout.
     */
    private static final int IN_TWO = 2;

    /** Every period read, which is the candidates and the two-pulse bar. */
    private static final int[] PERIODS = {IN_TWO, 3, 4, 6};

    /** The bar length assumed when the evidence does not displace it. */
    private static final int ASSUMED = 4;

    /**
     * The periodicity at which a bar length counts as supported by the harmony.
     *
     * <p>A significance level rather than a tuning: the statistic has
     * expectation one under a null of independent beats whatever the period, so
     * a point in that null's tail means the same thing at every candidate.
     * Below it a candidate is not distinguishable from a recording whose harmony
     * has no period at all.
     */
    private static final double SUPPORTED = 5.0;

    /**
     * How far a bar length must beat the assumed one before it may replace it.
     *
     * <p>A ratio, since the two are the same statistic on the same series. It is
     * the margin {@link BeatTracker#toBeatGrid}'s refusal to guess is being
     * traded for, so it is deliberately wide: every bar length the corpus states
     * clears it, and {@code tools/MeterSweep.java} prints by how much.
     */
    private static final double MARGIN = 4.0;

    /**
     * The share of a three-pulse reading that a six-pulse one must carry before
     * the six is believed instead.
     *
     * <p>Not a margin in the sense above, and the comparison runs the other way:
     * three divides six, so novelty that really repeats every six beats scores
     * the two <em>equally</em> and a three that is real leaves the six far
     * behind. What this separates is those two cases, and the corpus puts them
     * either side of it by a wide margin in both directions.
     */
    private static final double DIVIDED = 0.5;

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
     * <p>Below certainty because the vocabulary is smaller than the question: a
     * bar of four tracked pulses is read as 4/4 whether the recording is in four
     * or in twelve-eight, so a reading can be right about the bar and wrong
     * about what to write on the page (#701).
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

    /**
     * How much of the pulse's own periodicity the onset envelope must carry at
     * a triple division of it before the pulse counts as dividing in three.
     *
     * <p>A share rather than a level, because the envelope's periodicity at the
     * pulse is what the division is a division of, and recordings differ by an
     * order of magnitude in how periodic they are at all. The corpus leaves this
     * a wide gap to sit in: {@code tools/MeterSweep.java} prints the share for
     * every benchmark and every local-only recording, and no row lies between
     * the strongest that must be refused and the weakest that must be admitted.
     */
    private static final double DIVIDES_IN_THREE = 0.65;

    /**
     * How far either side of a division's lag the envelope's peak is looked for,
     * as a share of the lag.
     *
     * <p>The tracked pulse is a mean over the recording and a division of it is
     * played by hand, so the peak sits near the arithmetic lag rather than on
     * it. Wide enough and the three lags this reads start to overlap.
     */
    private static final double LAG_TOLERANCE = 0.03;

    private MeterEstimator() {
    }

    /** The longest bar a reading may name, which sets how long a reading needs. */
    private static int longestCandidate() {
        int longest = 0;
        for (int candidate : CANDIDATES) {
            longest = Math.max(longest, candidate);
        }
        return longest;
    }

    /**
     * The meter read from a recording, and how the tracked pulses fill its bars.
     *
     * <p>{@code pulsesPerBar} is not {@code meter.beatsPerBar()} in general: a
     * tracker that landed on the eighths of a 6/8 groove puts six pulses in a bar
     * that is counted in two, and a caller that barred on the counted beat would
     * draw bars a third of the right length.
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
     * <p>One statistic at four periods, so they may be read against each other,
     * and beside them the one thing here that is not read from the harmony at
     * all: how the tracked pulse divides.
     *
     * @param atTwo       harmonic periodicity at two tracked pulses
     * @param atThree     the same at three
     * @param atFour      the same at four, which is the assumption's own score
     * @param atSix       the same at six
     * @param inThree     the onset envelope's periodicity at a third of the
     *                    pulse, over its periodicity at the pulse itself; zero
     *                    where the pulse carries none for it to be a share of
     * @param inTwo       the same at a half of the pulse
     * @param usableBeats beats novelty is defined at, which is what the harmonic
     *                    periodicities are measured over
     */
    public record Reading(double atTwo, double atThree, double atFour, double atSix,
                          double inThree, double inTwo, int usableBeats) {

        /** The harmonic periodicity at a period, which need not be a candidate. */
        public double at(int pulses) {
            return switch (pulses) {
                case 2 -> atTwo;
                case 3 -> atThree;
                case 4 -> atFour;
                case 6 -> atSix;
                default -> throw new IllegalArgumentException(
                        "not a period this reads: " + pulses);
            };
        }
    }

    /**
     * Reads the meter, with 4/4 as the prior.
     *
     * @param beatTimes the tracked beats, read for their count and their spacing
     * @param chroma    beat-synchronous chroma over exactly those beats, as
     *                  {@link DownbeatEstimator#estimate} takes it
     * @param envelope  the onset envelope those beats were tracked on, which is
     *                  where the pulse's division is read
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
        if (usable < BARS_FOR_A_READING * longestCandidate() || chroma.frameCount() == 0) {
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
        Divisions divisions = divisionsOfThePulse(envelope, medianInterval(beatTimes));
        return new Reading(
                periodicity(novelty, firstBeat, lastBeat, 2),
                periodicity(novelty, firstBeat, lastBeat, 3),
                periodicity(novelty, firstBeat, lastBeat, 4),
                periodicity(novelty, firstBeat, lastBeat, 6),
                divisions.inThree(), divisions.inTwo(),
                usable);
    }

    /**
     * The meter a reading names.
     *
     * <p>One winner and one fallback: a bar length that fails a gate returns the
     * assumption rather than the next candidate down, because a candidate that
     * only wins once the winner is disqualified was never the evidence.
     */
    public static Estimate decide(Reading reading) {
        Objects.requireNonNull(reading, "reading");
        if (barsInTwo(reading)) {
            return new Estimate(TimeSignature.SIX_EIGHT, IN_TWO, confidenceIn(reading, IN_TWO));
        }
        int best = ASSUMED;
        for (int candidate : CANDIDATES) {
            if (reading.at(candidate) > reading.at(best)) {
                best = candidate;
            }
        }
        // Three divides six, so a six-pulse bar scores three as strongly as it
        // scores itself and would take the reading on floating-point residue.
        if (best == 3 && reading.atSix() >= DIVIDED * reading.atThree()) {
            best = 6;
        }
        if (best == ASSUMED || !clearsThePrior(reading, best)) {
            return new Estimate(TimeSignature.FOUR_FOUR, ASSUMED,
                    confidenceIn(reading, ASSUMED));
        }
        return new Estimate(meterAt(reading, best), best, confidenceIn(reading, best));
    }

    /**
     * Whether the recording is barred in two tracked pulses, which this names
     * 6/8 — the pulse being a dotted quarter wherever it divides in three.
     *
     * <p>The harmony cannot decide this on its own and is not asked to: a
     * four-beat bar comping every two beats produces the same period two, and
     * the corpus puts recordings of that kind above the compound ones on the
     * harmonic statistic. So the harmony is asked only not to contradict it —
     * period two leads, and no longer bar length is supported on its own, which
     * is what keeps a shuffle's four-beat bar and a waltz's three out of reach
     * of the division below — and the division of the pulse decides.
     *
     * <p>Deliberately answered before the candidates are ranked. Nothing is lost
     * by the order: a bar length the harmony supports refuses this outright.
     */
    private static boolean barsInTwo(Reading reading) {
        for (int candidate : CANDIDATES) {
            if (reading.at(candidate) >= SUPPORTED || reading.at(candidate) >= reading.atTwo()) {
                return false;
            }
        }
        return reading.inThree() >= DIVIDES_IN_THREE && reading.inThree() > reading.inTwo();
    }

    /**
     * Whether a bar length other than the assumed one is worth acting on: it has
     * to be a period at all, and to beat the four-beat bar by the margin that
     * leaving the prior costs.
     */
    private static boolean clearsThePrior(Reading reading, int candidate) {
        return reading.at(candidate) >= SUPPORTED
                && reading.at(candidate) >= MARGIN * reading.at(ASSUMED);
    }

    /**
     * The meter a bar length names.
     *
     * <p>Three pulses is 3/4. Six is the tracker on a subdivision rather than on
     * the counted beat, and which meter that is comes from how the six group —
     * in two threes, which is 6/8, or in three twos, which is 3/4 — read from the
     * same harmonic statistic, there being nothing below the pulse left to hear.
     * Both divide six, so a bar that marks nothing inside itself scores them
     * alike. Nothing about the bar lines turns on it: 3/4 and 6/8 hold the same
     * three quarter notes, so at six pulses to a bar they agree on every bar
     * line and on the pulse, and differ only in what is printed.
     */
    private static TimeSignature meterAt(Reading reading, int pulsesPerBar) {
        if (pulsesPerBar == 3) {
            return TimeSignature.THREE_FOUR;
        }
        return reading.atThree() >= reading.atTwo()
                ? TimeSignature.SIX_EIGHT : TimeSignature.THREE_FOUR;
    }

    /**
     * How far the evidence backs a bar length, from the floor to the ceiling.
     *
     * <p>Two things have to hold and they fail separately: the length has to
     * carry periodicity at all, and it has to carry more of it than the best of
     * the others by the margin leaving the prior costs. Multiplied, so that
     * either failing brings the number down. A four-beat bar held against a
     * stronger rival therefore reports near the floor, which is the honest
     * reading of an assumption that survived contrary evidence.
     */
    private static Confidence confidenceIn(Reading reading, int chosen) {
        double rival = 0;
        for (int period : PERIODS) {
            if (period != chosen) {
                rival = Math.max(rival, reading.at(period));
            }
        }
        double observed = Math.clamp(reading.at(chosen) / SUPPORTED, 0, 1);
        double separation = Math.clamp(
                reading.at(chosen) / (MARGIN * Math.max(rival, SUPPORTED)), 0, 1);
        return Confidence.clamped(
                ASSUMED_CONFIDENCE + (CEILING - ASSUMED_CONFIDENCE) * observed * separation);
    }

    /** How the pulse divides, each as a share of the pulse's own periodicity. */
    private record Divisions(double inThree, double inTwo) {
    }

    /**
     * How the tracked pulse divides, read from the onset envelope's own
     * periodicity at the lags a division of the pulse puts a peak at.
     *
     * <p>The envelope repeats at every level of the metre, so a division is
     * read against the pulse rather than in absolute terms: the pulse's lag is
     * what the recording is periodic at by construction, having been tracked,
     * and each division is scored as a share of it. A third of the pulse and
     * two thirds of it are the same division seen at two lags, and either alone
     * is thin — a shuffle strikes two of three positions and a compound bar all
     * three — so the stronger of them stands for the division.
     *
     * <p>Zero for both where the envelope carries no periodicity at the pulse,
     * since a share of nothing decides nothing.
     */
    private static Divisions divisionsOfThePulse(OnsetEnvelope envelope, double pulseSeconds) {
        double lag = envelope.frameRate() * pulseSeconds;
        int longest = (int) Math.ceil(lag * (1 + LAG_TOLERANCE)) + 1;
        if (!(lag > 0) || longest >= envelope.length()) {
            return new Divisions(0, 0);
        }
        double[] correlation = TempoEstimator.autocorrelate(envelope.strength(), longest);
        double pulse = peakNear(correlation, lag);
        if (!(pulse > 0)) {
            return new Divisions(0, 0);
        }
        double inThree = Math.max(peakNear(correlation, lag / 3), peakNear(correlation, 2 * lag / 3));
        double inTwo = Math.max(peakNear(correlation, lag / 2), peakNear(correlation, lag / 4));
        return new Divisions(inThree / pulse, inTwo / pulse);
    }

    /** The envelope's strongest periodicity within {@link #LAG_TOLERANCE} of a lag. */
    private static double peakNear(double[] correlation, double lag) {
        double strongest = Double.NEGATIVE_INFINITY;
        for (double at = lag * (1 - LAG_TOLERANCE); at <= lag * (1 + LAG_TOLERANCE); at += 0.25) {
            strongest = Math.max(strongest, TempoEstimator.interpolate(correlation, at));
        }
        return strongest;
    }

    /**
     * The middle interval between tracked beats, which is the pulse the bars
     * are drawn on. A median rather than a mean: a tracker that dropped a beat
     * leaves one interval of twice the pulse, and the lags above are read at a
     * tolerance far narrower than that error.
     */
    private static double medianInterval(List<Double> beatTimes) {
        List<Double> intervals = new ArrayList<>(beatTimes.size() - 1);
        for (int beat = 1; beat < beatTimes.size(); beat++) {
            intervals.add(beatTimes.get(beat) - beatTimes.get(beat - 1));
        }
        Collections.sort(intervals);
        return intervals.get(intervals.size() / 2);
    }

    /**
     * How strongly a per-beat series repeats at a period, against a null of
     * independent beats.
     *
     * <p>The squared magnitude of the series' Fourier coefficient at that period
     * over the series' own energy. Comparable across periods because that ratio
     * has expectation one at every one of them: the coefficient at a period of
     * two is real and the others are complex, and the two arrive at the same
     * expectation by different routes. It measures energy at a frequency and not
     * a period, so it cannot tell a period from its own divisors — see the
     * divisor rule in {@link #decide}.
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
}
