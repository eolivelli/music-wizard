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

package dev.olivelli.musicwizard.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Tracked beat positions, with downbeats marked.
 *
 * <p>Produced by beat tracking and consumed by everything that needs to speak in
 * musical time. Downbeat detection is markedly less reliable than beat
 * detection, so the two carry separate confidences and callers are expected to
 * check them independently.
 *
 * <p>A grid holds <em>pulses</em>, and what one pulse is worth is not something
 * the pulses themselves say. Usually it is the meter's counted beat -- a quarter
 * note in 4/4, a dotted quarter in 6/8 -- but a tracker locked onto half tempo
 * emits one pulse every two quarter notes, and nothing in a list of times
 * distinguishes the two. {@link #pulseQuarters()} records it where it was known,
 * so that {@link #medianTempo(TimeSignature)} and
 * {@link TempoMap#fromBeatTimes(List, TimeSignature, double)} built from the same
 * pulses cannot answer figures a factor apart (#139).
 *
 * <p>The pulse is <em>not</em> derivable from the bar cycle, tempting though the
 * arithmetic is -- pulses per bar times quarter notes per pulse is the meter's
 * quarter beats per bar, always. A grid of {@link Beat#unphased} pulses has no
 * cycle, and a grid shorter than a bar has one that is observably wrong: three
 * tracked pulses of a 4/4 bar reach a maximum {@code positionInBar} of 2 and
 * would imply a pulse of 4/3 quarter notes.
 *
 * @param beats  the tracked beats, ordered in time and non-empty
 * @param beatConfidence     trust in the beat positions themselves
 * @param downbeatConfidence trust in which of those beats are downbeats
 * @param pulseQuarters quarter notes spanned by one tracked pulse, when the
 *                      stage that tracked them recorded it; empty for a grid
 *                      read from a file written before the pulse was carried,
 *                      or assembled by a caller that did not say
 */
public record BeatGrid(
        List<Beat> beats,
        Confidence beatConfidence,
        Confidence downbeatConfidence,
        OptionalDouble pulseQuarters) {

    /**
     * One tracked beat.
     *
     * @param seconds    when the beat falls
     * @param downbeat   whether this beat begins a bar
     * @param positionInBar zero-based position within its bar, or -1 if unknown
     */
    public record Beat(double seconds, boolean downbeat, int positionInBar) {
        public Beat {
            if (!Double.isFinite(seconds) || seconds < 0) {
                throw new IllegalArgumentException("beat time must be finite and non-negative, got: " + seconds);
            }
            if (positionInBar < -1) {
                throw new IllegalArgumentException("positionInBar must be -1 or non-negative, got: " + positionInBar);
            }
            // A downbeat is by definition position 0 of its bar; letting the two
            // disagree would give downstream stages two contradictory answers.
            if (downbeat && positionInBar < 0) {
                throw new IllegalArgumentException(
                        "a downbeat is by definition position 0 of its bar, so positionInBar"
                                + " must not be unknown");
            }
            if (positionInBar >= 0 && downbeat != (positionInBar == 0)) {
                throw new IllegalArgumentException(
                        "downbeat=" + downbeat + " contradicts positionInBar=" + positionInBar);
            }
        }

        /** A beat whose position within the bar has not been determined. */
        public static Beat unphased(double seconds) {
            return new Beat(seconds, false, -1);
        }
    }

    public BeatGrid {
        Objects.requireNonNull(beats, "beats");
        Objects.requireNonNull(beatConfidence, "beatConfidence");
        Objects.requireNonNull(downbeatConfidence, "downbeatConfidence");
        // Normalised rather than rejected, because this is the property that an
        // existing score.json does not have. Jackson passes null for a missing
        // creator property, and rejecting it here would make every file written
        // before this change unreadable -- which is #22, the breakage this
        // project has already paid for once, and #142 is the same shape still
        // open.
        if (pulseQuarters == null) {
            pulseQuarters = OptionalDouble.empty();
        }
        if (pulseQuarters.isPresent()) {
            requirePulseQuarters(pulseQuarters.getAsDouble());
        }
        if (beats.isEmpty()) {
            throw new IllegalArgumentException("a beat grid needs at least one beat");
        }
        beats = List.copyOf(beats);
        for (int i = 1; i < beats.size(); i++) {
            if (beats.get(i).seconds() <= beats.get(i - 1).seconds()) {
                throw new IllegalArgumentException(
                        "beats must strictly increase in time; beat " + i + " at "
                                + beats.get(i).seconds() + "s does not follow beat " + (i - 1)
                                + " at " + beats.get(i - 1).seconds() + "s");
            }
        }
    }

    /**
     * A grid that does not record what its tracked pulse was worth.
     *
     * <p>Kept so that a caller assembling a grid by hand -- a test, or a stage
     * that genuinely does not know, such as a tracker given no meter -- says so
     * by omission rather than by picking a plausible-looking pulse. A reader is
     * then left with the meter's counted beat, which is what every grid in
     * existence was tracked at. A producer that knows should use
     * {@link #withPulseQuarters(double)} or the four-argument constructor.
     */
    public BeatGrid(List<Beat> beats, Confidence beatConfidence, Confidence downbeatConfidence) {
        this(beats, beatConfidence, downbeatConfidence, OptionalDouble.empty());
    }

    /**
     * Returns a copy recording that one tracked pulse spans
     * {@code pulseQuarters} quarter notes.
     *
     * <p>For the stage that tracked the pulses and knows the meter they were
     * tracked against, which is not always the one that built the grid: a beat
     * tracker phases bars from onset energy without ever being told what a bar
     * is worth.
     *
     * <p>This records a claim and can check only that it is a note value. It
     * cannot check it against the bars the grid already marks, because a grid
     * holds no meter. A pulse that contradicts them is caught when the grid
     * meets a tempo map, in {@link Score} -- but only where the grid shows two
     * downbeats to contradict and the map holds one meter, so this is a
     * <em>claim</em> a caller should get right rather than one that will always
     * be checked.
     */
    public BeatGrid withPulseQuarters(double pulseQuarters) {
        return new BeatGrid(
                beats, beatConfidence, downbeatConfidence, OptionalDouble.of(pulseQuarters));
    }

    /**
     * Rejects a pulse no note value the model can name could be.
     *
     * <p>The same bounds
     * {@link TempoMap#fromBeatTimes(List, TimeSignature, double)} enforces, and
     * deliberately so: the two are given the same figure by the same caller, and
     * a pulse one accepts and the other rejects would send that caller looking
     * for a difference between them that is not there.
     */
    private static void requirePulseQuarters(double pulseQuarters) {
        if (!Double.isFinite(pulseQuarters)
                || pulseQuarters < 1.0 / 1024 || pulseQuarters > 1024) {
            throw new IllegalArgumentException(
                    "pulseQuarters must be finite and between 1/1024 and 1024 quarter notes,"
                            + " got: " + pulseQuarters);
        }
    }

    /**
     * Builds a grid from bare beat times, assuming a fixed meter and a downbeat
     * on the first beat.
     *
     * <p>Prefer {@link #ofTimes(List, TimeSignature, Confidence)}: the count
     * wanted here is tracked pulses per bar, which is the meter's numerator only
     * in simple time. In 6/8 it is two, and passing six phases the downbeats
     * three times too slowly.
     *
     * @param beatsPerBar tracked pulses in one bar, not the meter's numerator
     */
    public static BeatGrid ofTimes(List<Double> beatSeconds, int beatsPerBar, Confidence confidence) {
        Objects.requireNonNull(beatSeconds, "beatSeconds");
        if (beatsPerBar < 1) {
            throw new IllegalArgumentException("beatsPerBar must be positive, got: " + beatsPerBar);
        }
        List<Beat> built = new ArrayList<>(beatSeconds.size());
        for (int i = 0; i < beatSeconds.size(); i++) {
            int position = i % beatsPerBar;
            built.add(new Beat(beatSeconds.get(i), position == 0, position));
        }
        return new BeatGrid(built, confidence, confidence);
    }

    /**
     * Builds a grid from bare beat times in a known meter, one tracked pulse per
     * counted beat.
     *
     * <p>The safe form of {@link #ofTimes(List, int, Confidence)}, because the
     * meter answers the question that overload leaves to the caller: 6/8 bars
     * every two pulses, not every six.
     *
     * <p>The resulting grid records the pulse it was built on -- the meter's
     * counted beat -- so a later reader is told rather than left to assume it.
     * {@link #medianTempo(TimeSignature)} therefore answers what it always did
     * <em>when asked in this meter</em>, since the recorded figure is the one
     * the assumption produced. Asked in another meter it now answers this
     * grid's tempo rather than that meter's reading of its pulses, which is the
     * point of #139 and a change from what it used to say.
     */
    public static BeatGrid ofTimes(
            List<Double> beatSeconds, TimeSignature timeSignature, Confidence confidence) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        // Through the pulse-aware form rather than straight to beatsPerBar(),
        // so that this file holds one route from a pulse to a bar length and not
        // two. The count is identical -- pulsesPerBar(beatUnitQuarters()) is
        // beatsPerBar() for all 448 legal signatures, asserted -- so no grid
        // this ever built is barred differently.
        return ofTimes(beatSeconds, timeSignature, timeSignature.beatUnitQuarters(), confidence);
    }

    /**
     * Builds a grid from bare beat times tracked at a pulse of their own, rather
     * than at the meter's counted beat.
     *
     * <p>The counterpart of
     * {@link TempoMap#fromBeatTimes(List, TimeSignature, double)}, and to be
     * given the same {@code pulseQuarters} by the same caller: a map and a grid
     * built from one set of pulses under two different pulse units disagree
     * about the tempo by exactly their ratio, which is #139.
     *
     * <p>Bars follow from the pulse rather than from the meter's beat count,
     * since the meter counts beats and this grid does not hold them: a 4/4 bar
     * takes two pulses of two quarter notes, not four.
     *
     * @param pulseQuarters quarter notes spanned by one tracked pulse: 1.0 for a
     *                      quarter-note pulse, 1.5 for the dotted-quarter pulse
     *                      of compound time, 2.0 for a grid tracked at half tempo
     * @throws IllegalArgumentException if the pulse does not divide a bar of this
     *         meter into a whole number of pulses, which would leave the grid
     *         unable to say where a bar begins
     */
    public static BeatGrid ofTimes(List<Double> beatSeconds, TimeSignature timeSignature,
                                   double pulseQuarters, Confidence confidence) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        requirePulseQuarters(pulseQuarters);
        return ofTimes(beatSeconds, timeSignature.pulsesPerBar(pulseQuarters), confidence)
                .withPulseQuarters(pulseQuarters);
    }

    /** Just the beat times. */
    public List<Double> beatTimes() {
        return beats.stream().map(Beat::seconds).toList();
    }

    /** Just the downbeat times. */
    public List<Double> downbeatTimes() {
        return beats.stream().filter(Beat::downbeat).map(Beat::seconds).toList();
    }

    /**
     * Median rate of the tracked pulses, in pulses per minute.
     *
     * <p>Deliberately not called a tempo. A grid holds pulses, and a pulse is a
     * quarter note only in simple time, so this is 1.5x under the quarter-note
     * tempo in 6/8 -- exactly the conflation that mis-barred compound meters in
     * the first place. Use {@link #medianTempo(TimeSignature)} for a figure
     * comparable with {@link TempoMap#tempoAtBeat(double)}.
     */
    public double medianPulseRate() {
        if (beats.size() < 2) {
            throw new IllegalStateException("cannot infer tempo from fewer than two beats");
        }
        double[] intervals = new double[beats.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = beats.get(i + 1).seconds() - beats.get(i).seconds();
        }
        java.util.Arrays.sort(intervals);
        int middle = intervals.length / 2;
        double median = intervals.length % 2 == 1
                ? intervals[middle]
                : (intervals[middle - 1] + intervals[middle]) / 2.0;
        return 60.0 / median;
    }

    /**
     * Median tempo in quarter notes per minute, the unit every other tempo in the
     * model is in.
     *
     * <p>A recorded {@link #pulseQuarters()} wins over the meter, because it is
     * what the grid was tracked at and the meter is only what it was probably
     * tracked at. Reading the meter regardless is #139: a grid tracked at half
     * tempo reported half the tempo the map built from the very same pulses
     * described.
     *
     * <p>Where the grid records nothing, the meter's counted beat is still the
     * answer -- every grid written before the pulse was carried was tracked at
     * it, so such a grid reports exactly the tempo it always did. The meter is
     * therefore still required rather than optional: it is the fallback, not
     * decoration.
     */
    public double medianTempo(TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return medianPulseRate() * pulseQuarters.orElseGet(timeSignature::beatUnitQuarters);
    }

    /** Index of the beat nearest a given time. */
    public int nearestBeatIndex(double seconds) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < beats.size(); i++) {
            double distance = Math.abs(beats.get(i).seconds() - seconds);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            } else {
                // Times are sorted, so once distance grows we are past the nearest.
                break;
            }
        }
        return best;
    }

    public int size() {
        return beats.size();
    }
}
