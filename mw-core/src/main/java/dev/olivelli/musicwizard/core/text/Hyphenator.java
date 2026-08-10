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
 * author's royalty-free grant, both attributed in {@code NOTICE}.
 *
 * <p>Implemented here rather than taken from a library because the patterns have
 * to be vendored and attributed either way, and what is left is a short
 * well-understood algorithm — the same trade this project makes for its DSP.
 *
 * <p><b>A syllable of one letter is allowed at the front.</b> The pattern files
 * state the minima their language wants for <em>typesetting</em>: two letters
 * must stay behind before a break, so a printed line never ends in a stranded
 * one. That is a rule about paper and it costs a note — it forbids <i>a-more</i>,
 * and Italian <i>amore</i> is sung on three. See {@link #LEFT_MINIMUM} and
 * {@link #RIGHT_MINIMUM}, which are not the same number and say why.
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
     * How many letters must lie before the first break.
     *
     * <p>One, against the two the pattern files recommend for typesetting. That
     * recommendation keeps a printed line from ending in a stranded letter, and
     * it costs a syllable a singer holds: it forbids <i>a-more</i>, and Italian
     * <i>amore</i> is sung on three notes.
     */
    private static final int LEFT_MINIMUM = 1;

    /**
     * How many letters must lie after the last break.
     *
     * <p>Two, not one, and it bounds what a break may carry onto the next line.
     * A final consonant on its own is not a syllable in either language — one
     * gives <i>abandon-s</i> and <i>abbot-s</i> in English, and in Italian a
     * handful of foreign words like <i>gol-f</i>. The asymmetry is deliberate: a
     * vowel can open a syllable alone and a consonant cannot close one alone.
     */
    private static final int RIGHT_MINIMUM = 2;

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
     * <p>Only the token's <b>letters</b> are hyphenated. Anything before the
     * first letter or after the last is carried along on the syllable it touches,
     * because the minima count sung sounds and punctuation is not one: measured
     * over characters instead, a trailing comma fills the slot the right minimum
     * reserves and {@code abandons,} comes out {@code a-ban-don-s,} — the very
     * split {@link #RIGHT_MINIMUM} exists to prevent. A full stop is the same
     * mistake wearing a different hat, since it is the character the patterns use
     * for a word boundary, so a token with one <em>inside</em> its letters is left
     * whole rather than scored as several words.
     *
     * <p>An apostrophe is not punctuation here but part of the word, which is
     * what the data expects: the Italian file ships a pattern for every position
     * an elision can take, in both the typewriter and the typographic quote, so
     * {@code dell'amore} comes out {@code del-l'a-mo-re} — four notes, the elided
     * article breaking away from the article before it.
     *
     * <p>Nothing else needs a gate. No pattern matches a digit, so {@code 1999}
     * and {@code 24/7} come back whole without being tested for.
     */
    public List<String> syllables(String word) {
        Objects.requireNonNull(word, "word");
        int first = firstLetter(word);
        int last = lastLetter(word);
        if (first < 0 || last - first + 1 < LEFT_MINIMUM + RIGHT_MINIMUM) {
            return List.of(word);
        }
        String prefix = word.substring(0, first);
        String core = word.substring(first, last + 1);
        String suffix = word.substring(last + 1);
        if (core.indexOf('.') >= 0) {
            return List.of(word);
        }

        String lower = core.toLowerCase(Locale.ROOT);
        // One code point in Unicode lowercases to two -- Turkish dotted capital
        // I -- and the scores below are read at this token's own offsets, so a
        // token holding one would be cut at gaps belonging to other letters.
        if (lower.length() != core.length()) {
            return List.of(word);
        }

        // The core is scored with a boundary marker at each end, which is what
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
        // scores[i + 1] scores the gap after the i-th character of the core, the
        // offset coming from the boundary marker in front of it.
        for (int i = LEFT_MINIMUM; i <= core.length() - RIGHT_MINIMUM; i++) {
            if (scores[i + 1] % 2 == 1) {
                syllables.add(core.substring(start, i));
                start = i;
            }
        }
        syllables.add(core.substring(start));

        // The punctuation rejoins the syllable it was attached to, so the pieces
        // still concatenate to the token they came from.
        List<String> whole = new ArrayList<>(syllables);
        whole.set(0, prefix + whole.get(0));
        whole.set(whole.size() - 1, whole.get(whole.size() - 1) + suffix);
        return List.copyOf(whole);
    }

    /** Where the token's letters begin, or -1 when it has none. */
    private static int firstLetter(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetter(word.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Where the token's letters end, or -1 when it has none. */
    private static int lastLetter(String word) {
        for (int i = word.length() - 1; i >= 0; i--) {
            if (Character.isLetter(word.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** How many syllables this word is sung on. */
    public int syllableCount(String word) {
        return syllables(word).size();
    }

}
