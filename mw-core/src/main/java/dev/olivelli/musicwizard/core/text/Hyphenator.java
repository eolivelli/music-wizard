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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * <p><b>A syllable of one letter is allowed at the front.</b> Typesetting wants
 * two letters to stay behind before a break, so a printed line never ends in a
 * stranded one. That is a rule about paper and it costs a note — it forbids <i>a-more</i>,
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
     * <p>One, against the two typesetting wants. That convention keeps a printed
     * line from ending in a stranded letter, and
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

    /**
     * The non-letters this language's patterns actually mention, less the word
     * boundary marker.
     *
     * <p>Read off the data rather than listed here, because the answer differs by
     * language and only the data knows it: the Italian file carries a pattern for
     * every position an elision can take, in both quote characters, and the
     * English file mentions no apostrophe at all. A character outside this set is
     * not word material for this language — it separates one run of word from the
     * next, exactly as the space around the token does.
     */
    private final Set<Character> wordCharacters;

    private Hyphenator(Map<String, byte[]> patterns, Set<Character> wordCharacters) {
        this.patterns = patterns;
        this.wordCharacters = wordCharacters;
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
        Set<Character> wordCharacters = new HashSet<>();
        for (String letters : patterns.keySet()) {
            for (int i = 0; i < letters.length(); i++) {
                char c = letters.charAt(i);
                if (!Character.isLetter(c) && c != '.') {
                    wordCharacters.add(c);
                }
            }
        }
        return new Hyphenator(patterns, Set.copyOf(wordCharacters));
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
     * <p>The token is cut into <b>runs of word material</b> and each run is
     * hyphenated on its own. What counts as word material is read from the
     * loaded language's patterns — its letters, plus whatever non-letters those
     * patterns actually mention — so Italian treats an apostrophe as part of the
     * word and English, whose file never mentions one, does not. Anything else
     * separates one run from the next and rides the syllable it touches.
     *
     * <p>That is what keeps punctuation out of the minima's slots. Scored as
     * though it were word material, a trailing comma fills the slot
     * {@link #RIGHT_MINIMUM} reserves and {@code abandons,} comes out
     * {@code a-ban-don-s,} — the very split that constant exists to prevent —
     * and an English possessive puts {@code 's} on a note of its own. A run too
     * short to hold a break joins the piece before it for the same reason.
     *
     * <p>A full stop is word material for neither language and is also what the
     * patterns use to mean a word boundary, so {@code U.S.A.} becomes runs of one
     * and two letters and is left as it stands.
     *
     * <p>Nothing else needs a gate. No pattern matches a digit, so {@code 1999}
     * and {@code 24/7} come back whole without being tested for.
     */
    public List<String> syllables(String word) {
        Objects.requireNonNull(word, "word");
        List<String> pieces = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        int at = 0;
        while (at < word.length()) {
            if (!isWordMaterial(word.charAt(at))) {
                pending.append(word.charAt(at++));
                continue;
            }
            int from = at;
            while (at < word.length() && isWordMaterial(word.charAt(at))) {
                at++;
            }
            append(pieces, pending, split(word.substring(from, at)));
        }
        if (pending.length() > 0) {
            append(pieces, pending, List.of());
        }
        return pieces.isEmpty() ? List.of(word) : List.copyOf(pieces);
    }

    /** Whether this character is part of a word rather than something between two. */
    private boolean isWordMaterial(char c) {
        return Character.isLetter(c) || wordCharacters.contains(c);
    }

    /**
     * Adds one run's syllables, carrying whatever separated it from the last.
     *
     * <p>What is not word material rides the syllable before it, or the one after
     * it at the start of a token, so the pieces still concatenate to what came
     * in. A run too short to hold a break joins the piece before it rather than
     * standing alone: {@code Adirondack's} is sung on the syllables of
     * {@code Adirondack} with the inflection on the last, not with {@code 's} on
     * a note of its own.
     */
    private static void append(List<String> pieces, StringBuilder pending,
                               List<String> syllables) {
        String between = pending.toString();
        pending.setLength(0);
        if (syllables.isEmpty()) {
            if (pieces.isEmpty()) {
                pieces.add(between);
            } else {
                pieces.set(pieces.size() - 1, pieces.get(pieces.size() - 1) + between);
            }
            return;
        }
        List<String> run = new ArrayList<>(syllables);
        boolean joinsBack = !pieces.isEmpty()
                && (run.size() == 1 && run.get(0).length() < LEFT_MINIMUM + RIGHT_MINIMUM);
        if (joinsBack) {
            pieces.set(pieces.size() - 1,
                    pieces.get(pieces.size() - 1) + between + run.get(0));
            return;
        }
        if (!between.isEmpty()) {
            if (pieces.isEmpty()) {
                run.set(0, between + run.get(0));
            } else {
                pieces.set(pieces.size() - 1, pieces.get(pieces.size() - 1) + between);
            }
        }
        pieces.addAll(run);
    }

    /** One run of word material, split where the patterns say. */
    private List<String> split(String run) {
        if (run.length() < LEFT_MINIMUM + RIGHT_MINIMUM || firstLetter(run) < 0) {
            return List.of(run);
        }
        String lower = run.toLowerCase(Locale.ROOT);
        // One code point in Unicode lowercases to two -- Turkish dotted capital
        // I -- and the scores below are read at this run's own offsets, so a run
        // holding one would be cut at gaps belonging to other letters.
        if (lower.length() != run.length()) {
            return List.of(run);
        }

        // The run is scored with a boundary marker at each end, which is what the
        // leading and trailing "." in a pattern matches.
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
        // scores[i + 1] scores the gap after the i-th character of the run, the
        // offset coming from the boundary marker in front of it.
        for (int i = LEFT_MINIMUM; i <= run.length() - RIGHT_MINIMUM; i++) {
            if (scores[i + 1] % 2 == 1) {
                syllables.add(run.substring(start, i));
                start = i;
            }
        }
        syllables.add(run.substring(start));
        return syllables;
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
