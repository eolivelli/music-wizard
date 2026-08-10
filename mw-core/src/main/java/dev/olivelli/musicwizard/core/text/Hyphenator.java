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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Splits a word into the syllables it is sung on.
 *
 * <p>Liang's algorithm, the one TeX has used since 1983: a table of patterns
 * scores every position between two letters, and an odd score is a break. The
 * patterns are the licensed part and are vendored verbatim from {@code
 * hyph-utf8} — Italian dual-licensed with an MIT arm, American English under its
 * author's royalty-free grant, both attributed in {@code NOTICE}. Taking them
 * from the source matters: the copies that travel with Apache FOP are LPPL only,
 * because that conversion predates the Italian relicensing.
 *
 * <p>Implemented here rather than taken from a library because the patterns have
 * to be vendored and attributed either way, and what is left is a short
 * well-understood algorithm — the same trade this project makes for its DSP.
 *
 * <p><b>Both minima are one.</b> The pattern files state the minima their
 * language wants for <em>typesetting</em>: two letters before the first break
 * and two or three after the last, so that no line ends in a stranded letter.
 * Those are rules about paper, and applying them to singing is wrong in the one
 * direction that matters — Italian <i>amore</i> is sung on three notes, and
 * typesetting minima forbid both <i>a-more</i> and <i>amo-re</i>, leaving a word
 * of three syllables with two. A syllable a singer gives a note to gets one
 * here, however short.
 *
 * <p>What this is not: a pronunciation dictionary. Liang's patterns give the
 * breaks a typesetter would take, which for English are orthographic rather than
 * sung — {@code even-ing} against the two syllables a singer holds. Italian is
 * the closer fit of the two, being written much as it is spoken, and it is where
 * the accuracy is wanted first.
 */
public final class Hyphenator {

    /** Languages with patterns, keyed by the tag {@link #forLanguage} accepts. */
    private static final Map<String, String> PATTERN_FILES = Map.of(
            "it", "/hyphenation/hyph-it.pat.txt",
            "en", "/hyphenation/hyph-en-us.pat.txt");

    private static final Map<String, Hyphenator> LOADED = new HashMap<>();

    /**
     * How many letters must lie before the first break and after the last.
     *
     * <p>One and one, against the two and two or three the pattern files
     * recommend. See the class javadoc: those are typesetting rules, and a sung
     * syllable is a syllable however short.
     */
    private static final int MINIMUM = 1;

    /** Pattern letters to the scores between them, one longer than the letters. */
    private final Map<String, byte[]> patterns;

    private Hyphenator(Map<String, byte[]> patterns) {
        this.patterns = patterns;
    }

    /**
     * The hyphenator for a language, or empty when there are no patterns for it.
     *
     * <p>Takes a BCP 47 tag and reads only its language subtag, so {@code it-IT}
     * and {@code it} are the same request. {@code und} — which is what
     * {@link dev.olivelli.musicwizard.core.model.Lyrics} carries until something
     * establishes the language — yields empty, and a caller with no hyphenator
     * leaves words whole. Guessing between Italian and English would split a word
     * on the wrong language's rules, which is worse than not splitting it.
     */
    public static synchronized Optional<Hyphenator> forLanguage(String languageTag) {
        Objects.requireNonNull(languageTag, "languageTag");
        String language = Locale.forLanguageTag(languageTag).getLanguage();
        if (language.isEmpty() || !PATTERN_FILES.containsKey(language)) {
            return Optional.empty();
        }
        return Optional.of(LOADED.computeIfAbsent(language, Hyphenator::load));
    }

    /** Whether {@link #forLanguage} would find patterns for this tag. */
    public static boolean supports(String languageTag) {
        return forLanguage(languageTag).isPresent();
    }

    private static Hyphenator load(String language) {
        String resource = PATTERN_FILES.get(language);
        Map<String, byte[]> patterns = new HashMap<>();
        try (InputStream in = Hyphenator.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing hyphenation patterns: " + resource);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String pattern = line.strip();
                if (!pattern.isEmpty()) {
                    add(patterns, pattern);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
        return new Hyphenator(patterns);
    }

    /** Splits one TeX pattern into its letters and the scores between them. */
    private static void add(Map<String, byte[]> patterns, String pattern) {
        StringBuilder letters = new StringBuilder(pattern.length());
        byte[] scores = new byte[pattern.length() + 1];
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c >= '0' && c <= '9') {
                scores[letters.length()] = (byte) (c - '0');
            } else {
                letters.append(c);
            }
        }
        byte[] trimmed = new byte[letters.length() + 1];
        System.arraycopy(scores, 0, trimmed, 0, trimmed.length);
        patterns.put(letters.toString(), trimmed);
    }

    /**
     * The word split into syllables, in order, joining back to the original.
     *
     * <p>A word with no break comes back as one syllable, and so does anything
     * that is not letters — digits and punctuation are left alone rather than
     * guessed at, since a lyric carries years and worse.
     *
     * <p><b>An elided article is carried onto the syllable it is sung with.</b>
     * Italian writes {@code l'innocenza} and {@code dell'amore}, and the elided
     * piece is not a syllable of its own: it is sung on the first syllable of
     * what follows, which is what a singer sees on the page. So the stem is
     * split and the piece before the apostrophe joins the front of it. Without
     * this such a word is not letters at all and stays whole, which in Italian
     * is most of the articles in the song.
     */
    public List<String> syllables(String word) {
        Objects.requireNonNull(word, "word");
        int elision = word.lastIndexOf('\'');
        if (elision >= 0 && elision + 1 < word.length()) {
            String prefix = word.substring(0, elision + 1);
            List<String> stem = syllables(word.substring(elision + 1));
            List<String> joined = new ArrayList<>(stem.size());
            joined.add(prefix + stem.get(0));
            joined.addAll(stem.subList(1, stem.size()));
            return List.copyOf(joined);
        }
        if (word.length() < 2 * MINIMUM + 1) {
            return List.of(word);
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (!isLetters(lower)) {
            return List.of(word);
        }

        // The word is scored with a boundary marker at each end, which is what
        // the leading and trailing "." in a pattern matches.
        String bounded = "." + lower + ".";
        byte[] scores = new byte[bounded.length() + 1];
        for (int from = 0; from < bounded.length(); from++) {
            for (int to = from + 1; to <= bounded.length(); to++) {
                byte[] pattern = patterns.get(bounded.substring(from, to));
                if (pattern != null) {
                    for (int i = 0; i < pattern.length; i++) {
                        scores[from + i] = (byte) Math.max(scores[from + i], pattern[i]);
                    }
                }
            }
        }

        List<String> syllables = new ArrayList<>();
        int start = 0;
        // scores[i + 1] scores the gap after the i-th letter of the word, the
        // offset coming from the boundary marker in front of it.
        for (int i = MINIMUM; i <= word.length() - MINIMUM - 1; i++) {
            if (scores[i + 1] % 2 == 1) {
                syllables.add(word.substring(start, i));
                start = i;
            }
        }
        syllables.add(word.substring(start));
        return List.copyOf(syllables);
    }

    /** How many syllables this word is sung on. */
    public int syllableCount(String word) {
        return syllables(word).size();
    }

    private static boolean isLetters(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isLetter(word.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
