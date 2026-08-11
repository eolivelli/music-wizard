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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("display width")
class DisplayWidthTest {

    @Nested
    @DisplayName("one character")
    class OneCharacter {

        @ParameterizedTest(name = "U+{0} is {1} cells")
        @CsvSource({
            // ASCII and Latin: one cell, and the whole reason length() looked fine.
            "0041, 1",   // A
            "00E8, 1",   // e-grave, composed
            // Combining and format: drawn on the character before, or not drawn.
            "0301, 0",   // combining acute
            "200D, 0",   // zero-width joiner
            "FE0F, 0",   // variation selector 16
            "200B, 0",   // zero-width space
            // East Asian wide, the case #320 is about.
            "6708, 2",   // moon
            "3042, 2",   // hiragana a
            "AC00, 2",   // hangul syllable
            "FF21, 2",   // fullwidth A
            // Astral, both ways.
            "1F680, 2",  // rocket
            "1D11E, 1",  // musical symbol G clef -- astral and narrow
            "20000, 2",  // CJK extension B
            // Spacing marks are spacing: Unicode's own name for the category.
            "093E, 1",   // Devanagari vowel sign AA
            "0BBE, 1",   // Tamil vowel sign AA
            // Format characters that are printed, against the rest of Cf above.
            "00AD, 1",   // soft hyphen
            "0600, 1",   // Arabic number sign
            // Conjoining jamo stack into the syllable the consonant opened.
            "1100, 2",   // Hangul choseong kiyeok -- opens the square
            "1161, 0",   // Hangul jungseong A -- stacks into it
            "11A8, 0",   // Hangul jongseong kiyeok
            "AC01, 2",   // the precomposed syllable those three make
        })
        @DisplayName("takes the cells Annex 11 and its category give it")
        void widths(String hex, int expected) {
            assertThat(DisplayWidth.of(Integer.parseInt(hex, 16))).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("a string")
    class AString {

        @Test
        @DisplayName("is the sum of its characters, not its length")
        void sums() {
            assertThat(DisplayWidth.of("abc")).isEqualTo(3);
            assertThat(DisplayWidth.of("\u6708\u5149")).isEqualTo(4);
            assertThat("\u6708\u5149".length()).isEqualTo(2);
        }

        @Test
        @DisplayName("gives one answer for both normal forms of the same word")
        void normalisationDoesNotChangeIt() {
            // The chords must not move because an LRC was saved decomposed.
            assertThat(DisplayWidth.of("caffe\u0300")).isEqualTo(DisplayWidth.of("caff\u00E8"));
        }

        @Test
        @DisplayName("gives the joiner in an emoji sequence no cells of its own")
        void theJoinerTakesNoCells() {
            // Woman + ZWJ + rocket: two wide emoji and a joiner, so four cells
            // and not five. What a terminal draws for the sequence as a glyph
            // is its own business -- this is what the property says it costs.
            assertThat(DisplayWidth.of("\uD83D\uDC69\u200D\uD83D\uDE80")).isEqualTo(4);
        }

        @Test
        @DisplayName("is empty for the empty string")
        void empty() {
            assertThat(DisplayWidth.of("")).isZero();
        }
    }

    @Nested
    @DisplayName("padding")
    class Padding {

        @Test
        @DisplayName("measures cells, not characters, and never shortens")
        void padsToCells() {
            StringBuilder row = new StringBuilder("\u6708");
            DisplayWidth.padTo(row, 5);
            assertThat(DisplayWidth.of(row.toString())).isEqualTo(5);
            assertThat(row.toString()).isEqualTo("\u6708   ");

            StringBuilder full = new StringBuilder("\u6708\u5149\u5915");
            DisplayWidth.padTo(full, 2);
            assertThat(full.toString()).isEqualTo("\u6708\u5149\u5915");
        }
    }

    @Nested
    @DisplayName("the table")
    class Table {

        /**
         * The property file is not carried, so this cannot re-derive the ranges.
         * What it can hold is every edge bisection could get wrong: a table out
         * of order, or one range short at either end, answers "not wide" for
         * characters that are, and does it silently.
         */
        @ParameterizedTest(name = "U+{0} .. U+{1}")
        @CsvSource({
            "1100, 115F",    // Hangul Jamo, initial consonants
            "AC00, D7A3",    // Hangul syllables
            "FF01, FF60",    // fullwidth forms of ASCII
            "20000, 2FFFD",  // CJK unified ideographs extension B
        })
        @DisplayName("is exact at both ends of a range")
        void boundariesHold(String from, String to) {
            int first = Integer.parseInt(from, 16);
            int last = Integer.parseInt(to, 16);
            assertThat(DisplayWidth.of(first)).as("first of U+%s", from).isEqualTo(2);
            assertThat(DisplayWidth.of(last)).as("last of U+%s", to).isEqualTo(2);
            // Outside the range is "not wide" rather than "one cell": the
            // character after the leading Hangul consonants is the first
            // conjoining vowel, which stacks into the syllable and takes none.
            assertThat(DisplayWidth.of(first - 1)).as("before U+%s", from).isNotEqualTo(2);
            assertThat(DisplayWidth.of(last + 1)).as("after U+%s", to).isNotEqualTo(2);
        }

        /**
         * The one failure a regenerated table makes and nothing else catches.
         * Bisection over an array left out of order answers "narrow" for
         * whatever it can no longer reach, silently and only for the entries
         * that moved -- so the four boundary rows above still pass. Counting
         * what the table reaches is what notices.
         */
        @Test
        @DisplayName("is still wholly reachable by bisection")
        void everyRangeIsReachable() {
            int wide = 0;
            int runs = 0;
            boolean previousWide = false;
            for (int codePoint = 0; codePoint <= 0x10FFFF; codePoint++) {
                boolean isWide = DisplayWidth.of(codePoint) == 2;
                if (isWide) {
                    wide++;
                    if (!previousWide) {
                        runs++;
                    }
                }
                previousWide = isWide;
            }
            // Unicode 16.0.0 has 182719 wide code points; seven of them are
            // also nonspacing marks and take no cell, which is what splits the
            // table's 122 ranges into 123 runs here.
            assertThat(wide).isEqualTo(182_712);
            assertThat(runs).isEqualTo(123);
        }

        @Test
        @DisplayName("leaves every unlisted character at one cell")
        void unlistedIsOne() {
            // Cyrillic, Greek, Hebrew, Devanagari: none is East Asian, and a
            // range that swallowed one would move every chord on the line.
            assertThat(DisplayWidth.of("\u0416")).isEqualTo(1);
            assertThat(DisplayWidth.of("\u03B1")).isEqualTo(1);
            assertThat(DisplayWidth.of("\u05D0")).isEqualTo(1);
            assertThat(DisplayWidth.of("\u0915")).isEqualTo(1);
        }
    }
}
