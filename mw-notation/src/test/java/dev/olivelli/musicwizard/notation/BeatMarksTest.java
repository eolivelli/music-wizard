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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LrcLyrics;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BeatMarksTest {

    /** A {@code \skip} or a quoted mark, each with the duration that follows it. */
    private static final Pattern TOKEN =
            Pattern.compile("\\\\skip\\s+(\\S+)|\"([^\"]*)\"(\\S+)");

    /** {@code 4}, {@code 2.}, {@code 4*3/5} — what LilyPondDuration writes. */
    private static final Pattern DURATION =
            Pattern.compile("(\\d+)(\\.*)(?:\\*(\\d+)/(\\d+))?");

    private static final ChartOptions MARKED = new ChartOptions(true, false);

    /** One mark: where in its bar it falls, in quarter beats, and what it prints. */
    private record Mark(double quarters, String text) {
    }

    private static PitchSpelling root(NoteLetter letter) {
        return new PitchSpelling(letter, Accidental.NATURAL, 4);
    }

    /** A chart of {@code bars} bars at 120 BPM in 4/4, so a bar is two seconds. */
    private static Score chart(int bars) {
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            chords.add(Chord.ofSeconds(root(roots[i % 4]), ChordQuality.MAJOR,
                    i * 2.0, i * 2.0 + 2.0, Confidence.of(0.9)));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), bars * 2.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** The same chart, with a tracked beat every {@code every} seconds from zero. */
    private static Score tracked(int bars, double every) {
        List<Double> beats = new ArrayList<>();
        for (double at = 0; at < bars * 2.0; at += every) {
            beats.add(at);
        }
        return chart(bars).withBeatGrid(
                BeatGrid.ofTimes(beats, TimeSignature.FOUR_FOUR, Confidence.of(0.9)));
    }

    /** Each lane's bars: the lines between a {@code \lyricmode {} and its brace. */
    private static List<List<String>> lanes(String lilyPond) {
        List<List<String>> lanes = new ArrayList<>();
        List<String> bars = null;
        for (String line : lilyPond.split("\n")) {
            if (line.contains("\\lyricmode {")) {
                bars = new ArrayList<>();
            } else if (bars != null && line.strip().equals("}")) {
                lanes.add(bars);
                bars = null;
            } else if (bars != null) {
                bars.add(line.strip());
            }
        }
        return lanes;
    }

    /** The marks on one bar line, each with its offset from the bar's start. */
    private static List<Mark> marksIn(String barLine) {
        List<Mark> marks = new ArrayList<>();
        double cursor = 0;
        Matcher matcher = TOKEN.matcher(barLine);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                cursor += quartersOf(matcher.group(1));
                continue;
            }
            marks.add(new Mark(cursor, matcher.group(2)));
            cursor += quartersOf(matcher.group(3));
        }
        return marks;
    }

    private static double quartersOf(String duration) {
        Matcher matcher = DURATION.matcher(duration);
        assertThat(matcher.matches()).as("%s", duration).isTrue();
        double quarters = 4.0 / Integer.parseInt(matcher.group(1));
        double dotted = quarters;
        for (int dot = 0; dot < matcher.group(2).length(); dot++) {
            dotted /= 2;
            quarters += dotted;
        }
        if (matcher.group(3) != null) {
            quarters = quarters * Integer.parseInt(matcher.group(3))
                    / Integer.parseInt(matcher.group(4));
        }
        return quarters;
    }

    @Test
    @DisplayName("no marks unless the caller asks for them")
    void offByDefault() {
        Score score = tracked(4, 0.5);

        assertThat(lanes(ChordChart.toLilyPond(score))).isEmpty();
        assertThat(lanes(ChordChart.toLilyPond(score, ChartOptions.defaults()))).isEmpty();
    }

    @Test
    @DisplayName("a beat on every quarter prints one to four across each bar")
    void countsThroughTheBar() {
        List<List<String>> lanes = lanes(ChordChart.toLilyPond(tracked(4, 0.5), MARKED));

        assertThat(lanes).hasSize(1);
        assertThat(lanes.get(0)).hasSize(4);
        assertThat(lanes.get(0)).allSatisfy(bar ->
                assertThat(marksIn(bar)).as("%s", bar).containsExactly(
                        new Mark(0.0, "1"), new Mark(1.0, "2"),
                        new Mark(2.0, "3"), new Mark(3.0, "4")));
    }

    @Test
    @DisplayName("the marks follow the tracked beats, not the bars drawn around them")
    void readTheGridAndNotTheBars() {
        // A grid that slows as it goes: no constant bar length fits it, so the
        // bars the chart draws and the beats the tracker reported must come
        // apart. This is what the lane is for, and it is the one test a lane
        // drawn from the bar axis could not pass -- ticks at "a quarter of this
        // bar's length" are four to a bar and on the quarter, always.
        List<Double> beats = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            beats.add(0.5 * i + 0.004 * i * i);
        }
        Score score = chart(8).withBeatGrid(
                BeatGrid.ofTimes(beats, TimeSignature.FOUR_FOUR, Confidence.of(0.9)));

        List<String> bars = lanes(ChordChart.toLilyPond(score, MARKED)).get(0);

        List<Integer> perBar = bars.stream().map(bar -> marksIn(bar).size()).toList();
        assertThat(perBar).as("%s", bars).anyMatch(count -> count != 4);
        assertThat(bars.stream().flatMap(bar -> marksIn(bar).stream()))
                .as("%s", bars)
                .anyMatch(mark -> Math.abs(mark.quarters() - Math.round(mark.quarters())) > 1e-6);
    }

    @Test
    @DisplayName("beats before the first bar are left out rather than piled onto it")
    void beatsBeforeTheChartAreDropped() {
        // A recording that plays for a while before the first chord: the grid
        // runs from the top and the chart begins where the harmony does, so
        // twenty beats have no bar to fall in. Clamped instead of dropped they
        // would land on the chart's first unit and be spread a grid step apart
        // by the nudge, which reads as twenty beats tracked in the first bar.
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    10.0 + i * 2.0, 12.0 + i * 2.0, Confidence.of(0.9)));
        }
        Score chart = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 20.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));

        // The page a grid running from the top produces, against the page the
        // same grid produces with its lead-in beats never recorded. Dropping
        // them has to be the same thing as not having had them; clamping is what
        // makes the two differ, and it puts the whole lead-in in the first bar.
        List<String> withLeadIn = lanes(ChordChart.toLilyPond(
                chart.withBeatGrid(pulse(0.0, 18.0, 0.5)), MARKED)).get(0);
        List<String> without = lanes(ChordChart.toLilyPond(
                chart.withBeatGrid(pulse(10.0, 18.0, 0.5)), MARKED)).get(0);

        assertThat(withLeadIn).isEqualTo(without);
        assertThat(withLeadIn).allSatisfy(bar ->
                assertThat(marksIn(bar)).as("%s", bar).hasSize(4));
    }

    /** A phased 4/4 grid pulsing every {@code every} seconds over {@code [from, to)}. */
    private static BeatGrid pulse(double from, double to, double every) {
        List<Double> beats = new ArrayList<>();
        for (double at = from; at < to; at += every) {
            beats.add(at);
        }
        return BeatGrid.ofTimes(beats, TimeSignature.FOUR_FOUR, Confidence.of(0.9));
    }

    @Test
    @DisplayName("a grid whose downbeats were never decided prints ticks, not numbers")
    void unphasedGridPrintsTicks() {
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            beats.add(BeatGrid.Beat.unphased(0.5 * i));
        }
        Score score = chart(4).withBeatGrid(
                new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.1)));

        List<String> bars = lanes(ChordChart.toLilyPond(score, MARKED)).get(0);

        assertThat(bars.stream().flatMap(bar -> marksIn(bar).stream()).map(Mark::text))
                .isNotEmpty().containsOnly(".");
    }

    @Test
    @DisplayName("every bar of marks sums to the meter")
    void everyBarSums() {
        List<String> bars = lanes(ChordChart.toLilyPond(tracked(6, 0.37), MARKED)).get(0);

        assertThat(bars).hasSize(6);
        assertThat(bars).allSatisfy(bar -> {
            double total = 0;
            Matcher matcher = TOKEN.matcher(bar);
            while (matcher.find()) {
                total += quartersOf(matcher.group(1) != null ? matcher.group(1) : matcher.group(3));
            }
            assertThat(total).as("%s", bar).isCloseTo(4.0, within(1e-9));
        });
    }

    @Test
    @DisplayName("on the lyric sheet the marks sit between the chords and the words")
    void betweenTheChordsAndTheWords() {
        Score score = tracked(4, 0.5);
        Score sung = score.withLyrics(LrcLyrics.parse("""
                [00:00.00]<00:00.00>la <00:01.00>sol <00:02.00>mi <00:03.00>do
                """, score.durationSeconds()));

        String lilyPond = LyricSheet.toLilyPond(sung, MARKED);
        List<List<String>> lanes = lanes(lilyPond);

        assertThat(lanes).hasSize(2);
        assertThat(marksIn(lanes.get(0).get(0))).extracting(Mark::text)
                .containsExactly("1", "2", "3", "4");
        assertThat(lilyPond.indexOf("\\chordmode"))
                .isLessThan(lilyPond.indexOf("\\lyricmode"));
    }
}
