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
     * <p>Two, not one, and what it still decides is a stranded final <b>vowel</b>:
     * <i>acaci-a</i>, <i>Abyssini-a</i>. A stranded final consonant was its
     * original reason and is no longer its doing — {@link #joinUnsung} rejoins
     * <i>abandon-s</i> and <i>gol-f</i> whatever this is set to, having a vowel
     * to test that a letter count does not.
     *
     * <p>So the asymmetry with {@link #LEFT_MINIMUM} now rests on the narrower
     * fact that a vowel opens a syllable alone more readily than it closes one.
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
     * <p>A full stop is what the patterns mean by a word boundary, so one sitting
     * between letters would cut an abbreviation into its initials; {@code U.S.A.}
     * is left as it stands. One after the letters is ordinary punctuation, so
     * {@code amore.} splits.
     *
     * <p>Nothing else needs a gate. No pattern matches a digit, so {@code 1999}
     * and {@code 24/7} come back whole without being tested for.
     */
    public List<Syllable> syllables(String word) {
        Objects.requireNonNull(word, "word");
        if (hasInternalStop(word)) {
            return List.of(new Syllable(word, false));
        }
        List<Syllable> pieces = new ArrayList<>();
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
        return pieces.isEmpty() ? List.of(new Syllable(word, false)) : List.copyOf(pieces);
    }

    /**
     * Whether a full stop sits between two letters, as in an abbreviation.
     *
     * <p>The stop is what the patterns mean by a word boundary, so it separates
     * runs like any other non-word character — which would cut {@code U.S.A.}
     * into its initials and sing them as two. A stop after the letters is
     * ordinary punctuation and rides the last syllable, so {@code amore.} splits
     * as it should.
     */
    private static boolean hasInternalStop(String word) {
        int first = firstLetter(word);
        int last = lastLetter(word);
        for (int i = first + 1; i < last; i++) {
            if (word.charAt(i) == '.') {
                return true;
            }
        }
        return false;
    }

    /** Whether this character is part of a word rather than something between two. */
    private boolean isWordMaterial(char c) {
        return Character.isLetter(c) || wordCharacters.contains(c);
    }

    /**
     * Adds one run's syllables, carrying whatever separated it from the last.
     *
     * <p>What is not word material rides the syllable beside it, so the pieces
     * still concatenate to what came in, and <b>it is carried without a hyphen</b>
     * — a compound already prints the one it was written with, and drawing
     * another gives {@code well--known}.
     *
     * <p><b>A run with no vowel is not a syllable</b> and joins its neighbour: it
     * is an inflection or a bare consonant, and {@code Adirondack's} is sung on
     * the syllables of {@code Adirondack} rather than with {@code 's} on a note of
     * its own. A short run that <em>has</em> one is a syllable and keeps its note,
     * which is what {@code sha-la-la} is made of. Backwards where there is
     * something to join, and otherwise held over for the run that follows, so
     * {@code y'all} is one note and not two.
     *
     * <p>The same rule runs over the pieces of a single run — see
     * {@link #joinUnsung}, which is where {@code s-ing} is put back together.
     */
    private static void append(List<Syllable> pieces, StringBuilder pending,
                               List<String> syllables) {
        String between = pending.toString();
        pending.setLength(0);
        boolean joinsBack = !pieces.isEmpty()
                && (syllables.isEmpty() || (syllables.size() == 1 && !hasVowel(syllables.get(0), true)));
        if (joinsBack) {
            int last = pieces.size() - 1;
            String tail = syllables.isEmpty() ? "" : syllables.get(0);
            pieces.set(last, new Syllable(pieces.get(last).text() + between + tail,
                    pieces.get(last).hyphenToNext()));
            return;
        }
        if (syllables.isEmpty() || (pieces.isEmpty() && syllables.size() == 1
                && !hasVowel(syllables.get(0), true))) {
            // Nothing to join backwards to: hold it for the run after this one.
            pending.append(between).append(syllables.isEmpty() ? "" : syllables.get(0));
            return;
        }
        if (!between.isEmpty()) {
            // Backwards where there is something to carry it, so a compound reads
            // "well- known" the way a lead sheet writes it rather than "well
            // -known"; forwards only at the start of a token, where there is
            // nothing behind.
            if (pieces.isEmpty()) {
                syllables = new ArrayList<>(syllables);
                syllables.set(0, between + syllables.get(0));
            } else {
                int last = pieces.size() - 1;
                pieces.set(last, new Syllable(pieces.get(last).text() + between,
                        pieces.get(last).hyphenToNext()));
            }
        }
        for (int i = 0; i < syllables.size(); i++) {
            // Only a break this engine chose is drawn as a hyphen. The last
            // syllable of a run ends at a separator the token already carries.
            pieces.add(new Syllable(syllables.get(i), i + 1 < syllables.size()));
        }
    }

    /**
     * Whether this text holds a sound a syllable can be built on.
     *
     * <p><b>{@code y} counts, except as a word's first letter.</b> The letter
     * spells two sounds and where it stands is what tells them apart: opening a
     * word it is the consonant, so {@code y'all} and {@code York} are sung on one
     * note, and anywhere else it is the vowel, so {@code lone-ly}, {@code by-pass}
     * and {@code sky-high} are sung on two. A rule that ignores the position
     * loses one group or the other.
     *
     * <p>Both languages, because word-initial {@code y} reaches Italian only in
     * loanwords — <i>yogurt</i>, <i>yoga</i>, <i>yacht</i> — where it is the
     * consonant, and Italian lyrics borrow English words freely. Exempting
     * Italian puts a bare {@code y} on a note of its own in <i>you</i>,
     * <i>your</i> and <i>young</i>, 43 words against the one it buys back.
     *
     * <p><b>The word, not the piece.</b> A piece the patterns cut is a syllable,
     * and a syllable's own {@code y} is the vowel wherever the syllable falls:
     * anchoring on the piece calls the {@code y} in {@code lar-ynx} a consonant
     * and joins the note away.
     *
     * <p>Position is not the whole answer and does not claim to be: a few words
     * open on the vowel — {@code yt-tri-um}, Italian {@code yp-si-lon} — and this
     * joins each into one note fewer. Telling those from {@code York} wants a
     * pronunciation dictionary, which is #332's own conclusion.
     *
     * <p>Any letter above U+007F counts as a vowel. Both languages are written in
     * the Latin alphabet, so what that reaches is an accented letter, and Italian
     * accents vowels.
     */
    private static boolean hasVowel(String text, boolean atWordStart) {
        int consonantY = atWordStart ? firstLetter(text) : -1;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if ("aeiou".indexOf(c) >= 0 || (Character.isLetter(c) && c > 127)) {
                return true;
            }
            if (c == 'y' && i != consonantY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Joins any piece with no vowel to the one it is sung with.
     *
     * <p>The patterns score a break by where a typesetter may end a line, and a
     * line may end after a letter that is not a syllable: English gives
     * {@code s-ing}, {@code n-ev-er}, {@code Bis-mar-ck} and {@code Am-s-ter-dam}.
     * The rule that fixes them is the one {@link #append} already applies between
     * runs — a piece with no vowel is not a syllable — and applying it here is why
     * neither minimum has to move. Raising {@link #LEFT_MINIMUM} to the two
     * typesetting wants would take {@code s-ing} and {@code a-long} together, and
     * the right minimum of three that TeX's English asks for would take
     * {@code Amer-i-c-as} and {@code hap-py} together.
     *
     * <p>Forwards, because a consonant with no vowel of its own is the head of the
     * syllable after it: {@code Am-ster-dam}, not {@code Ams-ter-dam}. A last
     * piece has nothing to head and joins the one before.
     *
     * <p>The test is on <b>what has been held so far</b> rather than on the piece
     * alone, which is not the same answer and is the better one. The patterns cut
     * {@code gynecology} into {@code g} and {@code y} before {@code ne}: asked
     * separately neither is a vowel, and the word loses a note; asked together
     * {@code gy} is one, because the {@code y} is no longer the word's first
     * letter.
     */
    private static List<String> joinUnsung(List<String> pieces) {
        if (pieces.size() < 2) {
            return pieces;
        }
        List<String> sung = new ArrayList<>(pieces.size());
        StringBuilder held = new StringBuilder();
        for (String piece : pieces) {
            held.append(piece);
            if (hasVowel(held.toString(), sung.isEmpty())) {
                sung.add(held.toString());
                held.setLength(0);
            }
        }
        if (held.length() == 0) {
            return sung;
        }
        if (sung.isEmpty()) {
            return List.of(held.toString());
        }
        int last = sung.size() - 1;
        sung.set(last, sung.get(last) + held);
        return sung;
    }

    /** Where the run's letters begin, or -1 when it has none. */
    private static int firstLetter(String run) {
        for (int i = 0; i < run.length(); i++) {
            if (Character.isLetter(run.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Where the run's letters end, or -1 when it has none. */
    private static int lastLetter(String run) {
        for (int i = run.length() - 1; i >= 0; i--) {
            if (Character.isLetter(run.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** One run of word material, split where the patterns say. */
    private List<String> split(String run) {
        int first = firstLetter(run);
        int last = lastLetter(run);
        if (first < 0 || last - first + 1 < LEFT_MINIMUM + RIGHT_MINIMUM) {
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
        // Bounded by the run's letters rather than its length: an apostrophe is
        // word material in Italian, and counted against the minima it fills the
        // slot RIGHT_MINIMUM reserves -- elf' would break as el-f'.
        // scores[i + 1] scores the gap in front of the run's i-th character, the
        // offset coming from the boundary marker before it; a break at i puts
        // that character at the head of the next syllable.
        for (int i = first + LEFT_MINIMUM; i <= last + 1 - RIGHT_MINIMUM; i++) {
            if (scores[i + 1] % 2 == 1) {
                syllables.add(run.substring(start, i));
                start = i;
            }
        }
        syllables.add(run.substring(start));
        return joinUnsung(syllables);
    }

    /** How many syllables this word is sung on. */
    public int syllableCount(String word) {
        return syllables(word).size();
    }

    /**
     * One syllable, and whether a hyphen joins it to the next.
     *
     * <p>The flag is not "there is another syllable": a compound carries its own
     * hyphen inside the text, and drawing a second one gives {@code well--known}.
     * Only a break this engine chose is marked.
     */
    public record Syllable(String text, boolean hyphenToNext) {

        public Syllable {
            Objects.requireNonNull(text, "text");
        }
    }

}
