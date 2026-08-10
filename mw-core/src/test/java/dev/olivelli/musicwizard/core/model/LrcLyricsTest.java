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
        @DisplayName("a break is found on a short file, and two breaks do not hide each other")
        void breaksAreFoundAtEveryFileLength() {
            // A threshold read off the spread of the gaps is pushed up by the
            // very gaps it exists to find, and its quantile indices degenerate on
            // short files -- so a five-line file with one solo, and any file with
            // two, stopped finding them at all.
            Lyrics one = LrcLyrics.parse("""
                    [00:00.00]a
                    [00:04.00]b
                    [00:08.00]c
                    [01:08.00]d
                    [01:12.00]e
                    """, 300.0);
            assertThat(one.lines().get(2).endSeconds()).isEqualTo(12.0);

            Lyrics two = LrcLyrics.parse("""
                    [00:00.00]a
                    [00:04.00]b
                    [01:04.00]c
                    [01:08.00]d
                    [02:08.00]e
                    [02:12.00]f
                    """, 300.0);
            assertThat(two.lines().get(1).endSeconds()).isEqualTo(8.0);
            assertThat(two.lines().get(3).endSeconds()).isEqualTo(72.0);
        }

        @Test
        @DisplayName("no line outlives its successor, whatever a word tag says")
        void linesNeverOverlap() {
            // A mistyped minute in an A2 tag put one line's end past several
            // later ones, and the sheet's cursor then handed it every chord they
            // should have had.
            // The gap after "solo carrier" is the break, so that line is the one
            // taking the branch that reads its own word tags rather than simply
            // ending at its successor -- which is where the bound that stops it
            // outliving the lines after it has to hold.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]one
                    [00:04.00]two
                    [00:08.00]three
                    [00:12.00]<01:39.00>solo carrier
                    [01:40.00]after
                    [01:44.00]later
                    """, 300.0);

            for (int i = 0; i + 1 < lyrics.lines().size(); i++) {
                assertThat(lyrics.lines().get(i).endSeconds())
                        .as("line %d must not outlive line %d", i, i + 1)
                        .isLessThanOrEqualTo(lyrics.lines().get(i + 1).startSeconds());
            }
        }

        @Test
        @DisplayName("no line that starts inside the recording runs past its end")
        void everyLineIsBoundedByTheRecording() {
            // A file timed against a longer edit of the song. A line beginning
            // after the recording has ended is left alone -- there is no end to
            // clamp it to that would not fall before its own start.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]a
                    [00:04.00]b
                    [00:08.00]c
                    [00:12.00]d
                    """, 6.0);

            assertThat(lyrics.lines()).filteredOn(line -> line.startSeconds() < 6.0)
                    .allSatisfy(line ->
                            assertThat(line.endSeconds()).isLessThanOrEqualTo(6.0));
        }

        @Test
        @DisplayName("several lines on one moment do not make every gap a break (#324)")
        void duplicateTimestampsDoNotTruncateEveryLine() {
            // Two source lines on the same instant -- a second voice, a
            // two-line display -- so most of this file's gaps are zero.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]a1
                    [00:00.00]a2
                    [00:20.00]b1
                    [00:20.00]b2
                    [00:40.00]c1
                    [00:40.00]c2
                    [01:00.00]d1
                    """, 300.0);

            assertThat(lyrics.lines()).hasSize(7);
            // a2 runs to b1, not to a nominal four seconds.
            assertThat(lyrics.lines().get(1).endSeconds()).isEqualTo(20.0);
            assertThat(lyrics.lines().get(3).endSeconds()).isEqualTo(40.0);
            // And the closing line lasts as long as an ordinary one here.
            assertThat(lyrics.lines().get(6).endSeconds()).isEqualTo(80.0);
        }

        @Test
        @DisplayName("a zero gap does not split a run of long ones into breaks (#324)")
        void duplicateTimestampsDoNotBreakUpAHeldChorus() {
            // The held-chorus fixture with each chorus line doubled at its own
            // moment. A zero gap is never long, so counted as a neighbour it sits
            // between the chorus's long gaps and makes each of them look isolated
            // -- which is what the isolation test exists to tell apart, and it
            // cut every held line back to an ordinary one.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]v1
                    [00:01.00]v2
                    [00:02.00]v3
                    [00:03.00]v4
                    [00:04.00]c1
                    [00:04.00]c1b
                    [00:12.00]c2
                    [00:12.00]c2b
                    [00:20.00]c3
                    [00:20.00]c3b
                    """, 200.0);

            // c1b is held to the next chorus line, not cut to an ordinary line.
            assertThat(lyrics.lines().get(5).endSeconds()).isEqualTo(12.0);
            assertThat(lyrics.lines().get(7).endSeconds()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("ordinary jitter is not an instrumental break")
        void jitteringGapsAreLeftAlone() {
            // Hand-timed lines jitter around four seconds. Cutting every line at
            // the median would cut the longer half of them and grow a false
            // break in the middle of the verse.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]one
                    [00:03.20]two
                    [00:08.00]three
                    [00:11.90]four
                    [00:17.00]five
                    [00:21.00]six
                    """, 200.0);

            for (int i = 0; i + 1 < lyrics.lines().size(); i++) {
                assertThat(lyrics.lines().get(i).endSeconds())
                        .as("line %d runs to its successor", i)
                        .isEqualTo(lyrics.lines().get(i + 1).startSeconds());
            }
        }

        @Test
        @DisplayName("a held chorus keeps its lines, however much longer than the verse")
        void aHeldChorusIsNotABreak() {
            // Verse lines rattled off, chorus lines held eight times longer. The
            // chorus gaps are as long as a solo's, and what separates them is
            // that there are several in a row.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]v1
                    [00:01.00]v2
                    [00:02.00]v3
                    [00:03.00]v4
                    [00:04.00]c1
                    [00:12.00]c2
                    [00:20.00]c3
                    """, 200.0);

            assertThat(lyrics.lines().get(4).endSeconds()).isEqualTo(12.0);
            assertThat(lyrics.lines().get(5).endSeconds()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("two breaks around a single line are both missed, which is #323")
        void twoBreaksAroundOneLineAreBothMissed() {
            // Each break is the other's long neighbour, so the isolation test
            // clears neither. One line sung between two instrumental stretches
            // therefore keeps both. Pinned rather than left to be rediscovered;
            // a fix shows up as this test changing.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]first
                    [00:04.00]second
                    [01:04.00]bridge alone
                    [02:04.00]chorus
                    [02:08.00]more
                    """, 300.0);

            assertThat(lyrics.lines().get(1).endSeconds()).isEqualTo(64.0);
            assertThat(lyrics.lines().get(2).endSeconds()).isEqualTo(124.0);
            // And the misses inflate what an ordinary line is taken to be, since
            // the two stretches stay in the pool that measures it -- so the
            // closing line is given an instrumental's length. Asserted here so
            // that answering #323 shows its effect on duration too.
            assertThat(lyrics.lines().get(4).endSeconds()).isEqualTo(188.0);
        }

        @Test
        @DisplayName("an instrumental gap does not count as a line when lines are measured")
        void breaksAreLeftOutOfTheLineLength() {
            // Half the gaps are instrumental. Measuring a line across all of them
            // makes an ordinary line as long as a solo, and every cut line with
            // it.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]a
                    [00:04.00]b
                    [01:04.00]c
                    [01:08.00]d
                    [02:08.00]e
                    [02:12.00]f
                    [03:12.00]g
                    [03:16.00]h
                    [04:16.00]i
                    """, 400.0);

            assertThat(lyrics.lines().get(8).endSeconds()).isEqualTo(260.0);
        }

        @Test
        @DisplayName("timestamps sharing a moment do not shrink the line length to nothing")
        void duplicateTimestampsAreLeftOutOfTheLineLength() {
            // Several lines on one moment is what a repeated chorus writes. They
            // are zero-length gaps, and counting them as lines makes the measure
            // zero -- which gives every cut line no duration at all.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]a
                    [00:00.00]b
                    [00:00.00]c
                    [00:00.00]d
                    [00:10.00]e
                    [00:20.00]f
                    """, 400.0);

            assertThat(lyrics.lines().get(5).endSeconds()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("the closing line lasts as long as an ordinary line, not as long as the shortest")
        void closingLineUsesOrdinaryLines() {
            // The scale the break threshold is read against takes the lower of
            // two middle gaps on purpose; how long a cut line lasts must not
            // inherit that bias, or the closing line of a file with long lines
            // is reported as a short one.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]v1
                    [00:01.00]v2
                    [00:02.00]v3
                    [00:03.00]v4
                    [00:11.00]c1
                    [00:19.00]c2
                    [00:27.00]c3
                    """, 300.0);

            assertThat(lyrics.lines().get(6).endSeconds()).isEqualTo(35.0);
        }

        @Test
        @DisplayName("a lone long gap is a break even where a run of them is not")
        void oneLongGapIsStillABreak() {
            // The same shape with a single held line rather than a run of them.
            // One long gap between short ones is what a solo looks like.
            Lyrics lyrics = LrcLyrics.parse("""
                    [00:00.00]v1
                    [00:01.00]v2
                    [00:02.00]v3
                    [00:03.00]v4
                    [00:04.00]c1
                    [00:12.00]v5
                    [00:13.00]v6
                    """, 200.0);

            assertThat(lyrics.lines().get(4).endSeconds()).isLessThan(12.0);
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
                    [00:12.00]four
                    [00:16.00]five
                    [01:16.00]after the solo
                    """, 200.0);

            assertThat(lyrics.lines().get(4).endSeconds()).isEqualTo(20.0);
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
    @DisplayName("a known language counts syllables by its own rules, not English's")
    void languageDecidesTheShare() {
        // syllableEstimate drops a trailing "e", which is an English rule: in
        // Italian a final e is always sounded, so particolare is five syllables
        // and not four, and counted short it is given too little of the line.
        Lyrics guessed = LrcLyrics.parse("[00:00.00]a particolare\n[00:06.00]end\n", RECORDING);
        Lyrics known = LrcLyrics.parse(
                "[00:00.00]a particolare\n[00:06.00]end\n", RECORDING, "it");

        assertThat(known.language()).isEqualTo("it");
        // One syllable against five rather than against four, so "a" takes a
        // sixth of the line rather than a fifth.
        assertThat(known.lines().get(0).words().get(0).endSeconds()).isEqualTo(1.0);
        assertThat(guessed.lines().get(0).words().get(0).endSeconds()).isEqualTo(1.2);
    }

    @Test
    @DisplayName("an unknown language falls back to the crude count")
    void unknownLanguageStillSpreads() {
        Lyrics lyrics = LrcLyrics.parse("[00:00.00]a banana\n[00:04.00]end\n", RECORDING, "de");

        assertThat(lyrics.language()).isEqualTo("de");
        assertThat(lyrics.lines().get(0).words().get(0).endSeconds()).isCloseTo(1.0, within(1e-9));
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
