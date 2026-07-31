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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.notation.ChordChart;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The chart's bar arithmetic against a real recording's timing.
 *
 * <p>Tier 2, and the only test in the project that is. {@code samples/list.txt}
 * documents {@code gmajorblues.mp3} as a twelve-bar blues in G looped for eleven
 * minutes, which gives a known progression over eleven minutes of timing nobody
 * synthesised -- and synthetic timing is exactly what hid #200. Every fixture in
 * {@code ChordChartTest} rides on an even beat grid, and on an even grid the
 * median interval and the rate the grid ran at are the same number, so none of
 * them could say which one the chart was spacing its bars at.
 *
 * <p><b>The measurement supplies the chords rather than transcribing them.</b>
 * The progression {@code samples/list.txt} documents is laid on the recording's
 * own detected downbeats, one chord to each. What that measures is where this
 * project's bar arithmetic puts a chord whose intended bar is known; it is not a
 * figure for what the product recognises and must not be quoted as one.
 *
 * <p>The reason first given for supplying them was that chord recognition
 * returned a single {@code N.C.} span covering the whole recording. That was
 * true when this was written and is not now: since #3 the same file yields 740
 * spans and no {@code N.C.} at all. The choice stands for the better reason
 * {@code ChartLayout} gives -- a layout measurement wants a progression known to
 * be right, not one that is 50% right -- so nothing here changed when its
 * original justification stopped applying.
 *
 * <p><b>What it cannot reach.</b> The bar lines are still one downbeat and a
 * constant rate, so they can be no straighter than the tracked beat is. That
 * beat wanders (#187): it holds no single bar length over this recording, and no
 * choice of constant follows it to the end. This test pins where the wander
 * takes over, not its absence -- {@link #theRemainingErrorIsTheGridsOwnWander()}
 * measures the ceiling so the claim above cannot quietly grow.
 *
 * <p>Named {@code *Test} rather than {@code *IT} under this module's rule (#148,
 * #155): it needs no LilyPond, no model and no network, only a file the repo
 * already carries. It costs about five seconds, which is the price of the one
 * gate that would have caught this.
 */
class GmajorBluesChartTest {

    /** {@code samples/list.txt}: G7 G7 G7 G7 / C7 C7 G7 G7 / D7 C7 G7 D7, looped. */
    private static final NoteLetter[] TWELVE_BARS = {
            NoteLetter.G, NoteLetter.G, NoteLetter.G, NoteLetter.G,
            NoteLetter.C, NoteLetter.C, NoteLetter.G, NoteLetter.G,
            NoteLetter.D, NoteLetter.C, NoteLetter.G, NoteLetter.D};

    /** The tracked grid, which costs a decode and an onset pass to obtain. */
    private static BeatGrid grid;

    private static double duration;

    /** Everything the transcriber said on the way, for {@link #oneCommandPrintsOneTempo()}. */
    private static final List<String> progress = new ArrayList<>();

    @BeforeAll
    static void trackTheRecording() {
        Path sample = repositoryRoot().resolve("samples").resolve("gmajorblues.mp3");
        assertThat(sample).as("the sample this test is about is tracked in the repository")
                .exists();
        Score transcribed = new AudioTranscriber(progress::add)
                .transcribe(sample, AudioTranscriber.Options.defaults());
        grid = transcribed.beatGrid().orElseThrow();
        duration = transcribed.durationSeconds();
    }

    /**
     * The repository root, found by walking up rather than assumed to be the
     * module's parent, so the test survives being run from anywhere.
     */
    private static Path repositoryRoot() {
        Path at = Path.of("").toAbsolutePath();
        while (at != null && !Files.isDirectory(at.resolve("samples"))) {
            at = at.getParent();
        }
        assertThat(at).as("no directory at or above %s holds samples/",
                Path.of("").toAbsolutePath()).isNotNull();
        return at;
    }

    /**
     * A chord per detected downbeat, spelt from a caller's pattern, under a
     * caller's tempo map.
     */
    private static Score chartOf(PitchSpelling[] pattern, TempoMap map) {
        List<Double> downbeats = grid.downbeatTimes();
        List<Chord> chords = new ArrayList<>(downbeats.size());
        for (int i = 0; i < downbeats.size(); i++) {
            chords.add(Chord.ofSeconds(pattern[i % pattern.length],
                    ChordQuality.DOMINANT_SEVENTH, downbeats.get(i),
                    i + 1 < downbeats.size() ? downbeats.get(i + 1) : duration,
                    Confidence.of(0.9)));
        }
        return Score.empty(map, duration)
                .withBeatGrid(grid)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    private static PitchSpelling[] documentedProgression() {
        PitchSpelling[] spellings = new PitchSpelling[TWELVE_BARS.length];
        for (int i = 0; i < spellings.length; i++) {
            spellings[i] = new PitchSpelling(TWELVE_BARS[i], Accidental.NATURAL, 4);
        }
        return spellings;
    }

    /**
     * Twelve roots no two of which are spelt alike, cycling every twelve bars
     * like the progression does.
     *
     * <p>Only for {@link #firstChordInTheWrongBar}, which has to tell a chord
     * that begins a bar from one held over into it, and the emitted source spells
     * both the same way -- a held chord is written again in the bar it runs into,
     * because the bar check after it only checks anything if the durations in the
     * bar add up. With every neighbour spelt differently, a repeated spelling is
     * a held chord and nothing else. The real progression cannot serve: it plays
     * G7 four bars running, and four identical symbols say nothing about which
     * bar each one started in.
     */
    private static PitchSpelling[] twelveDistinctRoots() {
        List<PitchSpelling> roots = new ArrayList<>();
        for (NoteLetter letter : NoteLetter.values()) {
            roots.add(new PitchSpelling(letter, Accidental.NATURAL, 4));
        }
        for (NoteLetter letter : NoteLetter.values()) {
            if (roots.size() == 12) {
                break;
            }
            roots.add(new PitchSpelling(letter, Accidental.SHARP, 4));
        }
        return roots.toArray(new PitchSpelling[0]);
    }

    /** The map the transcriber builds, so {@code estimatedTempo} reads the grid. */
    private static TempoMap tracked() {
        return TempoMap.fromBeatTimes(grid.beatTimes(), TimeSignature.FOUR_FOUR);
    }

    /**
     * A map stating one tempo as a correction, which the chart is obliged to
     * obey -- the route {@code --tempo} takes, and the only way left to ask the
     * chart for the spacing it used to choose by itself.
     */
    private static TempoMap corrected(double quarterBpm) {
        return new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0.0, quarterBpm, Provenance.SUPPLIED)),
                List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
    }

    /**
     * The index of the first chord the chart does not begin in the bar of the
     * same number, or the chord count if there is none.
     *
     * <p>#200's measurement. Chord {@code k} is laid on downbeat {@code k} and
     * the chart is anchored on downbeat zero, so chord {@code k} belongs at the
     * head of bar {@code k}, and it is the accumulated bar spacing that decides
     * whether it gets there.
     */
    private static int firstChordInTheWrongBar(TempoMap map) {
        return misplacedChords(map)[0];
    }

    /** {@code {first misplaced chord, how many are misplaced, chords recovered}}. */
    private static int[] misplacedChords(TempoMap map) {
        List<List<String>> bars = barsOf(
                ChordChart.toLilyPond(chartOf(twelveDistinctRoots(), map)));
        List<Integer> barOfChord = new ArrayList<>();
        String previous = null;
        for (int bar = 0; bar < bars.size(); bar++) {
            for (String event : bars.get(bar)) {
                if (!event.equals(previous)) {
                    barOfChord.add(bar);
                    previous = event;
                }
            }
        }
        int chords = grid.downbeatTimes().size();
        int first = chords;
        int misplaced = 0;
        for (int k = 0; k < chords; k++) {
            if (k >= barOfChord.size() || barOfChord.get(k) != k) {
                first = Math.min(first, k);
                misplaced++;
            }
        }
        return new int[] {first, misplaced, barOfChord.size()};
    }

    /**
     * The chord roots printed in each bar of an emitted chart, in order.
     *
     * <p>Off the LilyPond rather than off {@code toText}, because the text chart
     * writes {@code %} for a chord whose symbol repeats and the page does not:
     * the durations in a bar have to add up for its bar check to check anything,
     * so every cell is written out. A root is what precedes the duration, and no
     * root has a digit in it.
     */
    private static List<List<String>> barsOf(String lilyPond) {
        List<List<String>> bars = new ArrayList<>();
        for (String line : lilyPond.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.endsWith("|")) {
                continue;
            }
            List<String> events = new ArrayList<>();
            for (String token : trimmed.substring(0, trimmed.length() - 1).trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    events.add(token.split("[0-9]", 2)[0]);
                }
            }
            bars.add(List.copyOf(events));
        }
        return bars;
    }

    @Test
    @DisplayName("the tracked grid's median interval is not the rate it ran at")
    void theTwoRatesDisagreeOnThisRecording() {
        // The premise of everything below, and the thing no synthetic fixture
        // has. Were these ever to converge the assertions further down would
        // stop discriminating and pass on either behaviour.
        assertThat(grid.size()).isEqualTo(1281);
        assertThat(grid.downbeatTimes()).hasSize(320);
        assertThat(grid.medianTempo(TimeSignature.FOUR_FOUR))
                .as("the periodic template the tracker scored its pulses against")
                .isCloseTo(106.556, within(0.001));
        assertThat(grid.overallTempo(TimeSignature.FOUR_FOUR))
                .as("what those pulses actually did, 1.4% faster")
                .isCloseTo(108.067, within(0.001));
    }

    @Test
    @DisplayName("the rate it reports while tracking is the rate it charts with")
    void oneCommandPrintsOneTempo() {
        // Round 3 of review found this PR creating a divergence: the progress
        // line printed BeatTracker's median interval and the chart header
        // printed the score's tempo, which agreed until #200 and then did not.
        // 106.6 against 108 on this recording.
        //
        // Untidy, and worse than untidy, because the comment beside --tempo in
        // AudioTranscriber invites the user to type back "the rate this very run
        // just reported" and a supplied tempo beats the grid. 106.6 is the
        // spacing chordsStayInTheirBarsFourTimesLonger measures putting 295 of
        // 320 chords in the wrong bar, so the printed figure was a documented
        // route back to the defect.
        //
        // Only a real recording can hold this: on an even grid the two figures
        // agree whichever statistic each reads.
        String reported = progress.stream()
                .filter(line -> line.startsWith("found ") && line.contains("beats/min"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rate was reported: " + progress));

        // Parsed rather than compared against a literal, so what is pinned is
        // the agreement and not two numbers this test wrote down itself. Round 4
        // of review found the first version asserting both figures against its
        // own constants, which would have gone on passing if the two drifted
        // apart in step with the fixture.
        Matcher rate = Pattern.compile("found (\\d+) beats at ([0-9.]+) beats/min")
                .matcher(reported);
        assertThat(rate.matches()).as("%s", reported).isTrue();
        assertThat(Integer.parseInt(rate.group(1))).isEqualTo(grid.size());
        assertThat(Double.parseDouble(rate.group(2)))
                .as("the rate printed while tracking is the rate the chart is barred at")
                .isCloseTo(grid.overallPulseRate(), within(0.05));

        // And the same figure in the header, which is the other end of the
        // journey a user reads. 4/4 here, so a counted beat is a quarter note.
        assertThat(ChordChart.toText(chartOf(documentedProgression(), tracked())))
                .contains(String.format(Locale.ROOT, "Tempo  %.0f BPM",
                        grid.overallTempo(TimeSignature.FOUR_FOUR)));

        // The literals too, because the assertions above would both hold if the
        // two readers agreed on the median instead -- which is what they did
        // before #200, and what makes this recording worth the five seconds.
        assertThat(reported).isEqualTo("found 1281 beats at 108.1 beats/min");
        assertThat(reported)
                .as("the figure this used to print, and the one that mis-bars")
                .doesNotContain("106.6");
    }

    @Test
    @DisplayName("a chart of the tracked recording is barred at the rate the grid ran at")
    void theChartReadsTheGridsOwnRate() {
        Score score = chartOf(documentedProgression(), tracked());

        assertThat(score.estimatedTempo())
                .isEqualTo(grid.overallTempo(TimeSignature.FOUR_FOUR));
        // Both readers. The header and the bar lines are separate readers of one
        // answer, and reaching one and not the other is the failure this project
        // keeps repeating -- #184 shipped it twice in one branch.
        assertThat(ChordChart.toText(score)).contains("Tempo  108 BPM");
    }

    @Test
    @DisplayName("chords hold their bars four times further along the recording")
    void chordsStayInTheirBarsFourTimesLonger() {
        // #200's measurement, as a gate. The two charts differ in nothing but
        // the tempo they are handed: the same grid supplies the phase, the same
        // downbeats supply the chords.
        int[] atTheGridsRate = misplacedChords(tracked());
        int[] atTheMedian = misplacedChords(
                corrected(grid.medianTempo(TimeSignature.FOUR_FOUR)));

        assertThat(atTheGridsRate[2]).as("every chord was recovered from the page, so "
                + "the reconstruction above is not quietly losing one").isEqualTo(320);
        assertThat(atTheMedian[2]).isEqualTo(320);

        // Zero-based, where #200 counts chords from one: its 26, and 112 where
        // it measured 114. The two differ because #3 moved this recording's
        // downbeat phase by one beat after #200 was written; the beat times are
        // byte-identical and every tempo figure above is unchanged, which is why
        // only the placement literals moved. Re-measured on the merged tree
        // rather than adjusted until they passed.
        assertThat(atTheMedian[0])
                .as("the spacing before this change, still reachable through --tempo, "
                        + "so the fixture shows what it is measuring against")
                .isEqualTo(25);
        assertThat(atTheMedian[1]).isEqualTo(294);
        assertThat(atTheGridsRate[0]).isEqualTo(111);
        assertThat(atTheGridsRate[1]).isEqualTo(31);

        // Also as inequalities, because the equalities are measurements of one
        // beat tracker and a change to it may legitimately move them. What must
        // not come back is the order of magnitude.
        assertThat(atTheGridsRate[0]).isGreaterThan(4 * atTheMedian[0]);
        assertThat(atTheGridsRate[1]).isLessThan(atTheMedian[1] / 4);
    }

    @Test
    @DisplayName("and the documented symbols reach the bars they belong to")
    void theDocumentedProgressionPrintsInTheRightBars() {
        // The same result stated in the terms a reader of the chart would use:
        // the symbol bar k needs is printed in bar k. Weaker than the assertions
        // above -- the blues plays G7 four bars running, so a G7 held over from
        // the bar before satisfies this one -- and kept because it is the claim
        // about the actual progression rather than about a spelling device.
        assertThat(firstDocumentedSymbolMissing(tracked()))
                .isEqualTo(114);
        assertThat(firstDocumentedSymbolMissing(
                corrected(grid.medianTempo(TimeSignature.FOUR_FOUR))))
                .isEqualTo(81);
    }

    private static int firstDocumentedSymbolMissing(TempoMap map) {
        List<List<String>> bars = barsOf(
                ChordChart.toLilyPond(chartOf(documentedProgression(), map)));
        int chords = grid.downbeatTimes().size();
        for (int k = 0; k < chords; k++) {
            String expected = TWELVE_BARS[k % 12].name().toLowerCase(Locale.ROOT);
            if (k >= bars.size() || !bars.get(k).contains(expected)) {
                return k;
            }
        }
        return bars.size();
    }

    @Test
    @DisplayName("what is left is the beat grid's own wander, which no constant can remove")
    void theRemainingErrorIsTheGridsOwnWander() {
        // #187, measured rather than asserted, so the PR that fixes it has a
        // number to beat and this one cannot be read as claiming more than it
        // did. No constant bar length beats the best constant bar length, and
        // the best one is still a whole bar out before the recording ends --
        // because the tracked downbeats sit on no single bar length.
        List<Double> downbeats = grid.downbeatTimes();
        int last = downbeats.size() - 1;
        double bestBar = (downbeats.get(last) - downbeats.get(0)) / last;

        double worst = 0;
        for (int k = 0; k <= last; k++) {
            worst = Math.max(worst,
                    Math.abs(downbeats.get(0) + k * bestBar - downbeats.get(k)));
        }
        assertThat(worst)
                .as("worst gap between the best constant bar line and the downbeat it "
                        + "should sit on, over %d downbeats", downbeats.size())
                .isGreaterThan(bestBar / 2);
    }
}
