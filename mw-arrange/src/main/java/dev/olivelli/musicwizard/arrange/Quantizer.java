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

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.Section;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a performance into something a musician can read: chooses a grid per
 * bar, takes out any shuffle, and fills in the musical timing every note is
 * missing.
 *
 * <h2>What it is trying to do</h2>
 *
 * <p>Not to reproduce the recording. Even a transformer given ground-truth beats
 * reaches only about 83% note-value accuracy, and the ceiling is not the
 * interesting part -- the interesting part is that a <em>more</em> literal
 * transcription is usually a worse lead sheet. A triplet passage written as an
 * eighth followed by two sixteenths is arithmetically closer to what was played
 * and musically useless. So the objective is plausibility, and the mechanism is
 * a complexity penalty: a finer or more exotic grid has to buy enough accuracy
 * to be worth the reading cost.
 *
 * <h2>How the grid is chosen</h2>
 *
 * <p>Per bar, each candidate in {@link GridResolution} is scored as the total
 * absolute distance from every onset in the bar to its nearest grid position,
 * plus a complexity penalty charged per note. Because the binary grids are
 * nested, a finer one can only ever reduce the deviation, and on material that
 * really is on the coarse grid it reduces it only by the fraction of the
 * player's own noise it happens to absorb -- so the penalty decides, which is
 * what makes the choice stable. The triplet grids are not nested with the
 * binary ones and genuinely compete.
 *
 * <p>Those per-bar scores are then decoded with a Viterbi pass that charges
 * {@link QuantizationSettings#gridChangePenalty()} for changing subdivision
 * between adjacent bars, with the charge waived at a {@link Section} boundary.
 * This is not a refinement. A grid picked per bar with no prior changes
 * subdivision on nothing more than which way the noise fell, and the result is
 * unreadable. A bar holding no onsets scores zero everywhere and therefore
 * inherits its neighbours' grid rather than dragging the section back to whole
 * notes.
 *
 * <p>One grid is chosen per bar for the whole score rather than per track, so
 * that the staves of a system agree about what a beam means. The cost is that a
 * dense drum part can pull a bar finer than the melody needed; the benefit is
 * that the melody is then still exactly on the grid, because the binary grids
 * are nested.
 *
 * <h2>What it does not touch</h2>
 *
 * <p>The seconds. {@link Note} carries wall-clock and musical timing separately
 * precisely so that an un-quantized value cannot be mistaken for a quantized
 * one, and so that the approximation made here stays recoverable. Chords,
 * sections and keys keep their own optional beat fields empty; aligning those is
 * a separate decision with its own failure modes.
 */
public final class Quantizer {

    /**
     * Refuses to build a bar table longer than this.
     *
     * <p>Not a musical limit -- 400,000 bars is about a week of 4/4 at 120 BPM.
     * It is a guard on the tempo map: a map built from a units mix-up can put
     * the last note billions of bars in, and an array allocation is an unhelpful
     * way to find that out.
     */
    private static final int MAX_BARS = 400_000;

    /**
     * How far past a whole number of grid steps a note may sound before the
     * articulation allowance decides it must have been written longer.
     *
     * <p>Small because it is only there to recognise a note held <em>through</em>
     * its written end rather than short of it. Measured against three
     * articulation distributions and note lengths from one to sixteen steps,
     * two per cent recovers as many durations as applying the allowance
     * unconditionally does, and does not lengthen the notes that unconditional
     * version got wrong.
     */
    private static final double OVERLAP_TOLERANCE = 0.02;

    private Quantizer() {
    }

    /** Quantizes with the calibrated default settings. */
    public static QuantizedScore quantize(Score score) {
        return quantize(score, QuantizationSettings.DEFAULT);
    }

    /**
     * Quantizes every note in every track of a score.
     *
     * @throws IllegalArgumentException if the score's tempo map places a note
     *         past {@value #MAX_BARS} bars
     */
    public static QuantizedScore quantize(Score score, QuantizationSettings settings) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(settings, "settings");

        TempoMap tempoMap = score.tempoMap();
        List<Note> allNotes = score.tracks().stream().flatMap(t -> t.notes().stream()).toList();
        if (allNotes.isEmpty()) {
            return new QuantizedScore(score, List.of(), SwingFeel.STRAIGHT);
        }

        BarTable bars = BarTable.spanning(tempoMap, allNotes);
        SwingFeel swing = settings.detectSwing()
                ? SwingDetector.detect(allNotes, bars)
                : SwingFeel.STRAIGHT;

        GridResolution[] perBar = chooseGrids(allNotes, score, bars, swing, settings);

        List<NoteTrack> quantizedTracks = new ArrayList<>(score.tracks().size());
        boolean[] sounds = new boolean[bars.barCount()];
        for (NoteTrack track : score.tracks()) {
            List<Note> notes = new ArrayList<>(track.notes().size());
            for (Note note : track.notes()) {
                Note snapped = snap(note, bars, perBar, swing, settings);
                // Which bar a note prints in is decided by where it was snapped
                // to, not by where it was played. A downbeat rushed by ten
                // milliseconds sounds in the previous bar and is printed in this
                // one, and publishing by the played position would announce a
                // grid for a bar with nothing in it.
                sounds[bars.barOf(snapped.onsetBeat().orElseThrow())] = true;
                notes.add(snapped);
            }
            quantizedTracks.add(track.withNotes(notes));
        }

        Score quantized = new Score(score.title(), score.artist(), tempoMap, score.beatGrid(),
                score.keys(), score.sections(), quantizedTracks, score.chords(), score.lyrics(),
                score.durationSeconds());
        return new QuantizedScore(quantized, publishedGrids(sounds, bars, perBar), swing);
    }

    // ---------------------------------------------------------------- grids

    /**
     * Scores every grid in every bar, then decodes the sequence under the
     * section prior.
     */
    private static GridResolution[] chooseGrids(List<Note> notes, Score score, BarTable bars,
                                                SwingFeel swing, QuantizationSettings settings) {
        GridResolution[] candidates = GridResolution.values();
        return decode(costs(notes, bars, swing, settings, candidates), candidates,
                sectionStarts(score, bars), settings.gridChangePenalty());
    }

    /**
     * The cost of every grid in every bar.
     *
     * <p>A bar with no onsets accumulates nothing at all, so it costs zero on
     * every grid and inherits its neighbours' rather than voting for whole notes
     * and charging the section two grid changes to get back around it.
     *
     * <p>Every note votes in the bar it sounded in, including one played a hair
     * early against a bar line, which will be printed in the next. Two attempts
     * to charge such a note where it prints have now been withdrawn: deciding it
     * per candidate makes the six costs in a bar sums over six different sets of
     * notes, and deciding it from a provisional decode does not converge -- it
     * oscillates with period two, and changes nothing any measurement could see.
     * What such a note actually needed was its note value taken from the bar it
     * reaches, and {@link #snap} does that directly.
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
                cost[bar][g] += Math.abs(beatInBar - snapWithin(beatInBar, step, meter))
                        + complexity(candidates[g], meter, settings);
            }
        }
        return cost;
    }

    /**
     * What a grid costs to read one note on, in quarter-note beats so that it is
     * commensurable with the deviation it competes against.
     *
     * <p>Charged per note, not per bar, and that is the whole of why the choice
     * is stable. Human timing spread is a fixed fraction of a beat, so a finer
     * grid absorbs a fixed fraction of it <em>per note</em>: sixteen notes on a
     * thirty-second grid fit sixteen times better than four do, and a per-bar
     * penalty is outvoted the moment a bar gets busy. Charging per note puts
     * both sides of the comparison on the same footing, and it makes the meter's
     * bar length drop out -- a 12/8 bar is not pushed towards finer grids merely
     * for holding three times the music.
     */
    private static double complexity(GridResolution grid, TimeSignature meter,
                                     QuantizationSettings settings) {
        return settings.levelPenalty() * grid.depthIn(meter)
                + (grid.isTupletIn(meter) ? settings.tupletPenalty() : 0.0);
    }

    /**
     * Viterbi over the bars, where the only structure is a reluctance to change
     * subdivision inside a section.
     *
     * <p>Ties go to staying on the current grid, and failing that to the
     * simplest one, so that the result does not turn on the order comparisons
     * happen to be made in. Not because a tie is expected: an exact one needs a
     * bar with no notes, whose cost is zero on every grid, and such a bar is
     * never published. Both halves of the rule are therefore unreachable from
     * any score, and a mutation of either survives the suite.
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
     * The bars at which a section begins, where the grid may change for free.
     *
     * <p>A section boundary is the one place a reader expects the feel to
     * change, so it is the one place the prior should not resist. Bar 0 is not
     * marked: there is no previous bar to leave, and {@link #decode} never
     * charges it.
     */
    private static boolean[] sectionStarts(Score score, BarTable bars) {
        boolean[] starts = new boolean[bars.barCount()];
        for (Section section : score.sections()) {
            starts[bars.barOf(bars.rawBeat(section.startSeconds()))] = true;
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
        double onsetSteps = stepsWithin(bars.beatInBar(writtenOnset, onsetBar), onsetStep, onsetMeter);
        // A note rushed onto the next bar's line is printed there, so it takes
        // that bar's grid with it. Leaving it on the bar it sounded in gives it
        // a note value from a bar it does not appear in -- a downbeat ten
        // milliseconds early, printed among sextuplets, coming out as a whole
        // quarter because the bar behind it was counted in beats.
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
                offsetStep, offsetMeter);
        double offsetBeat = bars.startBeat(offsetBar) + offsetSteps * offsetStep;

        // The onset may have been carried forward onto the next bar's line while
        // the release stayed behind it -- a forty-millisecond blip fifty
        // milliseconds before a bar line does exactly that. The note then has no
        // length to measure, and measuring it anyway gave a whole bar: the step
        // count walked from a later bar to an earlier one, reported uniformity
        // because it had walked nothing, and subtracted zero from a full bar's
        // worth of steps. It gets one step of the bar it prints in.
        if (offsetBeat <= onsetBeat) {
            return note.quantizedTo(onsetBeat, onsetStep);
        }

        // Counted in whole grid steps and multiplied once, rather than
        // subtracting two snapped positions. On a triplet grid the step is not
        // representable, so the difference of two positions built from it lands
        // a few ulps off and one third comes out as several distinct doubles
        // depending on which bar the note fell in. The notation layer has to
        // match a duration against a note value, and "one third, nearly" is not
        // a note value.
        //
        // Only available while every bar the note crosses is divided the same
        // way, which is the ordinary case and includes every note that does not
        // cross a bar line at all. Where the division changes underneath a note
        // the difference of the two positions is all there is, and that note is
        // going to be split at the bar line and tied anyway.
        java.util.OptionalInt uniform =
                uniformSteps(bars, perBar, onsetBar, offsetBar, onsetStep);
        double duration = uniform.isPresent()
                ? (offsetSteps + uniform.getAsInt() - onsetSteps) * onsetStep
                : offsetBeat - onsetBeat;

        // A note shorter than half a grid step -- a grace note, or a staccato
        // sixteenth on an eighth grid -- collapses onto its own onset. It is
        // lengthened rather than dropped: the pitch was played, and one step is
        // the shortest thing this grid can print.
        return note.quantizedTo(onsetBeat, Math.max(duration, onsetStep));
    }

    /**
     * Grid steps between the start of the onset's bar and the start of the
     * offset's, when every bar in between is divided into steps of the same
     * length; empty when one of them is not.
     *
     * <p>The count is a whole number even across a meter change, because a
     * bar's length is always a whole number of its own grid steps. That is what
     * lets the duration be one multiplication rather than a subtraction.
     *
     * <p>Never asked about an offset earlier than the onset, which
     * {@link #snap} can produce by carrying a rushed onset onto the next bar
     * line: both of this loop's guards are vacuous in that case and it would
     * report a uniformity it never checked. {@code snap} returns before it gets
     * here, and that is the only guard -- a second one here would mask it, and
     * then neither could be shown to be doing anything.
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
     *
     * <p>Players let go early, so a note snapped straight to the nearest grid
     * position loses a step: a quarter released after 85% of it, on a sixteenth
     * grid, prints as a dotted eighth and a rest. The allowance divides the
     * played length by {@link QuantizationSettings#articulationRatio()} to undo
     * that, bounded so it can carry a release to the next grid position and no
     * further -- an unbounded proportional stretch grows with the note, and made
     * a nine-beat note into a ten.
     *
     * <p>It fires only when the plain rounding cannot already explain the length.
     * Bounding how far it reaches is not the same as knowing when to reach at
     * all: applied unconditionally it also lengthens notes that were never
     * shortened, and a half note held two milliseconds past its written end came
     * out a sixteenth long. So a length already within
     * {@value #OVERLAP_TOLERANCE} of a whole number of steps is left alone --
     * a player may hold slightly through a note, and that reading needs no help.
     *
     * <p>The tolerance is proportional, so past about twenty-five steps it
     * covers the whole rounding cell and the allowance stops firing. That is
     * moot rather than lucky: the one-step cap already bounds what it could
     * recover on a note that long.
     *
     * @param offsetInBar the released position within its bar, in quarter beats
     * @param playedBeats how long the note sounded, in quarter beats, measured
     *                    between the two un-snapped written positions
     */
    private static double articulated(double offsetInBar, double playedBeats, double step,
                                      QuantizationSettings settings) {
        double lengthSteps = playedBeats / step;
        double plain = Math.rint(lengthSteps);
        // Under half a step there is no whole-step reading for the tolerance to
        // confirm, so the allowance runs and the one-step floor catches it.
        if (plain >= 1 && lengthSteps <= plain * (1 + OVERLAP_TOLERANCE)) {
            return offsetInBar;
        }
        return Math.min(
                offsetInBar + playedBeats * (1 / settings.articulationRatio() - 1),
                Math.ceil(offsetInBar / step) * step);
    }

    /**
     * Snaps a position within a bar to the nearest grid step, clamped to the
     * bar.
     *
     * <p>The upper clamp is the next bar's downbeat rather than the last step
     * inside this bar, because a note played a hair early against a bar line
     * belongs on the bar line.
     */
    private static double snapWithin(double beatInBar, double step, TimeSignature meter) {
        return stepsWithin(beatInBar, step, meter) * step;
    }

    /** The same, left as a whole number of steps for the caller to scale once. */
    private static double stepsWithin(double beatInBar, double step, TimeSignature meter) {
        double steps = Math.rint(beatInBar / step);
        double limit = Math.rint(meter.quarterBeatsPerBar() / step);
        return Math.clamp(steps, 0, limit);
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

        /** A table long enough to hold every note's onset and stretched offset. */
        static BarTable spanning(TempoMap tempoMap, List<Note> notes) {
            double lastBeat = 0;
            for (Note note : notes) {
                lastBeat = Math.max(lastBeat, rawBeat(tempoMap, note.offsetSeconds()));
            }
            // Bounded before converting, not after. TempoMap.toMusicalTime
            // rejects anything past bar Integer.MAX_VALUE with a message about
            // bar indices, which describes the arithmetic rather than the
            // mistake -- and a map only slightly less absurd passes it and then
            // asks for a gigabyte of bar table. The shortest bar the map itself
            // declares bounds the bar index from above, so this is exact rather
            // than conservative.
            double shortestBar = tempoMap.meterChanges().stream()
                    .mapToDouble(change -> change.timeSignature().quarterBeatsPerBar())
                    .min().orElseThrow();
            if (!(lastBeat / shortestBar <= MAX_BARS)) {
                throw new IllegalArgumentException(
                        "the tempo map places the last note beyond bar " + MAX_BARS
                                + ", which is past the bar limit; the map is almost certainly"
                                + " wrong rather than the music that long");
            }
            int lastBar = tempoMap.toMusicalTime(lastBeat).bar();
            // One spare bar past the last release. Nothing can index past it --
            // every index in this class goes through barOf, which clamps -- but
            // without it a note rushed onto the final bar line has nowhere to be
            // published: snap declines to carry the onset forward when there is
            // no bar to carry it into, so the note is printed on a downbeat that
            // no BarGrid covers, and "one entry per bar that holds a note" stops
            // being true at the one place a consumer cannot work around.
            //
            // This was deleted once on the strength of a mutation sweep that
            // found nothing to tell the difference. Nothing could: the sweep had
            // no test for it either.
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
            // Belt and braces rather than a live case: TempoMap requires its
            // first segment to be anchored at beat 0 and second 0, and Note
            // rejects a negative onset, so no reachable input converts to a
            // negative beat today. It stays because toMusicalTime rejects one
            // outright and this is the only place that would notice a future
            // map whose anchoring rule had loosened.
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

        /** The bar a position on the beat axis falls in, clamped to the table. */
        int barOf(double beat) {
            return Math.min(tempoMap.toMusicalTime(beat).bar(), startBeat.length - 1);
        }

        /**
         * Where a position sits inside its bar, in quarter beats.
         *
         * <p>Clamped rather than asserted. The bar came from
         * {@link TempoMap#toMusicalTime} and the start from this table, and the
         * two agree exactly for every map this project builds -- but a caller
         * can hand-build a meter change that disagrees with its own tempo
         * segments, and a negative position would then snap into the previous
         * bar rather than fail visibly.
         */
        double beatInBar(double beat, int bar) {
            return Math.clamp(beat - startBeat[bar], 0.0, meter[bar].quarterBeatsPerBar());
        }

        /** Where a note's onset should be written, once any shuffle is removed. */
        double writtenOnsetBeat(Note note, SwingFeel swing) {
            return deswing(rawBeat(note.onsetSeconds()), swing);
        }

        /**
         * Where a note's release was played, on the written timeline.
         *
         * <p>The articulation allowance is not applied here: it needs to know
         * the grid step in order to be bounded by it, and the grid is chosen
         * later. See {@link Quantizer#snap}.
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
            // Checked here and not only where the feel was measured. A score
            // that swings its 4/4 verses and then goes to 6/8 gets one verdict
            // for the whole piece, and applying it to the compound bars puts
            // their plain eighths onto a duplet grid -- the very defect
            // excluding compound bars from the measurement was meant to remove.
            // The measurement and the correction are two readers of the same
            // fact, and guarding only the one the bug was reported against is
            // how a fix stops at the layer it was noticed on.
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
     *
     * <p>Straight playing puts its off-beats at 0.5; a shuffle puts them near
     * 0.667 and leaves the middle of the beat empty. So the test is bimodality:
     * a cluster on the beat, a second tight cluster late in the beat, and
     * nothing much in between.
     *
     * <p>The tightness requirement is what keeps a run of sixteenths from being
     * read as swing. Sixteenths put onsets at 0.25, 0.5 and 0.75, whose spread
     * is far wider than any shuffle's, even though their mean sits above 0.5.
     * It is also what keeps a genuine triplet passage out: onsets at 0.333 and
     * 0.667 average to 0.5 and spread wide, so triplets read as straight -- and
     * that is correct, because they are triplets, not a shuffle.
     *
     * <p>Compound bars are not looked at, and that is not a shortcut. A shuffle
     * <em>is</em> compound time written in a simple meter, so in a meter that is
     * already compound there is nothing to take out: the natural subdivision of
     * a 6/8 beat sits at a third and two thirds, and the commonest rhythm in the
     * meter -- a quarter and an eighth -- lands on the shuffle signature exactly,
     * with a tighter cluster than any human shuffle. Measured against a
     * straight-time expectation it reads as a 66% swing, and the bar is then
     * de-swung onto a duplet grid and engraved with a swing direction on top.
     * That is #4 arriving in the one place {@link GridResolution} does not
     * reach.
     *
     * <p>One verdict for the whole piece. A track that swings its bridge and not
     * its verses will have the majority feel applied throughout.
     */
    private static final class SwingDetector {

        /** Onsets within this much of a beat count as being on the beat. */
        private static final double ON_BEAT_WINDOW = 0.12;

        /**
         * The part of the beat an off-beat eighth can occupy.
         *
         * <p>The far edge is {@link SwingFeel#MAX_RATIO} rather than a number of
         * its own, so that everything this window can see is something the
         * correction map can straighten. The two drifting apart is what put
         * sixty-four notes on thirty-three downbeats.
         */
        private static final double OFF_BEAT_LOW = 0.30;
        private static final double OFF_BEAT_HIGH = SwingFeel.MAX_RATIO;

        /** Below this many off-beat onsets there is nothing to average. */
        private static final int MIN_OFF_BEAT = 8;

        /** A shuffle needs the on-beat cluster too, or it is not bimodal. */
        private static final int MIN_ON_BEAT = 4;

        /** How late the off-beat cluster has to sit before it counts as swung. */
        private static final double SWING_THRESHOLD = 0.58;

        /** How tight that cluster has to be. Sixteenths spread about 0.19. */
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
