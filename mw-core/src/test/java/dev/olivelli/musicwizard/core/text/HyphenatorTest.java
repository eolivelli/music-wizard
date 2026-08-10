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

package dev.olivelli.musicwizard.core.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HyphenatorTest {

    private static String split(String language, String word) {
        return String.join("-", Hyphenator.forLanguage(language).orElseThrow().syllables(word));
    }

    @Nested
    @DisplayName("Italian")
    class Italian {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "amore, a-mo-re",
            "particolare, par-ti-co-la-re",
            "canzone, can-zo-ne",
            "sole, so-le",
            "innocenza, in-no-cen-za",
            "respiravamo, re-spi-ra-va-mo",
            "occhi, oc-chi",
            "azzurri, az-zur-ri",
            "calzette, cal-zet-te",
        })
        @DisplayName("words split where they are sung")
        void ordinaryWords(String word, String expected) {
            assertThat(split("it", word)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a syllable of one letter is still a syllable")
        void oneLetterSyllables() {
            // The typesetting minima the pattern file recommends forbid a break
            // within two letters of either end, which leaves amore with two
            // syllables where a singer gives it three.
            assertThat(split("it", "amore")).isEqualTo("a-mo-re");
            assertThat(split("it", "ora")).isEqualTo("o-ra");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "l'innocenza, l'in-no-cen-za",
            "dell'amore, dell'a-mo-re",
            "un'altra, un'al-tra",
            "c'era, c'e-ra",
            "d'amore, d'a-mo-re",
        })
        @DisplayName("an elided article is sung on the syllable after it")
        void elision(String word, String expected) {
            assertThat(split("it", word)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a diphthong is one syllable")
        void diphthongs() {
            assertThat(split("it", "pianura")).isEqualTo("pia-nu-ra");
            assertThat(split("it", "cuore")).isEqualTo("cuo-re");
        }
    }

    @Nested
    @DisplayName("English")
    class English {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "trouble, trou-ble",
            "wisdom, wis-dom",
            "somebody, some-body",
            "associate, as-so-ci-ate",
        })
        @DisplayName("words split at the breaks a typesetter would take")
        void ordinaryWords(String word, String expected) {
            assertThat(split("en", word)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("what it declines to split")
    class LeavesAlone {

        @Test
        @DisplayName("digits and punctuation are left whole rather than guessed at")
        void notLetters() {
            assertThat(split("it", "1999")).isEqualTo("1999");
            assertThat(split("en", "24/7")).isEqualTo("24/7");
            assertThat(split("it", "po'")).isEqualTo("po'");
        }

        @Test
        @DisplayName("a word too short to break comes back whole")
        void shortWords() {
            assertThat(split("it", "e")).isEqualTo("e");
            assertThat(split("it", "di")).isEqualTo("di");
            assertThat(split("en", "a")).isEqualTo("a");
        }

        @Test
        @DisplayName("every split rejoins to the word it came from")
        void splittingLosesNothing() {
            List<String> words = List.of("amore", "l'innocenza", "particolare", "trouble",
                    "1999", "po'", "e", "rock'n'roll", "perché", "città");
            for (String language : List.of("it", "en")) {
                Hyphenator hyphenator = Hyphenator.forLanguage(language).orElseThrow();
                for (String word : words) {
                    assertThat(String.join("", hyphenator.syllables(word)))
                            .as("%s / %s", language, word)
                            .isEqualTo(word);
                }
            }
        }
    }

    @Nested
    @DisplayName("choosing a language")
    class Languages {

        @Test
        @DisplayName("a region subtag names the same patterns")
        void regionIsIgnored() {
            assertThat(Hyphenator.forLanguage("it-IT")).isPresent();
            assertThat(Hyphenator.forLanguage("en-GB")).isPresent();
        }

        @Test
        @DisplayName("an unknown language has no hyphenator rather than a wrong one")
        void unknownLanguage() {
            // "und" is what Lyrics carries until something establishes the
            // language. Splitting on another language's rules would be worse
            // than not splitting.
            assertThat(Hyphenator.forLanguage("und")).isEmpty();
            assertThat(Hyphenator.forLanguage("de")).isEmpty();
            assertThat(Hyphenator.forLanguage("")).isEmpty();
            assertThat(Hyphenator.supports("und")).isFalse();
            assertThat(Hyphenator.supports("it")).isTrue();
        }

        @Test
        @DisplayName("null is a mistake, not a language")
        void nullTag() {
            assertThatThrownBy(() -> Hyphenator.forLanguage(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the same language yields the same loaded patterns")
        void patternsAreLoadedOnce() {
            assertThat(Hyphenator.forLanguage("it").orElseThrow())
                    .isSameAs(Hyphenator.forLanguage("it-IT").orElseThrow());
        }
    }
}
