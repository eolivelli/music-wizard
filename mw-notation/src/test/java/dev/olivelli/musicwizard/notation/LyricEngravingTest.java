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
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LrcLyrics;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The engraved lyric line, read out of the generated source.
 *
 * <p>The arithmetic here is checked twice on purpose. These tests parse the
 * durations back out of the text, which is fast and runs offline; {@code
 * LyricEngravingIT} hands the same files to LilyPond, which is the only reader
 * that can say the bars really add up. A golden file over this text would say
 * neither — it would pass on any wrong page whose wrongness it had recorded.
 */
class LyricEngravingTest {

    /** {@code 4}, {@code 2.}, {@code 4*3/5} — what LilyPondDuration writes. */
    private static final Pattern DURATION =
            Pattern.compile("(\\d+)(\\.*)(?:\\*(\\d+)/(\\d+))?");

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

    private static Score sung(int bars, String lrc) {
        Score score = chart(bars);
        return score.withLyrics(LrcLyrics.parse(lrc, score.durationSeconds()));
    }

    /** The same, with the lyrics tagged so their words are split into syllables. */
    private static Score sungIn(String language, int bars, String lrc) {
        Score score = sung(bars, lrc);
        Lyrics tagged = new Lyrics(score.lyrics().lines(), language,
                score.lyrics().confidence());
        return score.withLyrics(tagged);
    }

