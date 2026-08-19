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

package dev.olivelli.musicwizard.arrange;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.Section;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleUnaryOperator;
import java.util.function.ToDoubleFunction;

/**
 * Turns a performance into something a musician can read: chooses a grid per
 * bar, takes out any shuffle, and fills in the musical timing every note is
 * missing.
 *
 * <p>The objective is plausibility, not reproduction — a more literal
 * transcription is usually a worse lead sheet. Per bar, each of
 * {@link GridResolution} the settings permit in that bar's meter is scored as
 * total onset deviation plus a complexity penalty charged per note; the per-bar scores are then decoded
 * with a Viterbi pass charging {@link QuantizationSettings#gridChangePenalty()}
 * for changing subdivision between bars, waived at a {@link Section} boundary.
 * One grid per bar for the whole score, so the staves of a system agree about
 * what a beam means.
 *
 * <p>Chords, sections and keys go onto the beat axis too — leaving them in
 * seconds would leave two independently rounded answers to where beat three is
 * — but not onto the note grid: a section or key change goes to the nearest
 * bar line, where it is engraved, and a chord to the nearest counted beat, the
 * unit both chord estimators already decide over. So the chord printed on a
 * beat need not be the one sounding there (#158): a change heard in the second
 * half of a beat is an anticipation and is written on the beat it approaches,
 * within half a counted beat of where the harmony changes (plus at most the
 * overlap {@code ChordProgression} tolerates, on the one clamp path;
 * {@code theBoundHoldsWhereTheClampMovesABoundary} asserts the excess). The
 * bound assumes boundaries stated in seconds — a progression re-read against a
 * corrected tempo map keeps its carried beats, which is #171, a separate
 * defect.
 *
 * <p>A span shorter than the unit it snaps to can collapse to nothing, and
 * nothing is ever deleted (#157, reversing #147: a dropped chord makes the
 * chart name a harmony nobody played, which no downstream fix can recover). A
 * collapsed section or key keeps its seconds and gets no beats; a collapsed
 * chord withdraws the whole progression from the beat axis, because
 * {@code isQuantized()} is one verdict and every consumer gates on it. #173
 * (per-chord placement) is the answer that is not a trade, and should land
 * before this pass is wired into the pipeline.
 *
 * <p>Untouched: the seconds on everything, so the approximation stays
 * recoverable; and the shuffle on spans — a chord boundary is where the
 * harmony changed, not where a finger landed, so de-swinging it would round it
 * to the wrong side of the beat it anticipates.
 */
public final class Quantizer {

    /**
     * Refuses to build a bar table longer than this. Not a musical limit but a
     * guard on the tempo map: a units mix-up can put the last note billions of
     * bars in, and an array allocation is an unhelpful way to find that out.
     */
    private static final int MAX_BARS = 400_000;

    private Quantizer() {
    }

    /** Quantizes with the calibrated default settings. */
    public static QuantizedScore quantize(Score score) {
        return quantize(score, QuantizationSettings.DEFAULT);
    }

    /**
     * Quantizes every note in every track of a score, and puts its chords,
     * sections and keys on the beat axis. A score with no notes is the
     * ordinary audio-path case, not a degenerate one.
     *
     * @throws IllegalArgumentException if the score's tempo map places its last
     *         event past {@value #MAX_BARS} bars
     */
    public static QuantizedScore quantize(Score score, QuantizationSettings settings) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(settings, "settings");

        TempoMap tempoMap = score.tempoMap();
        List<Note> allNotes = score.tracks().stream().flatMap(t -> t.notes().stream()).toList();
        if (allNotes.isEmpty() && score.chords().isEmpty()
                && score.sections().isEmpty() && score.keys().isEmpty()) {
            return new QuantizedScore(score, List.of(), SwingFeel.STRAIGHT);
        }

        BarTable bars = BarTable.spanning(tempoMap, score, allNotes);

        // The structure goes first, because the grid decision below reads it: a
        // section may change subdivision for free, and the bar it may do so at
        // has to be the bar the section is published at.
        List<Section> sections = onGrid(score.sections(),
                section -> bars.beatOf(section.startBeat(), section.startSeconds()),
                section -> bars.beatOf(section.endBeat(), section.endSeconds()),
                Section::quantizedTo, bars::snapToBarLine, Quantizer::inSecondsOnly);
        List<Key> keys = onGrid(score.keys(),
                key -> bars.beatOf(key.startBeat(), key.startSeconds()),
                key -> bars.beatOf(key.endBeat(), key.endSeconds()),
                Key::quantizedTo, bars::snapToBarLine, Quantizer::inSecondsOnly);
        List<Chord> chords = chordsOnGrid(score.chords().chords(), bars);

