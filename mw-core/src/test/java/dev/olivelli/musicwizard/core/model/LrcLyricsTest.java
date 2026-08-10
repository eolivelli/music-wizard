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

package dev.olivelli.musicwizard.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LrcLyricsTest {

    private static final double RECORDING = 200.0;

    @Nested
    @DisplayName("plain LRC")
    class Plain {

        @Test
        @DisplayName("a line's words fill the span up to the next line")
        void wordsFillTheLine() {
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:10.00]one two
                    [00:20.00]three
                    """, RECORDING);

            assertThat(lyrics.lines()).hasSize(2);
            List<LyricWord> first = lyrics.lines().get(0).words();
            assertThat(first).extracting(LyricWord::text).containsExactly("one", "two");
            assertThat(first.get(0).startSeconds()).isEqualTo(10.0);
            // The last word of the line ends exactly where the next line starts,
            // however the syllable weights happened to divide.
            assertThat(first.get(1).endSeconds()).isEqualTo(20.0);
            assertThat(first.get(0).endSeconds()).isEqualTo(first.get(1).startSeconds());
        }

        @Test
        @DisplayName("time is shared out by syllable count, not by word count")
        void syllablesDecideTheShare() {
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]a banana
                    [00:04.00]end
                    """, RECORDING);

            List<LyricWord> words = lyrics.lines().get(0).words();
            assertThat(words).extracting(LyricWord::text).containsExactly("a", "banana");
            // One syllable against three, so "a" takes a quarter of the line
            // rather than the half a per-word share would give it.
            assertThat(words.get(0).endSeconds()).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("the last line lasts as long as the lines before it, not to the end")
        void lastLineTakesTheTypicalLength() {
            // Five-second lines and a recording running long past them. Giving
            // the closing line everything left would hand it the whole outro,
            // and with it every chord change in the outro.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]one
                    [00:05.00]two
                    [00:10.00]three
                    """, 300.0);

            assertThat(lyrics.lines().get(2).endSeconds()).isEqualTo(15.0);
        }

        @Test
        @DisplayName("the last line never runs past the recording")
        void lastLineIsBoundedByTheRecording() {
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]one
                    [00:05.00]two
                    [00:10.00]three
                    """, 12.0);

            assertThat(lyrics.lines().get(2).endSeconds()).isEqualTo(12.0);
        }

        @Test
        @DisplayName("a lone line gets a nominal length, having nothing to learn from")
        void singleLineFallsBack() {
            Lyrics lyrics = LrcLyrics.parse("[01:00.00]alone\n", 300.0);

            assertThat(lyrics.lines().get(0).endSeconds())
                    .isGreaterThan(60.0)
                    .isLessThan(70.0);
        }

        @Test
        @DisplayName("a recording length that cannot be right is ignored")
        void nonsenseDurationFallsBack() {
            // Before the line starts: honouring it would build a line that ends
            // before it begins, which LyricLine would reject.
            Lyrics lyrics = LrcLyrics.parse("[01:00.00]last words\n", 5.0);

            assertThat(lyrics.lines().get(0).endSeconds()).isGreaterThan(60.0);
        }
    }

    @Nested
    @DisplayName("enhanced LRC")
    class Enhanced {

        @Test
        @DisplayName("word tags time their own words")
        void wordTagsAreHonoured() {
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:12.34]<00:12.34>But <00:12.86>when <00:13.04>you
                    [00:14.10]next
                    """, RECORDING);

            List<LyricWord> words = lyrics.lines().get(0).words();
            assertThat(words).extracting(LyricWord::text).containsExactly("But", "when", "you");
            assertThat(words).extracting(LyricWord::startSeconds)
                    .containsExactly(12.34, 12.86, 13.04);
            assertThat(words.get(0).endSeconds()).isEqualTo(12.86);
            assertThat(words.get(2).endSeconds()).isEqualTo(14.10);
        }

        @Test
        @DisplayName("a tagged word is trusted more than a spread one")
        void taggedWordsCarryMoreConfidence() {
            Lyrics tagged = LrcLyrics.parse("[00:00.00]<00:00.00>one\n[00:02.00]x\n", RECORDING);
            Lyrics spread = LrcLyrics.parse("[00:00.00]one two\n[00:02.00]x\n", RECORDING);

            assertThat(tagged.lines().get(0).words().get(0).confidence())
                    .isGreaterThan(spread.lines().get(0).words().get(0).confidence());
        }

        @Test
        @DisplayName("text before the first tag keeps the line's own start")
        void untaggedHeadKeepsLineStart() {
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:10.00]Oh <00:11.00>yes
                    [00:12.00]next
                    """, RECORDING);

            List<LyricWord> words = lyrics.lines().get(0).words();
            assertThat(words).extracting(LyricWord::text).containsExactly("Oh", "yes");
            assertThat(words.get(0).startSeconds()).isEqualTo(10.0);
            assertThat(words.get(0).endSeconds()).isEqualTo(11.0);
            assertThat(words.get(1).startSeconds()).isEqualTo(11.0);
        }
    }

    @Nested
    @DisplayName("the format's awkward corners")
    class Corners {

        @Test
        @DisplayName("one line repeated at several timestamps appears at each")
        void repeatedTimestamps() {
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:29.24][01:12.80]just like an angel
                    [02:00.00]end
                    """, RECORDING);

            assertThat(lyrics.lines()).hasSize(3);
            assertThat(lyrics.lines().get(0).startSeconds()).isEqualTo(29.24);
            assertThat(lyrics.lines().get(1).startSeconds()).isEqualTo(72.80);
            assertThat(lyrics.lines().get(0).text()).isEqualTo("just like an angel");
            assertThat(lyrics.lines().get(1).text()).isEqualTo("just like an angel");
        }

        @Test
        @DisplayName("a bare timestamp ends the line before it and is not a line")
        void bareTimestampClearsTheDisplay() {
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:10.00]sung
                    [00:12.00]
                    [00:30.00]again
                    """, RECORDING);

            assertThat(lyrics.lines()).hasSize(2);
            // The gap is what the bare tag is for: the line stops at 12, not at 30.
            assertThat(lyrics.lines().get(0).endSeconds()).isEqualTo(12.0);
            assertThat(lyrics.lines().get(1).startSeconds()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("the fraction is read as a decimal, at one, two or three digits")
        void fractionIsADecimal() {
            assertThat(LrcLyrics.parse("[00:01.5]x\n[00:09.00]y\n", RECORDING)
                    .lines().get(0).startSeconds()).isEqualTo(1.5);
            assertThat(LrcLyrics.parse("[00:01.50]x\n[00:09.00]y\n", RECORDING)
                    .lines().get(0).startSeconds()).isEqualTo(1.5);
            assertThat(LrcLyrics.parse("[00:01.500]x\n[00:09.00]y\n", RECORDING)
                    .lines().get(0).startSeconds()).isEqualTo(1.5);
            assertThat(LrcLyrics.parse("[00:01.05]x\n[00:09.00]y\n", RECORDING)
                    .lines().get(0).startSeconds()).isEqualTo(1.05);
        }

        @Test
        @DisplayName("an offset that is not a finite number is ignored, not propagated")
        void nonFiniteOffsetIsIgnored() {
            // Double.parseDouble accepts these, and a non-finite shift reaches
            // LyricWord's constructor, which rejects it -- out of a public
            // parser, past the caller's read guard, after the analysis it was
            // decorating had already succeeded.
            for (String bad : new String[] {"NaN", "-Infinity", "Infinity", "-1e999", "1e999"}) {
                Lyrics lyrics = LrcLyrics.parse(
                        "[offset:" + bad + "]\n[00:10.00]sung\n[00:20.00]again\n", RECORDING);

                assertThat(lyrics.lines()).as(bad).hasSize(2);
                assertThat(lyrics.lines().get(0).startSeconds()).as(bad).isEqualTo(10.0);
            }
        }

        @Test
        @DisplayName("a byte order mark does not eat the first line")
        void byteOrderMarkIsStripped() {
            // What Windows editors write by default, and LRC is largely authored
            // on them. Left in place it defeats the tag match on line one -- and
            // if that line is the offset tag, the whole lyric silently moves.
            Lyrics lyrics = LrcLyrics.parse(
                    "\uFEFF[offset:+2000]\n[00:10.00]first\n[00:20.00]second\n", RECORDING);

            assertThat(lyrics.lines()).hasSize(2);
            assertThat(lyrics.lines().get(0).startSeconds()).isEqualTo(8.0);
            assertThat(lyrics.lines().get(0).text()).isEqualTo("first");
        }

        @Test
        @DisplayName("repeated line tags may be separated by spaces")
        void spacedRepeatTagsBothCount() {
            Lyrics lyrics = LrcLyrics.parse(
                    "[00:10.00] [00:20.00]chorus\n[00:30.00]verse\n", RECORDING);

            assertThat(lyrics.lines()).hasSize(3);
            // Not "[00:20.00]chorus": a tag left inside the body prints as a word.
            assertThat(lyrics.lines().get(0).text()).isEqualTo("chorus");
            assertThat(lyrics.lines().get(1).startSeconds()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("an instrumental gap is not part of the line before it")
        void aLongGapIsNotPartOfTheLine() {
            // Four-second lines, then a minute of solo before the next. Reading
            // the line as lasting until its successor makes the whole solo part
            // of it, and everything asking which chords fall inside the line
            // then answers with the solo's.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]one
                    [00:04.00]two
                    [00:08.00]three
                    [01:08.00]after the solo
                    """, 200.0);

            assertThat(lyrics.lines().get(2).endSeconds()).isEqualTo(12.0);
            // An ordinary line is still bounded by its successor.
            assertThat(lyrics.lines().get(0).endSeconds()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("a positive offset makes the lyrics arrive sooner")
        void offsetShiftsEverything() {
            Lyrics lyrics = LrcLyrics.parse("""
                    [offset:+500]
                    [00:10.00]sung
                    [00:20.00]again
                    """, RECORDING);

            assertThat(lyrics.lines().get(0).startSeconds()).isEqualTo(9.5);
            assertThat(lyrics.lines().get(1).startSeconds()).isEqualTo(19.5);
        }

        @Test
        @DisplayName("an offset never pushes a line before the recording starts")
        void offsetIsClampedAtZero() {
            Lyrics lyrics = LrcLyrics.parse("[offset:+5000]\n[00:01.00]early\n", RECORDING);

            assertThat(lyrics.lines().get(0).startSeconds()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("metadata tags are not lyrics")
        void idTagsAreSkipped() {
            Lyrics lyrics = LrcLyrics.parse("""
                    [ti:Creep]
                    [ar:Radiohead]
                    [al:Pablo Honey]
                    [00:19.16]When you were here before
                    """, RECORDING);

            assertThat(lyrics.lines()).hasSize(1);
            assertThat(lyrics.lines().get(0).text()).isEqualTo("When you were here before");
        }

        @Test
        @DisplayName("minutes past an hour keep counting rather than wrapping")
        void longRecordingsKeepCounting() {
            Lyrics lyrics = LrcLyrics.parse("[75:30.00]late\n", 5000.0);

            assertThat(lyrics.lines().get(0).startSeconds()).isEqualTo(75 * 60 + 30.0);
        }
    }

    @Nested
    @DisplayName("files with nothing in them")
    class Empty {

        @Test
        @DisplayName("a file with no timestamps yields no lyrics")
        void untimedFile() {
            assertThat(LrcLyrics.parse("just some words\nand more\n", RECORDING).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a file with only metadata yields no lyrics")
        void metadataOnly() {
            assertThat(LrcLyrics.parse("[ti:Nothing]\n[ar:Nobody]\n", RECORDING).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("looksLikeLrc separates a wrong file from an empty one")
        void looksLikeLrcTellsThemApart() {
            assertThat(LrcLyrics.looksLikeLrc("[00:01.00]something")).isTrue();
            assertThat(LrcLyrics.looksLikeLrc("[ti:only metadata]")).isFalse();
            assertThat(LrcLyrics.looksLikeLrc("not a lyric file at all")).isFalse();
        }
    }

    @Test
    @DisplayName("nothing arrives on the beat axis: that is the aligner's job")
    void parsingDoesNotQuantize() {
        Lyrics lyrics = LrcLyrics.parse("[00:10.00]one two\n[00:20.00]three\n", RECORDING);

        assertThat(lyrics.isQuantized()).isFalse();
        assertThat(lyrics.allWords()).allSatisfy(word ->
                assertThat(word.isQuantized()).isFalse());
    }
}