    /** Each lane's bars: the lines between a {@code \lyricmode {} and its brace. */
    private static List<List<String>> lyricLanes(String lilyPond) {
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

    /** The bars of the one lane a score with no overlapping lines produces. */
    private static List<String> lyricBars(String lilyPond) {
        List<List<String>> lanes = lyricLanes(lilyPond);
        assertThat(lanes).hasSize(1);
        return lanes.get(0);
    }

    /** Every duration on one lyric bar line, summed in quarter beats. */
    private static double quartersIn(String barLine) {
        double total = 0;
        Matcher matcher = DURATION.matcher(barLine);
        while (matcher.find()) {
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
            total += quarters;
        }
        return total;
    }

    @Test
    @DisplayName("every lyric bar's durations sum to the meter")
    void everyBarSums() {
        Score score = sung(6, """
                [00:00.00]<00:00.00>When <00:00.50>I <00:01.10>find <00:01.50>my
                [00:02.00]<00:02.00>self <00:03.00>in <00:03.70>times
                [00:04.30]<00:04.30>of <00:05.00>trou <00:05.50>ble
                [00:08.00]<00:08.00>Mo <00:09.30>ther
                """);

        List<String> bars = lyricBars(LyricSheet.toLilyPond(score));

        assertThat(bars).hasSize(6);
        assertThat(bars).allSatisfy(bar ->
                assertThat(quartersIn(bar)).as("%s", bar).isCloseTo(4.0, within(1e-9)));
    }

    @Test
    @DisplayName("a bar holds one check, and there are as many bars as the chart has")
    void barChecksMatchTheChart() {
        Score score = sung(4, "[00:00.00]<00:00.00>one\n[00:04.00]<00:04.00>two\n");
        String lilyPond = LyricSheet.toLilyPond(score);

        List<String> lyric = lyricBars(lilyPond);

        assertThat(lyric).hasSize(4);
        assertThat(lyric).allSatisfy(bar -> assertThat(bar).endsWith("|"));
        assertThat(lyric).allSatisfy(bar -> assertThat(bar.chars().filter(c -> c == '|').count())
                .as("%s", bar).isEqualTo(1));
    }

    @Test
    @DisplayName("a bar with nothing sung in it is skipped, not left empty")
    void silentBarsAreSkipped() {
        Score score = sung(4, "[00:00.00]<00:00.00>one\n");

        List<String> bars = lyricBars(LyricSheet.toLilyPond(score));

        assertThat(bars.get(1)).isEqualTo("\\skip 1 |");
        assertThat(bars.get(3)).isEqualTo("\\skip 1 |");
    }

    @Test
    @DisplayName("every syllable is quoted, so a digit cannot be read as a duration")
    void syllablesAreQuoted() {
        // Unquoted, "1999" is a hard error and "Apollo8" silently becomes the
        // syllable Apollo with a duration of 8.
        Score score = sung(4, "[00:00.00]<00:00.00>1999 <00:01.00>Apollo8 <00:02.00>plain\n");

        String block = String.join(" ", lyricBars(LyricSheet.toLilyPond(score)));

        assertThat(block).contains("\"1999\"").contains("\"Apollo8\"").contains("\"plain\"");
        assertThat(block).doesNotContain(" 1999").doesNotContain(" Apollo8");
    }

    @Test
    @DisplayName("a hyphenated syllable joins the next one")
    void hyphensAreWritten() {
        Score plain = sung(4, "[00:00.00]<00:00.00>Hal <00:01.00>le\n");
        LyricLine line = plain.lyrics().lines().get(0);
        Lyrics joined = new Lyrics(List.of(new LyricLine(List.of(
                line.words().get(0).withHyphenToNext(true), line.words().get(1)),
                Confidence.of(0.9))), "und", Confidence.of(0.9));

        String block = String.join(" ", lyricBars(LyricSheet.toLilyPond(plain.withLyrics(joined))));

        assertThat(block).contains("\"Hal\"2 -- \"le\"");
    }

    @Test
    @DisplayName("a word sung after the last chord is dropped rather than piled on the end")
    void wordsPastTheChartAreDropped() {
        // The chart spans the harmony. A lyric after the last chord has no bar
        // to sit in, and putting it on the final unit would print it on top of
        // whatever is already there and stop the bar summing.
        Score score = sung(2, """
                [00:00.00]<00:00.00>inside
                [00:09.00]<00:09.00>outside
                """);

        String lilyPond = LyricSheet.toLilyPond(score);

        assertThat(lilyPond).contains("\"inside\"").doesNotContain("\"outside\"");
        assertThat(lyricBars(lilyPond)).allSatisfy(bar ->
                assertThat(quartersIn(bar)).as("%s", bar).isCloseTo(4.0, within(1e-9)));
    }

    @Test
    @DisplayName("a word past the chart does not take the words after it with it")
    void oneDroppedWordDoesNotAbandonTheRest() {
        // Built directly rather than from LRC, which clamps every word into its
        // own line and keeps lines from overlapping, so its words come out
        // globally ordered. Words in general are not -- Lyrics.allWords()'s
        // javadoc says recognition spans on sung speech overlap -- so a stray
        // onset says nothing about the next word, and returning there dropped
        // whole verses. The dropped word does not move the lane's cursor
        // either, so these two lines still share one lane -- which is what
        // lyricBars asserts.
        Confidence sure = Confidence.of(0.9);
        Lyrics strays = new Lyrics(List.of(
                new LyricLine(List.of(
                        LyricWord.ofSeconds("early", 0.5, 1.0, sure),
                        LyricWord.ofSeconds("strayed", 20.0, 20.5, sure)), sure),
                new LyricLine(List.of(
                        LyricWord.ofSeconds("still", 2.5, 3.0, sure),
                        LyricWord.ofSeconds("here", 4.5, 5.0, sure)), sure)),
                "und", sure);
        Score score = chart(4).withLyrics(strays);

        String block = String.join(" ", lyricBars(LyricSheet.toLilyPond(score)));

        assertThat(block).contains("\"early\"").contains("\"still\"").contains("\"here\"");
        assertThat(block).doesNotContain("\"strayed\"");
    }

    /** Two lines that overlap: the first ends after the second has begun. */
    private static Score overlapping() {
        Confidence sure = Confidence.of(0.9);
        return chart(2).withLyrics(new Lyrics(List.of(
                new LyricLine(List.of(
                        LyricWord.ofSeconds("open", 0.1, 0.4, sure),
                        LyricWord.ofSeconds("late", 3.9, 3.95, sure)), sure),
                new LyricLine(List.of(
                        LyricWord.ofSeconds("verse", 1.0, 1.4, sure),
                        LyricWord.ofSeconds("carries", 2.0, 2.4, sure),
                        LyricWord.ofSeconds("on", 3.0, 3.4, sure)), sure)),
                "und", sure));
    }

    @Test
    @DisplayName("a line overlapping the one before it takes a lane of its own")
    void overlappingLinesTakeSeparateLanes() {
        // In one lane the second line was pushed past the first line's late
        // word: crammed against the cursor, and its last word off the end of
        // the chart entirely.
        List<List<String>> lanes = lyricLanes(LyricSheet.toLilyPond(overlapping()));

        assertThat(lanes).hasSize(2);
        assertThat(lanes.get(0).get(0)).contains("\"open\"");
        assertThat(lanes.get(0).get(1)).contains("\"late\"");
        // Each word of the overlapping line in the bar it was sung in: verse at
        // one second is in the first bar, and nothing is dropped.
        assertThat(lanes.get(1).get(0)).contains("\"verse\"");
        assertThat(lanes.get(1).get(1)).contains("\"carries\"").contains("\"on\"");
        assertThat(lanes).allSatisfy(lane -> assertThat(lane).allSatisfy(bar ->
                assertThat(quartersIn(bar)).as("%s", bar).isCloseTo(4.0, within(1e-9))));
    }

    @Test
    @DisplayName("every lane spans the chart, so the lanes stay aligned with the chords")
    void everyLaneSpansTheWholeChart() {
        // A lane that stopped where its words did would leave the next system's
        // syllables sitting under the wrong bars.
        List<List<String>> lanes = lyricLanes(LyricSheet.toLilyPond(overlapping()));

        assertThat(lanes).allSatisfy(lane -> assertThat(lane).hasSize(2));
    }

    @Test
    @DisplayName("lines that do not overlap share one lane")
    void separateLinesShareOneLane() {
        // The ordinary lyric, and the reason a lane is opened by the cursor
        // rather than by the line count: a second lane costs vertical space on
        // every system of the chart.
        Score score = sung(4, """
                [00:00.00]<00:00.00>first <00:01.00>line
                [00:04.00]<00:04.00>second <00:05.00>line
                """);

        assertThat(lyricLanes(LyricSheet.toLilyPond(score))).hasSize(1);
    }

    @Test
    @DisplayName("more overlapping lines than lanes fall back to the forward push")
    void overlapsPastTheLastLaneArePushed() {
        // The page carries a fixed number of lanes, so a third line sung over
        // the first two is engraved the way a single lane always engraved it:
        // crammed against the cursor it could not clear. Its words are still on
        // the page and the bars still sum.
        Confidence sure = Confidence.of(0.9);
        Score score = chart(4).withLyrics(new Lyrics(List.of(
                new LyricLine(List.of(
                        LyricWord.ofSeconds("high", 0.1, 0.4, sure),
                        LyricWord.ofSeconds("voice", 5.0, 5.4, sure)), sure),
                new LyricLine(List.of(
                        LyricWord.ofSeconds("mid", 0.2, 0.5, sure),
                        LyricWord.ofSeconds("range", 5.1, 5.5, sure)), sure),
                new LyricLine(List.of(
                        LyricWord.ofSeconds("low", 0.3, 0.6, sure),
                        LyricWord.ofSeconds("part", 5.2, 5.6, sure)), sure)),
                "und", sure));

        List<List<String>> lanes = lyricLanes(LyricSheet.toLilyPond(score));

        assertThat(lanes).hasSize(2);
        String all = lanes.stream().map(lane -> String.join(" ", lane))
                .reduce("", (a, b) -> a + " " + b);
        assertThat(all).contains("\"low\"").contains("\"part\"");
        assertThat(lanes).allSatisfy(lane -> assertThat(lane).allSatisfy(bar ->
                assertThat(quartersIn(bar)).as("%s", bar).isCloseTo(4.0, within(1e-9))));
    }

    @Test
    @DisplayName("a hyphen chain ending a lane is not left dangling in it")
    void noDanglingHyphenInEitherLane() {
        // The hyphen is written only where a syllable follows it, and after
        // the lanes that is a question about the lane rather than about the
        // lyric: a chain closed by a word in the other lane leaves LilyPond an
        // unterminated hyphen, into the output this tool reads.
        Confidence sure = Confidence.of(0.9);
        Score score = chart(2).withLyrics(new Lyrics(List.of(
                new LyricLine(List.of(
                        LyricWord.ofSeconds("Hal", 0.1, 0.4, sure).withHyphenToNext(true),
                        LyricWord.ofSeconds("le", 3.9, 3.95, sure).withHyphenToNext(true)), sure),
                new LyricLine(List.of(
                        LyricWord.ofSeconds("lu", 1.0, 1.4, sure).withHyphenToNext(true),
                        LyricWord.ofSeconds("jah", 2.0, 2.4, sure).withHyphenToNext(true)), sure)),
                "und", sure));

        List<List<String>> lanes = lyricLanes(LyricSheet.toLilyPond(score));

        assertThat(lanes).hasSize(2);
        assertThat(lanes).allSatisfy(lane -> {
            String block = String.join(" ", lane);
            int last = block.lastIndexOf('"');
            assertThat(block.substring(last)).as("%s", block).doesNotContain("--");
        });
    }

    @Test
    @DisplayName("a hyphen is written only where there is a syllable to join")
    void noDanglingHyphen() {
        // LilyPond reports an unterminated hyphen, into the output this tool
        // reads -- and a chain running off the end of the lyric is the ordinary
        // way to produce one.
        Score plain = sung(4, "[00:00.00]<00:00.00>Hal <00:01.00>le\n");
        LyricLine line = plain.lyrics().lines().get(0);
        Lyrics trailing = new Lyrics(List.of(new LyricLine(List.of(
                line.words().get(0).withHyphenToNext(true),
                line.words().get(1).withHyphenToNext(true)),
                Confidence.of(0.9))), "und", Confidence.of(0.9));

        String block = String.join(" ", lyricBars(LyricSheet.toLilyPond(plain.withLyrics(trailing))));

        // The property, not a substring: a hyphen crossing a bar line is legal
        // and this emitter writes one, so "-- |" is not the thing to forbid.
        // What LilyPond rejects is a hyphen with no syllable after it anywhere.
        assertThat(block).contains("\"Hal\"2 -- \"le\"");
        int lastSyllable = block.lastIndexOf('"');
        assertThat(block.substring(lastSyllable)).doesNotContain("--");
    }

    @Test
    @DisplayName("a one-bar chart measures its bar rather than assuming a length for it")
    void oneBarChartUsesItsOwnRate() {
        // Nothing to borrow a rate from, and a hard-coded one placed every word
        // as though the bar lasted a second.
        Score score = sung(1, "[00:00.00]<00:00.00>a <00:00.50>b <00:01.00>c <00:01.50>d\n");

        List<String> bars = lyricBars(LyricSheet.toLilyPond(score));

        assertThat(bars).hasSize(1);
        assertThat(bars.get(0)).isEqualTo("\"a\"4 \"b\"4 \"c\"4 \"d\"4 |");
    }

    @Test
    @DisplayName("a bar is read at its own tempo, not at its neighbour's")
    void aTempoChangeIsReadPerBar() {
        // Chords stated on the beat axis, because that is the only route whose
        // clock follows a tempo change: the seconds route divides by one
        // quarter length and would build four equal bars whatever the map says.
        // Three bars at 120 then one at 40, so the last bar is three times as
        // long in seconds. Read at a neighbour's rate it looks a third as long,
        // and everything sung late in it falls off the end of the chart.
        List<Double> beats = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            beats.add(i * 0.5);
        }
        for (int i = 0; i < 5; i++) {
            beats.add(6.0 + i * 1.5);
        }
        TempoMap map = TempoMap.fromBeatTimes(beats, TimeSignature.FOUR_FOUR);
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chords.add(new Chord(root(NoteLetter.C), ChordQuality.MAJOR, Optional.empty(),
                    map.beatsToSeconds(i * 4.0), map.beatsToSeconds((i + 1) * 4.0),
                    Optional.of(i * 4.0), Optional.of((i + 1) * 4.0), Confidence.of(0.9)));
        }
        Score score = Score.empty(map, 12.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
        score = score.withLyrics(LrcLyrics.parse(
                "[00:06.50]<00:06.50>slow <00:09.00>er <00:11.00>still\n", 12.0));

        List<String> bars = lyricBars(LyricSheet.toLilyPond(score));

        // The final bar runs 6s to 12s. At the previous bar's rate it would
        // appear to end at 8s, and the last two words would be dropped.
        String last = bars.get(bars.size() - 1);
        assertThat(last).contains("\"slow\"").contains("\"er\"").contains("\"still\"");
    }

