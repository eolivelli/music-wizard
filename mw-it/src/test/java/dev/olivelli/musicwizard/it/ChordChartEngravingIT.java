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
                        Arguments.of("chattering-seven-eight", aChatteringSevenEight()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("charts")
    @DisplayName("LilyPond counts the chart's bars and finds nothing to say")
    void everyBarSumsToItsMeter(String name, Score score) {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve(name + "/chart.ly"),
                        ChordChart.toLilyPond(score));

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
     * A probe that makes every bar line LilyPond drew report its own height.
     *
     * <p>Substituted for the chart's own empty {@code \layout} block, so what is
     * engraved is the emitter's source and not a hand copy of it. {@code
     * MidiChordChartIT} patches the same anchor to ask for MIDI, though it
     * appends beside the block rather than replacing it. {@code BarLine} carries
     * no {@code after-line-breaking} of its own, so this adds a callback rather
     * than displacing one.
     *
     * <p>It reports a length rather than the interval because an undrawn bar
     * line has the <em>empty</em> interval, printed {@code (+inf.0 . -inf.0)},
     * which is not a pair of numbers a test can subtract; {@code
     * interval-length} answers 0 for it.
     */
    private static final String HEIGHT_PROBE = """
              \\layout {
                \\context {
                  \\ChordNames
                  \\override BarLine.after-line-breaking =
                    #(lambda (grob)
                       (ly:message "MW-BAR-HEIGHT ~a"
                                   (interval-length (ly:grob-extent grob grob Y))))
                }
              }
            """;

    /** The chart's own source with the probe in place of its {@code \layout}. */
    private static String probed(Score score) {
        String source = ChordChart.toLilyPond(score);
        assertThat(source).as("the emitter still writes the block the probe replaces")
                .contains("  \\layout { }\n");
        return source.replace("  \\layout { }\n", HEIGHT_PROBE);
    }

    private static final Pattern BAR_HEIGHT =
            Pattern.compile("MW-BAR-HEIGHT (\\S+)");

    /** Every bar-line height the probe reported, in the order LilyPond drew them. */
    private static List<Double> barLineHeights(String lilypondOutput) {
        List<Double> heights = new ArrayList<>();
        Matcher matcher = BAR_HEIGHT.matcher(lilypondOutput);
        while (matcher.find()) {
            heights.add(Double.parseDouble(matcher.group(1)));
        }
        return heights;
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
        // so a chart that lost a bar line cannot pass by also having lost the
        // bar.
        long bars = probed(score).lines().filter(line -> line.strip().endsWith("|")).count();
        assertThat(heights).as("%s", result.output()).hasSize((int) bars);
        assertEngravedCleanly(name, result);
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
        String clean = ChordChart.toLilyPond(fourBarsIn(TimeSignature.THREE_FOUR, 1.5, 120));
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
}