        SwingFeel swing = SwingFeel.STRAIGHT;
        List<NoteTrack> quantizedTracks = score.tracks();
        List<BarGrid> grids = List.of();
        if (!allNotes.isEmpty()) {
            swing = settings.detectSwing()
                    ? SwingDetector.detect(allNotes, bars)
                    : SwingFeel.STRAIGHT;

            GridResolution[] perBar = chooseGrids(allNotes, sections, bars, swing, settings);

            quantizedTracks = new ArrayList<>(score.tracks().size());
            boolean[] sounds = new boolean[bars.barCount()];
            for (NoteTrack track : score.tracks()) {
                List<Note> notes = new ArrayList<>(track.notes().size());
                for (Note note : track.notes()) {
                    Note snapped = snap(note, bars, perBar, swing, settings);
                    // Marked by where it was snapped to, not played — a rushed
                    // downbeat prints in the next bar — and through every bar
                    // it sounds in, because a tied tail still needs that bar's
                    // grid to be printed at.
                    markSounding(sounds, bars, snapped);
                    notes.add(snapped);
                }
                quantizedTracks.add(track.withNotes(notes));
            }
            grids = publishedGrids(sounds, bars, perBar);
        }

        Score quantized = new Score(score.title(), score.artist(), tempoMap, score.beatGrid(),
                keys, sections, quantizedTracks, score.chords().withChords(chords),
                score.lyrics(), score.durationSeconds());
        return new QuantizedScore(quantized, grids, swing);
    }

    // ---------------------------------------------------------------- spans

    /** How a span of the model is handed the two beat positions it was given. */
    @FunctionalInterface
    private interface Placed<T> {
        T at(T span, double startBeat, double endBeat);
    }

    /**
     * What a span whose boundaries snapped to the same position comes back as.
     * Meant to be total: {@link #onGrid} adds whatever comes back, once per
     * span, so it returns as many spans as it was handed.
     */
    @FunctionalInterface
    private interface Collapsed<T> {
        /** The span to publish in place of the one that had no length. */
        T instead(T span);
    }

    /**
     * Puts a progression on the beat axis, or leaves the whole of it in
     * seconds. The all-or-nothing is forced by
     * {@link dev.olivelli.musicwizard.core.model.ChordProgression#isQuantized()}
     * being one verdict, and the direction it falls is #157: no chord is
     * discarded. After this pass a non-empty progression whose
     * {@code isQuantized()} is false is one that was withdrawn.
     */
    private static List<Chord> chordsOnGrid(List<Chord> chords, BarTable bars) {
        boolean[] collapsed = {false};
        List<Chord> placed = onGrid(chords,
                chord -> bars.beatOf(chord.startBeat(), chord.startSeconds()),
                chord -> bars.beatOf(chord.endBeat(), chord.endSeconds()),
                Chord::quantizedTo, bars::snapToCountedBeat,
                chord -> {
                    collapsed[0] = true;
                    // Stripped rather than left carrying beats, so the list is
                    // well-formed even though the branch below discards it.
                    return inSecondsOnly(chord);
                });
        return collapsed[0] ? chords.stream().map(Quantizer::inSecondsOnly).toList() : placed;
    }

    /**
     * Strips any musical timing a collapsed span was carrying, so the
     * postcondition is unconditional: after this pass a span either sits on
     * the grid or carries no beats at all.
     */
    private static Section inSecondsOnly(Section section) {
        return section.isQuantized()
                ? new Section(section.kind(), section.label(), section.startSeconds(),
                        section.endSeconds(), Optional.empty(), Optional.empty(),
                        section.repetitionGroup(), section.confidence())
                : section;
    }

    private static Key inSecondsOnly(Key key) {
        return key.isQuantized()
                ? new Key(key.tonic(), key.mode(), key.startSeconds(), key.endSeconds(),
                        Optional.empty(), Optional.empty(), key.confidence(),
                        key.signatureConfidence(), key.tonicConfidence())
                : key;
    }

    /**
     * The same for a chord, applied to a whole progression at once — leaving
     * stale beat positions on a withdrawn progression would wait for the next
     * reader who ignores {@code isQuantized()}.
     */
    private static Chord inSecondsOnly(Chord chord) {
        return chord.isQuantized()
                ? new Chord(chord.root(), chord.quality(), chord.bass(), chord.startSeconds(),
                        chord.endSeconds(), Optional.empty(), Optional.empty(),
                        chord.confidence())
                : chord;
    }

    /**
     * Puts a list of ordered, non-overlapping spans onto the beat axis.
     *
     * <p>A start never precedes the furthest end already <em>placed</em>. The
     * model admits a microsecond of overlap in seconds, and a microsecond
     * straddling a rounding midpoint snaps to two positions a whole unit apart
     * — without the clamp this pass would emit the beat-axis overlap
     * {@link Score} rejects (#59). A span left with no length becomes the
     * caller's {@code Collapsed} decision, and {@code furthestEnd} does not
     * advance past it: a collapsed span's snapped end can be far ahead of the
     * value carried, and advancing would push the next span off a bar line it
     * snapped to cleanly, on the strength of a boundary that was not
     * published.
     */
    private static <T> List<T> onGrid(List<T> spans, ToDoubleFunction<T> startBeat,
                                      ToDoubleFunction<T> endBeat, Placed<T> placed,
                                      DoubleUnaryOperator snap, Collapsed<T> collapsed) {
        List<T> out = new ArrayList<>(spans.size());
        double furthestEnd = Double.NEGATIVE_INFINITY;
        for (T span : spans) {
            double start = Math.max(snap.applyAsDouble(startBeat.applyAsDouble(span)), furthestEnd);
            double end = snap.applyAsDouble(endBeat.applyAsDouble(span));
            if (end <= start) {
                out.add(collapsed.instead(span));
                continue;
            }
            out.add(placed.at(span, start, end));
            furthestEnd = end;
        }
        return out;
    }

    // ---------------------------------------------------------------- grids

    /**
     * Scores every grid in every bar, then decodes the sequence under the
     * section prior.
     */
    private static GridResolution[] chooseGrids(List<Note> notes, List<Section> sections,
                                                BarTable bars, SwingFeel swing,
                                                QuantizationSettings settings) {
        GridResolution[] candidates = GridResolution.values();
        return decode(costs(notes, bars, swing, settings, candidates), candidates,
                sectionStarts(sections, bars), settings.gridChangePenalty());
    }

    /**
     * The cost of every grid in every bar. A bar with no onsets costs zero on
     * every grid and inherits its neighbours' rather than voting for whole
     * notes. Every note votes in the bar it sounded in, including one rushed
     * onto the next bar line — charging it where it prints was tried twice and
     * withdrawn; what such a note needs is its value taken from the bar it
     * reaches, which {@link #snap} does directly.
     */
    private static double[][] costs(List<Note> notes, BarTable bars, SwingFeel swing,
                                    QuantizationSettings settings, GridResolution[] candidates) {
        double[][] cost = new double[bars.barCount()][candidates.length];
        for (Note note : notes) {
            double written = bars.writtenOnsetBeat(note, swing);
            int bar = bars.barOf(written);
            double beatInBar = bars.beatInBar(written, bar);
            TimeSignature meter = bars.meterOf(bar);
            for (int g = 0; g < candidates.length; g++) {
                double step = candidates[g].stepQuarters(meter);
                cost[bar][g] += Math.abs(beatInBar - snapWithin(beatInBar, step))
                        + complexity(candidates[g], meter, settings);
            }
        }
        // Priced out of the bar rather than out of the candidate array, because
        // which divisions a part may be written on depends on the meter and a
        // score may change meter (#594). Charged on the empty bars too: they
        // cost nothing on every grid and would otherwise inherit a division
        // their own meter forbids.
        for (int bar = 0; bar < bars.barCount(); bar++) {
            TimeSignature meter = bars.meterOf(bar);
            for (int g = 0; g < candidates.length; g++) {
                if (!settings.permits(candidates[g], meter)) {
                    cost[bar][g] = Double.POSITIVE_INFINITY;
                }
            }
        }
        return cost;
    }

    /**
     * What a grid costs to read one note on, in quarter-note beats so it is
     * commensurable with the deviation it competes against. Charged per note,
     * not per bar — a per-bar penalty is outvoted the moment a bar gets busy,
     * and per-note charging makes the bar length drop out of the comparison.
     */
    private static double complexity(GridResolution grid, TimeSignature meter,
                                     QuantizationSettings settings) {
        return settings.levelPenalty() * grid.depthIn(meter)
                + (grid.isTupletIn(meter) ? settings.tupletPenalty() : 0.0);
    }

    /**
     * Viterbi over the bars, where the only structure is a reluctance to
     * change subdivision inside a section. Ties go to staying on the current
     * grid, then to the simplest one, so the result does not turn on
     * comparison order.
     */
    private static GridResolution[] decode(double[][] cost, GridResolution[] candidates,
                                           boolean[] sectionStart, double changePenalty) {
        int barCount = cost.length;
        int states = candidates.length;
        double[][] best = new double[barCount][states];
        int[][] previous = new int[barCount][states];

        System.arraycopy(cost[0], 0, best[0], 0, states);

        for (int bar = 1; bar < barCount; bar++) {
            double penalty = sectionStart[bar] ? 0.0 : changePenalty;
            for (int g = 0; g < states; g++) {
                double bestValue = best[bar - 1][g];
                int bestFrom = g;
                for (int from = 0; from < states; from++) {
                    if (from == g) {
                        continue;
                    }
                    double value = best[bar - 1][from] + penalty;
                    if (value < bestValue) {
                        bestValue = value;
                        bestFrom = from;
                    }
                }
                best[bar][g] = bestValue + cost[bar][g];
                previous[bar][g] = bestFrom;
            }
        }

        int last = 0;
        for (int g = 1; g < states; g++) {
            if (best[barCount - 1][g] < best[barCount - 1][last]) {
                last = g;
            }
        }
        GridResolution[] chosen = new GridResolution[barCount];
        int state = last;
        for (int bar = barCount - 1; bar >= 0; bar--) {
            chosen[bar] = candidates[state];
            state = previous[bar][state];
        }
        return chosen;
    }

    /**
     * The bars at which a section begins, where the grid may change for free —
     * the one place a reader expects the feel to change. Read from the
     * sections <em>after</em> they have been placed, not from their seconds:
     * waiving the penalty at the bar a boundary sounds in while printing the
     * double bar at the one it is engraved at would answer the same fact
     * twice. A section too short to be placed marks nothing.
     */
    private static boolean[] sectionStarts(List<Section> sections, BarTable bars) {
        boolean[] starts = new boolean[bars.barCount()];
        for (Section section : sections) {
            section.startBeat().ifPresent(beat -> starts[bars.barOf(beat)] = true);
        }
        return starts;
    }

    /** One entry per bar a note is printed in, in bar order. */
    private static List<BarGrid> publishedGrids(boolean[] sounds, BarTable bars,
                                                GridResolution[] perBar) {
        List<BarGrid> published = new ArrayList<>();
        for (int bar = 0; bar < bars.barCount(); bar++) {
            if (sounds[bar]) {
                published.add(new BarGrid(bar, bars.startBeat(bar), perBar[bar], bars.meterOf(bar)));
            }
        }
        return published;
    }

    /** Marks every bar a quantized note sounds in, ties included. */
    private static void markSounding(boolean[] sounds, BarTable bars, Note note) {
        double onsetBeat = note.onsetBeat().orElseThrow();
        double endBeat = note.offsetBeat().orElseThrow();
        int last = bars.barOf(endBeat);
        // A note ending exactly on a bar line does not sound in the bar it ends
        // on; that bar line is where the tie stops, not where the note begins.
        if (endBeat <= bars.startBeat(last)) {
            last--;
        }
        for (int bar = bars.barOf(onsetBeat); bar <= last; bar++) {
            sounds[bar] = true;
        }
    }

    // ---------------------------------------------------------------- snapping

    /**
     * Places one note on the grid.
     *
     * <p>Onset and offset are each snapped in their own bar's grid rather than
     * both in the onset's. A note held across a bar line into a differently
     * divided bar should end where that bar's reader expects a note to end; the
     * alternative prints a triplet-length tail four bars after the triplet
     * stopped.
     */
    private static Note snap(Note note, BarTable bars, GridResolution[] perBar,
                             SwingFeel swing, QuantizationSettings settings) {
        double writtenOnset = bars.writtenOnsetBeat(note, swing);
        int onsetBar = bars.barOf(writtenOnset);
        TimeSignature onsetMeter = bars.meterOf(onsetBar);
        double onsetStep = perBar[onsetBar].stepQuarters(onsetMeter);
        double onsetSteps = stepsWithin(bars.beatInBar(writtenOnset, onsetBar), onsetStep);
        // A note rushed onto the next bar's line is printed there, so it takes
        // that bar's grid with it — left behind, it gets a note value from a
        // bar it does not appear in.
        if (onsetSteps >= onsetMeter.quarterBeatsPerBar() / onsetStep
                && onsetBar + 1 < bars.barCount()) {
            onsetBar++;
            onsetMeter = bars.meterOf(onsetBar);
            onsetStep = perBar[onsetBar].stepQuarters(onsetMeter);
            onsetSteps = 0;
        }
        double onsetBeat = bars.startBeat(onsetBar) + onsetSteps * onsetStep;

        double writtenOffset = bars.writtenOffsetBeat(note, swing);
        int offsetBar = bars.barOf(writtenOffset);
        TimeSignature offsetMeter = bars.meterOf(offsetBar);
        double offsetStep = perBar[offsetBar].stepQuarters(offsetMeter);
        double offsetInBar = bars.beatInBar(writtenOffset, offsetBar);
        double offsetSteps = stepsWithin(
                articulated(offsetInBar, writtenOffset - writtenOnset, offsetStep, settings),
                offsetStep);
        double offsetBeat = bars.startBeat(offsetBar) + offsetSteps * offsetStep;

        // A carried onset can leave the release behind it, and a note too
        // short to print collapses onto its own onset. Either way it is
        // lengthened to one step of the bar it prints in rather than dropped —
        // the pitch was played, and one step is the shortest thing the grid
        // can say. Everything past this point has strictly positive length.
        if (offsetBeat <= onsetBeat) {
            return note.quantizedTo(onsetBeat, onsetStep);
        }

        // Counted in whole grid steps and multiplied once, rather than
        // subtracting two snapped positions: on a triplet grid the step is not
        // representable, and the notation layer has to match the duration
        // against a note value — "one third, nearly" is not one. Only
        // available while every bar the note crosses is divided the same way;
        // otherwise the note will be split at the bar line and tied anyway.
        java.util.OptionalInt uniform =
                uniformSteps(bars, perBar, onsetBar, offsetBar, onsetStep);
        double duration = uniform.isPresent()
                ? (offsetSteps + uniform.getAsInt() - onsetSteps) * onsetStep
                : bars.startBeat(offsetBar) - bars.startBeat(onsetBar)
                        + offsetSteps * offsetStep - onsetSteps * onsetStep;

        return note.quantizedTo(onsetBeat, duration);
    }

    /**
     * Grid steps between the start of the onset's bar and the start of the
     * offset's, when every bar in between is divided into steps of the same
     * length; empty when one of them is not.
     *
     * <p>Never asked about an offset earlier than the onset, which
     * {@link #snap} can produce by carrying a rushed onset forward: both
     * guards here are vacuous in that case and it would report a uniformity it
     * never checked. {@code snap} returns before it gets here.
     */
    private static java.util.OptionalInt uniformSteps(BarTable bars, GridResolution[] perBar,
                                                      int onsetBar, int offsetBar, double step) {
        int divisions = 0;
        for (int bar = onsetBar; bar < offsetBar; bar++) {
            TimeSignature meter = bars.meterOf(bar);
            if (perBar[bar].stepQuarters(meter) != step) {
                return java.util.OptionalInt.empty();
            }
            divisions += perBar[bar].divisionsPerBar(meter);
        }
        if (offsetBar > onsetBar && perBar[offsetBar].stepQuarters(bars.meterOf(offsetBar)) != step) {
            return java.util.OptionalInt.empty();
        }
        return java.util.OptionalInt.of(divisions);
    }

    /**
     * A release position moved to where the note was probably written to end.
     * Players let go early, so a note snapped straight to the nearest grid
     * position loses a step; the allowance divides the played length by
     * {@link QuantizationSettings#articulationRatio()} to undo that, bounded
     * to one grid position because an unbounded stretch grows with the note.
     * It fires only when plain rounding cannot already explain the length —
     * within {@link QuantizationSettings#overlapTolerance()} of whole steps is
     * left alone, or notes nobody shortened get lengthened; the trade is
     * measured in {@code ArticulationAllowanceTest}.
     *
     * @param offsetInBar the released position within its bar, in quarter beats
     * @param playedBeats how long the note sounded, in quarter beats, measured
     *                    between the two un-snapped written positions
     */
    private static double articulated(double offsetInBar, double playedBeats, double step,
                                      QuantizationSettings settings) {
        double lengthSteps = playedBeats / step;
        double plain = Math.rint(lengthSteps);
        if (lengthSteps <= plain * (1 + settings.overlapTolerance())) {
            return offsetInBar;
        }
        return Math.min(
                offsetInBar + playedBeats * (1 / settings.articulationRatio() - 1),
                Math.ceil(offsetInBar / step) * step);
    }

    /**
     * Snaps a position within a bar to the nearest grid step.
     *
     * <p>A note played a hair early against a bar line lands on the bar line,
     * and it is {@code rint} that puts it there: the last position in a bar is
     * the next bar's downbeat, not the last step inside it. Nothing clamps that
     * -- see {@link #stepsWithin}.
     */
    private static double snapWithin(double beatInBar, double step) {
        return stepsWithin(beatInBar, step) * step;
    }

    /**
     * The same, left as a whole number of steps for the caller to scale once.
     *
     * <p>A position exactly halfway between two steps goes to the later one —
     * a tie is common on a chord boundary, where a change on the off-beat
     * eighth before a downbeat is an <em>anticipation</em> and belongs on the
     * beat ahead. {@code rint}, which this used to be, rounds a tie to the
     * even step, so the direction alternated with the beat index and half a
     * progression's anticipations printed a beat early. On notes only a
     * tick-exact MIDI import reaches the tie, and the direction is right there
     * too.
     */
    private static double stepsWithin(double beatInBar, double step) {
        // floor(x + 0.5) rather than Math.round, which would take a long and
        // need the position bounded; and rather than rint, whose tie rule is
        // the defect above.
        return Math.floor(beatInBar / step + 0.5);
    }

    // ---------------------------------------------------------------- bar table

    /**
     * Bar starts and meters, precomputed once, plus the conversions every stage
     * of the quantizer shares.
     *
     * <p>Bar lengths are whole numbers of sixteenths -- a numerator of at most
     * 64 over a power of two of at most 64 -- so accumulating them is exact in a
     * double, and a bar start computed here is bit-identical to the one
     * {@link TempoMap#toBeat} would produce. That matters because the notation
     * layer places a bar line at whichever of the two it happens to ask.
     */
    private static final class BarTable {

        private final TempoMap tempoMap;
        private final double[] startBeat;
        private final TimeSignature[] meter;

        private BarTable(TempoMap tempoMap, double[] startBeat, TimeSignature[] meter) {
            this.tempoMap = tempoMap;
            this.startBeat = startBeat;
            this.meter = meter;
        }

        /**
         * A table long enough to hold everything in the score that has a
         * position — the spans too, which routinely outlast the notes. Sizing
         * from the notes alone left an audio-path score with a table too short
         * to place its own harmony, and {@link #barOf} clamped the tail into
         * the last bar it had.
         */
        static BarTable spanning(TempoMap tempoMap, Score score, List<Note> notes) {
            double lastBeat = 0;
            for (Note note : notes) {
                lastBeat = Math.max(lastBeat, rawBeat(tempoMap, note.offsetSeconds()));
            }
            for (Chord chord : score.chords().chords()) {
                lastBeat = Math.max(lastBeat,
                        beatOf(tempoMap, chord.endBeat(), chord.endSeconds()));
            }
            for (Section section : score.sections()) {
                lastBeat = Math.max(lastBeat,
                        beatOf(tempoMap, section.endBeat(), section.endSeconds()));
            }
            for (Key key : score.keys()) {
                lastBeat = Math.max(lastBeat, beatOf(tempoMap, key.endBeat(), key.endSeconds()));
            }
            // Bounded before converting, not after: a map slightly less absurd
            // than what toMusicalTime rejects still asks for a gigabyte of bar
            // table. The shortest declared bar bounds the index exactly.
            double shortestBar = tempoMap.meterChanges().stream()
                    .mapToDouble(change -> change.timeSignature().quarterBeatsPerBar())
                    .min().orElseThrow();
            if (!(lastBeat / shortestBar <= MAX_BARS)) {
                throw new IllegalArgumentException(
                        "the tempo map places the last event beyond bar " + MAX_BARS
                                + ", which is past the bar limit; the map is almost certainly"
                                + " wrong rather than the music that long");
            }
            int lastBar = tempoMap.toMusicalTime(lastBeat).bar();
            // One spare bar past the last release, or a note rushed onto the
            // final bar line has no bar to be carried into and prints on a
            // downbeat no BarGrid covers.
            int count = lastBar + 2;
            double[] starts = new double[count];
            TimeSignature[] meters = new TimeSignature[count];
            for (int bar = 0; bar < count; bar++) {
                meters[bar] = tempoMap.timeSignatureAtBar(bar);
                if (bar > 0) {
                    starts[bar] = starts[bar - 1] + meters[bar - 1].quarterBeatsPerBar();
                }
            }
            return new BarTable(tempoMap, starts, meters);
        }

        private static double rawBeat(TempoMap tempoMap, double seconds) {
            // No reachable input converts negative today; this is the only
            // place that would notice a future map whose anchoring loosened.
            return Math.max(0, tempoMap.secondsToBeats(seconds));
        }

        int barCount() {
            return startBeat.length;
        }

        double startBeat(int bar) {
            return startBeat[bar];
        }

        TimeSignature meterOf(int bar) {
            return meter[bar];
        }

        /** A wall-clock time as a raw, un-quantized position on the beat axis. */
        double rawBeat(double seconds) {
            return rawBeat(tempoMap, seconds);
        }

        /**
         * Where a span boundary already is on the beat axis: the position it
         * carries if it carries one, otherwise its wall-clock time converted.
         * Preferring the carried value keeps re-quantizing an
         * already-quantized score a no-op rather than a slow drift (#115).
         */
        static double beatOf(TempoMap tempoMap, Optional<Double> carried, double seconds) {
            return carried.isPresent() ? carried.get() : rawBeat(tempoMap, seconds);
        }

        double beatOf(Optional<Double> carried, double seconds) {
            return beatOf(tempoMap, carried, seconds);
        }

        /**
         * The nearest counted beat, the finest position a chord symbol can
         * take. Measured inside the bar so the answer follows the meter, and
         * built back up from the bar's own start so it stays bit-identical to
         * the bar line the notation layer will draw.
         */
        double snapToCountedBeat(double beat) {
            int bar = barOf(beat);
            double unit = meter[bar].beatUnitQuarters();
            return startBeat[bar] + stepsWithin(beatInBar(beat, bar), unit) * unit;
        }

        /**
         * The nearest bar line, the only position a key change or a section
         * boundary can take. A boundary rounded up lands on exactly the double
         * this table holds for the next bar, not an ulp away from it.
         */
        double snapToBarLine(double beat) {
            int bar = barOf(beat);
            double barLength = meter[bar].quarterBeatsPerBar();
            return startBeat[bar] + stepsWithin(beatInBar(beat, bar), barLength) * barLength;
        }

        /** The bar a position on the beat axis falls in, clamped to the table. */
        int barOf(double beat) {
            return Math.min(tempoMap.toMusicalTime(beat).bar(), startBeat.length - 1);
        }

        /**
         * Where a position sits inside its bar, in quarter beats. Clamped: a
         * hand-built meter change can disagree with its own tempo segments,
         * and a negative position would snap into the previous bar rather
         * than fail visibly.
         */
        double beatInBar(double beat, int bar) {
            return Math.clamp(beat - startBeat[bar], 0.0, meter[bar].quarterBeatsPerBar());
        }

        /** Where a note's onset should be written, once any shuffle is removed. */
        double writtenOnsetBeat(Note note, SwingFeel swing) {
            return deswing(rawBeat(note.onsetSeconds()), swing);
        }

        /**
         * Where a note's release was played, on the written timeline. The
         * articulation allowance is applied later, once the grid it is
         * bounded by is known; see {@link Quantizer#snap}.
         */
        double writtenOffsetBeat(Note note, SwingFeel swing) {
            return deswing(rawBeat(note.offsetSeconds()), swing);
        }

        /**
         * Moves a position from where it was played to where it should be
         * written.
         *
         * <p>The map acts within one counted beat and never moves a position out
         * of it, so bar and beat are unaffected and this is safe to apply before
         * the grid is known.
         */
        double deswing(double rawBeat, SwingFeel swing) {
            if (!swing.swung()) {
                return rawBeat;
            }
            int bar = barOf(rawBeat);
            // Checked here as well as where the feel was measured: one verdict
            // covers the whole piece, and de-swinging a compound bar puts its
            // plain eighths onto a duplet grid.
            if (meter[bar].isCompound()) {
                return rawBeat;
            }
            double beatUnit = meter[bar].beatUnitQuarters();
            double position = beatInBar(rawBeat, bar) / beatUnit;
            double index = Math.floor(position);
            return startBeat[bar]
                    + (index + swing.toWrittenPhase(position - index)) * beatUnit;
        }

        /** The phase of a raw beat position within its counted beat, in [0,1). */
        double phaseWithinBeat(double rawBeat) {
            int bar = barOf(rawBeat);
            double position = beatInBar(rawBeat, bar) / meter[bar].beatUnitQuarters();
            return position - Math.floor(position);
        }
    }

    // ---------------------------------------------------------------- swing

    /**
     * Finds a shuffle by looking at where onsets fall inside the counted beat.
     * Straight playing puts its off-beats at the half; a shuffle puts them
     * near two thirds and leaves the middle empty, so the test is bimodality:
     * a cluster on the beat, a tight cluster late, nothing much between. The
     * tightness requirement is what keeps sixteenths and genuine triplets
     * from reading as swing.
     *
     * <p>Compound bars are not looked at, and that is not a shortcut: a
     * shuffle <em>is</em> compound time written in a simple meter, and the
     * commonest 6/8 rhythm lands on the shuffle signature exactly — measured
     * against a straight-time expectation it reads as swung, gets de-swung
     * onto a duplet grid, and is engraved with a swing direction on top (#4).
     *
     * <p>One verdict for the whole piece: a track that swings its bridge and
     * not its verses has the majority feel applied throughout.
     */
    private static final class SwingDetector {

        /** Onsets within this much of a beat count as being on the beat. */
        private static final double ON_BEAT_WINDOW = 0.12;

        /**
         * The part of the beat an off-beat eighth can occupy. The far edge is
         * {@link SwingFeel#MAX_RATIO} rather than a number of its own, so
         * everything this window can see is something the correction map can
         * straighten.
         */
        private static final double OFF_BEAT_LOW = 0.30;
        private static final double OFF_BEAT_HIGH = SwingFeel.MAX_RATIO;

        /** Below this many off-beat onsets there is nothing to average. */
        private static final int MIN_OFF_BEAT = 8;

        /** A shuffle needs the on-beat cluster too, or it is not bimodal. */
        private static final int MIN_ON_BEAT = 4;

        /** How late the off-beat cluster has to sit before it counts as swung. */
        private static final double SWING_THRESHOLD = 0.58;

        /**
         * How tight that cluster has to be. Sits between the spread of played
         * sixteenths (above it) and played shuffles (below it), both measured
         * in {@code SwingDetectionTest}'s fixtures; what loses a genuine
         * shuffle in practice is {@link #SWING_THRESHOLD} rather than this.
         */
        private static final double MAX_SPREAD = 0.09;

        /** Off-beat onsets at which the detection is considered fully supported. */
        private static final double FULL_SUPPORT = 24.0;

        /** The classic triplet shuffle, at which the reading is unequivocal. */
        private static final double FULL_SWING = 2.0 / 3;

        private SwingDetector() {
        }

        static SwingFeel detect(List<Note> notes, BarTable bars) {
            List<Double> offBeat = new ArrayList<>();
            int onBeat = 0;
            for (Note note : notes) {
                double beat = bars.rawBeat(note.onsetSeconds());
                if (bars.meterOf(bars.barOf(beat)).isCompound()) {
                    continue;
                }
                double phase = bars.phaseWithinBeat(beat);
                if (phase < ON_BEAT_WINDOW || phase >= 1 - ON_BEAT_WINDOW) {
                    onBeat++;
                }
                if (phase >= OFF_BEAT_LOW && phase <= OFF_BEAT_HIGH) {
                    offBeat.add(phase);
                }
            }
            if (offBeat.size() < MIN_OFF_BEAT || onBeat < MIN_ON_BEAT) {
                return SwingFeel.STRAIGHT;
            }

            double mean = offBeat.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double variance = offBeat.stream()
                    .mapToDouble(p -> (p - mean) * (p - mean))
                    .sum() / offBeat.size();
            double spread = Math.sqrt(variance);
            if (mean < SWING_THRESHOLD || spread > MAX_SPREAD) {
                return SwingFeel.STRAIGHT;
            }

            // Confidence has to fall off near the threshold as well as with
            // spread and sample count. A cluster at 0.585 is a shuffle by a hair
            // and should not be reported as confidently as one at 0.667.
            double tightness = Math.clamp(1 - spread / MAX_SPREAD, 0, 1);
            double support = Math.clamp(offBeat.size() / FULL_SUPPORT, 0, 1);
            double decisiveness = Math.clamp(
                    (mean - SWING_THRESHOLD) / (FULL_SWING - SWING_THRESHOLD), 0, 1);
            return new SwingFeel(true, mean,
                    Confidence.clamped(tightness * support * decisiveness));
        }
    }
}
