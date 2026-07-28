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

        BarTable bars = BarTable.spanning(tempoMap, allNotes, settings);
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
        double[][] cost = new double[bars.barCount()][candidates.length];

        for (Note note : notes) {
            double written = bars.writtenOnsetBeat(note, swing);
            int bar = bars.barOf(written);
            double beatInBar = bars.beatInBar(written, bar);
            TimeSignature meter = bars.meterOf(bar);
            for (int g = 0; g < candidates.length; g++) {
                double step = candidates[g].stepQuarters(meter);
                double snapped = snapWithin(beatInBar, step, meter);
                // A note played a hair early against a bar line is printed on
                // that bar line, which belongs to the next bar, so it votes
                // there. Attributing it by where it sounded instead leaves the
                // bar it prints in with no vote and the bar before it with one
                // it never shows.
                int votingBar = snapped >= meter.quarterBeatsPerBar() && bar + 1 < cost.length
                        ? bar + 1
                        : bar;
                cost[votingBar][g] += Math.abs(beatInBar - snapped)
                        + complexity(candidates[g], meter, settings);
            }
        }
        // A bar with no onsets accumulates nothing at all, so it costs zero on
        // every grid and inherits its neighbours' rather than voting for whole
        // notes and charging the section two grid changes to get back around it.

        return decode(cost, candidates, sectionStarts(score, bars), settings.gridChangePenalty());
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
     * simplest one, so that the result does not turn on floating-point noise
     * between two readings that are equally good.
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
     * change, so it is the one place the prior should not resist. Bar 0 counts,
     * which costs nothing since there is no previous bar to leave.
     */
    private static boolean[] sectionStarts(Score score, BarTable bars) {
        boolean[] starts = new boolean[bars.barCount()];
        starts[0] = true;
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
        double onsetBeat = bars.startBeat(onsetBar)
                + snapWithin(bars.beatInBar(writtenOnset, onsetBar), onsetStep, onsetMeter);

        double writtenOffset = bars.writtenOffsetBeat(note, swing, settings.articulationRatio());
        int offsetBar = bars.barOf(writtenOffset);
        TimeSignature offsetMeter = bars.meterOf(offsetBar);
        double offsetStep = perBar[offsetBar].stepQuarters(offsetMeter);
        double offsetBeat = bars.startBeat(offsetBar)
                + snapWithin(bars.beatInBar(writtenOffset, offsetBar), offsetStep, offsetMeter);

        // A note shorter than half a grid step -- a grace note, or a staccato
        // sixteenth on an eighth grid -- collapses onto its own onset. It is
        // lengthened rather than dropped: the pitch was played, and one step is
        // the shortest thing this grid can print.
        return note.quantizedTo(onsetBeat, Math.max(offsetBeat - onsetBeat, onsetStep));
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
        double steps = Math.rint(beatInBar / step);
        double limit = Math.rint(meter.quarterBeatsPerBar() / step);
        return Math.clamp(steps, 0, limit) * step;
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

        /** Spare bars past the last note, for an offset that lands on the end. */
        private static final int TRAILING_BARS = 2;

        private final TempoMap tempoMap;
        private final double[] startBeat;
        private final TimeSignature[] meter;

        private BarTable(TempoMap tempoMap, double[] startBeat, TimeSignature[] meter) {
            this.tempoMap = tempoMap;
            this.startBeat = startBeat;
            this.meter = meter;
        }

        /** A table long enough to hold every note's onset and stretched offset. */
        static BarTable spanning(TempoMap tempoMap, List<Note> notes,
                                 QuantizationSettings settings) {
            double lastBeat = 0;
            for (Note note : notes) {
                double onset = rawBeat(tempoMap, note.onsetSeconds());
                double end = rawBeat(tempoMap, note.offsetSeconds());
                lastBeat = Math.max(lastBeat,
                        onset + (end - onset) / settings.articulationRatio());
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
            int count = lastBar + 1 + TRAILING_BARS;
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
            // Clamped because a map anchored a hair off zero can return a
            // negative beat for the very first note, and every position below
            // has to be on the musical timeline for toMusicalTime to accept it.
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
         * Where a note's release should be written.
         *
         * <p>The played length is divided by the articulation allowance first.
         * A player holds a quarter note for something like nine tenths of its
         * written value, and snapping that release to the nearest grid position
         * prints a dotted eighth followed by a rest on any grid finer than the
         * eighth. Stretching first is what makes an ordinary detached
         * performance come out as plain quarter notes.
         */
        double writtenOffsetBeat(Note note, SwingFeel swing, double articulationRatio) {
            double onset = rawBeat(note.onsetSeconds());
            double end = rawBeat(note.offsetSeconds());
            return deswing(onset + (end - onset) / articulationRatio, swing);
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
     * <p>One verdict for the whole piece. A track that swings its bridge and not
     * its verses will have the majority feel applied throughout.
     */
    private static final class SwingDetector {

        /** Onsets within this much of a beat count as being on the beat. */
        private static final double ON_BEAT_WINDOW = 0.12;

        /** The part of the beat an off-beat eighth can occupy. */
        private static final double OFF_BEAT_LOW = 0.30;
        private static final double OFF_BEAT_HIGH = 0.88;

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

        private SwingDetector() {
        }

        static SwingFeel detect(List<Note> notes, BarTable bars) {
            List<Double> offBeat = new ArrayList<>();
            int onBeat = 0;
            for (Note note : notes) {
                double phase = bars.phaseWithinBeat(bars.rawBeat(note.onsetSeconds()));
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

            double ratio = Math.clamp(mean, SwingFeel.MIN_RATIO, SwingFeel.MAX_RATIO);
            double tightness = Math.clamp(1 - spread / MAX_SPREAD, 0, 1);
            double support = Math.clamp(offBeat.size() / FULL_SUPPORT, 0, 1);
            return new SwingFeel(true, ratio, Confidence.clamped(tightness * support));
        }
    }
}
