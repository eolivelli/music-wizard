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

package dev.olivelli.musicwizard.it;

import static dev.olivelli.musicwizard.it.LilyPondComplaints.assertEngravedCleanly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.notation.ChartOptions;
import dev.olivelli.musicwizard.notation.ChordChart;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LilyPond reading back the chord chart's bar arithmetic.
 *
 * <p>This is the oracle {@link ChordChart} did not have. Its bars were decided
 * by code that nothing else could contradict — the text chart counted them one
 * way, the engraving accumulated durations another, and a page whose bars did
 * not add up engraved without a murmur, because a bare {@code \chordmode}
 * sequence with no {@code \time} and no {@code |} gives LilyPond nothing to
 * count. #164 measured that: an emitter mutated so every bar came out half a bar
 * left the whole suite green.
 *
 * <p>Now the chart states its meter and closes each bar with a check, so
 * engraving it is a test of the arithmetic. The fixtures here are the ones the
 * arithmetic used to get wrong: a meter that is not 4/4 (#64), a phase taken
 * from the beat grid rather than from the first chord (#83), and progressions
 * whose chords do not line up with bars (#174).
 *
 * <p>{@link ChordChartTest} in {@code mw-notation} asserts the same bars sum,
 * by reading the emitted durations back; that runs in {@code mvn verify} and is
 * the guard a change trips first. This one is the version where the claim is not
 * ours.
 */
class ChordChartEngravingIT {

    /** With the repeat tags asked for, which is not the default (#417). */
    private static final ChartOptions TAGGED = new ChartOptions(false, true);

    @TempDir
    Path tempDirectory;

    private static PitchSpelling root(NoteLetter letter) {
        return new PitchSpelling(letter, Accidental.NATURAL, 4);
    }

    private static Chord chord(NoteLetter letter, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(root(letter), quality, from, to, Confidence.of(0.9));
    }

    private static final NoteLetter[] ROOTS =
            {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};

    /** Four bars of one chord each in a stated meter, at a stated bar length. */
    private static Score fourBarsIn(TimeSignature meter, double barSeconds, double quarterBpm) {
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chords.add(chord(ROOTS[i], ChordQuality.MAJOR, i * barSeconds, (i + 1) * barSeconds));
        }
        return Score.empty(TempoMap.constant(quarterBpm, meter), 4 * barSeconds)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** A 4/4 click track whose downbeats sit on a chosen one of the four phases. */
    private static Score clickTrackPhasedAt(int phase) {
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int position = Math.floorMod(i - phase, 4);
            beats.add(new BeatGrid.Beat(i * 0.5, position == 0, position));
        }
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chords.add(chord(ROOTS[i], ChordQuality.MAJOR, i * 2.0, i * 2.0 + 2.0));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 12.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** Eight chords a half-beat apart under a corrected {@code --tempo 60}. */
    private static Score eightChordsAtAForcedTempo() {
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            chords.add(chord(ROOTS[i % 4], ChordQuality.MAJOR, i * 0.5, i * 0.5 + 0.5));
        }
        return Score.empty(TempoMap.constantPulse(60, TimeSignature.FOUR_FOUR), 4.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** One sub-beat chord among four that are beat-aligned. */
    private static Score anOrnamentalChord() {
        double[][] spans = {{0, 1}, {1, 1.1}, {1.1, 2}, {2, 3}, {3, 4}};
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A,
                NoteLetter.F, NoteLetter.C};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < spans.length; i++) {
            chords.add(chord(roots[i], ChordQuality.MAJOR, spans[i][0], spans[i][1]));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** A quantized chart whose meter changes at bar 2, which only MIDI produces. */
    private static Score aMeterChange() {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR)
                .withMeterChange(2, TimeSignature.SIX_EIGHT);
        double[] starts = {0, 4, 8, 11};
        double[] ends = {4, 8, 11, 14};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < starts.length; i++) {
            chords.add(Chord.ofSeconds(root(ROOTS[i]), ChordQuality.MAJOR,
                            map.beatsToSeconds(starts[i]), map.beatsToSeconds(ends[i]),
                            Confidence.of(0.9))
                    .quantizedTo(starts[i], ends[i]));
        }
        return Score.empty(map, map.beatsToSeconds(14))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /**
     * A progression that chatters, in a meter whose bar the reduction cannot
     * halve.
     *
     * <p>#212 rewrites a bar's cell lengths, so a bar that no longer sums to its
     * meter is a bar this file exists to catch. The awkward case is a meter whose
     * only divisions are one slot and all of them: a 5/4 bar written on five
     * slots merges runs of them into cells of one, two and three quarters, none
     * of which is a length the unreduced chart would ever have produced here.
     *
     * <p>Sixteen chords over four 5/4 bars, changing on beats the reduction will
     * partly absorb: 2+1+1+1 quarters a bar, so the first bar is written whole
     * and the rest are not.
     */
    private static Score aChatteringFiveFour() {
        TimeSignature meter = new TimeSignature(5, 4);
        double quarter = 0.5;
        double[] lengths = {2, 1, 1, 1};
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.C, NoteLetter.C,
                NoteLetter.A, NoteLetter.A, NoteLetter.F, NoteLetter.A,
                NoteLetter.C, NoteLetter.G, NoteLetter.G, NoteLetter.G,
                NoteLetter.F, NoteLetter.C, NoteLetter.F, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        double at = 0;
        for (int i = 0; i < roots.length; i++) {
            double length = lengths[i % lengths.length];
            chords.add(chord(roots[i], ChordQuality.MAJOR, at * quarter,
                    (at + length) * quarter));
            at += length;
        }
        return Score.empty(TempoMap.constant(120, meter), at * quarter)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /**
     * A 7/8 chart whose bars the reduction rewrites.
     *
     * <p>Three and a half quarters to a bar and seven counted beats, so the only
     * divisions are one slot and seven, and a second chord has to hold most of
     * the bar to be worth its place. Each bar here is laid out as three cells and
     * written as one, so the length LilyPond is asked to check — seven eighths of
     * a whole note, which no single note value names — is one this change
     * computed rather than one it passed through.
     *
     * <p>Round 1 of review found the first draft of this fixture inert: its
     * boundaries were tidied by the layout's own snapping before the reduction
     * saw them, so it engraved the same page either way and tested nothing.
     */
    private static Score aChatteringSevenEight() {
        TimeSignature meter = new TimeSignature(7, 8);
        double quarter = 0.5;
        double[] lengths = {1.5, 0.5, 1.5};
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.C,
                NoteLetter.A, NoteLetter.F, NoteLetter.A,
                NoteLetter.G, NoteLetter.C, NoteLetter.G,
                NoteLetter.F, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        double at = 0;
        for (int i = 0; i < roots.length; i++) {
            double length = lengths[i % lengths.length];
            chords.add(chord(roots[i], ChordQuality.MAJOR, at * quarter,
                    (at + length) * quarter));
            at += length;
        }
        return Score.empty(TempoMap.constant(120, meter), at * quarter)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /**
     * Two four-bar lines, each printed twice, with a chord change inside the
     * first bar of each.
     *
     * <p>The only fixture here whose chart repeats itself, so the only one that
     * engraves #218's repeat brackets at all: they ride in a context beside the
     * chord names, spelled against spacers that mirror the chord cells, and a
     * bracket whose ends do not land on an event is a LilyPond warning rather
     * than a wrong page. Two distinct lines rather than one repeated, because
     * two tags are two different letters and a test that measured brackets by
     * how tall they were drawn would pass on one letter and fail on two. Four
     * lines, so brackets meet end to end, and a split first bar, so a bracket
     * opens on a cell shorter than its bar.
     *
     * <p>Each line ends on a chord neither line opens with: a cell is printed
     * only where the chord differs from the one before it, so a line ending on
     * the chord the next one begins with would print differently the second
     * time and the chart would not repeat at all.
     */
    private static Score repeatedLines() {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        NoteLetter[][] lines = {
                {NoteLetter.C, NoteLetter.A, NoteLetter.F, NoteLetter.G, NoteLetter.D},
                {NoteLetter.G, NoteLetter.E, NoteLetter.C, NoteLetter.F, NoteLetter.A}};
        List<Chord> chords = new ArrayList<>();
        for (int line = 0; line < 4; line++) {
            double offset = 16 * line;
            NoteLetter[] roots = lines[line % 2];
            for (int i = 0; i < 4; i++) {
                chords.add(quantized(map, roots[i], offset + 2 * i, offset + 2 * i + 2));
            }
            chords.add(quantized(map, roots[4], offset + 8, offset + 16));
        }
        return Score.empty(map, map.beatsToSeconds(64))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    private static Chord quantized(TempoMap map, NoteLetter letter,
            double fromBeat, double toBeat) {
        return Chord.ofSeconds(root(letter), ChordQuality.MAJOR, map.beatsToSeconds(fromBeat),
                        map.beatsToSeconds(toBeat), Confidence.of(0.9))
                .quantizedTo(fromBeat, toBeat);
    }

    static Stream<Arguments> charts() {
        return Stream.of(
                        // #64: a bar that is not four quarters long.
                        Arguments.of("three-four", fourBarsIn(TimeSignature.THREE_FOUR, 1.5, 120)),
                        Arguments.of("six-eight", fourBarsIn(TimeSignature.SIX_EIGHT, 1.0, 180)),
                        Arguments.of("five-four", fourBarsIn(new TimeSignature(5, 4), 2.5, 120)),
                        Arguments.of("seven-eight",
                                fourBarsIn(new TimeSignature(7, 8), 1.75, 120)),
                        // #83: bar lines on the grid's phase, harmony off it.
                        Arguments.of("phase-2", clickTrackPhasedAt(2)),
                        Arguments.of("phase-3", clickTrackPhasedAt(3)),
                        // #174: chords that do not line up with bars.
                        Arguments.of("forced-tempo", eightChordsAtAForcedTempo()),
                        Arguments.of("ornamental", anOrnamentalChord()),
                        // A meter change mid-chart, which needs a second \time.
                        Arguments.of("meter-change", aMeterChange()),
                        // #212: bars whose cell lengths the reduction rewrote,
                        // in the two meters whose bar it cannot halve.
                        Arguments.of("chattering-five-four", aChatteringFiveFour()),
                        Arguments.of("chattering-seven-eight", aChatteringSevenEight()),
                        // #218: the chart repeats itself, so the page carries
                        // brackets over the lines it repeats.
                        Arguments.of("repeated-lines", repeatedLines()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("charts")
    @DisplayName("LilyPond counts the chart's bars and finds nothing to say")
    void everyBarSumsToItsMeter(String name, Score score) {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve(name + "/chart.ly"),
                        ChordChart.toLilyPond(score, TAGGED));

        // The narrow question first, so a failure says which defect came back
        // rather than merely that LilyPond said something.
        assertThat(result.failedBarChecks()).as("%s", result.output()).isEmpty();
        // Then everything else it might have said. No tolerance: the one
        // assertEngravedCleanly knows about is a spacing complaint between a
        // tuplet number and a beam, and a chord chart has neither.
        assertEngravedCleanly(name, result);
        assertThat(result.pdf()).isPresent();
    }

    // ---------------------------------------------------------------- #217 --

    /**
     * A probe that makes the page report two things about itself: how tall each
     * bar line LilyPond drew is, and how far right each system reached.
     *
     * <p>Substituted for the chart's own empty {@code \layout} block, so what is
     * engraved is the emitter's source and not a hand copy of it. {@code
     * MidiChordChartIT} patches the same anchor to ask for MIDI, though it
     * appends beside the block rather than replacing it. Neither {@code BarLine}
     * nor {@code System} carries an {@code after-line-breaking} of its own, so
     * this adds callbacks rather than displacing any.
     *
     * <p>The bar line reports a length rather than an interval because an undrawn
     * one has the <em>empty</em> interval, printed {@code (+inf.0 . -inf.0)},
     * which is not a pair of numbers a test can subtract; {@code
     * interval-length} answers 0 for it. The system reports the right end of its
     * interval and the line width beside it, because what matters is only which
     * way the right edge falls: a system's left end sits a little outside the
     * line on every page, where the bar number is printed.
     */
    private static final String LAYOUT_PROBE = """
              \\layout {
                \\context {
                  \\ChordNames
                  \\override BarLine.after-line-breaking =
                    #(lambda (grob)
                       (ly:message "MW-BAR-HEIGHT ~a"
                                   (interval-length (ly:grob-extent grob grob Y))))
                }
                \\context {
                  \\Score
                  \\override System.after-line-breaking =
                    #(lambda (grob)
                       (ly:message "MW-SYSTEM-RIGHT ~a ~a"
                                   (cdr (ly:grob-extent grob grob X))
                                   (ly:output-def-lookup (ly:grob-layout grob) 'line-width)))
                }
              }
            """;

    /** The chart's own source with the probe in place of its {@code \layout}. */
    private static String probed(Score score) {
        String source = ChordChart.toLilyPond(score, TAGGED);
        assertThat(source).as("the emitter still writes the block the probe replaces")
                .contains("  \\layout { }\n");
        return source.replace("  \\layout { }\n", LAYOUT_PROBE);
    }

    private static final Pattern BAR_HEIGHT =
            Pattern.compile("MW-BAR-HEIGHT (\\S+)");

    private static final Pattern SYSTEM_RIGHT =
            Pattern.compile("MW-SYSTEM-RIGHT (\\S+) (\\S+)");

    /** Every bar-line height the probe reported, in the order LilyPond drew them. */
    private static List<Double> barLineHeights(String lilypondOutput) {
        List<Double> heights = new ArrayList<>();
        Matcher matcher = BAR_HEIGHT.matcher(lilypondOutput);
        while (matcher.find()) {
            heights.add(Double.parseDouble(matcher.group(1)));
        }
        return heights;
    }

    /** How far each system overran the line, in staff spaces; negative is room to spare. */
    private static List<Double> systemOverruns(String lilypondOutput) {
        List<Double> overruns = new ArrayList<>();
        Matcher matcher = SYSTEM_RIGHT.matcher(lilypondOutput);
        while (matcher.find()) {
            overruns.add(Double.parseDouble(matcher.group(1))
                    - Double.parseDouble(matcher.group(2)));
        }
        return overruns;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("charts")
    @DisplayName("every bar boundary is drawn, not merely counted")
    void barLinesAreEngravedWithAHeight(String name, Score score) {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve(name + "/drawn.ly"), probed(score));

        // Two distinct failures, and neither can be told from a chart that is
        // right, because neither puts a bar line on the page. Without a
        // Bar_engraver there is no BarLine grob at all and this list is empty;
        // with one but no bar-extent there is a grob per boundary, each of
        // height zero. The chart shipped in the first state until #217.
        List<Double> heights = barLineHeights(result.output());
        assertThat(heights).as("%s", result.output()).isNotEmpty();
        assertThat(heights).as("%s", result.output()).allSatisfy(
                height -> assertThat(height).isGreaterThan(0.0));
        // One per bar: the boundary after each bar but the last, plus the one
        // LilyPond draws at the end of the score. That last one is not evidence
        // of the chart's \bar "|." -- LilyPond draws it either way, and the mark
        // only chooses the glyph, so what closes the chart is asserted on the
        // text in ChordChartTest. Counted from the bar checks the emitter wrote,
        // which catches a bar line lost against a bar kept and nothing more:
        // both sides come from the same text, so a bar dropped in ChartLayout
        // takes its bar line with it and this still passes. Round 4 of review
        // dropped ChartLayout's last bar and measured which tests noticed --
        // not this one, and not the two-outputs comparison either, since both
        // its sides read one layout. The ones that fail are the ones asserting
        // what is in which bar against an expectation formed outside the
        // layout, ChordChartTest.printsOneChordPerBar and
        // theBarLinesFollowTheGridsDownbeats among them.
        long bars = probed(score).lines().filter(line -> line.strip().endsWith("|")).count();
        assertThat(heights).as("%s", result.output()).hasSize((int) bars);
        assertEngravedCleanly(name, result);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("charts")
    @DisplayName("no system runs off the page, which bar lines made possible")
    void noBarIsTooWideForTheLine(String name, Score score) {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve(name + "/width.ly"), probed(score));

        // The cost of the bar lines, and the reason this test exists (#225).
        // Without a Bar_engraver LilyPond could break a system between any two
        // chord names, so a bar wider than the line simply wrapped; with one,
        // breaks fall only at bar lines, as in any engraved music, and a single
        // over-wide bar is set past the margin and then past the edge of the
        // sheet, where the chord names are gone. LilyPond exits zero and says
        // nothing about it, so assertEngravedCleanly cannot be the guard.
        //
        // No fixture here is anywhere near the limit -- a bar has to hold far
        // more chords than any real recording tried has put in one, and how
        // many depends on how wide their names are -- so this passes today and
        // is here to fail when a future ChartLayout change makes a chart clip.
        List<Double> overruns = systemOverruns(result.output());
        assertThat(overruns).as("%s", result.output()).isNotEmpty();
        // A hair of tolerance, not a threshold: a system that fills its line
        // reports a right edge equal to the line width to within rounding, and
        // the overrun that matters is tens of staff spaces.
        assertThat(overruns).as("%s", result.output()).allSatisfy(
                overrun -> assertThat(overrun).isLessThan(1e-6));
    }

    @Test
    @DisplayName("a chart bar that does not sum is caught, so the clean runs mean something")
    void aShortBarIsCaught() {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        // The emitter's own 3/4 output with one bar shortened, rather than
        // hand-written LilyPond: a hand-copied file pins LilyPond's behaviour and
        // says nothing about ours (#92, round 4). No bar check is inserted here,
        // which is the whole difference from before #160 -- the chart brings its
        // own.
        String clean = ChordChart.toLilyPond(fourBarsIn(TimeSignature.THREE_FOUR, 1.5, 120), TAGGED);
        assertThat(clean).as("the emitter no longer writes a 3/4 bar this way, so the "
                        + "damage below would be a no-op and this test would pass for nothing")
                .contains("c2. |");
        String damaged = clean.replace("c2. |", "c2 |");

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve("short/chart.ly"), damaged);

        // A bar a quarter short of its 3/4 meter: LilyPond reports the moment it
        // reached at the check, which is a half note where three quarters were
        // due. It still exits zero and still writes a page, which is why reading
        // the diagnostics is the only thing that catches this.
        assertThat(result.failedBarChecks()).as("%s", result.output()).contains("1/2");
        assertThat(result.succeeded()).as("%s", result.output()).isTrue();
    }

    @Test
    @DisplayName("the repeating fixture really repeats, so the brackets are engraved at all")
    void theRepeatingFixtureIsNotInert() {
        // Without this, a change to the layout that stopped the fixture's
        // lines printing alike would leave the fixture in charts() engraving a chart
        // with no brackets on it, and every test above would still pass while
        // covering nothing. The same trap #212's 7/8 fixture fell into.
        Score score = repeatedLines();

        assertThat(ChordChart.toText(score, TAGGED).lines().filter(line -> line.endsWith("[A]")))
                .hasSize(2);
        assertThat(ChordChart.toText(score, TAGGED).lines().filter(line -> line.endsWith("[B]")))
                .hasSize(2);
        String source = ChordChart.toLilyPond(score, TAGGED);
        assertThat(source.split("\\\\startTextSpan", -1)).hasSize(5);
        // And that the first bar of a line still holds two chords, which is the
        // other half of what this fixture is for: a bracket opening on a cell
        // shorter than its bar. Were the split to go, every assertion above
        // would still pass.
        assertThat(source).contains("s2\\startTextSpan");
    }

    /**
     * A probe that reports, for each piece of each repeat bracket, whether
     * LilyPond drew a label on it and how many end hooks it drew -- on a line
     * narrow enough that it has to break some of them into pieces.
     *
     * <p>Read off the stencil rather than measured. A piece that drew its tag
     * has a text primitive in it and a piece that drew only the bracket has
     * none, which is the question, where any measurement of the drawing is a
     * stand-in for it: an earlier version compared heights, and a bold {@code A}
     * and a bold {@code B} are not the same height. The hooks are counted the
     * same way, from the same string, because the two ends of a bracket are
     * asked for separately and each can be lost on its own.
     *
     * <p>The narrow line is the point rather than a convenience. Where LilyPond
     * breaks a system has nothing to do with the chart's four-bar line, so a
     * bracket straddling a break is ordinary on any real chart and is reached
     * here on purpose instead of being waited for.
     */
    private static final String BRACKET_PROBE = """
              \\layout {
                line-width = 42\\mm
                \\context {
                  \\Dynamics
                  \\override TextSpanner.after-line-breaking =
                    #(lambda (grob)
                       (let* ((drawn (format #f "~a"
                                (ly:stencil-expr (ly:grob-property grob 'stencil))))
                              (hooks (let count ((from 0) (seen 0))
                                       (let ((at (string-contains drawn "0 0 0 -1" from)))
                                         (if at (count (+ at 1) (+ seen 1)) seen)))))
                         (ly:message "MW-BRACKET ~a ~a"
                                     (if (string-contains drawn "utf-8-string")
                                         "labelled" "plain")
                                     hooks)))
                }
              }
            """;

    private static final Pattern BRACKET_PIECE =
            Pattern.compile("MW-BRACKET (\\S+) (\\d+)");

    /** One entry per bracket piece LilyPond drew: did it draw the label, and how many hooks. */
    private record Piece(boolean labelled, int hooks) {
    }

    private static List<Piece> bracketPieces(String lilypondOutput) {
        List<Piece> pieces = new ArrayList<>();
        Matcher matcher = BRACKET_PIECE.matcher(lilypondOutput);
        while (matcher.find()) {
            pieces.add(new Piece("labelled".equals(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))));
        }
        return pieces;
    }

    @Test
    @DisplayName("a bracket a system break cuts in two is still one labelled bracket")
    void aBrokenBracketIsNotDrawnAsTwo() {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        String source = ChordChart.toLilyPond(repeatedLines(), TAGGED);
        int opened = source.split("\\\\startTextSpan", -1).length - 1;
        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond).renderSource(
                tempDirectory.resolve("broken/chart.ly"),
                source.replace("  \\layout { }\n", BRACKET_PROBE));

        // Each piece of a broken bracket takes the label and the closing hook
        // unless it is told not to, and then reads as a whole bracket over part
        // of a line -- a claim about bars the chart never grouped, made
        // silently, since LilyPond has nothing to warn about.
        List<Piece> pieces = bracketPieces(result.output());
        assertThat(pieces).as("the narrow line really did break a bracket: %s",
                result.output()).hasSizeGreaterThan(opened);
        assertThat(pieces.stream().filter(Piece::labelled).count())
                .as("one label for each bracket, not one for each piece of one: %s",
                        result.output())
                .isEqualTo(opened);
        // Two hooks to a bracket however many pieces it is drawn in: the one
        // under its label and the one that closes it. A hook at a break makes a
        // third, and says the group ended where the page ran out.
        assertThat(pieces.stream().mapToInt(Piece::hooks).sum())
                .as("two ends for each bracket, and none at a break: %s", result.output())
                .isEqualTo(2 * opened);
        assertEngravedCleanly("the broken-bracket page", result);
    }
}