    @Test
    @DisplayName("a syllable lands in the bar the word is sung in")
    void syllablesLandInTheirBar() {
        // Second bar starts at two seconds; the word is sung a beat into it.
        Score score = sung(4, "[00:00.00]<00:02.50>late\n");

        List<String> bars = lyricBars(LyricSheet.toLilyPond(score));

        assertThat(bars.get(0)).isEqualTo("\\skip 1 |");
        assertThat(bars.get(1)).startsWith("\\skip 4 \"late\"");
    }

    @Test
    @DisplayName("a word is split into the syllables it is sung on")
    void wordsAreSplitIntoSyllables() {
        Score score = sungIn("it", 4, "[00:00.00]<00:00.00>amore <00:02.00>canzone\n");

        String block = String.join(" ", lyricBars(LyricSheet.toLilyPond(score)));

        assertThat(block).contains("\"a\"").contains("\"mo\"").contains("\"re\"");
        assertThat(block).contains("\"can\"").contains("\"zo\"").contains("\"ne\"");
        assertThat(block).doesNotContain("\"amore\"");
    }

    @Test
    @DisplayName("syllables of one word are joined by hyphens, and the last is not")
    void syllablesAreHyphenated() {
        Score score = sungIn("it", 4, "[00:00.00]<00:00.00>amore <00:02.00>sole\n");

        String block = String.join(" ", lyricBars(LyricSheet.toLilyPond(score)));

        assertThat(block).contains("-- \"mo\"").contains("-- \"re\"");
        // "re" ends its word, so nothing hyphenates it to "so".
        int re = block.indexOf("\"re\"");
        int so = block.indexOf("\"so\"");
        assertThat(block.substring(re, so)).doesNotContain("--");
    }

