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
import java.util.List;
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
 * returns. The engraved page then carries a {@code |} bar check per bar
 * (#160), which makes LilyPond itself check that the durations in a bar sum to
 * the meter this class says that bar is in — the one contradiction the chart
 * could not previously produce.
 *
 * <p><b>A chord holds until the next one starts.</b> Cell boundaries are chord
 * <em>starts</em>, never ends, which is both how a chart is read and why no
 * chord can be dropped: every chord in the progression begins exactly one cell,
 * and the last cell runs to the final bar line. A chord that stops early leaves
 * its symbol standing, and a silence the estimator states explicitly arrives as
 * an {@code N.C.} chord of its own.
 */
final class ChartLayout {

    /**
     * The finest position the seconds route prints: a sixteenth note.
     *
     * <p>Chord boundaries estimated from audio are not on any grid, so they have
     * to be snapped to one before a bar can hold a whole number of them, and the
     * choice of grid is a choice about what the chart claims. A sixteenth is
     * fine enough for every subdivision a chord chart shows — a chord landing on
     * an off-beat eighth keeps its place — and coarse enough that a boundary
     * detected 40 ms early at 120 BPM lands on the beat it belongs to rather
     * than a 64th before it, which would print as a duration no reader could
     * account for.
     *
     * <p>Deliberately coarser than {@link LilyPondDuration#SHORTEST_QUARTERS},
     * which is the grid the <em>quantized</em> route uses: there the rhythm was
     * stated exactly by the source and there is nothing to estimate away.
     */
    static final double SECONDS_GRID_QUARTERS = 0.25;

    /**
     * One printed chord, or the silence before the first one.
     *
     * @param chord          the chord sounding, or empty for the gap between the
     *                       first bar line and the first chord
     * @param lengthQuarters how much of its bar this cell fills, in quarter beats
     * @param named          whether the chart names the chord here, which it does
     *                       once per run: a cell continuing the chord of the
     *                       previous cell is a {@code %} in the text and prints
     *                       nothing on the page
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
        double origin = firstBarStart(score, meter.quarterBeatsPerBar() * quarterSeconds);

        double[] starts = new double[chords.size()];
        double end = 0;
        for (int i = 0; i < chords.size(); i++) {
            Chord chord = chords.get(i);
            starts[i] = snap((chord.startSeconds() - origin) / quarterSeconds,
                    SECONDS_GRID_QUARTERS);
            end = Math.max(end,
                    snap((chord.endSeconds() - origin) / quarterSeconds, SECONDS_GRID_QUARTERS));
        }
        return assemble(chords, starts, end, SECONDS_GRID_QUARTERS, k -> meter);
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
        // chords can snap onto one grid point -- the audio estimator can place a
        // chord change a few milliseconds after the last -- and the alternative
        // to nudging the second one along is printing a zero-length chord, which
        // no duration can name, or dropping it, which is the defect being fixed.
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
        String named = null;
        boolean anyNamed = false;
        int bar = 0;
        for (Span span : spans) {
            while (bar + 1 < barStarts.size() && barStarts.get(bar + 1) <= span.from()) {
                bar++;
            }
            boolean first = true;
            double at = span.from();
            while (at < span.to() && bar < barStarts.size()) {
                double barEnd = barStarts.get(bar) + meters.get(bar).quarterBeatsPerBar();
                double cut = Math.min(span.to(), barEnd);
                String symbol = span.chord().map(Chord::symbol).orElse(ChordQuality.NONE.symbol());
                // Named once per run of equal symbols, which is what the text
                // chart's "%" has always meant and what chordChanges makes
                // LilyPond do. Both emitters read this flag rather than deciding
                // it, so the page and the text cannot disagree about which bar
                // introduces a chord.
                boolean namesIt = first && (!anyNamed || !symbol.equals(named));
                cells.get(bar).add(new Cell(span.chord(), cut - at, namesIt));
                if (namesIt) {
                    named = symbol;
                    anyNamed = true;
                }
                first = false;
                at = cut;
                if (cut == barEnd) {
                    bar++;
                }
            }
        }

        List<Bar> bars = new ArrayList<>(barStarts.size());
        for (int i = 0; i < barStarts.size(); i++) {
            bars.add(new Bar(meters.get(i), i == 0 || !meters.get(i).equals(meters.get(i - 1)),
                    List.copyOf(cells.get(i))));
        }
        return List.copyOf(bars);
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
     * 0.06s of <em>some</em> downbeat, which is a tenth of a beat at 120 BPM and
     * not the exact agreement measured above -- it would stay green on a
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
     * <p>Exactly one downbeat is read. Everything after the first bar line is
     * spaced at {@link Score#estimatedTempo()}, so the grid supplies a phase and
     * the tempo supplies a rate; on a recording that drifts the later bar lines
     * drift with it. That is #187, and it is a real limit rather than an
     * oversight: the chart is headed with a tempo and its bars have to be
     * countable at that tempo, which is what
     * {@code ChordChartTest.headerAndBarsCannotDisagree} holds.
     */
    private static double firstBarStart(Score score, double barSeconds) {
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
        // Half a printed cell: a chord detected a hair before the downbeat it
        // belongs to must anchor on that downbeat, not on the one a bar earlier.
        double tolerance = barSeconds * SECONDS_GRID_QUARTERS
                / (2 * score.tempoMap().initialTimeSignature().quarterBeatsPerBar());
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
