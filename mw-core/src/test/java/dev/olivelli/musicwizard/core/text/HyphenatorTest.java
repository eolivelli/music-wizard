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

    /** The syllables joined by "-", which is how a split reads in an assertion. */
    private static String split(String language, String word) {
        return Hyphenator.forLanguage(language).orElseThrow().syllables(word).stream()
                .map(Hyphenator.Syllable::text)
                .collect(java.util.stream.Collectors.joining("-"));
    }

    /** The same, marking a drawn hyphen "=" and an undrawn boundary "/". */
    private static String marked(String language, String word) {
        List<Hyphenator.Syllable> parts =
                Hyphenator.forLanguage(language).orElseThrow().syllables(word);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            out.append(parts.get(i).text());
            if (i + 1 < parts.size()) {
                out.append(parts.get(i).hyphenToNext() ? "=" : "/");
            }
        }
        return out.toString();
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
        @DisplayName("a syllable of one letter is allowed at the front")
        void theLeftMinimumIsOne() {
            // A vowel opens a syllable alone, so the typesetting minimum of two
            // on the left costs a note: it forbids a-more, and amore is sung on
            // three.
            assertThat(split("it", "amore")).isEqualTo("a-mo-re");
            assertThat(split("it", "ora")).isEqualTo("o-ra");
            // The right minimum decides nothing here any more: golf is one
            // syllable because gol-f has no vowel in its second piece, which is
            // what joins it back whatever that constant says.
            assertThat(split("it", "golf")).isEqualTo("golf");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "l'innocenza, l'in-no-cen-za",
            "dell'amore, del-l'a-mo-re",
            "sull'erba, sul-l'er-ba",
            "quell'uomo, quel-l'uo-mo",
            "all'improvviso, al-l'im-prov-vi-so",
            "un'altra, un'al-tra",
            "c'era, c'e-ra",
            "d'amore, d'a-mo-re",
        })
        @DisplayName("an elision breaks where the patterns say")
        void elision(String word, String expected) {
            // An apostrophe is part of the word, not punctuation around it: the
            // Italian file ships a pattern for every position an elision can
            // take, so the elided article breaks away from the article before it.
            assertThat(split("it", word)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "dell\u2019amore, del-l\u2019a-mo-re",
            "l\u2019innocenza, l\u2019in-no-cen-za",
            "nell\u2019aria, nel-l\u2019a-ria",
        })
        @DisplayName("the typographic quote is an apostrophe too, as the patterns assume")
        void typographicQuote(String word, String expected) {
            // Every apostrophe pattern in the file has a U+2019 twin.
            assertThat(split("it", word)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a word-initial y is the consonant here too, because it arrives in loanwords")
        void openingYIsTheConsonantInBothLanguages() {
            // Italian lyrics borrow English words freely, and word-initial y
            // reaches Italian only that way -- yogurt, yacht -- where it is the
            // consonant. Exempting Italian strands a bare y on its own note
            // across this whole family.
            assertThat(split("it", "you")).isEqualTo("you");
            assertThat(split("it", "young")).isEqualTo("young");
            assertThat(split("it", "yield")).isEqualTo("yield");
            // What it costs, from the same population rather than a word that
            // population does not contain: these open on the vowel and lose a
            // note. Named so the trade is visible, not hidden.
            assertThat(split("it", "yttrium")).isEqualTo("yttrium");
            assertThat(split("it", "Yggdrasil")).isEqualTo("Yggdra-sil");
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

        @Test
        @DisplayName("a lone final vowel is not a syllable either, and only the minimum says so")
        void theRightMinimumIsTwo() {
            // What this constant still decides. A stranded final consonant is
            // joined back by the vowel rule below whatever it is set to, so
            // "abandon-s" is no longer its doing; a stranded final vowel has a
            // vowel and survives that rule, so acaci-a is the constant's alone.
            assertThat(split("en", "acacia")).isEqualTo("a-ca-cia");
            assertThat(split("en", "academia")).isEqualTo("a-cad-e-mia");
            assertThat(split("en", "abandons")).isEqualTo("a-ban-dons");
            assertThat(split("en", "abbots")).doesNotEndWith("-s");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "sing, sing",
            "never, nev-er",
            "Bismarck, Bis-marck",
            "Amsterdam, Am-ster-dam",
            "Americas, Amer-i-cas",
        })
        @DisplayName("a piece with no vowel is joined to the one it is sung with")
        void vowellessPiecesAreJoined(String word, String expected) {
            // The patterns break where a line may end, and a line may end after a
            // letter that is not a syllable. Left alone these are s-ing, n-ev-er,
            // Bis-mar-ck, Am-s-ter-dam and Amer-i-c-as.
            assertThat(split("en", word)).isEqualTo(expected);
        }

        @Test
        @DisplayName("neither minimum moves to get them, because both would cost a note")
        void theMinimaStayWhereTheyAre() {
            // The left minimum of two that typesetting wants would take "s-ing"
            // and these together; the right minimum of three that TeX's own
            // English asks for would take "Amer-i-c-as" and these together.
            assertThat(split("en", "along")).isEqualTo("a-long");
            assertThat(split("en", "happy")).isEqualTo("hap-py");
        }

        @Test
        @DisplayName("y is a vowel, except opening a word, where it is the consonant")
        void yIsAVowelUnlessItOpensTheWord() {
            // Sung on two notes, and their only vowel is the y -- so a flat rule
            // that y is not a vowel joins each of them into one note.
            assertThat(marked("en", "sky-high")).isEqualTo("sky-/high");
            assertThat(split("en", "lonely")).isEqualTo("lone-ly");
            // Sung on one, and the y opens it.
            assertThat(split("en", "y'all")).isEqualTo("y'all");
            assertThat(split("en", "York")).isEqualTo("York");
        }

        @Test
        @DisplayName("the word is what the y rule is anchored to, not the piece")
        void aSyllablesOwnYIsAVowelWhereverItFalls() {
            // The patterns cut ynx and ysm as syllables in their own right, and
            // their y is the vowel. Anchored on the piece rather than the word,
            // each is called consonant-only and joined away: lar-ynx loses a note.
            assertThat(split("en", "larynx")).isEqualTo("lar-ynx");
            assertThat(split("en", "paroxysm")).isEqualTo("parox-ysm");
            assertThat(split("en", "dialysis")).isEqualTo("dial-y-sis");
        }
    }

    @Nested
    @DisplayName("what it declines to split")
    class LeavesAlone {

        @Test
        @DisplayName("digits are left whole, because no pattern matches one")
        void digits() {
            assertThat(split("it", "1999")).isEqualTo("1999");
            assertThat(split("en", "24/7")).isEqualTo("24/7");
            assertThat(split("it", "po'")).isEqualTo("po'");
        }

        @Test
        @DisplayName("punctuation rides the syllable it touches, at either end")
        void punctuationRidesAlong() {
            assertThat(split("it", "amore,")).isEqualTo("a-mo-re,");
            assertThat(split("it", "canzone!")).isEqualTo("can-zo-ne!");
            assertThat(split("it", "citt\u00e0,")).isEqualTo("cit-t\u00e0,");
            assertThat(split("en", "(chorus)")).isEqualTo("(cho-rus)");
            assertThat(split("it", "\"cuore\"")).isEqualTo("\"cuo-re\"");
        }

        @Test
        @DisplayName("punctuation does not fill a slot the minima reserve for a letter")
        void punctuationDoesNotCountTowardsTheMinima() {
            // The minima count sung sounds. Measured over characters instead, a
            // trailing comma occupies the right minimum's second slot and strands
            // the final consonant -- the split RIGHT_MINIMUM exists to prevent --
            // and a leading bracket makes itself a syllable.
            assertThat(split("en", "abandons,")).isEqualTo("a-ban-dons,");
            assertThat(split("en", "hearts,")).isEqualTo("hearts,");
            assertThat(split("it", "golf,")).isEqualTo("golf,");
            assertThat(split("it", "non,")).isEqualTo("non,");
            // The same word bare and punctuated splits the same way.
            for (String word : List.of("abandons", "abbots", "wisdom")) {
                assertThat(split("en", word + ",")).as(word)
                        .isEqualTo(split("en", word) + ",");
            }
        }

        @Test
        @DisplayName("a sentence-final word still splits; a dotted one does not")
        void fullStops() {
            // The full stop is what the patterns mean by a word boundary, so a
            // token holding one among its letters would be scored as several
            // words. One after them is just punctuation.
            assertThat(split("it", "amore.")).isEqualTo("a-mo-re.");
            assertThat(split("en", "goodbye.")).isEqualTo("good-bye.");
            assertThat(split("it", "dell'amore.")).isEqualTo("del-l'a-mo-re.");
            // A stop between letters would cut an abbreviation into its initials.
            assertThat(split("en", "U.S.A.")).isEqualTo("U.S.A.");
        }

        @Test
        @DisplayName("a letter that lowercases to two characters is left whole")
        void lengthChangingLowercase() {
            // Turkish dotted capital I is the one code point in Unicode whose
            // lowercase is longer, and the scores are read at the token's own
            // offsets -- so a token holding one would be cut at gaps belonging to
            // other letters.
            assertThat(split("it", "\u0130mmagine")).isEqualTo("\u0130mmagine");
            assertThat(split("en", "\u0130nteresting")).isEqualTo("\u0130nteresting");
        }

        @Test
        @DisplayName("a character the language's patterns never mention separates two runs")
        void foreignCharactersSeparateRuns() {
            // The English file mentions no apostrophe at all, so one is not word
            // material there: scored as though it were, it fills a slot the
            // minima reserve and "'s" ends up a syllable with a note of its own.
            assertThat(split("en", "Adirondack's")).isEqualTo("Adiron-dack's");
            assertThat(split("en", "Anderson's")).isEqualTo("An-der-son's");
            assertThat(split("en", "don't")).isEqualTo("don't");
            // Neither file mentions a hyphen, so a compound is two runs.
            assertThat(split("en", "well-known")).isEqualTo("well--known");
            assertThat(split("it", "bio-vita")).isEqualTo("bio--vi-ta");
        }

        @Test
        @DisplayName("a separator the token already carries is not drawn a second time")
        void aCarriedHyphenIsNotDrawn() {
            // "=" is a hyphen this engine chose and draws; "/" is a boundary it
            // does not, the token having carried its own. Drawing one at every
            // boundary gave the page "well--known".
            assertThat(marked("en", "well-known")).isEqualTo("well-/known");
            assertThat(marked("en", "mother-in-law")).isEqualTo("moth=er-/in-/law");
            assertThat(marked("en", "abandons")).isEqualTo("a=ban=dons");
            assertThat(marked("it", "dell'amore")).isEqualTo("del=l'a=mo=re");
        }

        @Test
        @DisplayName("a run with no vowel joins its neighbour; a short one with a vowel does not")
        void aSyllableNeedsAVowel() {
            // The inflection in a possessive is not a note of its own, and nor is
            // the consonant in a contraction -- but "la" is, which is what
            // sha-la-la is made of.
            assertThat(split("en", "y'all")).isEqualTo("y'all");
            assertThat(split("en", "sha-la-la")).isEqualTo("sha--la--la");
            assertThat(split("en", "hi-fi")).isEqualTo("hi--fi");
            assertThat(split("en", "co-op")).isEqualTo("co--op");
        }

        @Test
        @DisplayName("an apostrophe does not fill a slot the minima reserve")
        void apostropheDoesNotCountTowardsTheMinima() {
            // It is word material in Italian, so counted as a character it takes
            // the slot RIGHT_MINIMUM reserves and strands the consonant: elf'
            // would break as el-f'.
            assertThat(split("it", "elf'")).isEqualTo("elf'");
            assertThat(split("it", "citta'")).isEqualTo("cit-ta'");
            assertThat(split("it", "perche'")).isEqualTo("per-che'");
        }

        @Test
        @DisplayName("the apostrophe is word material in Italian, where the patterns use it")
        void apostropheIsLanguageSpecific() {
            // The same character, read from the data rather than from a rule of
            // ours: Italian breaks inside the elision, English does not.
            assertThat(split("it", "dell'amore")).isEqualTo("del-l'a-mo-re");
            assertThat(split("en", "hack's")).isEqualTo("hack's");
        }

        @Test
        @DisplayName("a token with no letters at all is left whole")
        void noLetters() {
            assertThat(split("it", "--")).isEqualTo("--");
            assertThat(split("en", "...")).isEqualTo("...");
            assertThat(split("it", "!?!")).isEqualTo("!?!");
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
            // Raw concatenation, not a normalised one: a hyphen inside a piece is
            // part of the token and losing it must fail here. The list reaches
            // each way append can move text -- a run joined backwards, one held
            // over for the run after it, a separator carried, an abbreviation.
            List<String> words = List.of("amore", "l'innocenza", "particolare", "trouble",
                    "1999", "po'", "e", "rock'n'roll", "perch\u00e9", "citt\u00e0",
                    "well-known", "sha-la-la", "y'all", "U.S.A.", "elf'", "x-ray",
                    "Adirondack's", "(chorus)", "amore,", "--");
            for (String language : List.of("it", "en")) {
                Hyphenator hyphenator = Hyphenator.forLanguage(language).orElseThrow();
                for (String word : words) {
                    String rejoined = hyphenator.syllables(word).stream()
                            .map(Hyphenator.Syllable::text)
                            .collect(java.util.stream.Collectors.joining());
                    assertThat(rejoined).as("%s / %s", language, word).isEqualTo(word);
                    assertThat(hyphenator.syllables(word)).as("%s / %s", language, word)
                            .allSatisfy(piece -> assertThat(piece.text()).isNotEmpty());
                }
            }
        }

        @Test
        @DisplayName("an abbreviation is left whole wherever its stops begin")
        void abbreviationsWithLeadingPunctuation() {
            // The guard looked only at the first stop, so one in front of the
            // letters hid every stop after it.
            assertThat(split("en", "U.S.A.")).isEqualTo("U.S.A.");
            assertThat(split("en", ".U.S.A.")).isEqualTo(".U.S.A.");
            assertThat(split("en", "...U.S.A.")).isEqualTo("...U.S.A.");
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