    @Test
    @DisplayName("without a language the words stay whole")
    void noLanguageNoSplit() {
        // An LRC file does not state one, and splitting on the wrong language's
        // rules is worse than not splitting.
        Score score = sung(4, "[00:00.00]<00:00.00>amore <00:02.00>canzone\n");

        String block = String.join(" ", lyricBars(LyricSheet.toLilyPond(score)));

        assertThat(block).contains("\"amore\"").contains("\"canzone\"");
        assertThat(block).doesNotContain("--");
    }

    @Test
    @DisplayName("splitting a word does not move it out of its bar or unbalance one")
    void splittingKeepsTheBarsSumming() {
        Score score = sungIn("it", 4, """
                [00:00.00]<00:00.20>respiravamo <00:01.60>piano
                [00:02.00]<00:02.30>particolare <00:03.40>sole
                """);

        List<String> bars = lyricBars(LyricSheet.toLilyPond(score));

        assertThat(bars.get(0)).contains("\"re\"").contains("\"pia\"");
        assertThat(bars.get(1)).contains("\"par\"").contains("\"so\"");
        assertThat(bars).allSatisfy(bar ->
                assertThat(quartersIn(bar)).as("%s", bar).isCloseTo(4.0, within(1e-9)));
    }

    @Test
    @DisplayName("a word near the chart's end is placed whole or not at all, never in pieces")
    void aWordIsNeverTruncated() {
        // particolare is five syllables and the chart has room for three of
        // them.
        Score score = sungIn("it", 2, "[00:00.00]<00:03.90>particolare <00:03.95>amore\n");

        String block = String.join(" ", lyricBars(LyricSheet.toLilyPond(score)));

        assertThat(block).doesNotContain("\"par\"").doesNotContain("\"ti\"");
        // The whole word is tried when its syllables will not fit.
        assertThat(block).contains("\"particolare\"");
    }

