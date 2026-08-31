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

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

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
 * <p><b>4/4 is the prior and nothing leaves it cheaply.</b> A wrong meter moves
 * every bar line, which is why {@link BeatTracker} declined to guess one at all,
 * so the gates are asymmetric by design and a bar length that does not clear
 * them is reported as 4/4 at the confidence the evidence gave it rather than
 * hidden. Three and six leave it on a margin over the four-beat bar; two cannot,
 * for the reason below, and leaves it on evidence of another kind entirely.
 *
 * <p><b>The statistic cannot prefer a period over its own divisors.</b> Novelty
 * that repeats every six beats carries the same coefficient at three and at two,
 * six being a multiple of both, so where six is comparable it is six that is
 * believed: the shorter reading is implied by the longer and never the other way
 * round. Four neither divides three or six nor is divided by them, so nothing of
 * the kind relates it to either.
 *
 * <p><b>A bar of two tracked pulses is the one length the harmony may not
 * choose on its own</b> (#707). Harmony moving every two beats of a four-beat
 * bar is ordinary comping and scores at period two exactly as a compound bar
 * counted in two does, so the two-pulse bar is admitted only where the pulse
 * also divides in three — which is a reading of the onset envelope's own
 * periodicity rather than of the harmony, and is the only place this class asks
 * the envelope anything. The corpus holds two-beat comping under a pulse that
 * divides in two, so the division is needed; it is what the two-pulse bar
 * rests on.
 *
 * <p><b>The harmony vetoes only the bar lengths two does not divide</b>
 * (#712). A chord loop two bars long is periodic at four tracked pulses
 * whether the bar is two of them or four, so a supported four is a length the
 * shorter bar tiles rather than a rival account of it, and refusing on it puts
 * every ordinary two-bar 6/8 loop out of reach. A supported three is a rival —
 * no number of two-pulse bars makes three of them — unless a six that would
 * have taken the reading accounts for it, three dividing six (#727). What the
 * harmony is asked for is that it say something at some length two divides,
 * and nothing louder at a three of its own — which leaves the 4/4 prior
 * carried by the division alone, and that is what a shuffle's four-beat bar
 * now survives on.
 *
 * <p>Two things this still does not read. <b>A swung eighth is not a compound
 * bar</b>: a shuffle divides its pulse in three and is barred in four by its own
 * ground-truth cycle, so the division may admit a two-pulse bar and may not
 * promote a four-pulse one, and 12/8 and 3/8 stay with {@code --time-signature}
 * (#701). Not for want of a sharper statistic: the position a compound sounds
 * and a shuffle leaves out is {@code tools/MeterSweep.java}'s {@code mid}
 * column, and a shuffle the corpus confirms in four carries more of it than
 * either the 12/8 that is compound by construction or a compound recording
 * whose tracked pulse is its counted beat. What it measures is the middle of a
 * pulse against the beat itself, and the corpus holds that independent of the
 * meter. And
 * <b>the accent is not asked about the bar length</b>: its strongest periodicity
 * on ordinary drum material is the backbeat (#70), which argues for a two-beat
 * bar on most of the corpus and prefers four to three on material whose harmony
 * names three unambiguously.
 *
 * <p>{@code tools/MeterSweep.java} prints the readings behind every constant
 * here, for the committed benchmarks and the local-only ones alike; read it
 * rather than this text for what they are worth.
 */
public final class MeterEstimator {

    /**
     * The bar length the harmony alone may not choose, because two divides both
     * four and six and the statistic below cannot prefer a period to its own
     * divisors. It reaches {@link Estimate#pulsesPerBar()} only through
     * {@link #barsInTwo}, and it is never a rival to a length it divides: a
     * four-pulse bar scores at period two both by that degeneracy and by the
     * ordinary comping that fills it, so neither reading is a hypothesis
     * competing with the four.
     */
    private static final int IN_TWO = 2;

    /** The bar length assumed when the evidence does not displace it. */
    private static final int ASSUMED = 4;

    /**
     * A bar length this reads, and the meter it names.
     *
     * @param pulses    tracked pulses to one of its bars
     * @param candidate whether the harmony may choose it, which {@link #IN_TWO}
     *                  alone may not
     * @param names     the meter a bar of that many pulses is written in
     */
    private record BarLength(int pulses, boolean candidate,
                             Function<Reading, TimeSignature> names) {
    }

    /**
     * Every bar length this reads, each beside the meter it names — the one
     * place a length is written down, so what a {@link Reading} holds, what the
     * harmony may choose between and what is engraved cannot come to disagree
     * (#706).
     *
     * <p>Three and four are bars; six is a bar the tracker has filled with its
     * own subdivision, which is a reading about the pulse level rather than
     * about the bar and is why {@link Estimate#pulsesPerBar()} is carried beside
     * the meter. Two is read but not chosen, for the reason {@link #IN_TWO}
     * gives.
     */
    private static final List<BarLength> BAR_LENGTHS = List.of(
            new BarLength(IN_TWO, false, reading -> TimeSignature.SIX_EIGHT),
            new BarLength(3, true, reading -> TimeSignature.THREE_FOUR),
            new BarLength(ASSUMED, true, reading -> TimeSignature.FOUR_FOUR),
            new BarLength(6, true, MeterEstimator::groupingOfSix));

    /** The bar lengths the harmony chooses between. */
    private static final List<BarLength> CANDIDATES =
            BAR_LENGTHS.stream().filter(BarLength::candidate).toList();

    /** The lengths a {@link Reading} carries one periodicity for each of. */
    private static final Set<Integer> PULSE_COUNTS = pulseCounts();

    private static Set<Integer> pulseCounts() {
        Set<Integer> pulses = new LinkedHashSet<>();
        for (BarLength length : BAR_LENGTHS) {
            pulses.add(length.pulses());
        }
        return Collections.unmodifiableSet(pulses);
    }

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
     * the longest bar length this reads.
     *
     * <p>Below it the Fourier coefficient is describing the window rather than
     * the music, and its null no longer holds.
     */
    private static final int BARS_FOR_A_READING = 4;

    /**
     * The harmonic statistic's null expectation, which is the floor the
     * two-pulse bar's harmony has to clear.
     *
     * <p>Not {@link #SUPPORTED}: the two-pulse bar is not chosen on the harmony
     * and cannot be held to the level a chosen length is. What it may not do is
     * rest on harmony that scores, at every length two divides, below what a
     * period nothing happens at scores on average. That average is a property
     * of the statistic rather than of the corpus, and it is a floor rather than
     * a test: the null scores above its own mean often enough that aperiodic
     * harmony still passes.
     */
    private static final double THE_NULL = 1.0;

    /**
     * How much of the pulse's own periodicity the onset envelope must carry at
     * a triple division of it before the pulse counts as dividing in three.
     *
     * <p>A share rather than a level, because the envelope's periodicity at the
     * pulse is what the division is a division of, and recordings differ by an
     * order of magnitude in how periodic they are at all.
     * {@code tools/MeterSweep.java} prints the share, and the pulse it is a
     * share of, for every benchmark and every local-only recording; read it
     * rather than this text for the room either side of this.
     */
    private static final double DIVIDES_IN_THREE = 0.65;

    /**
     * How much of the envelope's own energy has to sit at the tracked pulse
     * before a division of it is a share of anything.
     *
     * <p>The divisions are read against the pulse, so a pulse the envelope does
     * not carry makes them a ratio of two noise levels, and a ratio of noise
     * clears any level in either direction. {@code tools/MeterSweep.java}'s
     * {@code pulse} column is what the corpus carries here, and
     * {@code MeterEstimationTest} pins the other side on an envelope with
     * nothing at the pulse at all.
     */
    private static final double PULSE_PERIODIC = 0.10;

    /**
     * How far either side of a division's lag the envelope's peak is looked for,
     * as a share of the lag.
     *
     * <p>The tracked pulse is a mean over the recording and a division of it is
     * played by hand, so the peak sits near the arithmetic lag rather than on
     * it.
     */
    private static final double LAG_TOLERANCE = 0.03;

    private MeterEstimator() {
    }

    /** The longest bar a reading may name, which sets how long a reading needs. */
    private static int longestBar() {
        int longest = 0;
        for (BarLength length : BAR_LENGTHS) {
            longest = Math.max(longest, length.pulses());
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
     * <p>One statistic at every bar length {@link #BAR_LENGTHS} holds, so they
     * may be read against each other, and beside them the one thing here that is
     * not read from the harmony at all: how the tracked pulse divides.
     *
     * @param harmonic    harmonic periodicity at each of those lengths, keyed by
     *                    tracked pulses to a bar; the assumed length's entry is
     *                    the assumption's own score
     * @param inThree     the stronger of the onset envelope's periodicities at
     *                    the two lags a triple division of the pulse puts a peak
     *                    at, over its periodicity at the pulse itself; zero
     *                    where the pulse carries too little for it to be a share
     *                    of anything
     * @param inTwo       the same at a half of the pulse and at a quarter of it,
     *                    which is where dividing it evenly puts a peak
     * @param onThePulse  how much of the envelope's energy sits at the pulse
     *                    itself, which is what the two above are shares of and
     *                    the floor they have to clear to be shares at all
     * @param usableBeats beats novelty is defined at, which is what the harmonic
     *                    periodicities are measured over
     */
    public record Reading(Map<Integer, Double> harmonic, double inThree, double inTwo,
                          double onThePulse, int usableBeats) {

        public Reading {
            Objects.requireNonNull(harmonic, "harmonic");
            if (!harmonic.keySet().equals(PULSE_COUNTS)) {
                throw new IllegalArgumentException(
                        "a reading carries one periodicity per bar length: expected "
                                + PULSE_COUNTS + ", got " + harmonic.keySet());
            }
            SortedMap<Integer, Double> copied = new TreeMap<>();
            for (int pulses : PULSE_COUNTS) {
                copied.put(pulses, Objects.requireNonNull(harmonic.get(pulses),
                        () -> "no periodicity at " + pulses + " tracked pulses"));
            }
            harmonic = Collections.unmodifiableSortedMap(copied);
        }

        /** The harmonic periodicity at a bar length this reads. */
        public double at(int pulses) {
            Double periodicity = harmonic.get(pulses);
            if (periodicity == null) {
                throw new IllegalArgumentException("not a period this reads: " + pulses);
            }
            return periodicity;
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
        if (usable < BARS_FOR_A_READING * longestBar() || chroma.frameCount() == 0) {
            return new Reading(noHarmony(), 0, 0, 0, Math.max(0, usable));
        }
        if (!chroma.isBeatSynchronous() || chroma.frameCount() != beatTimes.size() - 1) {
            throw new IllegalArgumentException(
                    "chroma must be beat-synchronous over these beats: expected "
                            + (beatTimes.size() - 1) + " inter-beat spans, got "
                            + chroma.frameCount()
                            + (chroma.isBeatSynchronous() ? "" : " on a fixed time grid"));
        }

        double[] novelty = DownbeatEstimator.harmonicNovelty(chroma);
        Map<Integer, Double> harmonic = new HashMap<>();
        for (BarLength length : BAR_LENGTHS) {
            harmonic.put(length.pulses(),
                    periodicity(novelty, firstBeat, lastBeat, length.pulses()));
        }
        Divisions divisions = divisionsOfThePulse(envelope, trackedPulse(beatTimes));
        return new Reading(harmonic, divisions.inThree(), divisions.inTwo(),
                divisions.onThePulse(), usable);
    }

    /** No periodicity at any length, which is what too short a stretch reports. */
    private static Map<Integer, Double> noHarmony() {
        Map<Integer, Double> harmonic = new HashMap<>();
        for (BarLength length : BAR_LENGTHS) {
            harmonic.put(length.pulses(), 0.0);
        }
        return harmonic;
    }

    /**
     * The meter a reading names.
     *
     * <p>One winner and one fallback: a bar length that fails a gate returns the
     * assumption rather than the next candidate down, because a candidate that
     * only wins once the winner is disqualified was never the evidence.
     *
     * <p>{@link #barsInTwo} is answered first, so the division of the pulse
     * outranks a bar length the harmony won (#727). What it takes precedence
     * over is only ever a six, a winning three refusing the two-pulse bar
     * outright; and six pulses that each divide in three are not a bar anyone
     * writes, both meters {@link #meterAt} names for six holding three quarter
     * notes and so counting the pulse an eighth.
     */
    public static Estimate decide(Reading reading) {
        Objects.requireNonNull(reading, "reading");
        if (barsInTwo(reading)) {
            return new Estimate(meterAt(reading, IN_TWO), IN_TWO, confidenceInTwo(reading));
        }
        int best = ASSUMED;
        for (BarLength length : CANDIDATES) {
            if (reading.at(length.pulses()) > reading.at(best)) {
                best = length.pulses();
            }
        }
        best = longestAccountingFor(reading, best);
        if (best == ASSUMED || !clearsThePrior(reading, best)) {
            return new Estimate(meterAt(reading, ASSUMED), ASSUMED,
                    confidenceIn(reading, ASSUMED));
        }
        return new Estimate(meterAt(reading, best), best, confidenceIn(reading, best));
    }

    /**
     * Whether a longer bar length accounts for a shorter one's periodicity
     * rather than the shorter being a reading of its own.
     *
     * <p>The shorter divides the longer, so novelty that really repeats at the
     * longer scores the shorter as strongly, and {@link #DIVIDED} is where the
     * two cases part. Written over whatever divisor pairs the candidates hold.
     */
    private static boolean accountsFor(Reading reading, int longer, int shorter) {
        return longer > shorter && longer % shorter == 0
                && reading.at(longer) >= DIVIDED * reading.at(shorter);
    }

    /**
     * The longest candidate that accounts for the one the harmony scored
     * highest, which is that one where nothing longer does. Without it the
     * shorter reading takes the bar on floating-point residue.
     */
    private static int longestAccountingFor(Reading reading, int best) {
        int longest = best;
        for (BarLength length : CANDIDATES) {
            if (length.pulses() > longest && accountsFor(reading, length.pulses(), best)) {
                longest = length.pulses();
            }
        }
        return longest;
    }

    /**
     * Whether the recording is barred in two tracked pulses, which this names
     * 6/8 — the pulse being a dotted quarter wherever it divides in three.
     *
     * <p>The harmony cannot decide this on its own and is not asked to: a
     * four-beat bar comping every two beats produces the same period two, and
     * the corpus puts recordings of that kind above the compound ones on the
     * harmonic statistic. So the harmony is asked only not to contradict it, at
     * the lengths where it can: the best it scores at a length two divides
     * clears {@link #THE_NULL} and leads whatever a three of its own says. The
     * division of the pulse decides, and a waltz's three-pulse bar is what the
     * veto still keeps out of its reach.
     *
     * <p>A three a six accounts for is not contrary either (#727).
     * Three divides six, so novelty that repeats every six beats states the
     * three as well, and that three is the six seen again rather than a rival
     * to it — six being a length two divides, its shadow cannot be one. Which
     * of the two it is, {@link #shadowOfALongerBar} answers.
     */
    private static boolean barsInTwo(Reading reading) {
        double tiled = 0;
        double contrary = 0;
        for (BarLength length : BAR_LENGTHS) {
            int pulses = length.pulses();
            if (pulses % IN_TWO == 0) {
                tiled = Math.max(tiled, reading.at(pulses));
            } else if (!shadowOfALongerBar(reading, pulses)) {
                contrary = Math.max(contrary, reading.at(pulses));
            }
        }
        if (tiled < THE_NULL || contrary >= SUPPORTED || contrary >= tiled) {
            return false;
        }
        return reading.inThree() >= DIVIDES_IN_THREE && reading.inThree() > reading.inTwo();
    }

    /**
     * Whether a period is one a longer candidate already accounts for, rather
     * than a reading of its own. Every gate that candidate has to clear to be
     * believed, so a period dismissed here is dismissed by a bar length that
     * would otherwise have taken the reading.
     */
    private static boolean shadowOfALongerBar(Reading reading, int period) {
        for (BarLength length : CANDIDATES) {
            if (accountsFor(reading, length.pulses(), period)
                    && clearsThePrior(reading, length.pulses())) {
                return true;
            }
        }
        return false;
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

    /** The meter a bar length names, from the entry in {@link #BAR_LENGTHS} that names it. */
    private static TimeSignature meterAt(Reading reading, int pulsesPerBar) {
        for (BarLength length : BAR_LENGTHS) {
            if (length.pulses() == pulsesPerBar) {
                return length.names().apply(reading);
            }
        }
        throw new IllegalArgumentException("not a bar length this reads: " + pulsesPerBar);
    }

    /**
     * Which meter a bar of six tracked pulses is written in, the tracker being on
     * a subdivision rather than on the counted beat: how the six group — in two
     * threes, which is 6/8, or in three twos, which is 3/4 — read from the same
     * harmonic statistic. Both divide six, so a bar that marks nothing inside
     * itself scores them alike. Nothing about the bar lines turns on it: 3/4 and
     * 6/8 hold the same three quarter notes, so at six pulses to a bar they agree
     * on every bar line and on the pulse, and differ only in what is printed.
     */
    private static TimeSignature groupingOfSix(Reading reading) {
        return reading.at(3) >= reading.at(IN_TWO)
                ? TimeSignature.SIX_EIGHT : TimeSignature.THREE_FOUR;
    }

    /**
     * How far the evidence backs a bar length, from the floor to the ceiling.
     *
     * <p>Two things have to hold and they fail separately: the length has to
     * carry periodicity at all, and it has to carry more of it than the best of
     * its rivals by the margin leaving the prior costs. Multiplied, so that
     * either failing brings the number down. A four-beat bar held against a
     * stronger rival therefore reports near the floor, which is the honest
     * reading of an assumption that survived contrary evidence.
     *
     * <p>A period the chosen length is a multiple of is held to parity instead
     * of to that margin (#709). The statistic scores a divisor of a real period
     * as strongly as the period itself, so the shorter reading is the chosen
     * one's own shadow rather than a competing account of the same beats, and
     * asking a bar length to beat its shadow by the margin asks the impossible.
     * What it may still be asked is not to fall below it: a length its own
     * divisor outscores is carried by what fills it rather than by its own
     * line, which is what {@link #DIVIDED} admits and this is what it costs.
     */
    private static Confidence confidenceIn(Reading reading, int chosen) {
        double rival = 0;
        double shadow = 0;
        for (BarLength length : CANDIDATES) {
            int pulses = length.pulses();
            if (chosen % pulses != 0) {
                rival = Math.max(rival, reading.at(pulses));
            } else if (pulses != chosen) {
                shadow = Math.max(shadow, reading.at(pulses));
            }
        }
        double observed = Math.clamp(reading.at(chosen) / SUPPORTED, 0, 1);
        double separation = Math.clamp(reading.at(chosen)
                / Math.max(MARGIN * Math.max(rival, SUPPORTED), shadow), 0, 1);
        return Confidence.clamped(
                ASSUMED_CONFIDENCE + (CEILING - ASSUMED_CONFIDENCE) * observed * separation);
    }

    /**
     * How the pulse divides, each as a share of the pulse's own periodicity, and
     * that periodicity as a share of the envelope's energy.
     */
    private record Divisions(double inThree, double inTwo, double onThePulse) {
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
     * <p>Zero for both where the envelope carries less than
     * {@link #PULSE_PERIODIC} of its energy at the pulse, since a share of noise
     * is noise and reaches any level in either direction.
     */
    private static Divisions divisionsOfThePulse(OnsetEnvelope envelope, double pulseSeconds) {
        double lag = envelope.frameRate() * pulseSeconds;
        int longest = (int) Math.ceil(lag * (1 + LAG_TOLERANCE)) + 1;
        if (!(lag > 0) || longest >= envelope.length()) {
            return new Divisions(0, 0, 0);
        }
        double[] correlation = TempoEstimator.autocorrelate(centred(envelope.strength()), longest);
        if (!(correlation[0] > 0)) {
            return new Divisions(0, 0, 0);
        }
        double pulse = peakNear(correlation, lag);
        double onThePulse = pulse / correlation[0];
        if (!(onThePulse >= PULSE_PERIODIC)) {
            return new Divisions(0, 0, onThePulse);
        }
        double inThree =
                Math.max(peakNear(correlation, lag / 3), peakNear(correlation, 2 * lag / 3));
        double inTwo = Math.max(peakNear(correlation, lag / 2), peakNear(correlation, lag / 4));
        return new Divisions(inThree / pulse, inTwo / pulse, onThePulse);
    }

    /**
     * The signal about its own mean, which is what makes the shares above
     * shares of anything (#726).
     */
    private static double[] centred(double[] signal) {
        double mean = 0;
        for (double value : signal) {
            mean += value;
        }
        mean /= Math.max(1, signal.length);
        double[] out = new double[signal.length];
        for (int i = 0; i < signal.length; i++) {
            out[i] = signal[i] - mean;
        }
        return out;
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
     * How long one tracked pulse is, in seconds, which is the pulse the
     * divisions above are read against: {@link BeatGrid#steadyPulseRate(List)}
     * inverted, so the estimator and the grid derive this one fact one way
     * (#711).
     *
     * <p>That rule keeps what a median was chosen here for — a dropped beat
     * leaves one interval of twice the pulse, and the trimmed mean discards it
     * as readily as a median does — and drops what a median cost: an observed
     * interval comes off the frame axis, so the lag placed from it is quantised
     * to the analysis hop, which is the same defect #200 took off the chart's
     * bar rate.
     *
     * <p>Public so that {@code tools/MeterSweep.java} prints the pulse this
     * class read rather than deriving one of its own beside it.
     *
     * @throws IllegalArgumentException if there are fewer than two beats, or
     *                                  they are not finite, non-negative and
     *                                  strictly increasing
     */
    public static double trackedPulse(List<Double> beatTimes) {
        return 60.0 / BeatGrid.steadyPulseRate(beatTimes);
    }

    /**
     * How far the evidence backs a two-pulse bar, which is a different question
     * from the one {@link #confidenceIn} answers.
     *
     * <p>Mostly the division, because that is what chose the length: how much of
     * the pulse the triple carries, and how far it leads the duple. The harmony
     * is read only up to {@link #SUPPORTED} and is flat above it, so what it
     * contributes is whether it says anything about period two rather than how
     * strongly — a strong one is a four-beat bar's comping as readily as a bar
     * of two, while one barely over {@link #THE_NULL} means the veto was
     * satisfied by something a recording with no harmonic period would have
     * satisfied it with too. Period two rather than the length
     * {@link #barsInTwo} let through, because a chord loop spanning several
     * bars corroborates the bar it tiles no more than the one it does.
     */
    private static Confidence confidenceInTwo(Reading reading) {
        double periodic = Math.clamp(reading.at(IN_TWO) / SUPPORTED, 0, 1);
        double carried = Math.clamp(reading.inThree(), 0, 1);
        double lead = Math.clamp(reading.inThree() - reading.inTwo(), 0, 1);
        return Confidence.clamped(
                ASSUMED_CONFIDENCE + (CEILING - ASSUMED_CONFIDENCE) * periodic * carried * lead);
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
