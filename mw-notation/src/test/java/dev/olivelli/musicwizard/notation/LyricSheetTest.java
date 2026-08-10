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

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LrcLyrics;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LyricSheetTest {

    private static PitchSpelling root(NoteLetter letter) {
        return new PitchSpelling(letter, Accidental.NATURAL, 4);
    }

    /**
     * A score at 120 BPM whose chords run back to back: each ends where the next
     * begins, which is what {@link ChordProgression} requires of them.
     */
    private static Score song(double durationSeconds, Object... rootsAndTimes) {
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < rootsAndTimes.length; i += 2) {
            NoteLetter letter = (NoteLetter) rootsAndTimes[i];
            double at = ((Number) rootsAndTimes[i + 1]).doubleValue();
            double until = i + 2 < rootsAndTimes.length
                    ? ((Number) rootsAndTimes[i + 3]).doubleValue() : at + 2.0;
            chords.add(Chord.ofSeconds(root(letter), ChordQuality.MAJOR,
                    at, until, Confidence.of(0.9)));
        }
        Score score = Score.empty(
                TempoMap.constant(120, TimeSignature.FOUR_FOUR), durationSeconds);
        return chords.isEmpty()
                ? score
                : score.withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    private static Lyrics lrc(String text, double durationSeconds) {
        return LrcLyrics.parse(text, durationSeconds);
    }

    /** The rows after the header, which the header test pins separately. */
    private static List<String> body(String sheet) {
        List<String> all = List.of(sheet.split("\n", -1));
        int blank = all.indexOf("");
        return all.subList(blank + 1, all.size());
    }

    @Test
    @DisplayName("a chord stands over the word it arrives on")
    void chordSitsOverItsWord() {
        Score score = song(20, NoteLetter.C, 0.0, NoteLetter.G, 4.0)
                .withLyrics(lrc("[00:00.00]<00:00.00>one <00:04.00>two\n", 8.0));

        List<String> body = body(LyricSheet.toText(score));

        assertThat(body.get(0)).isEqualTo("C   G");
        assertThat(body.get(1)).isEqualTo("one two");
        // The G is at column 4, which is where "two" begins.
        assertThat(body.get(1).indexOf("two")).isEqualTo(body.get(0).indexOf("G"));
    }

    @Test
    @DisplayName("a chord arriving before the first word sits at the left margin")
    void chordBeforeTheFirstWord() {
        Score score = song(20, NoteLetter.C, 5.0)
                .withLyrics(lrc("[00:05.00]<00:05.00>sung\n", 8.0));

        assertThat(body(LyricSheet.toText(score)).get(0)).isEqualTo("C");
    }

    @Test
    @DisplayName("chords before the singing get a row of their own")
    void introChordsStandAlone() {
        Score score = song(30, NoteLetter.C, 0.0, NoteLetter.G, 2.0, NoteLetter.F, 10.0)
                .withLyrics(lrc("[00:10.00]<00:10.00>late\n", 14.0));

        List<String> body = body(LyricSheet.toText(score));

        assertThat(body.get(0)).isEqualTo("C G");
        assertThat(body.get(1)).isEqualTo("F");
        assertThat(body.get(2)).isEqualTo("late");
    }

    @Test
    @DisplayName("a chord in the gap between lines is a break, not a syllable's chord")
    void gapChordsBecomeABreak() {
        Score score = song(40,
                NoteLetter.C, 0.0, NoteLetter.G, 10.0, NoteLetter.F, 20.0)
                .withLyrics(lrc("""
                        [00:00.00]<00:00.00>first
                        [00:05.00]
                        [00:20.00]<00:20.00>second
                        """, 25.0));

        List<String> body = body(LyricSheet.toText(score));

        assertThat(body.get(0)).isEqualTo("C");
        assertThat(body.get(1)).isEqualTo("first");
        // G falls at 10s, after the first line ends at 5s and before the second
        // begins at 20s, so it is an instrumental break rather than a chord over
        // a word it does not accompany.
        assertThat(body.get(2)).isEqualTo("G");
        assertThat(body.get(3)).isEmpty();
        assertThat(body.get(4)).isEqualTo("F");
        assertThat(body.get(5)).isEqualTo("second");
    }

    @Test
    @DisplayName("a run of one chord prints once, where it changes")
    void repeatedChordsCollapse() {
        Score score = song(40,
                NoteLetter.C, 0.0, NoteLetter.C, 2.0, NoteLetter.C, 4.0, NoteLetter.G, 6.0)
                .withLyrics(lrc(
                        "[00:00.00]<00:00.00>aa <00:02.00>bb <00:04.00>cc <00:06.00>dd\n", 8.0));

        List<String> body = body(LyricSheet.toText(score));

        assertThat(body.get(1)).isEqualTo("aa bb cc dd");
        // Three C spans, one printed C. Without this a real recording's hundreds
        // of spans would fill the row.
        assertThat(body.get(0)).isEqualTo("C        G");
        assertThat(body.get(0).indexOf("G")).isEqualTo(body.get(1).indexOf("dd"));
    }

    @Test
    @DisplayName("two changes on one word are pushed apart rather than overlapped")
    void collidingChordsArePushedRight() {
        Score score = song(20,
                NoteLetter.C, 0.0, NoteLetter.G, 0.1, NoteLetter.F, 0.2)
                .withLyrics(lrc("[00:00.00]<00:00.00>word\n", 4.0));

        List<String> body = body(LyricSheet.toText(score));

        assertThat(body.get(0)).isEqualTo("C G F");
        assertThat(body.get(1)).isEqualTo("word");
    }

    @Test
    @DisplayName("a hyphenated syllable joins the next without a space, as it prints")
    void hyphenatedSyllablesShareAWord() {
        Score score = song(20, NoteLetter.C, 0.0, NoteLetter.G, 1.0)
                .withLyrics(lrc("[00:00.00]<00:00.00>Hal <00:01.00>le\n", 4.0));
        Lyrics joined = new Lyrics(List.of(
                new dev.olivelli.musicwizard.core.model.LyricLine(List.of(
                        score.lyrics().lines().get(0).words().get(0).withHyphenToNext(true),
                        score.lyrics().lines().get(0).words().get(1)),
                        Confidence.of(0.9))),
                "und", Confidence.of(0.9));

        List<String> body = body(LyricSheet.toText(score.withLyrics(joined)));

        assertThat(body.get(1)).isEqualTo("Halle");
        assertThat(body.get(0).indexOf("G")).isEqualTo(3);
    }

    @Test
    @DisplayName("lyrics with no chords still make a sheet")
    void lyricsWithoutChords() {
        Score score = song(20).withLyrics(lrc("[00:00.00]just words\n", 4.0));

        List<String> body = body(LyricSheet.toText(score));

        assertThat(body.get(0)).isEqualTo("just words");
    }

    @Test
    @DisplayName("a score with no lyrics says so rather than writing an empty file")
    void noLyricsAtAll() {
        assertThat(LyricSheet.toText(song(20, NoteLetter.C, 0.0)))
                .isEqualTo("(no lyrics were found)\n");
    }

    @Test
    @DisplayName("the header is the one the chord chart prints")
    void headerMatchesTheChordChart() {
        Score score = song(20, NoteLetter.C, 0.0, NoteLetter.G, 4.0)
                .withLyrics(lrc("[00:00.00]<00:00.00>one <00:04.00>two\n", 8.0));

        String sheet = LyricSheet.toText(score);
        String chart = ChordChart.toText(score);

        // Both open with the same rows, so the two files cannot disagree about
        // the tempo or the meter they are counted in.
        String head = chart.substring(0, chart.indexOf("\n|"));
        assertThat(sheet).startsWith(head.stripTrailing());
        assertThat(sheet).contains("Tempo  120 BPM").contains("Meter  4/4");
    }

    @Test
    @DisplayName("a chord over a long line keeps its column rather than wrapping")
    void aWideLineKeepsItsAlignment() {
        // A lyric line wider than the page is ordinary, and the line itself is
        // not wrapped. Wrapping the chord row would move a symbol standing over
        // a word near the end onto a fresh row, where it reads as the chord on
        // the line's first word -- an over-wide row is the lesser failure.
        String words = "Iiiiiiiiiiiiiiiiiiiiiiii haaaaaaaaaaaaaaaaaaaaaaave "
                + "beeeeeeeeeeeeeeeeeeeeeeen reeeeeeeeeeeeeeeeeeeading";
        Score score = song(30, NoteLetter.C, 0.0, NoteLetter.F, 3.0)
                .withLyrics(lrc("[00:00.00]<00:00.00>" + words.split(" ")[0]
                        + " <00:01.00>" + words.split(" ")[1]
                        + " <00:02.00>" + words.split(" ")[2]
                        + " <00:03.00>" + words.split(" ")[3] + "\n", 8.0));

        List<String> body = body(LyricSheet.toText(score));

        assertThat(body.get(1)).isEqualTo(words);
        assertThat(body.get(0).indexOf("F")).isEqualTo(words.lastIndexOf(' ') + 1);
    }

    @Test
    @DisplayName("a chord row over words is never wrapped, at any line width")
    void aRowOverWordsNeverWraps() {
        // Wrapping puts a symbol at column zero, which is the line's first word.
        // Whatever decides when to wrap, the symbol it moves lands over a word it
        // did not accompany -- so on a row that sits over words, nothing does.
        String words = "Somebody once told me the world was gonna roll me now";
        List<Object> spec = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            spec.add(i % 2 == 0 ? NoteLetter.C : NoteLetter.G);
            spec.add(8.0 + i * 0.2);
        }
        StringBuilder lrc = new StringBuilder("[00:00.00]");
        String[] tokens = words.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            lrc.append(String.format("<00:%02d.00>%s ", i, tokens[i]));
        }
        Score score = song(60, spec.toArray())
                .withLyrics(lrc(lrc.append("\n").toString(), 40.0));

        List<String> body = body(LyricSheet.toText(score));

        assertThat(body.get(1)).isEqualTo(words);
        // One row, and nothing at column zero: the first chord arrives at 8s,
        // long after "Somebody" is sung.
        assertThat(body.get(0).charAt(0)).isEqualTo(' ');
        assertThat(body.get(2)).isNotEqualTo(words);
    }

    @Test
    @DisplayName("a row with no words beneath it still wraps, having no column to collide with")
    void aBreakRowStillWraps() {
        List<Object> spec = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            spec.add(i % 2 == 0 ? NoteLetter.C : NoteLetter.G);
            spec.add(20.0 + i * 0.5);
        }
        Score score = song(120, spec.toArray())
                .withLyrics(lrc("[00:00.00]<00:00.00>early\n", 100.0));

        List<String> rows = body(LyricSheet.toText(score)).stream()
                .filter(line -> !line.isBlank() && !line.contains("early")).toList();

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(line ->
                assertThat(line.length()).isLessThanOrEqualTo(75));
    }

    @Test
    @DisplayName("nothing in the sheet can be read as a chord-chart bar line")
    void noLineLooksLikeABarLine() {
        Score score = song(20, NoteLetter.C, 0.0, NoteLetter.G, 4.0)
                .withLyrics(lrc("[00:00.00]<00:00.00>one <00:04.00>two\n", 8.0));

        // tools/score-chart.py reads every line starting with "|" out of
        // chords.txt as a bar. This is a different file, but the two are written
        // side by side and a stray bar line here would be a trap laid for the
        // next person to point the harness at it.
        assertThat(LyricSheet.toText(score).lines())
                .noneMatch(line -> line.startsWith("|"));
    }
}