    @Test
    @DisplayName("a compound's own hyphen is not drawn a second time")
    void aCarriedHyphenIsNotDrawnAgain() {
        // The token already carries the hyphen it was written with, so marking
        // the boundary as well printed "well--known" on the page.
        Score score = sungIn("en", 4, "[00:00.00]<00:00.00>well-known <00:02.00>trouble\n");

        String block = String.join(" ", lyricBars(LyricSheet.toLilyPond(score)));

        assertThat(block).contains("\"well-\"").contains("\"known\"");
        assertThat(block).doesNotContain("-- \"known\"");
        // A break the hyphenator did choose is still drawn. Asserted on the
        // syllable that carries it, since a bar line can fall between the two.
        assertThat(block).contains("\"trou\"1 --");
    }

    @Test
    @DisplayName("the lyric context points down, like the two above it")
    void staffAffinityPointsDown() {
        // ChordNames is DOWN and the repeat lane is DOWN. A Lyrics context on
        // its own default of UP makes the sequence increase, which LilyPond
        // complains about on every run -- into output this tool parses.
        Score score = sung(2, "[00:00.00]<00:00.00>word\n");

        assertThat(LyricSheet.toLilyPond(score))
                .contains("\\override VerticalAxisGroup.staff-affinity = #DOWN");
    }

