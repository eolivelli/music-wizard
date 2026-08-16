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
 * <p>One derivation, read by both of the chart's outputs — the text and the
 * engraving used to derive the bars independently and could disagree (#174).
 * The exception is whether a cell <em>names</em> its chord, decided twice
 * because {@code chordmode} cannot decline to print a name (see
 * {@link Cell#named()}).
 *
 * <p>A chord holds until the next one starts: cell boundaries are chord
 * starts, never ends. The layout walk drops nothing (#174); the bar is then
 * deliberately reduced to the harmonic rhythm its evidence supports, which
 * does drop chords (#212, {@link #atHarmonicRhythm}).
 */
final class ChartLayout {

    /**
     * The coarsest grid the seconds route will snap chord starts to: one counted
     * beat. Never coarser — snapping to the bar would pull every chord onto a
     * bar line and make the grid's phase unobservable (#83).
     */
    private static final double COARSEST_GRID_BEATS = 1.0;

    /**
     * The share of a bar a second chord has to explain to be worth printing;
     * see {@link #atHarmonicRhythm}. It trades accuracy for readability (#212)
     * — raising it merges real changes, lowering it prints chatter — and it
     * sits just above an exact tie against a quarter of a 4/4 bar, because a
     * decision resting on tie-breaking order is a decision waiting to move.
     */
    private static final double EXTRA_CHORD_COST = 0.3;

    /**
     * One printed chord, or the silence before the first one.
     *
     * @param chord          the chord sounding, or empty for the gap between the
     *                       first bar line and the first chord
     * @param lengthQuarters how much of its bar this cell fills, in quarter beats
     * @param named          whether this cell begins a run of equal symbols, so
     *                       the text chart names the chord here and writes
     *                       {@code %} after it. Read by the text chart alone:
     *                       the page applies the same rule via
     *                       {@code chordChanges}, which legitimately differs at
     *                       a system break
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
    record Bar(TimeSignature meter, boolean meterChanged, List<Cell> cells,
               double startQuarters, double startSeconds, double endSeconds) {

        /** How long this bar is, in quarter beats. */
        double lengthQuarters() {
            return meter.quarterBeatsPerBar();
        }

        /**
         * How long a quarter beat lasts in this bar. Carried per bar so the
         * last bar has an answer of its own; on the {@link #oneChordPerBar}
         * route it is the chord's rate, no tempo being knowable there.
         */
        double secondsPerQuarter() {
            double length = lengthQuarters();
            return length > 0 ? (endSeconds - startSeconds) / length : 0;
        }
    }

    /** The meter of each chart bar, counted from the chart's own first bar. */
    @FunctionalInterface
    private interface BarRuler {
        TimeSignature meterOf(int chartBar);
    }

    /**
     * Where a position on the chart's quarter-beat axis falls in the recording.
     * Supplied by whichever route built the chart and read nowhere else: what
     * leaves this class is each bar's own two ends, so no consumer rebuilds an
     * origin or a rate (#103, #150).
     */
    @FunctionalInterface
    private interface QuartersToSeconds {
        double secondsAt(double quarters);
    }

    /** A chord and the span it holds, in quarter beats from the first bar line. */
    private record Span(Optional<Chord> chord, double from, double to) {
    }

    private ChartLayout() {
    }

    /**
     * The chart's bars, or an empty list when there is no harmony to chart.
     * Taken from the beat axis whenever the progression carries one (the MIDI
     * path, which states its rhythm exactly, #115), and from seconds otherwise
     * (the audio path, whose chords are not quantized).
     */
    static List<Bar> of(Score score) {
        return atHarmonicRhythm(unreduced(score));
    }

    /**
     * The same bars before {@link #atHarmonicRhythm} reduces them. This stage
     * drops nothing — every chord begins exactly one cell (#174); the
     * reduction then drops chords deliberately (#212). Kept separate so a
     * chord absorbed on purpose stays distinguishable from one rounded out of
     * existence; {@code ChordChartTest} holds #174's property here.
     */
    static List<Bar> unreduced(Score score) {
        List<Chord> chords = score.chords().chords();
        if (chords.isEmpty()) {
            return List.of();
        }
        return score.chords().isQuantized()
                ? fromBeats(score, chords)
                : fromSeconds(score, quarterNoteSeconds(score, harmonyStarts(score)));
    }

    /**
     * The bars of a progression that states its own rhythm. Bar lines come
     * from {@link TempoMap#toMusicalTime}, so they honour meter and tempo
     * changes, and the chart's first bar is the bar the first chord falls in.
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
        // Clamped rather than left to wrap: a wrapped bar index reads a meter
        // from the wrong end of the piece.
        return assemble(chords, starts, end, grid,
                k -> map.timeSignatureAtBar((int) Math.min(Integer.MAX_VALUE, (long) firstBar + k)),
                quarters -> map.beatsToSeconds(origin + quarters));
    }

    /**
     * The bars of a progression that is still in seconds — every chart taken
     * from audio — at a stated quarter-note length.
     *
     * <p>The length is a parameter so the degenerate case is reachable from a
     * test: everything here divides by it, and an unusable value would send
     * the bar walk into an endless loop. Only the unusable is guarded — an
     * absurd but positive tempo is honoured, because a plausibility bound
     * belongs on the option, not the chart (#188).
     */
    static List<Bar> fromSeconds(Score score, double quarterSeconds) {
        List<Chord> chords = score.chords().chords();
        TimeSignature meter = score.tempoMap().initialTimeSignature();
        if (!(quarterSeconds > 0) || !Double.isFinite(quarterSeconds)) {
            return oneChordPerBar(chords, meter);
        }
        // Bar lines, then grid, then opening line — in that order because each
        // needs the one before it, and the opening line and the snapping must
        // agree on how far a chord may move, or a first chord heard just early
        // snaps negative and shunts every chord behind it into the next bar.
        BarLines unopened = BarLines.of(score, meter, quarterSeconds);
        double grid = chartGrid(chords, unopened, meter);
        BarLines axis = unopened.opening(harmonyStarts(score), grid);

        double[] starts = new double[chords.size()];
        double lastEnd = 0;
        for (int i = 0; i < chords.size(); i++) {
            starts[i] = snap(axis.quartersAt(chords.get(i).startSeconds()), grid);
            lastEnd = Math.max(lastEnd, snap(axis.quartersAt(chords.get(i).endSeconds()), grid));
        }
        return assemble(chords, starts, lastEnd, grid, k -> meter, axis::secondsAt);
    }

    /**
     * The coarsest grid no narrower than the closest two chord changes.
     *
     * <p>A chord boundary estimated from audio is on no grid, and which grid to
     * put it on is a claim about how precisely the estimate is worth believing
     * — so it is read off the chords rather than fixed. The chart does not
     * resolve a position more finely than the chord changes themselves
     * demonstrate: a grid narrower than the timing error trips over it, and a
     * fixed sixteenth was measured misplacing chords a counted beat held.
     *
     * <p>Measured on <em>gaps</em>, on the axis the chords will be placed on
     * ({@link BarLines#quartersBetween}): a fact about the progression alone,
     * knowable before the chart has an origin — which the anchor needs — and a
     * gap of at least one grid step guarantees distinct positions after
     * snapping, so above the floor no chord can land on another.
     *
     * <p>Bounded below by {@link LilyPondDuration#SHORTEST_QUARTERS} and above
     * by {@link #COARSEST_GRID_BEATS}; candidates that are not a whole number
     * of the shortest value are skipped so every cell length stays nameable.
     * One close pair draws the whole chart on the finer grid, which costs only
     * how a duration reads — a finer grid moves a chord less, not more.
     */
    private static double chartGrid(List<Chord> chords, BarLines axis, TimeSignature meter) {
        double closest = Double.MAX_VALUE;
        for (int i = 1; i < chords.size(); i++) {
            closest = Math.min(closest, axis.quartersBetween(
                    chords.get(i - 1).startSeconds(), chords.get(i).startSeconds()));
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
     * Turns chord starts on a quarter-beat axis into bars of cells. The bar
     * count and every chord's place in it come out of this one walk (#174:
     * rounded independently, a chord could be silently discarded).
     *
     * @param starts each chord's start, in quarter beats from the first bar line
     * @param end    where the harmony stops, on the same axis
     * @param grid   the smallest span this route prints
     */
    private static List<Bar> assemble(List<Chord> chords, double[] starts, double end,
                                      double grid, BarRuler ruler, QuartersToSeconds clock) {
        // Strictly increasing, so every chord gets a cell of its own: two
        // chords closer than the shortest nameable duration can snap onto one
        // grid point, and the alternatives are a zero-length chord or dropping
        // one. The clamp holds the callers' anchoring invariant — broken, it
        // would shunt every chord behind the first into the next bar, and a
        // negative length would reach LilyPondDuration.scaled and throw.
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
        // The gap before the first chord is printed rather than absorbed: it is
        // the chart's one visible statement of the grid's phase, which
        // --first-downbeat corrects. atHarmonicRhythm spares it for the same
        // reason.
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
            double from = barStarts.get(i);
            double to = from + meters.get(i).quarterBeatsPerBar();
            bars.add(new Bar(meters.get(i), i == 0 || !meters.get(i).equals(meters.get(i - 1)),
                    cells.get(i), from, clock.secondsAt(from), clock.secondsAt(to)));
        }
        return named(bars);
    }

    /**
     * Marks the cell that begins each run of equal symbols, in chart order —
     * the text chart's flag; the page applies its own copy of the rule via
     * {@code chordChanges}, since {@code chordmode} cannot decline to print a
     * name.
     *
     * <p>A separate pass over finished cells, because {@link #atHarmonicRhythm}
     * may drop the cell a chord was named at. Every cell is rebuilt, including
     * unchanged ones: this runs twice — over the laid-out bars and again over
     * the reduced ones — and a flag only ever set would survive against a
     * predecessor that no longer exists.
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
            out.add(new Bar(bar.meter(), bar.meterChanged(), List.copyOf(cells),
                    bar.startQuarters(), bar.startSeconds(), bar.endSeconds()));
        }
        return List.copyOf(out);
    }

    /**
     * The chart written at the harmonic rhythm each bar's own evidence
     * supports (#212) — without this, every estimator span is printed and
     * mostly-right harmony reads as chatter.
     *
     * <p><b>The rule, per bar.</b> The bar is divided into equal slots; each
     * slot is written as the chord filling most of it; runs of equal slots
     * merge. The division used is the one minimising
     *
     * <pre>
     *   (share of the bar the written chords do not cover)
     *       + EXTRA_CHORD_COST * (chords written - 1)
     * </pre>
     *
     * with ties to the coarser division. A coverage threshold alone does not
     * work — the finest division covers on-beat chords exactly, so the
     * chattiest bars pass untouched; what decides has to be what a further
     * chord <em>buys</em>. Divisions are whole numbers of counted beats per
     * slot, never finer than the beat, which is what a lead sheet does and
     * what both chord estimators decide over.
     *
     * <p>Applied to every progression rather than only the audio path:
     * {@code isQuantized()} is a fact about the beat axis, not provenance, so
     * gating on it would switch the reduction off if {@code Quantizer} ever
     * joined the audio path (#213 carries the provenance that would answer
     * this). Gating on harmony speed was measured a worse discriminator —
     * chord gaps are whole multiples of the tracked interval, so the signal is
     * mostly grid variation; both columns are in
     * {@code tools/baselines/score-chart.txt}.
     *
     * <p>The costs, stated rather than special-cased: a chord holding one beat
     * of a 4/4 bar is absorbed (in 3/4 and 6/8 it survives — that follows from
     * the constant); a badly wrong {@code --tempo} loses most of its chords
     * from the page (#157, #174); a MIDI bar genuinely changing every beat is
     * written as one chord. Nothing is lost from the model — {@link
     * Score#chords()} keeps every span, so the reduction only affects the page.
     *
     * <p>Cells sum to exactly what they summed to before, which keeps the
     * engraved bar check a check: the last run is measured back from the bar's
     * own total rather than forward from a slot boundary.
     */
    private static List<Bar> atHarmonicRhythm(List<Bar> bars) {
        List<Bar> reduced = new ArrayList<>(bars.size());
        for (Bar bar : bars) {
            reduced.add(new Bar(bar.meter(), bar.meterChanged(),
                    holdsTheLeadIn(bar) ? bar.cells() : written(bar.cells(), bar.meter()),
                    bar.startQuarters(), bar.startSeconds(), bar.endSeconds()));
        }
        return named(reduced);
    }

    /**
     * Whether a bar holds the gap before the first chord — the chart's only
     * visible statement of the grid's phase, which {@code --first-downbeat}
     * corrects, so reducing it away reopens #83. The whole bar is written as
     * it stands: pinning the gap to a slot boundary either deletes it or moves
     * the first chord.
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
     * The cells one bar is written as. Every offered division is scored and
     * the cheapest wins — the scores are not monotone in the number of slots,
     * so stopping at the first good-enough division would pick one a later one
     * beats.
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
            // A slot no duration could name is not a division this can offer;
            // unreachable from any meter the model admits, but a mismatched
            // bar total would otherwise reach LilyPondDuration and throw.
            double step = total / slots;
            if (Math.rint(step / LilyPondDuration.SHORTEST_QUARTERS)
                    * LilyPondDuration.SHORTEST_QUARTERS != step) {
                continue;
            }
            Written candidate = atDivision(cells, total, slots);
            // Strictly cheaper, walked coarsest first, so a tie — which is
            // ordinary, every one-chord bar produces one — keeps the coarser.
            if (best == null || candidate.cost(total) < best.cost(total)) {
                best = candidate;
            }
        }
        // Only when no division could be named; the bar is then written as it
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
     * One bar written on a stated number of equal slots, each carrying the
     * chord that fills most of it, with runs of equal symbols merged. Ties
     * within a slot go to the earlier chord.
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
            // happen while they fill the bar.
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
            // The final run is measured back from the bar's own total, so the
            // cells sum to exactly what they were handed.
            double to = end == slots ? total : end * step;
            out.add(new Cell(winners[slot].chord(), to - slot * step, false));
            slot = end;
        }
        return new Written(List.copyOf(out), covered);
    }

    /**
     * Every divisor of a positive count, ascending — load-bearing:
     * {@link #written} keeps the first of any equal-cost pair, so the order is
     * what makes a tie go to the coarser chart.
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
     * Printing the chords in order at one a bar states no rhythm the model
     * does not have, and still gives the engraving a meter and a bar check.
     */
    private static List<Bar> oneChordPerBar(List<Chord> chords, TimeSignature meter) {
        List<Bar> bars = new ArrayList<>(chords.size());
        String named = null;
        for (int i = 0; i < chords.size(); i++) {
            Chord chord = chords.get(i);
            boolean namesIt = i == 0 || !chord.symbol().equals(named);
            named = namesIt ? chord.symbol() : named;
            bars.add(new Bar(meter, i == 0, List.of(
                    new Cell(Optional.of(chord), meter.quarterBeatsPerBar(), namesIt)),
                    i * meter.quarterBeatsPerBar(), chord.startSeconds(), chord.endSeconds()));
        }
        return List.copyOf(bars);
    }

    /**
     * Where the chart's bar lines fall in the recording, and the one place the
     * chart's quarter-beat axis is converted to and from seconds. The chart is
     * uniform in quarter beats — which keeps every duration nameable and every
     * bar check a check — and need not be uniform in seconds.
     *
     * <p>Where the chart is counted at the grid's own rate and the grid's
     * downbeats are every one of them a plausible bar, they <em>are</em> the
     * bar lines (#187). Nothing is predicted and nothing is repaired: #421
     * measured every way of mending a faulty sequence worse than the constant
     * rate, so a sequence is taken whole or not at all
     * ({@link #evenThroughout}). Where it is not taken, the chart is one bar
     * length hung on the phase the downbeats agree on (#233).
     *
     * <p>A tempo the user supplied is spaced uniformly at that tempo — the
     * correction that matters most is the one where the grid is what the user
     * is disagreeing with — so the sequence is followed only while the chart's
     * quarter is the grid's own ({@link BeatGrid#steadyTempo}). That guard is
     * not a corollary of {@link #evenThroughout};
     * {@code ChordChartTest.aCorrectedTempoKeepsItsOwnBars} holds it.
     *
     * <p>Compared as quarter lengths for exact equality, and both halves are
     * load-bearing: each side is one division by the same tempo, so equality
     * is exact where a tolerance would be arbitrary — and comparing
     * <em>bars</em> instead multiplies by the quarters per bar, which is not
     * associative in floating point unless that factor is a power of two, so
     * it disagrees in triple and compound time.
     * {@code ChordChartTest.aWaltzGridWandersOntoItsOwnDownbeats} holds that
     * side.
     */
    private static final class BarLines {

        /**
         * How far a bar may sit from the sequence's own rate and still be a
         * bar. #421's, with its sweep behind it; see {@link #evenThroughout}.
         */
        private static final double EVEN_ENOUGH = 0.25;

        /** The shortest and longest bar the stated tempo admits. #421's. */
        private static final double SHORTEST_BAR = 0.8;
        private static final double LONGEST_BAR = 1.25;

        /**
         * The bar lines, in seconds and ascending, or empty for an axis that is
         * uniform in seconds too.
         *
         * <p>Strictly increasing: {@link BeatGrid} guarantees it of the
         * downbeats, and the extensions step by a bar length the caller has
         * already checked is positive.
         */
        private final double[] lines;

        /** Which of {@link #lines} the chart's first bar line is. */
        private final int zero;

        /** Where the chart's first bar line falls, in seconds. */
        private final double origin;

        private final double quarterSeconds;
        private final double barQuarters;

        private BarLines(double[] lines, int zero, double origin, double quarterSeconds,
                        double barQuarters) {
            this.lines = lines;
            this.zero = zero;
            this.origin = origin;
            this.quarterSeconds = quarterSeconds;
            this.barQuarters = barQuarters;
        }

        /** When a position on the chart's quarter-beat axis is heard. */
        double secondsAt(double quarters) {
            if (lines.length == 0) {
                return origin + quarters * quarterSeconds;
            }
            int bar = barOf(quarters);
            return lines[bar] + (quarters - (bar - zero) * barQuarters) * rateOf(bar);
        }

        /**
         * How far apart two moments are on this axis, in quarter beats. On the
         * uniform axis this is deliberately not the difference of two
         * {@link #quartersAt} calls, which rounds twice where {@code (b - a)/q}
         * rounds once — {@link #chartGrid} compares this for exact inequality,
         * so a last-bit difference would choose a different grid and move a
         * chord half a beat.
         */
        double quartersBetween(double from, double to) {
            return lines.length == 0
                    ? (to - from) / quarterSeconds
                    : quartersAt(to) - quartersAt(from);
        }

        /** Where a moment in the recording falls on that axis. */
        double quartersAt(double seconds) {
            if (lines.length == 0) {
                return (seconds - origin) / quarterSeconds;
            }
            int bar = barAt(seconds);
            return (bar - zero) * barQuarters + (seconds - lines[bar]) / rateOf(bar);
        }

        /** How long a quarter beat lasts in one bar. */
        private double rateOf(int bar) {
            return (lines[bar + 1] - lines[bar]) / barQuarters;
        }

        /**
         * The bar a position on the quarter-beat axis is in, as an index into
         * {@link #lines}. Clamped, so a position off either end extrapolates
         * at the nearest bar's rate rather than falling off the array.
         */
        private int barOf(double quarters) {
            long bar = zero + (long) Math.floor(quarters / barQuarters);
            return (int) Math.max(0, Math.min(lines.length - 2, bar));
        }

        /** The same, for a moment in the recording. */
        private int barAt(double seconds) {
            int found = java.util.Arrays.binarySearch(lines, seconds);
            int bar = found >= 0 ? found : -found - 2;
            return Math.max(0, Math.min(lines.length - 2, bar));
        }

        /**
         * The axis a chart drawn at {@code quarterSeconds} is laid out on.
         *
         * <p>Anchored on the beat grid's phase, and on the first chord only
         * when the grid states none. Anchoring on the first chord predates
         * #27's harmonic-change downbeat detector; since then, reaching past
         * the grid is not using a better signal but ignoring the only signal a
         * user can correct — which was #83. #189 records what stays open: the
         * phase is read without the confidence beside it.
         *
         * <p>This decides where the bar lines fall, not yet which one the
         * chart opens on — that needs the snapping grid, which
         * {@link #chartGrid} reads off gaps <em>on this axis</em>.
         * {@link #opening} is the second half.
         */
        static BarLines of(Score score, TimeSignature meter, double quarterSeconds) {
            double barQuarters = meter.quarterBeatsPerBar();
            double barSeconds = barQuarters * quarterSeconds;
            double firstChord = harmonyStarts(score);
            Optional<BeatGrid> grid = score.beatGrid();
            List<Double> downbeats = grid.map(BeatGrid::downbeatTimes).orElse(List.of());
            if (downbeats.isEmpty() || !(barSeconds > 0)) {
                return new BarLines(new double[0], 0, firstChord, quarterSeconds, barQuarters);
            }
            if (!atTheGridsOwnRate(grid.orElseThrow(), meter, quarterSeconds)
                    || !evenThroughout(downbeats, barSeconds)) {
                // The phase is asked for here and nowhere else: a followed
                // sequence hangs on its own downbeats and has none to choose.
                return new BarLines(new double[0], 0,
                        barPhase(downbeats, barSeconds, barSeconds / meter.beatsPerBar()),
                        quarterSeconds, barQuarters);
            }
            double lastChord = score.chords().chords().stream()
                    .mapToDouble(Chord::endSeconds)
                    .max()
                    .orElse(firstChord);
            double[] lines = extended(downbeats, barSeconds,
                    firstChord - 2 * barSeconds, lastChord + 2 * barSeconds);
            return new BarLines(lines, 0, lines[0], quarterSeconds, barQuarters);
        }

        /**
         * The same axis, opened on the last bar line at or before the first
         * chord, so the chart opens on a bar line and the harmony's offset
         * within that first bar is visible rather than absorbed. An axis whose
         * lines all follow the harmony is stepped backwards by the same rule.
         *
         * @param gridQuarters the grid chord starts will be snapped to, which is
         *                     how far the opening line may overshoot the first
         *                     chord -- see {@link #firstBarOf}
         */
        BarLines opening(double firstChord, double gridQuarters) {
            if (lines.length == 0) {
                double barSeconds = barQuarters * quarterSeconds;
                double bars = Math.floor(
                        (firstChord + gridQuarters * quarterSeconds / 2 - origin) / barSeconds);
                return new BarLines(lines, 0, origin + bars * barSeconds, quarterSeconds,
                        barQuarters);
            }
            int bar = firstBarOf(lines, firstChord, gridQuarters, barQuarters);
            return new BarLines(lines, bar, lines[bar], quarterSeconds, barQuarters);
        }

        /**
         * Whether every bar the grid marks is a bar, so the sequence can be
         * followed at all. A chart cannot tell from the downbeats alone where
         * the tracker lost the beat, so one bar that is not a bar refuses the
         * whole sequence — #421's sweep found every way of repairing part of
         * one worse than the constant rate.
         *
         * <p>Two conditions, both #421's: every gap close to the sequence's
         * own rate ({@link #EVEN_ENOUGH}), which a sequence with no rate of
         * its own fails, and every gap within the stated bar's bounds
         * ({@link #SHORTEST_BAR}, {@link #LONGEST_BAR}), which a dropped or
         * doubled downbeat fails. #421's median-change gate is deliberately
         * not taken: that statistic is the derivative of the chart's error,
         * and slow drift accumulates unseen beneath it. Fewer than four
         * downbeats is refused too — three gaps give nothing to take a median
         * of.
         */
        private static boolean evenThroughout(List<Double> downbeats, double barSeconds) {
            if (downbeats.size() < 4) {
                return false;
            }
            double[] gaps = new double[downbeats.size() - 1];
            for (int i = 0; i < gaps.length; i++) {
                gaps[i] = downbeats.get(i + 1) - downbeats.get(i);
            }
            double[] sorted = gaps.clone();
            java.util.Arrays.sort(sorted);
            double typical = sorted[sorted.length / 2];
            // The mean of the gaps that look like bars, which is a rate; the
            // median of all of them is a typical value and not one, which is
            // the correction #200 made a layer up.
            double total = 0;
            int counted = 0;
            for (double gap : gaps) {
                if (Math.abs(gap - typical) <= EVEN_ENOUGH * typical) {
                    total += gap;
                    counted++;
                }
            }
            double rate = total / counted;
            for (double gap : gaps) {
                if (Math.abs(gap - rate) > EVEN_ENOUGH * rate
                        || gap < barSeconds * SHORTEST_BAR || gap > barSeconds * LONGEST_BAR) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether the chart's bar is the one the grid itself ran at.
         *
         * <p>See the class javadoc: this is what separates a tempo the user
         * corrected, which is spaced as the user asked, from the tracked rate,
         * whose bar lines may be fitted back onto the beats it was read from.
         */
        private static boolean atTheGridsOwnRate(BeatGrid grid, TimeSignature meter,
                                                 double quarterSeconds) {
            return grid.size() >= 2 && quarterSeconds == 60.0 / grid.steadyTempo(meter);
        }

        /**
         * The tracked downbeats, with whole bars of the stated length added at
         * each end until they cover {@code [from, to]}. The bar lines
         * <em>are</em> the downbeats — {@link #evenThroughout} has already
         * vetoed any sequence with a bar that is not one — and the extensions
         * are the stated bar because nothing was measured out there.
         */
        private static double[] extended(List<Double> downbeats, double barSeconds,
                                         double from, double to) {
            double first = downbeats.get(0);
            double last = downbeats.get(downbeats.size() - 1);
            int before = (int) Math.max(0, Math.ceil((first - from) / barSeconds));
            int after = (int) Math.max(0, Math.ceil((to - last) / barSeconds));
            double[] lines = new double[before + downbeats.size() + after];
            for (int i = 0; i < before; i++) {
                lines[i] = first - (before - i) * barSeconds;
            }
            for (int i = 0; i < downbeats.size(); i++) {
                lines[before + i] = downbeats.get(i);
            }
            for (int i = 0; i < after; i++) {
                lines[before + downbeats.size() + i] = last + (i + 1) * barSeconds;
            }
            return lines;
        }

        /**
         * Which line the chart opens on: the last one at or before the first
         * chord, allowed to overshoot by half the snapping grid so a chord
         * heard a hair early opens the bar it belongs to. Any wider and the
         * first chord snaps negative, which the caller clamps and every chord
         * behind it shunts into the next bar (#184). Half a grid step <em>of
         * the bar the chord is in</em> — the bar {@link #quartersAt} measures
         * the same gap through — or an overshoot into a shorter bar passes the
         * check and still snaps past half a step.
         */
        private static int firstBarOf(double[] lines, double firstChord, double gridQuarters,
                                      double barQuarters) {
            int bar = 0;
            while (bar + 1 < lines.length && lines[bar + 1] <= firstChord) {
                bar++;
            }
            if (bar + 2 < lines.length && lines[bar + 1] - firstChord
                    <= gridQuarters * (lines[bar + 1] - lines[bar]) / barQuarters / 2) {
                bar++;
            }
            // Every bar needs the line after it to state its own rate, and the
            // lines run two bars past the harmony, so the walk cannot reach the
            // end. Clamped anyway rather than relying on that.
            return Math.min(bar, lines.length - 2);
        }
    }

    /**
     * The downbeat the rest of the grid agrees with best: the one whose bar
     * lines, drawn every {@code barSeconds}, leave the smallest total distance
     * to every other downbeat. Taking the <em>first</em> downbeat instead was
     * #233 — one sample of the phase, the one placed with the least evidence,
     * and any rate mismatch walks the downbeats entirely to one side of it.
     *
     * <p>A phase is a position on a circle one bar round, and every step here
     * is taken on that circle — {@link Math#IEEEremainder}. Total distance
     * rather than a mean because a badly placed downbeat is what this has to
     * survive: the answer is a median on a circle, found by trying the
     * downbeats themselves as candidates, ties to the first. Quadratic in the
     * downbeats, which is a few hundred at most.
     *
     * <p>It may not move a bar line by more than half a counted beat — past
     * that the line is on the <em>next</em> beat, and which beat begins a bar
     * is the grid's decision and the user's (#83). A fit that far out is
     * refused, not clamped, and that case is ordinary: a supplied
     * {@code --tempo} puts the downbeats at every phase in turn
     * ({@code ChordChartTest.headerAndBarsCannotDisagree}).
     */
    private static double barPhase(List<Double> downbeats, double barSeconds, double beatSeconds) {
        double nominated = downbeats.get(0);
        double[] around = new double[downbeats.size()];
        for (int i = 0; i < around.length; i++) {
            around[i] = Math.IEEEremainder(downbeats.get(i) - nominated, barSeconds);
        }
        double agreed = 0;
        double least = Double.POSITIVE_INFINITY;
        for (double candidate : around) {
            double total = 0;
            for (double other : around) {
                total += Math.abs(Math.IEEEremainder(other - candidate, barSeconds));
            }
            if (total < least) {
                least = total;
                agreed = candidate;
            }
        }
        return Math.abs(agreed) <= beatSeconds / 2 ? nominated + agreed : nominated;
    }

    /**
     * How long a quarter note lasts, for a progression that has no beat axis:
     * {@link TempoMark#headline}, read at {@link #harmonyStarts}. The header
     * reads it at the chart's first bar line, which is not knowable until the
     * axis this builds exists; the two agree on every score the pipeline can
     * build, since a score stating a tempo change is always quantized and
     * never reaches here. Reading the header and the axis from different
     * sources has shipped twice, headed at one tempo and spaced at another.
     */
    private static double quarterNoteSeconds(Score score, double harmonyStarts) {
        return 60.0 / TempoMark.headline(score, harmonyStarts);
    }

    /**
     * When the chart's harmony starts. Both the bar lines and their rate are
     * decided from this one answer; see {@link #quarterNoteSeconds}.
     */
    static double harmonyStarts(Score score) {
        return score.chords().chords().stream()
                .mapToDouble(Chord::startSeconds)
                .min()
                .orElse(0.0);
    }

    /**
     * A chord's length as it will be printed: its own, snapped to the shortest
     * value a duration can name. Only the last chord's is read — it decides
     * the bar count, and a final note-off a few ticks past a bar line would
     * otherwise add a trailing empty bar.
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
