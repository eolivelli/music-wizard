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
 * <h2>The spans: chords, sections and keys</h2>
 *
 * <p>These carry the same optional musical timing a {@link Note} does, and for
 * the same reason, so leaving them in seconds while the notes moved onto the
 * beat axis would leave two readers of one fact: everything that aligns harmony
 * or structure to a bar -- the chord chart, chord-over-lyric placement, a key
 * signature -- would be working from a second, independently rounded, answer to
 * where beat three is.
 *
 * <p>They do <em>not</em> go onto the note grid, and that is the one design
 * decision here worth arguing about.
 *
 * <ul>
 *   <li><b>A section boundary and a key change go to the nearest bar line.</b>
 *       Both are engraved there and nowhere else -- a rehearsal mark and a
 *       double bar, a key signature -- so any finer position is a position the
 *       notation stage would have to round away again, and rounding it twice is
 *       how the two answers diverge. This class already <em>assumed</em> as much:
 *       {@link #sectionStarts} has always waived the grid-change penalty at the
 *       bar a section falls in, and now waives it at the bar the section is
 *       published at, which is the same bar by construction rather than by
 *       coincidence.
 *   <li><b>A chord goes to the nearest counted beat</b> -- a quarter in 4/4, a
 *       dotted quarter in 6/8. Not the bar's note grid: a chart does not change
 *       harmony inside a subdivision, and a bar pulled onto a sextuplet grid by
 *       a busy drum part would otherwise let a chord symbol land a sixth of a
 *       beat in. The counted beat is also the unit both chord estimators already
 *       decide over -- {@code SymbolicChordEstimator} takes exactly one decision
 *       per counted beat, and the audio one is beat-synchronous -- so a chord
 *       that arrives already aligned is snapped to the position it already had
 *       rather than perturbed. And because every grid in
 *       {@link GridResolution} divides the counted beat, a chord position is
 *       always a position on the note grid too; the coarser axis is a sub-grid
 *       of the finer one rather than a rival to it.
 * </ul>
 *
 * <h2>When a span has nowhere to go</h2>
 *
 * <p>A span whose two boundaries snap to the same position has no length, and
 * {@link Chord}, {@link Section} and {@link Key} all refuse that. It happens
 * only when the span is shorter than the unit it is being snapped to, which
 * bounds what can be affected: <b>in a list whose spans do not overlap, a chord
 * as long as a counted beat, and a section or key as long as a bar, is always
 * placed</b>. Two points less than a unit apart can share a rounding cell; two
 * points a unit or more apart cannot.
 *
 * <p>Both qualifiers on that sentence are load-bearing and each was wrong in an
 * earlier draft of it.
 *
 * <ul>
 *   <li><b>The unit is the longest one the span touches</b>, not the one it
 *       starts on. Where a span crosses a meter change into a longer beat -- 7/8
 *       into 4/4, where the counted beat grows from an eighth to a quarter -- a
 *       chord of exactly one 7/8 beat has both ends inside the quarter-note cell
 *       that follows it, and goes.
 *   <li><b>The spans must not overlap.</b> {@link Score} and
 *       {@link dev.olivelli.musicwizard.core.model.ChordProgression} tolerate a
 *       microsecond of overlap, and {@link #onGrid} resolves such a boundary to
 *       the later of its two positions -- so a span handed in overlapping is
 *       effectively a shade shorter here than it declares itself to be, and one
 *       that is exactly a unit long and lands on a rounding midpoint is then
 *       lost. Not a corner that better arithmetic removes: the alternative is
 *       publishing two spans that overlap on the beat axis, which {@code Score}
 *       rejects outright. Measured rather than feared -- of 400 unit-length
 *       spans swept across four meters,
 *       {@code theToleratedOverlapCostsOnlyTheMidpoints} counts how many are
 *       lost once a sub-microsecond overlap is injected into every one of them.
 * </ul>
 *
 * <p>The pass does not reject the score over one -- that would throw away a
 * whole quantization for a blip -- and what it does instead differs between the
 * two kinds, for a reason rather than by oversight:
 *
 * <ul>
 *   <li><b>A chord is dropped</b>, which is the <em>merge</em> the issue asks
 *       for: its neighbours snapped onto the very position it collapsed to, so
 *       the axis stays exactly as continuous as it was, and a chord too short to
 *       be given a beat of its own is one an engraver would not print. Leaving
 *       it in seconds is not available here.
 *       {@link dev.olivelli.musicwizard.core.model.ChordProgression#isQuantized()}
 *       is one verdict for the whole progression, and every consumer of the beat
 *       axis gates on it, so a single unplaceable chord would drop the entire
 *       chart back onto the seconds route it is being lifted off.
 *   <li><b>A section or a key keeps its seconds and is given no beats.</b>
 *       Nothing reports a list of these as quantized or not in one verdict, so
 *       there is nothing to poison, and {@link Score} is built to hold the
 *       mixture -- {@code requireOrderedBeats} skips un-quantized spans by
 *       design. Deleting them instead would be the more expensive answer by far:
 *       a key of less than a bar is dropped and its music is then engraved under
 *       the <em>next</em> key's signature, which is a page of accidentals that
 *       were never played.
 * </ul>
 *
 * <p>Where this is reachable is worth stating precisely, because the obvious
 * answer is wrong. It is <em>not</em> compound time: the audio path builds its
 * map with {@link dev.olivelli.musicwizard.core.model.TempoMap#fromBeatTimes},
 * whose pulse is the meter's own counted beat, so a 6/8 chord span is already a
 * dotted quarter. What does reach it is a supplied tempo that disagrees with the
 * tracked pulse -- {@code --tempo 60} on a track heard at 120 leaves the chord
 * boundaries a half beat apart in a map counting whole ones -- and any producer
 * whose spans are finer than the meter's beat.
 *
 * <h2>What it does not touch</h2>
 *
 * <p>The seconds, on any of them. {@link Note} carries wall-clock and musical
 * timing separately precisely so that an un-quantized value cannot be mistaken
 * for a quantized one, and so that the approximation made here stays
 * recoverable.
 *
 * <p>The shuffle, on a span. {@link SwingFeel} describes where an onset sits
 * inside its beat, and a chord boundary has no such position to correct: it is
 * where the harmony changed, not where a finger landed. De-swinging one would
 * pull a change on the swung off-beat back towards the beat it is anticipating
 * and then round it to the wrong side of it.
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
     * Quantizes every note in every track of a score, and puts its chords,
     * sections and keys on the beat axis.
     *
     * <p>A score with no notes at all is still worth running through here, and
     * that is not a degenerate case: the audio path produces chords long before
     * it produces a note track, so a chord chart is exactly such a score.
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
        List<Chord> chords = onGrid(score.chords().chords(),
                chord -> bars.beatOf(chord.startBeat(), chord.startSeconds()),
                chord -> bars.beatOf(chord.endBeat(), chord.endSeconds()),
                Chord::quantizedTo, bars::snapToCountedBeat, Quantizer::dropped);

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
                    // Which bar a note prints in is decided by where it was
                    // snapped to, not by where it was played. A downbeat rushed
                    // by ten milliseconds sounds in the previous bar and is
                    // printed in this one, and publishing by the played position
                    // would announce a grid for a bar with nothing in it.
                    //
                    // Every bar the note sounds through, not only the one it
                    // starts in. A note held across a bar that has no onset of
                    // its own still has to be engraved there -- tied, and its
                    // tail on that bar's grid, which the quantizer chose and
                    // then used to snap this very note. Publishing only onsets
                    // threw that decision away and left the tail with no
                    // resolution to print it at.
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

    /** What becomes of a span whose boundaries snapped to the same position. */
    @FunctionalInterface
    private interface Collapsed<T> {
        /** The span to keep in its place, or {@code null} to drop it. */
        T instead(T span);
    }

    /** Drops it, which is what a chord that cannot be printed gets. */
    private static <T> T dropped(T span) {
        return null;
    }

    /**
     * Strips any musical timing a collapsed span was carrying.
     *
     * <p>Reachable only from a hand-assembled score whose beats were already off
     * the grid, since anything this pass placed is a whole unit long and cannot
     * collapse on a second run. It is here so that the postcondition is
     * unconditional: after this pass, a span either sits on the grid or carries
     * no beats at all. Leaving the old pair in place would publish a bar line
     * that no bar has.
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
                        Optional.empty(), Optional.empty(), key.confidence())
                : key;
    }

    /**
     * Puts a list of ordered, non-overlapping spans onto the beat axis.
     *
     * <p>Two decisions here, and the first is the only guard.
     *
     * <p><b>A start never precedes the furthest end already placed.</b> Snapping
     * is monotone, so for spans that genuinely do not overlap this can only ever
     * be an equality -- but {@link Score} and
     * {@link dev.olivelli.musicwizard.core.model.ChordProgression} both admit a
     * microsecond of overlap in seconds, and a microsecond straddling a rounding
     * midpoint snaps to two positions a whole unit apart. Without this the pass
     * would emit exactly the beat-axis overlap that {@code Score} rejects and
     * that #59 records as still unchecked on a progression. The furthest end
     * rather than the previous one, for the reason
     * {@code Score.requireOrderedBeats} gives: comparing against the immediately
     * preceding span lets a nested one hide an overlap.
     *
     * <p>Clamping here rather than on the raw position before snapping is not a
     * choice between two behaviours. Snapping is monotone, so
     * {@code max(snap(a), snap(b))} and {@code snap(max(a, b))} are the same
     * function -- checked over two million pairs, zero disagreements -- and a
     * rewrite to the second form was reverted for being a refactor wearing a
     * fix's clothes. What the clamp costs is real and is stated on the class:
     * for a span handed in overlapping, the boundary resolves to the later of
     * the two positions, so a span that is exactly one unit long and lands on a
     * midpoint can still be lost. That is inherent to admitting the overlap at
     * all, not to where the clamp sits: refusing to clamp would publish two
     * spans that overlap on the beat axis instead, which is the defect the guard
     * is here for.
     *
     * <p><b>What happens to a span left with no length</b> is the class's
     * {@code Collapsed} decision, and it is the only place anything is
     * discarded. {@code furthestEnd} does not advance past one either way: a
     * collapsed span has no end to carry forward, and its snapped end is at or
     * behind the value already carried in any case.
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
                T instead = collapsed.instead(span);
                if (instead != null) {
                    out.add(instead);
                }
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
                cost[bar][g] += Math.abs(beatInBar - snapWithin(beatInBar, step))
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
     *
     * <p>Read from the sections <em>after</em> they have been placed, not from
     * their seconds. The two differ whenever a boundary falls in the second half
     * of a bar: the containing bar is the one it sounds in, the published bar is
     * the one it is engraved at, and waiving the penalty at the first while
     * printing the double bar at the second is the same fact answered twice.
     *
     * <p>A section too short to be placed marks nothing, because it has no bar
     * to mark. Where sections abut -- which is the ordinary case, and the only
     * one the seconds-based version handled differently -- the bar it would have
     * marked is the bar its neighbour snapped to and marked instead. Where they
     * do not, {@code Score} permits a gap and permits the last section to be the
     * short one, and then the waiver is genuinely lost and the decode charges
     * for a subdivision change at a boundary a reader can see. That is a
     * sub-bar section in a score with gaps between its sections, and it is a
     * worse outcome than not placing it would be only if the grid actually
     * flips there.
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
        // No upper bound on the loop beyond last: barOf clamps into the table,
        // so last is already in range. A bound here would never fire, and if it
        // ever did it would quietly drop the tail bars of a note rather than say
        // so.
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
                offsetStep);
        double offsetBeat = bars.startBeat(offsetBar) + offsetSteps * offsetStep;

        // The onset may have been carried forward onto the next bar's line while
        // the release stayed behind it -- a forty-millisecond blip fifty
        // milliseconds before a bar line does exactly that. The note then has no
        // length to measure, and measuring it anyway gave a whole bar: the step
        // count walked from a later bar to an earlier one, reported uniformity
        // because it had walked nothing, and subtracted zero from a full bar's
        // worth of steps. It gets one step of the bar it prints in.
        // This is also where a note too short to print ends up: a grace note or
        // a staccato sixteenth on an eighth grid collapses onto its own onset.
        // It is lengthened rather than dropped -- the pitch was played, and one
        // step is the shortest thing this grid can say. Everything past this
        // point has a strictly positive length, which is why no floor is applied
        // to the duration below; one was, until a probe showed it could not
        // fire.
        if (offsetBeat <= onsetBeat) {
            // One step of the bar it prints in, which is the onset's bar after
            // the carry -- not the offset's, which is the bar it was played in
            // and may be divided differently.
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
                : bars.startBeat(offsetBar) - bars.startBeat(onsetBar)
                        + offsetSteps * offsetStep - onsetSteps * onsetStep;

        return note.quantizedTo(onsetBeat, duration);
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
     * {@link QuantizationSettings#overlapTolerance()} of a whole number of steps
     * is left alone -- a player may hold slightly through a note, and that
     * reading needs no help.
     *
     * <p>That suppression is a trade, not a free correction, and the numbers are
     * in {@code ArticulationAllowanceTest} where they can be re-derived. Against
     * applying the allowance to everything, the default tolerance recovers 105
     * more durations from a mostly-legato player and 164 fewer from a detached
     * one. It is kept because what the unconditional version does to the notes
     * it gets wrong is qualitatively worse -- a whole extra grid step on a note
     * nobody shortened -- not because it wins on count.
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
        // confirm. That needs no test of its own: the comparison becomes
        // "length at or below zero", and at zero the other branch returns the
        // same position anyway.
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
     * <p>No bounds of its own, at either end. {@link BarTable#beatInBar} has
     * already put the position inside the bar and rounding is monotone, so
     * neither bound could ever bind -- measured at zero over seven million
     * calls. This is the sixth guard in this class found to be shadowing the one
     * doing the work, and the fix for the fifth stopped one line short of it.
     *
     * <p>A position exactly halfway between two steps goes to the later one,
     * and that is not the arbitrary half of an arbitrary choice. {@code rint},
     * which this used to be, rounds a tie to the <em>even</em> step -- 0.5 down,
     * 1.5 up, 2.5 down -- so the direction alternates with the step index.
     *
     * <p>On a span that is systematic: a chord progression whose spans are
     * exactly half a counted beat -- which is what the audio path produces once
     * {@code --tempo} halves the map's pulse against the tracked beats -- puts
     * every boundary on a tie, and alternating meant the chord kept on each beat
     * was the one that sounded on the beat <em>after</em> it, so the chart named
     * a harmony that never sounded there. Rounding a tie one way throughout
     * keeps the survivor the chord that was playing.
     *
     * <p>On a note it is rare rather than absent, and that distinction is worth
     * stating because the rule is shared and the decision to share it rests on
     * the size of the effect. Measured over 5,040 fixtures -- nine meters, five
     * tempos, eight subdivisions, one tick-exact and one un-jittered rendering
     * plus twelve seeded human performances of each -- the two rules disagree on
     * <b>311 of 131,040</b> note positions, in 60 fixtures, and on <b>0 of
     * 20,254</b> published {@link BarGrid} entries. Every one of the 60 is
     * tick-exact or un-jittered, and they are concentrated in the meters whose
     * bar is not a power-of-two multiple of the grid step: 7/8, 3/4, 9/8 and
     * 5/4. So a human performance does not reach a tie, but a MIDI import does,
     * by construction -- and the direction is right where it does: straight
     * eighths pulled onto a triplet grid alternate 2,1,2,1 steps under this rule
     * where {@code rint} gave 2,1,1,2.
     */
    private static double stepsWithin(double beatInBar, double step) {
        // floor(x + 0.5) rather than Math.round, which would take a long and
        // would need the position bounded; and rather than rint, whose tie rule
        // is the defect above. Safe at the bottom because beatInBar is clamped
        // non-negative, and at the top because the quotient here is at most the
        // number of divisions in a bar.
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
         * position: every note's onset and stretched offset, and the end of
         * every chord, section and key.
         *
         * <p>The spans are in it because they are quantized here too, and
         * because they routinely outlast the notes -- a chord chart from the
         * audio path has no note tracks at all, and a final chord ringing past
         * the last transcribed note is ordinary. Sizing from the notes alone
         * left such a score with a table too short to place its own harmony in,
         * and {@link #barOf} would have clamped the whole tail into the last
         * bar it did have.
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
                        "the tempo map places the last event beyond bar " + MAX_BARS
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

        /**
         * Where a span boundary already is on the beat axis: the position it
         * carries if it carries one, and otherwise its wall-clock time
         * converted.
         *
         * <p>Preferring the carried value is not an optimisation. A MIDI import
         * states its rhythm exactly and has produced beat-aligned chords since
         * #115; converting those to seconds and back would put a rounding step
         * between a value and itself, and re-quantizing an already-quantized
         * score is meant to be a no-op rather than a slow drift.
         */
        static double beatOf(TempoMap tempoMap, Optional<Double> carried, double seconds) {
            return carried.isPresent() ? carried.get() : rawBeat(tempoMap, seconds);
        }

        double beatOf(Optional<Double> carried, double seconds) {
            return beatOf(tempoMap, carried, seconds);
        }

        /**
         * The nearest counted beat, which is the finest position a chord symbol
         * can take.
         *
         * <p>Measured inside the bar rather than from the origin, so the answer
         * follows the meter: in 6/8 the counted beat is a dotted quarter and a
         * bar holds two of them, not six. Building the position back up from the
         * bar's own start keeps it bit-identical to the bar line the notation
         * layer will draw, which subtracting from a global beat count would not.
         */
        double snapToCountedBeat(double beat) {
            int bar = barOf(beat);
            double unit = meter[bar].beatUnitQuarters();
            return startBeat[bar] + stepsWithin(beatInBar(beat, bar), unit) * unit;
        }

        /**
         * The nearest bar line, which is the only position a key change or a
         * section boundary can take.
         *
         * <p>{@code beatsPerBar() * beatUnitQuarters()} equals
         * {@code quarterBeatsPerBar()} to the last bit, so a boundary rounded up
         * lands on exactly the double this table holds for the next bar rather
         * than an ulp away from it.
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
     * is several times a shuffle's, even though their mean sits above 0.5. The
     * measured figures are on {@link SwingDetector#MAX_SPREAD}, and they are
     * there only: a multiple stated in more than one place is a multiple that
     * goes stale in all but one of them.
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

        /**
         * How tight that cluster has to be.
         *
         * <p>Measured against the window this detector actually uses, at the
         * 25 ms spread this project's fixtures play at, and stated as the range
         * each population covers rather than as a ratio between them -- a ratio
         * moves with the fixture and was twice quoted from one draw of it.
         *
         * <p>Played sixteenths spread <b>0.117 to 0.169</b>, over every played
         * sixteenth fixture in the suite: two hundred re-seedings of the
         * detector's own eight-bar fixture at 120 BPM, and the calibration
         * suite's four-bar one at 60, 90 and 120. Both endpoints are from that
         * whole set, which is worth saying because an earlier version of this
         * sentence took its ceiling from one population and its floor from
         * another and overstated the floor by fifteen per cent -- in the number
         * a reader consults before raising this constant, which at 0.12 already
         * fails the build.
         *
         * <p>A played shuffle spreads <b>0.034 to 0.084</b>, over thirty
         * re-seedings at every phase from 0.58 to 0.75. So the threshold sits
         * between the two populations, with the shuffle side the tighter
         * margin -- and what loses a genuine shuffle in practice is
         * {@link #SWING_THRESHOLD} rather than this.
         *
         * <p>Neither figure takes evidence from a constructed cluster, and six
         * of the suite's shuffle fixtures are constructed -- two play to the
         * tick and spread zero, and four set their own spread either side of
         * the shuffle point. A seventh is constructed past the window
         * altogether, so its cluster is empty rather than tight and it is not
         * one of the six. A constructed cluster is still evidence about the
         * <em>threshold</em>, and the widest of them is what holds its lower
         * end: 0.085, inside the gap between the populations and just under this
         * value. The upper end is held by played sixteenths, not by anything
         * constructed.
         *
         * <p>An earlier comment said sixteenths spread about 0.19, which is
         * what they spread against a window starting at 0.25; the figure
         * predated {@link #OFF_BEAT_LOW} being raised to 0.30.
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