    @Test
    @DisplayName("the chord chart carries no lyrics, whatever the score holds")
    void theChartStaysAChart() {
        Score score = sung(4, "[00:00.00]<00:00.00>one <00:02.00>two\n");

        assertThat(ChordChart.toLilyPond(score))
                .doesNotContain("\\lyricmode")
                .doesNotContain("\"one\"");
        assertThat(LyricSheet.toLilyPond(score)).contains("\\lyricmode");
    }

    @Test
    @DisplayName("a score with no lyrics engraves the chart unchanged")
    void noLyricsNoBlock() {
        Score score = chart(4);

        assertThat(LyricSheet.toLilyPond(score))
                .doesNotContain("\\lyricmode")
                .isEqualTo(ChordChart.toLilyPond(score));
    }

    @Test
    @DisplayName("two words on one moment still get a duration each")
    void wordsSharingAMomentAreSeparated() {
        // A zero-length syllable is one LilyPond cannot name, so the second is
        // nudged onto the next grid step.
        Score score = sung(2, "[00:00.00]<00:01.00>same <00:01.00>moment\n");

        List<String> bars = lyricBars(LyricSheet.toLilyPond(score));

        assertThat(String.join(" ", bars)).contains("\"same\"").contains("\"moment\"");
        assertThat(bars).allSatisfy(bar ->
                assertThat(quartersIn(bar)).as("%s", bar).isCloseTo(4.0, within(1e-9)));
    }

    @Test
    @DisplayName("a quotation mark in a lyric does not end its syllable")
    void quotesAreEscaped() {
        Score plain = sung(2, "[00:00.00]<00:00.00>x\n");
        LyricWord word = plain.lyrics().lines().get(0).words().get(0);
        Lyrics awkward = new Lyrics(List.of(new LyricLine(
                List.of(word.withText("do\"n't")), Confidence.of(0.9))),
                "und", Confidence.of(0.9));

        assertThat(LyricSheet.toLilyPond(plain.withLyrics(awkward)))
                .contains("\"do\\\"n't\"");
    }
}
