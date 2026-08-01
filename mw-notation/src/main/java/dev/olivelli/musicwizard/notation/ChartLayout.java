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

package dev.olivelli.musicwizard.notation;

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.MusicalTime;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a chord chart's bar lines fall, and what sounds in each bar.
 *
 * <p>One derivation, read by both of the chart's outputs. That is the point of
 * the class: {@link ChordChart} used to decide the bars twice — once by counting
 * which bar each chord started in, for the text, and once by writing each
 * chord's own length as a duration and letting LilyPond accumulate, for the
 * engraving — and the two answers were free to differ. They did. A progression
 * that printed four chords in one text bar engraved as eight bars of whole
 * notes (#174), because neither derivation could see the other's answer and
 * nothing compared them.
 *
 * <p>So the bars are decided here, once, and both emitters spell what this
 * returns: where a bar line falls, which chords are in each bar, and how much of
 * its bar each one fills. The engraved page then carries a {@code |} bar check
 * per bar (#160), which makes LilyPond itself check that the durations in a bar
 * sum to the meter this class says that bar is in — the one contradiction the
 * chart could not previously produce.
 *
 * <p>Two things a reader might expect to follow from that do not, and both were
 * found by review rather than reasoned about here. Whether a bar <em>names</em>
 * its chord is decided twice, because {@code chordmode} cannot be told not to
 * print a name and {@code chordChanges} has to be left to apply the same rule
 * (see {@link Cell#named()}). And the text chart's {@code Meter} header states
 * one meter where {@link Bar#meter()} can state several, which is #191.
 *
 * <p><b>A chord holds until the next one starts.</b> Cell boundaries are chord
 * <em>starts</em>, never ends, which is how a chart is read: every chord in the
 * progression begins exactly one cell, the last cell runs to the final bar line,
 * a chord that stops early leaves its symbol standing, and a silence the
 * estimator states explicitly arrives as an {@code N.C.} chord of its own.
 *
 * <p><b>Then the bar is written at the harmonic rhythm its own evidence
 * supports, which does drop chords.</b> That is #212 and it is deliberate, so it
 * is worth separating from the sentence above rather than qualifying it: the
 * walk that lays chords into bars still drops nothing -- a chord silently
 * discarded there was #174 and remains a defect -- and {@link #atHarmonicRhythm}
 * then reduces each bar, which is a decision rather than an accident and states
 * its own cost. Before it existed the chart printed every span the estimator
 * produced, two to three a bar on real recordings, so harmony that was mostly
 * right read as noise.
 */
final class ChartLayout {

    /**
     * The coarsest grid the seconds route will snap chord starts to: one counted
     * beat.
     *
     * <p>Never coarser, because a chord that does not start on a downbeat has to
     * be able to say so. Snapping to the bar would pull every chord onto a bar
     * line whatever its evidence, and that is not a tolerance -- it is #83. The
     * chart used to do exactly that, and the reason {@code --first-downbeat}
     * moved nothing a user could see is that with every chord rounded to a bar
     * line, no phase is observable. Measured on the four 4/4 phases before this
     * change: identical text, identical LilyPond, identical PDF.
     */
    private static final double COARSEST_GRID_BEATS = 1.0;

    /**
     * The share of a bar a second chord has to explain to be worth printing.
     * See {@link #atHarmonicRhythm}.
     *
     * <p>Read as a rate rather than a threshold: writing one more chord in a bar
     * has to account for at least this much more of it. What that means in 4/4 is
     * the whole of the setting -- a chord holding one of four beats explains a
     * quarter of the bar, which at 0.3 does not pay for itself, and one holding
     * two of four explains a half, which does. So a 4/4 bar is written as two
     * chords when they split it and as one otherwise. In 3/4 and 6/8 the same
     * constant keeps a one-beat chord, since a third and a half of a bar clear
     * it.
     *
     * <p><b>This is a trade and not a free win, and two drafts of this paragraph
     * said otherwise.</b> Round 1 of review swept the constant by recompiling and
     * re-emitting all five charts, and corrected both halves of what was here:
     *
     * <ul>
     *   <li>The band over which all five charts are identical is narrower than
     *       was claimed, and 0.3 sits at its <em>upper edge</em> rather than in
     *       its middle: raising it changes what one of the five recordings
     *       prints almost at once, and most of them a little further up.
     *   <li>Lowering it does <em>not</em> cost accuracy, which is what the
     *       previous draft asserted. It buys accuracy. At a cost low enough to
     *       print about two chords a bar, per-bar root accuracy on
     *       {@code blues-a-90bpm.mp3} is several points higher than it is here.
     * </ul>
     *
     * <p>So what this constant buys is readability, and it is paid for on at
     * least one benchmark in accuracy. That is the right trade for #212 --
     * two chords a bar is the chatter the issue is about, and a chart nobody can
     * read scores nothing in practice -- but it is a trade, and a maintainer
     * moving this should expect the two columns to move against each other.
     * Neither is given a number, because nothing committed reproduces a sweep
     * over the constant; {@code tools/score-chart.py} measures the chart this
     * value produces, not the ones others would.
     *
     * <p>0.3 rather than 0.25, which behaves identically on all five, because
     * 0.25 is an exact tie against a quarter of a 4/4 bar and a decision resting
     * on tie-breaking order is a decision waiting to move.
     */
    private static final double EXTRA_CHORD_COST = 0.3;

    /**
     * One printed chord, or the silence before the first one.
     *
     * @param chord          the chord sounding, or empty for the gap between the
     *                       first bar line and the first chord
     * @param lengthQuarters how much of its bar this cell fills, in quarter beats
     * @param named          whether this cell begins a run of equal symbols, so
     *                       that the text chart names the chord here and writes
     *                       {@code %} for the cells after it. Read by the text
     *                       chart alone: the page reaches the same answer
     *                       through {@code chordChanges}, which is LilyPond's
     *                       own copy of this rule and differs from it at a
     *                       system break, where a held chord is correctly
     *                       reprinted
     */
    record Cell(Optional<Chord> chord, double lengthQuarters, boolean named) {

        /** What the text chart writes when it names this cell. */
        String symbol() {
            return chord.map(Chord::symbol).orElse(ChordQuality.NONE.symbol());
        }
    }

    /**
     * One bar of the chart, whose cells fill it exactly.
     *
     * @param meter        the meter this bar is in
     * @param meterChanged whether it differs from the previous bar's, so the
     *                     engraving states it again
     * @param cells        the chords sounding, in order
     */
    record Bar(TimeSignature meter, boolean meterChanged, List<Cell> cells) {
    }

    /** The meter of each chart bar, counted from the chart's own first bar. */
    @FunctionalInterface
    private interface BarRuler {
        TimeSignature meterOf(int chartBar);
    }

    /** A chord and the span it holds, in quarter beats from the first bar line. */
    private record Span(Optional<Chord> chord, double from, double to) {
    }

    private ChartLayout() {
    }

    /**
     * The chart's bars, or an empty list when there is no harmony to chart.
     *
     * <p>Taken from the beat axis whenever the progression carries one, and from
     * seconds only when it does not. The two agree at a constant tempo and
     * disagree everywhere else, because the seconds route divides by a
     * <em>single</em> bar length derived from {@link Score#estimatedTempo()}: on
     * a piece that changes tempo the grid drifts, and chords pile into one bar
     * while later bars come out empty. A MIDI import states its rhythm exactly
     * and has done since #115, so on that path the question has an answer and
     * estimating it would be a step backwards.
     *
     * <p>Seconds remain the route for the audio path, whose chords are not
     * quantized -- which is exactly what
     * {@link dev.olivelli.musicwizard.core.model.ChordProgression#isQuantized()}
     * is for.
     */
    static List<Bar> of(Score score) {
        return atHarmonicRhythm(unreduced(score));
    }

    /**
     * The same bars before {@link #atHarmonicRhythm} reduces them.
     *
     * <p>A stage of its own rather than a step inside one, because the two make
     * different promises and each has to be able to fail on its own. This one
     * <b>drops nothing</b>: every chord in the progression begins exactly one
     * cell, which is the property #174 is, and a chord lost here is a chord lost
     * to arithmetic. The reduction then drops chords deliberately, which is #212.
     * Collapsing the two would leave no way to tell a chord absorbed on purpose
     * from one rounded out of existence -- and #174 was found precisely because
     * the second was mistaken for the first.
     *
     * <p>Read by {@code ChordChartTest}, which holds #174's property here rather
     * than on the finished chart, where it is no longer true.
     */
    static List<Bar> unreduced(Score score) {
        List<Chord> chords = score.chords().chords();
        if (chords.isEmpty()) {
            return List.of();
        }
        return score.chords().isQuantized()
                ? fromBeats(score, chords)
                : fromSeconds(score, quarterNoteSeconds(score));
    }

    /**
     * The bars of a progression that states its own rhythm.
     *
     * <p>Bar lines come from {@link TempoMap#toMusicalTime}, so they honour a
     * meter change as well as a tempo change, and the chart's first bar is the
     * bar the first chord falls in rather than the first chord itself.
     */
    private static List<Bar> fromBeats(Score score, List<Chord> chords) {
        TempoMap map = score.tempoMap();
        double grid = LilyPondDuration.SHORTEST_QUARTERS;
        int firstBar = map.toMusicalTime(snap(chords.get(0).startBeat().orElseThrow(), grid)).bar();
        double origin = map.toBeat(
                new MusicalTime(firstBar, 0, map.timeSignatureAtBar(firstBar)));

        double[] starts = new double[chords.size()];
        double end = 0;
        for (int i = 0; i < chords.size(); i++) {
            Chord chord = chords.get(i);
            starts[i] = snap(chord.startBeat().orElseThrow(), grid) - origin;
            end = Math.max(end, starts[i] + printedLengthBeats(chord));
        }
        // Bar indices are ints, and a chart long enough to run off the end of one
        // would have to come from a beat position toMusicalTime has already
        // rejected -- but the addition is still clamped rather than left to wrap,
        // because a wrapped index reads a meter from the wrong end of the piece.
        return assemble(chords, starts, end, grid,
                k -> map.timeSignatureAtBar((int) Math.min(Integer.MAX_VALUE, (long) firstBar + k)));
    }

    /**
     * The bars of a progression that is still in seconds, which is every chart
     * taken from audio, at a stated quarter-note length.
     *
     * <p>The length is a parameter rather than read from the score so that the
     * degenerate case below can be reached from a test. {@link
     * Score#estimatedTempo()} cannot yield anything unusable today -- every
     * route through it ends at a tempo the model has already validated as
     * positive -- but everything here divides by it, and an infinite position
     * would send the bar walk below into a loop of some 10^17 iterations that no
     * user could interrupt. A guard against that is worth having, and worth
     * nothing if nothing can exercise it.
     *
     * <p>It guards the unusable, not the implausible. A <em>small</em> positive
     * length passes and is honoured: {@code --tempo 1e7} really does mean a
     * million-bar chart, and a million bars really are allocated. Nothing here
     * says where the absurd begins, and #188 argues that a plausibility bound
     * belongs on the option rather than on the chart -- truncating a chart to fit
     * a limit would be #174 wearing a different hat.
     */
    static List<Bar> fromSeconds(Score score, double quarterSeconds) {
        List<Chord> chords = score.chords().chords();
        TimeSignature meter = score.tempoMap().initialTimeSignature();
        if (!(quarterSeconds > 0) || !Double.isFinite(quarterSeconds)) {
            return oneChordPerBar(chords, meter);
        }
        // The grid first, because it is the one value that says how far a chord
        // may be moved, and both the anchor and the snapping have to agree on
        // it. They did not, for one round: the anchor was allowed half a counted
        // beat while the snapping could be much finer, so a first chord heard
        // just before its downbeat anchored on that downbeat, snapped to a
        // negative position, and shunted every chord behind it a grid step along
        // -- into the next bar, in the text as well as on the page. Read off the
        // gaps between chords, which is a fact about the progression and not
        // about where the chart happens to start, so it can be known first.
        double grid = chartGrid(chords, quarterSeconds, meter);
        double origin = firstBarStart(score, meter.quarterBeatsPerBar() * quarterSeconds,
                grid * quarterSeconds / 2);

        double[] starts = new double[chords.size()];
        double lastEnd = 0;
        for (int i = 0; i < chords.size(); i++) {
            starts[i] = snap((chords.get(i).startSeconds() - origin) / quarterSeconds, grid);
            lastEnd = Math.max(lastEnd,
                    snap((chords.get(i).endSeconds() - origin) / quarterSeconds, grid));
        }
        return assemble(chords, starts, lastEnd, grid, k -> meter);
    }

    /**
     * The coarsest grid no narrower than the closest two chord changes.
     *
     * <p>A chord boundary estimated from audio is not on any grid, and it has to
     * be put on one before a bar can hold a whole number of cells. Which grid is
     * a claim about how precisely the estimate is worth believing, so it is read
     * off the chords rather than fixed: <b>the chart does not resolve a position
     * more finely than the chord changes themselves demonstrate.</b> A
     * progression changing once a bar is drawn on the beat, where a boundary
     * heard a fifth of a beat early still lands on the beat it belongs to; one
     * changing on off-beat eighths is drawn on eighths, because there it is the
     * eighths that are the evidence.
     *
     * <p>This is not cosmetic, and a fixed sixteenth was measured getting it
     * wrong. <b>The measurement supplies the chords rather than transcribing
     * them, and that has to be said</b>: it laid the progression that
     * {@code samples/list.txt} documents for {@code samples/gmajorblues.mp3} on
     * that recording's own detected downbeats, one chord each. That measures
     * this class's arithmetic against real timing and a known progression; it is
     * not a figure for what the product recognises, and it must not be quoted as
     * one.
     *
     * <p>The reason originally given for supplying them was that chord
     * recognition returned one {@code N.C.} span covering the whole recording.
     * That was true and is not: since #3 the same file yields hundreds of spans
     * and no {@code N.C.} at all -- 740 then, 666 since #196 removed the
     * spurious beats their boundaries were taken from. The caveat above stands
     * anyway and stands for a
     * better reason — a layout measurement wants a progression known to be
     * right, not one that is 50% right — so the method here did not change when
     * its original justification stopped applying.
     *
     * <p>Counting bars from the first downbeat, a grid step of a sixteenth --
     * which moves a chord by at most an eighth of a quarter beat -- put the
     * fourth chord in the wrong bar, where a step of one counted beat, moving a
     * chord by at most half a beat, held through the twenty-fifth chord and
     * first misplaced the twenty-sixth. Eight times as far, and that is the
     * whole of what this method is answering for.
     *
     * <p><b>Both figures were taken against a downbeat grid that #3 has since
     * changed, and both want re-taking.</b> Not the beat times -- those come out
     * byte-identical -- but the downbeat phase, which moved by one beat on this
     * recording, and with it the irregularity the sixteenth grid was tripping
     * over. The comparison does not narrow and it does not invert: it collapses.
     * Re-measured exactly as described above -- bars from the first downbeat,
     * one chord per detected downbeat, through this class's own snap and its own
     * {@code quarterNoteSeconds} -- the sixteenth grid first misplaces the
     * fourth chord before that change and the twenty-fifth after it, against a
     * counted beat that first misplaces the twenty-sixth either way.
     *
     * <p>Both original figures reproduce on the unchanged code, which is what
     * makes the pair worth stating, and the quarter length has to be this
     * class's own for them to. {@code quarterNoteSeconds} gives 0.5631s here;
     * fitting a bar length to the downbeats instead gives 0.5546s, and at that
     * value the counted beat reads in the hundreds rather than 26.
     *
     * <p>Which of the two figures does the validating is the part worth keeping.
     * The 4 reproduces at every quarter length from 0.554 to 0.572, being one
     * local irregularity, so agreeing with it demonstrates nothing; the 26 holds
     * only in a narrow band about the right value, and 0.5546 falls inside the
     * first and outside the second. So check a harness against the 26. One
     * checked against the 4 alone is one checked against something that could
     * not have failed.
     *
     * <p>What the collapse means is that after chord 25 the grid width has
     * stopped being the thing that decides. The recording's beats run 0.5583s
     * over the hundred from the first downbeat to chord 26, and 0.5552s over the
     * whole recording, against an estimate of 0.5631s -- the drift is not
     * uniform, which is why the whole-recording rate does not predict the
     * hundred-beat one -- and by chord 26 the bar lines have walked off the
     * music by 0.86 of a beat whatever the grid does. That is past the half beat
     * a counted-beat grid can absorb, which is why 26 fails, and it is #196
     * rather than a result about grid width. The drift reaches two beats by
     * chord 47 and seventeen by the end. The one-chord margin between 25 and 26
     * is noise.
     *
     * <p><b>#196 has moved that paragraph's three rates, and this time it moved
     * the beat times rather than only the downbeat phase.</b> They read 0.5636s
     * over the hundred, 0.5658s over the whole recording and 0.5689s for the
     * estimate. The conclusion survives and its shape changes: the drift by
     * chord 26 is unchanged at about a beat, so 26 still fails for the reason
     * given, but the whole-recording figure falls from seventeen beats to seven.
     * What is left of it is no longer the tracker running fast -- it now runs
     * within a tenth of a percent of the music, which is what #196 fixed -- but
     * the gap between the median interval this class then spaced bars at and the
     * rate the grid actually ran at, which is #200.
     *
     * <p><b>#200 has since moved the third of those three rates, and with it the
     * paragraph above's "unchanged at about a beat".</b>
     * {@code quarterNoteSeconds} now gives 0.56651s here rather than 0.5689s,
     * because {@link Score#estimatedTempo()} answers with the rate the grid ran
     * at over the pulses it tracked steadily rather than with a median interval.
     * Against the same 0.5658s the whole-recording drift falls from about seven
     * beats to about one and a half.
     *
     * <p><b>The drift by chord 26 halves too, and it lands on the threshold the
     * argument above turns on rather than clear of it.</b> Over the hundred beats
     * to chord 26 the recording runs at 0.5636s; at 0.5689s that is 0.93 of a
     * beat and at 0.56651s it is 0.51 -- so the counted-beat grid, which absorbs
     * half a beat, no longer misses by a comfortable margin but by a hair.
     * <b>Whether chord 26 is still the first misplaced one is therefore an open
     * question and not a re-derivation</b>; the chord indices in this javadoc
     * want re-taking for the third time and have not been. What is certain is
     * that the reason the paragraph above gives for the failure -- drift past
     * what the grid can absorb -- is now a much narrower claim than it was.
     *
     * <p>What is left of the drift is neither the tracker nor the statistic: it
     * is that the recording does not hold one bar length (#187) and that the
     * anchor is the grid's first downbeat (#233).
     *
     * <p>So the choice stands and its evidence does not, and the reason has to
     * carry it alone: a grid narrower than the timing error trips over it, which
     * is a claim about what a grid must survive rather than about what one
     * recording happens to contain. That is a real gap, and closing it wants a
     * recording whose downbeats are still irregular rather than a re-run of this
     * one.
     *
     * <p>What goes wrong beyond that is not this method's, and rounds 5, 6, 7
     * and 8 of review each found a different wrong story about whose it is, so
     * this states the one measurement that bears on the ceiling and stops. The
     * fourth downbeat was detected 0.18s early and the fifth was back within
     * 0.012s -- one bad downbeat, and a grid narrower than it is wide trips over
     * it.
     *
     * <p>Past tense, and the figure needs re-taking before it is leaned on
     * again. #3 changed what {@link dev.olivelli.musicwizard.core.model.BeatGrid}
     * this recording produces: the beat times are byte-identical, but the
     * downbeat phase moved by one beat, and over the same window the worst
     * deviation from a uniform bar fell from 0.152s to 0.017s. So the bad
     * downbeat this argument rests on is no longer there to trip over, and a
     * maintainer re-running the measurement today would wrongly conclude the
     * grid choice was unjustified. The choice is still the right one -- it is
     * about what a grid must survive, not about what this recording happens to
     * contain -- but it now wants a recording that still exhibits the defect.
     *
     * <p>Past there the chart's bar phase is taken from one downbeat (#233) and
     * the recording's beat does not keep to any single bar length (#187); the
     * two run against each other rather than adding, and the measurements are on
     * those issues where they can be corrected without touching this file. The
     * bar *length* used to be the third of these and is #200, now fixed.
     *
     * <p>Measured on <em>gaps</em> rather than on positions, which matters for
     * two reasons beyond taste. It is a fact about the progression alone, so it
     * is knowable before the chart has an origin -- and the origin needs it,
     * because the anchor may not move a chord further than the snapping will.
     * And a gap of at least one grid step guarantees two distinct positions
     * after snapping, since {@code round(x + 1) == round(x) + 1} exactly, so
     * above the floor no chord can land on another.
     *
     * <p>Bounded below by {@link LilyPondDuration#SHORTEST_QUARTERS}, since no
     * duration can name anything shorter, and above by
     * {@link #COARSEST_GRID_BEATS}. Candidates that are not a whole number of
     * the shortest value are skipped, so that every cell length stays nameable:
     * halving a 6/8 beat reaches 3/16 of a quarter and then 3/32, which is not
     * one.
     *
     * <p>One close pair draws the whole chart on the finer grid, rather than the
     * grid varying along it. That costs only how a duration reads, never where a
     * chord sits: a finer grid moves a chord less, not more.
     */
    private static double chartGrid(List<Chord> chords, double quarterSeconds,
                                    TimeSignature meter) {
        double closest = Double.MAX_VALUE;
        for (int i = 1; i < chords.size(); i++) {
            closest = Math.min(closest,
                    (chords.get(i).startSeconds() - chords.get(i - 1).startSeconds())
                            / quarterSeconds);
        }
        double finest = LilyPondDuration.SHORTEST_QUARTERS;
        for (double grid = COARSEST_GRID_BEATS * meter.beatUnitQuarters();
                grid > finest; grid /= 2) {
            if (Math.rint(grid / finest) * finest == grid && closest >= grid) {
                return grid;
            }
        }
        return finest;
    }

    /**
     * Turns chord starts on a quarter-beat axis into bars of cells.
     *
     * <p>The bar count and every chord's place in it come out of this one walk,
     * which is the correction #174 asked for: they used to be rounded
     * independently, and a chord that rounded past the last bar the other
     * rounding had counted was discarded without a word.
     *
     * @param starts each chord's start, in quarter beats from the first bar line
     * @param end    where the harmony stops, on the same axis
     * @param grid   the smallest span this route prints
     */
    private static List<Bar> assemble(List<Chord> chords, double[] starts, double end,
                                      double grid, BarRuler ruler) {
        // Strictly increasing, so that every chord gets a cell of its own. Two
        // chords can snap onto one grid point -- when they are closer than the
        // shortest value a duration can name, which is the one case chartGrid
        // cannot answer by choosing a finer grid -- and the alternative to
        // nudging the second one along is printing a zero-length chord, which no
        // duration can name, or dropping it, which is the defect being fixed.
        //
        // The clamp holds an invariant rather than repairing anything: the
        // seconds route anchors within half a grid of the first chord, so the
        // first position rounds to zero at worst, and the beat route starts from
        // the bar the first chord is in. Kept because a caller that broke that
        // invariant would otherwise shunt every chord behind the first into the
        // next bar -- which is what a mismatched anchor tolerance did, measured
        // in round 4 of review -- and because a negative length would reach
        // LilyPondDuration.scaled and throw.
        starts[0] = Math.max(0, starts[0]);
        for (int i = 1; i < starts.length; i++) {
            starts[i] = Math.max(starts[i], starts[i - 1] + grid);
        }
        double lastStart = starts[starts.length - 1];
        end = Math.max(end, lastStart + grid);

        List<Double> barStarts = new ArrayList<>();
        List<TimeSignature> meters = new ArrayList<>();
        double boundary = 0;
        while (true) {
            TimeSignature meter = ruler.meterOf(barStarts.size());
            double length = meter.quarterBeatsPerBar();
            // A bar is on the chart when a chord starts in it, when the harmony
            // fills more than half of it, or when it is the first. The middle
            // clause is what stops a chord ending a few ticks past a bar line
            // from adding an empty bar the engraving would not have.
            if (!(barStarts.isEmpty() || lastStart >= boundary || end > boundary + length / 2)) {
                break;
            }
            barStarts.add(boundary);
            meters.add(meter);
            boundary += length;
        }
        double chartEnd = boundary;

        List<Span> spans = new ArrayList<>(chords.size() + 1);
        // The gap between the first bar line and the first chord, when the
        // downbeat the chart is anchored on sits before the harmony starts.
        // Printed rather than absorbed: a chart whose first chord arrives half a
        // bar late is telling the reader the downbeat is out, which is precisely
        // what --first-downbeat is for, and back-dating the chord would hide it.
        // atHarmonicRhythm leaves the bar holding this gap alone for the same
        // reason -- see its javadoc.
        if (starts[0] > 0) {
            spans.add(new Span(Optional.empty(), 0, starts[0]));
        }
        for (int i = 0; i < chords.size(); i++) {
            spans.add(new Span(Optional.of(chords.get(i)), starts[i],
                    i + 1 < chords.size() ? starts[i + 1] : chartEnd));
        }

        List<List<Cell>> cells = new ArrayList<>(barStarts.size());
        for (int i = 0; i < barStarts.size(); i++) {
            cells.add(new ArrayList<>());
        }
        int bar = 0;
        for (Span span : spans) {
            while (bar + 1 < barStarts.size() && barStarts.get(bar + 1) <= span.from()) {
                bar++;
            }
            double at = span.from();
            while (at < span.to() && bar < barStarts.size()) {
                double barEnd = barStarts.get(bar) + meters.get(bar).quarterBeatsPerBar();
                double cut = Math.min(span.to(), barEnd);
                // Unnamed for now: which cells name their chord is decided in one
                // pass over the finished bars, because a cell the reduction
                // removes must not have been the one carrying the name.
                cells.get(bar).add(new Cell(span.chord(), cut - at, false));
                at = cut;
                if (cut == barEnd) {
                    bar++;
                }
            }
        }

        List<Bar> bars = new ArrayList<>(barStarts.size());
        for (int i = 0; i < barStarts.size(); i++) {
            bars.add(new Bar(meters.get(i), i == 0 || !meters.get(i).equals(meters.get(i - 1)),
                    cells.get(i)));
        }
        return named(bars);
    }

    /**
     * Marks the cell that begins each run of equal symbols, in chart order.
     *
     * <p>That is what the text chart's {@code %} has always meant, and it is the
     * rule {@code chordChanges} applies on the page -- but the page applies its
     * own copy of it, because {@code chordmode} has no way to write a chord event
     * that declines to print its name. So this is the text chart's flag, and the
     * agreement between the two outputs is held by keeping two rules in step
     * rather than by one reader. Round 2 of review found the comment here
     * claiming otherwise. Where they legitimately part is a system break, at
     * which LilyPond reprints a held chord and the text, having no systems, does
     * not.
     *
     * <p>A separate pass over the finished cells rather than a flag set while
     * they are built, because {@link #atHarmonicRhythm} may drop the cell a chord
     * was named at and keep a later one carrying the same symbol. Deciding the
     * name first left such a bar naming nothing and the text chart writing
     * {@code %} for a chord it had never printed.
     *
     * <p><b>Every cell is rebuilt, including the ones whose answer does not
     * change.</b> That is what makes this idempotent, and it has to be, because
     * it runs twice -- once over the laid-out bars and again over the reduced
     * ones. An earlier version only ever <em>set</em> the flag and returned an
     * unchanged cell otherwise, so a bar the reduction handed back untouched kept
     * a {@code named} decided against a predecessor that no longer existed.
     * Round 1 of review measured the result on real output: five bars across the
     * sample recordings where the text chart printed a chord change the engraved
     * page did not, because {@code chordChanges} recomputes the same rule from
     * the symbols it is given and reached the right answer. That is precisely the
     * disagreement between the two outputs that this class exists to prevent,
     * arriving through the flag instead of through a second derivation.
     */
    private static List<Bar> named(List<Bar> bars) {
        List<Bar> out = new ArrayList<>(bars.size());
        String previous = null;
        for (Bar bar : bars) {
            List<Cell> cells = new ArrayList<>(bar.cells().size());
            for (Cell cell : bar.cells()) {
                cells.add(new Cell(cell.chord(), cell.lengthQuarters(),
                        previous == null || !cell.symbol().equals(previous)));
                previous = cell.symbol();
            }
            out.add(new Bar(bar.meter(), bar.meterChanged(), List.copyOf(cells)));
        }
        return List.copyOf(out);
    }

    /**
     * The chart written at the harmonic rhythm each bar's own evidence supports.
     *
     * <p>This is #212, and it is what turns an estimate a musician can measure
     * into a chart a musician can read. Every span the estimator produces used to
     * be printed, so a recording whose per-bar majority chord is right most of
     * the time still came out at two to three chords a bar -- correct harmony
     * buried in chatter. Measured through {@code tools/score-chart.py} on the
     * five benchmarks in {@code samples/} before this existed: 2.01, 2.04, 2.37,
     * 2.76 and 3.03 printed chords a bar, against music that changes chord about
     * once a bar.
     *
     * <p><b>The rule, per bar.</b> The bar is divided into equal slots; each slot
     * is written as the chord filling most of it; runs of equal slots merge. The
     * division used is the one minimising
     *
     * <pre>
     *   (share of the bar the written chords do not cover)
     *       + EXTRA_CHORD_COST * (chords written - 1)
     * </pre>
     *
     * with ties going to the coarser division, so a bar holding one chord is
     * written as one chord, a bar genuinely split in half is written as two, and
     * a bar the estimator disagreed with itself about is written as whatever it
     * mostly said.
     *
     * <p>A threshold on coverage alone was tried first and does not work, which
     * is worth recording because it is the obvious rule. The finest division
     * always covers a bar whose chords sit on counted beats <em>exactly</em>, so
     * any absolute coverage bar is cleared there and the chattiest bars -- three
     * and four chords, each on its own beat -- came through untouched. What
     * decides has to be what a further chord <em>buys</em>, not what a division
     * reaches.
     *
     * <p>The divisions offered are those giving a whole number of counted beats
     * per slot: 1, 2 and 4 in 4/4, 1 and 3 in 3/4, 1 and 2 in 6/8. Never finer
     * than the counted beat, which is both what a lead sheet does and what the
     * two chord estimators decide over -- {@code SymbolicChordEstimator} takes
     * one decision per counted beat and the audio one is beat-synchronous.
     *
     * <p><b>Why this is not decided from where the chords came from.</b> The
     * obvious rule is to reduce an audio progression and leave a MIDI one alone.
     * It would have to read provenance out of {@code isQuantized()}, which is a
     * fact about the beat axis and not about where the chords came from, so
     * wiring {@code Quantizer} into the audio path -- which #212 weighed and
     * which is a live option -- would silently switch the reduction off. #213
     * carries the provenance that would answer this properly.
     *
     * <p>The next thing to reach for is "reduce a bar only where the harmony is
     * no faster than the counted beat", and it is a worse discriminator than it
     * looks. Rounds 1 and 2 of review established why between them, and
     * {@code tools/score-chart.py} now reports both halves of it per recording.
     * Measured against the <em>tracked</em> beat grid, no change on any of the
     * five benchmarks is faster than a beat, and that is structural rather than
     * lucky: {@code ChordEstimator} takes both boundaries of every span from the
     * tracked beat times. Measured against the beat the chart's bars are spaced
     * at, which is the grid's steady rate, 7.8% to 24.1% of changes are. That
     * range was 12.0% to 32.9% before #196 and 11.3% to 24.1% before #200.
     *
     * <p><b>How much a recording's cell moves is not how much its rate moved</b>,
     * and a draft of this paragraph said it was. Chord gaps are whole multiples
     * of the tracked interval, so the threshold this counts against sits on a
     * mode of the distribution rather than between modes: a change of either sign
     * flips a whole cohort of one-beat gaps across it at once, or flips none.
     * At #200 the direction followed the sign -- a faster rate makes the counted
     * beat shorter, so fewer gaps fall under it -- but the size followed how many
     * gaps happened to lie between the old beat and the new one. Two of the five
     * did not move at all, including the one whose rate moved <em>most</em>; and
     * the recording that sets the range's new bottom end had the <em>smallest</em>
     * rate correction of the five.
     *
     * <p>The whole of that difference is one constant bar length drifting
     * against a recording that does not keep one -- #187 and #233 -- and
     * it says nothing about how fast the harmony moves. So the signal such a gate
     * would read is mostly the chart's own grid error: it would decline to reduce
     * a substantial minority of perfectly ordinary bars, which is where the
     * chatter is, and it would still fire on a wrong {@code --tempo}, where the
     * same drift is total rather than partial. What is left is how much of its
     * bar a chord holds, and that is what this reads.
     *
     * <p><b>What it costs, and it is a reduction rather than a clean-up.</b>
     *
     * <ul>
     *   <li>In 4/4 a bar is written as two chords only where each holds about
     *       half of it; a chord holding a single beat is absorbed into its
     *       neighbour. In 3/4 and 6/8 a chord holding one counted beat survives,
     *       because a third and a half of a bar are larger claims than a quarter
     *       of one. That difference between meters follows from the constant
     *       rather than being chosen per meter.
     *   <li><b>A progression read at a badly wrong {@code --tempo} loses most of
     *       its chords from the page.</b> Eight chords a half-beat apart, which
     *       is {@code --tempo 60} against material heard at 120, are written as
     *       the one that holds most of the bar. That case is #157's and #174's
     *       and it used to print all eight. It is not separable from ordinary
     *       chatter by anything this class can see -- see the paragraph above --
     *       so it is stated rather than special-cased, and the chart still heads
     *       itself with the tempo the user supplied.
     *   <li>A MIDI import whose harmony genuinely changes on every beat of a 4/4
     *       bar is written as one chord for that bar. Same mechanism, same lack
     *       of a signal to separate it by; #213 carries the provenance that would.
     * </ul>
     *
     * <p>Nothing is lost from the model: {@link Score#chords()} still holds every
     * span and {@code analyze} still reports how many, so the reduction is
     * recoverable and only ever affects the page.
     *
     * <p>Cells sum to exactly what they summed to before, which is what keeps the
     * engraved bar check a check: the last run is measured back from the bar's
     * own total rather than forward from a slot boundary, so no accumulated slot
     * width can leave a bar a 64th short.
     */
    private static List<Bar> atHarmonicRhythm(List<Bar> bars) {
        List<Bar> reduced = new ArrayList<>(bars.size());
        for (Bar bar : bars) {
            reduced.add(new Bar(bar.meter(), bar.meterChanged(),
                    holdsTheLeadIn(bar) ? bar.cells() : written(bar.cells(), bar.meter())));
        }
        return named(reduced);
    }

    /**
     * Whether a bar holds the gap before the first chord, which is the one thing
     * here that is not a chord and must not be reduced away.
     *
     * <p><b>This is #83, and leaving it out reopened it.</b> How far into its
     * first bar the harmony starts is the chart's only visible statement of the
     * beat grid's phase, and {@code --first-downbeat} is how a user corrects that
     * phase -- which {@code CLAUDE.md} calls one of the two highest-value actions
     * available to them. Reduce this bar like any other and a grid one beat out
     * writes the same page as a grid in phase, because a one-beat straddle is
     * exactly what the rule absorbs. Measured on the click track of
     * {@code ChordChartTest.theBarLinesFollowTheGridsDownbeats}: without this,
     * phases 0 and 3 engrave identically, which is the defect #83 named.
     *
     * <p>So the whole bar is written as it stands, rather than the gap being
     * pinned and the chords around it reduced. Pinning it would need the gap
     * rounded onto a slot boundary, and both directions are wrong: rounding down
     * deletes a short gap, which is the phase signal again, and rounding up
     * lengthens it, which moves the first chord. One bar of a chart is a cheap
     * price for a signal the user is meant to act on.
     */
    private static boolean holdsTheLeadIn(Bar bar) {
        for (Cell cell : bar.cells()) {
            if (cell.chord().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cells one bar is written as.
     *
     * <p>Every offered division is scored and the cheapest wins, rather than the
     * walk stopping at the first that is good enough. The scores are not monotone
     * in the number of slots -- coverage rises with it and the chord count does
     * not, since equal neighbouring slots merge -- so stopping early would pick a
     * division a later one beats.
     */
    private static List<Cell> written(List<Cell> cells, TimeSignature meter) {
        if (cells.size() < 2) {
            return cells;
        }
        double total = 0;
        for (Cell cell : cells) {
            total += cell.lengthQuarters();
        }
        Written best = null;
        for (int slots : divisorsOf(meter.beatsPerBar())) {
            // A slot no duration could name is not a division this can offer.
            // Unreachable from any meter the model admits -- a bar is a whole
            // number of its own counted beats and a counted beat is a whole
            // number of 64ths -- but a bar assembled to a total the meter does
            // not agree with would otherwise reach LilyPondDuration and throw.
            double step = total / slots;
            if (Math.rint(step / LilyPondDuration.SHORTEST_QUARTERS)
                    * LilyPondDuration.SHORTEST_QUARTERS != step) {
                continue;
            }
            Written candidate = atDivision(cells, total, slots);
            // Strictly cheaper, and the divisions are walked coarsest first, so
            // a tie keeps the coarser. The ties are ordinary rather than exotic:
            // a finer division whose slots all fall to the same chords writes the
            // same cells at the same cost, which is every bar holding one chord.
            // Breaking them the other way would make the answer depend on how
            // many divisions the meter happens to offer.
            if (best == null || candidate.cost(total) < best.cost(total)) {
                best = candidate;
            }
        }
        // Only when no division could be named, which the guard above cannot
        // reach from a meter the model admits. The bar is then written as it
        // stands, which drops nothing.
        return best == null ? cells : best.cells();
    }

    /** One way of writing a bar, and how much of the bar it accounts for. */
    private record Written(List<Cell> cells, double covered) {

        /** What it costs to read: what it leaves out, plus every chord after the first. */
        double cost(double total) {
            return (total - covered) / total + EXTRA_CHORD_COST * (cells.size() - 1);
        }
    }

    /**
     * One bar written on a stated number of equal slots, each carrying the chord
     * that fills most of it, with runs of equal symbols merged.
     *
     * <p>Ties within a slot go to the earlier chord, so a slot split evenly reads
     * as the chord it opens on rather than as whichever the iteration order
     * reached last.
     *
     * <p>Linear in the bar's cells for each slot, and both are small: a chart bar
     * holds at most one cell per grid step and is offered at most one slot per
     * counted beat.
     */
    private static Written atDivision(List<Cell> cells, double total, int slots) {
        double step = total / slots;
        Cell[] winners = new Cell[slots];
        double covered = 0;
        for (int slot = 0; slot < slots; slot++) {
            double from = slot * step;
            double to = slot + 1 == slots ? total : from + step;
            Map<String, Double> held = new LinkedHashMap<>();
            Map<String, Cell> first = new LinkedHashMap<>();
            double at = 0;
            for (Cell cell : cells) {
                double overlap = Math.min(to, at + cell.lengthQuarters()) - Math.max(from, at);
                at += cell.lengthQuarters();
                if (overlap <= 0) {
                    continue;
                }
                first.putIfAbsent(cell.symbol(), cell);
                held.merge(cell.symbol(), overlap, Double::sum);
            }
            Cell best = null;
            double most = 0;
            for (Map.Entry<String, Double> entry : held.entrySet()) {
                if (entry.getValue() > most) {
                    most = entry.getValue();
                    best = first.get(entry.getKey());
                }
            }
            // Only where a slot lies wholly outside the cells, which cannot
            // happen while they fill the bar. Falls back to the bar's first
            // chord rather than to nothing, since a cell always carries either a
            // chord or the gap before the first one.
            winners[slot] = best == null ? cells.get(0) : best;
            covered += most;
        }

        List<Cell> out = new ArrayList<>(slots);
        int slot = 0;
        while (slot < slots) {
            int end = slot + 1;
            while (end < slots && winners[end].symbol().equals(winners[slot].symbol())) {
                end++;
            }
            // Measured back from the bar's own total for the final run, so the
            // cells sum to exactly what they were handed however the slot width
            // rounds.
            double to = end == slots ? total : end * step;
            out.add(new Cell(winners[slot].chord(), to - slot * step, false));
            slot = end;
        }
        return new Written(List.copyOf(out), covered);
    }

    /**
     * Every divisor of a positive count, ascending.
     *
     * <p>Ascending is load-bearing rather than tidy: {@link #written} walks these
     * in order and keeps the first of any equal-cost pair, so the order is what
     * makes a tie go to the coarser chart.
     */
    private static int[] divisorsOf(int count) {
        int found = 0;
        int[] divisors = new int[count];
        for (int candidate = 1; candidate <= count; candidate++) {
            if (count % candidate == 0) {
                divisors[found++] = candidate;
            }
        }
        return java.util.Arrays.copyOf(divisors, found);
    }

    /**
     * One chord to a bar, for a progression whose tempo is not knowable.
     *
     * <p>Reached only when {@link Score#estimatedTempo()} yields nothing usable,
     * which leaves no way to say how much of a bar a chord fills. Printing the
     * chords in order at one a bar states no rhythm the model does not have, and
     * still gives the engraving a meter and a bar check, which a bare list of
     * chord names would not.
     */
    private static List<Bar> oneChordPerBar(List<Chord> chords, TimeSignature meter) {
        List<Bar> bars = new ArrayList<>(chords.size());
        String named = null;
        for (int i = 0; i < chords.size(); i++) {
            Chord chord = chords.get(i);
            boolean namesIt = i == 0 || !chord.symbol().equals(named);
            named = namesIt ? chord.symbol() : named;
            bars.add(new Bar(meter, i == 0, List.of(
                    new Cell(Optional.of(chord), meter.quarterBeatsPerBar(), namesIt))));
        }
        return List.copyOf(bars);
    }

    /**
     * Where the chart's first bar line falls, in seconds.
     *
     * <p>On the beat grid's downbeat, and on the first chord only when the grid
     * has none. That is a reversal, and the reason the old behaviour existed is
     * worth stating before the reason it changed: this used to anchor on the
     * first chord because the downbeat detector had been measured half a bar
     * out on a fixture whose chord changes were right -- 0.05s, 1.96s and 3.96s
     * against a detected downbeat at 0.96s -- and anchoring on that pushed the
     * first two chords into one bar.
     *
     * <p>That detector was phased from onset energy. #27 rebuilt it to phase
     * from harmonic change, which is the evidence the old code was reaching past
     * it to use directly, and on the same four-chord fixture the two now agree
     * exactly -- every one of the sixteen chord changes lands on a detected
     * downbeat to within 0.0000s. Reaching past the grid is no longer using the
     * better signal; it is ignoring the only signal a user can correct.
     *
     * <p>Which is what #83 was: {@code --first-downbeat} reached the model and
     * nothing downstream read it, so the correction CLAUDE.md calls the
     * highest-value action available to a user changed nothing on the page. It
     * does now, because the phase the chart is drawn on is the grid's.
     *
     * <p><b>What is and is not guarded.</b>
     * {@code EndToEndIT.downbeatsAgreeWithChords} holds each chord start within
     * 0.06s of <em>some</em> downbeat, which is 0.12 of a quarter beat at 120
     * BPM and not the exact agreement measured above -- it would stay green on a
     * detector that had degraded, and on one that marked every beat a downbeat.
     * It is a floor, not a re-measurement. That floor is also one tier-1
     * fixture, and #189 records what that leaves open: the detector reports a
     * phase it says it cannot know for an anticipated chord change, and this
     * reads the phase without reading the confidence beside it.
     *
     * <p>The anchor is the latest downbeat at or before the first chord, so the
     * chart opens on a bar line and the harmony's offset within that first bar
     * is visible rather than absorbed. Where every downbeat follows the first
     * chord -- a grid that starts late -- the first is stepped back by whole
     * bars, which keeps the phase and the chart's beginning.
     *
     * <p>"At or before" is allowed to overshoot by {@code toleranceSeconds}, so
     * that a chord heard a hair early anchors on the downbeat it belongs to
     * rather than on the one a bar back. That tolerance is <em>half the grid the
     * chart will snap to</em>, and it has to be exactly that: any wider and the
     * first chord snaps to a negative position, which the caller clamps to zero
     * and then pushes every chord behind it a grid step along, into the next
     * bar. Round 4 of review measured that -- nine chords placed correctly
     * became eight in one bar and one alone in the next. So the tolerance is a
     * parameter rather than something derived here, because the only value that
     * keeps the invariant is one this method cannot see.
     *
     * <p>Exactly one downbeat is read. Everything after the first bar line is
     * spaced at {@link Score#estimatedTempo()}, so the grid supplies a phase and
     * the tempo supplies a rate; on a recording that drifts the later bar lines
     * drift with it. That is #187, and it is a real limit rather than an
     * oversight: the chart is headed with a tempo and its bars have to be
     * countable at that tempo, which is what
     * {@code ChordChartTest.headerAndBarsCannotDisagree} holds.
     */
    private static double firstBarStart(Score score, double barSeconds, double toleranceSeconds) {
        double firstChord = score.chords().chords().stream()
                .mapToDouble(Chord::startSeconds)
                .min()
                .orElse(0.0);
        Optional<BeatGrid> grid = score.beatGrid();
        if (grid.isEmpty()) {
            return firstChord;
        }
        List<Double> downbeats = grid.get().downbeatTimes();
        if (downbeats.isEmpty() || !(barSeconds > 0)) {
            return firstChord;
        }
        double tolerance = toleranceSeconds;
        double anchor = Double.NaN;
        for (double downbeat : downbeats) {
            if (downbeat <= firstChord + tolerance) {
                anchor = downbeat;
            } else {
                break;
            }
        }
        if (Double.isNaN(anchor)) {
            double first = downbeats.get(0);
            return first - Math.ceil((first - firstChord - tolerance) / barSeconds) * barSeconds;
        }
        return anchor;
    }

    /**
     * How long a quarter note lasts, for a progression that has no beat axis.
     *
     * <p>Derived from {@link Score#estimatedTempo()}, which is also what the
     * chart's header prints, so the header and the bar lines cannot disagree.
     * They used to: the header read the tempo map while this measured the tracked
     * beats, and {@code --tempo} moves only the map, so a chart could be headed
     * 60 BPM above bars a musician would count at 120.
     *
     * <p>That accessor keeps the reason this preferred the grid in the first
     * place -- the map's synthetic lead-in segment carries an implausible tempo
     * when the first tracked beat is a fraction of a beat in, and a drifting bar
     * grid shows up immediately as chords landing in the wrong bar. It just keeps
     * it in one place rather than two.
     */
    private static double quarterNoteSeconds(Score score) {
        return 60.0 / score.estimatedTempo();
    }

    /**
     * A chord's length as it will be printed: its own, snapped to the shortest
     * value a duration can name.
     *
     * <p>Only the last chord's is read, since every other cell ends where the
     * next chord begins -- but the last one decides how many bars the chart has,
     * and a piece whose final note-off lands a few MIDI ticks past a bar line
     * would otherwise get a trailing empty bar the engraved page does not have.
     */
    private static double printedLengthBeats(Chord chord) {
        double beats = chord.durationBeats().orElseThrow();
        double units = Math.max(1, Math.round(beats / LilyPondDuration.SHORTEST_QUARTERS));
        return units * LilyPondDuration.SHORTEST_QUARTERS;
    }

    /** A position snapped to the nearest whole multiple of {@code grid}. */
    private static double snap(double quarters, double grid) {
        return Math.round(quarters / grid) * grid;
    }
}
