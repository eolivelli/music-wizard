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

import java.util.Objects;
import java.util.Optional;

/**
 * One sung word with its timing.
 *
 * <p>Word timings are approximate. Speech recognition on singing gives segment
 * timings rather than per-word ones, so words are distributed within a segment
 * and then snapped to the beat grid. That is good enough for a chart, which
 * only needs to know which beat a word lands on, and this type deliberately
 * does not pretend to more precision than that.
 *
 * <p>The two engraving flags are what a lyric line needs beyond its text.
 * LilyPond writes a word split across notes as {@code Hal -- le -- lu -- jah},
 * and a syllable held over several notes as {@code jah __}; neither mark can be
 * inferred from the text, and neither can be recovered from a link to the note a
 * syllable starts on. Splitting a word into syllables needs pronunciation, and
 * whether a held syllable is a melisma or two repeated notes is a musical
 * decision, so both are recorded here by whichever stage makes them rather than
 * re-derived downstream. A word that was never split simply leaves both false.
 *
 * @param text              the word, or one syllable of it, as written
 * @param startSeconds      approximate start
 * @param endSeconds        approximate end
 * @param startBeat         beat-snapped start, once decided
 * @param hyphenatedToNext  true when this syllable joins the next one with a hyphen
 * @param melisma           true when this syllable is sung over more than one note
 * @param confidence        how much the pipeline trusts this word
 */
public record LyricWord(
        String text,
        double startSeconds,
        double endSeconds,
        Optional<Double> startBeat,
        boolean hyphenatedToNext,
        boolean melisma,
        Confidence confidence) {

    public LyricWord {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(startBeat, "startBeat");
        Objects.requireNonNull(confidence, "confidence");
        if (text.isBlank()) {
            throw new IllegalArgumentException("lyric word must not be blank");
        }
        if (!Double.isFinite(startSeconds) || startSeconds < 0) {
            throw new IllegalArgumentException("startSeconds must be finite and non-negative, got: " + startSeconds);
        }
        if (!Double.isFinite(endSeconds) || endSeconds < startSeconds) {
            throw new IllegalArgumentException(
                    "endSeconds must be finite and not before startSeconds; got start=" + startSeconds
                            + " end=" + endSeconds);
        }
    }

    /** A plain word as recognition first produces it: no beat, no engraving marks. */
    public static LyricWord ofSeconds(String text, double startSeconds, double endSeconds,
                                      Confidence confidence) {
        return new LyricWord(text, startSeconds, endSeconds, Optional.empty(),
                false, false, confidence);
    }

    /**
     * Rough syllable count, used to distribute words across a recognition
     * segment. Counts vowel groups, which is crude but adequate for
     * apportioning time and keeps the model free of a pronunciation dictionary.
     */
    public int syllableEstimate() {
        String lower = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        if (lower.isEmpty()) {
            return 1;
        }
        int count = 0;
        boolean previousWasVowel = false;
        for (int i = 0; i < lower.length(); i++) {
            boolean isVowel = "aeiouy".indexOf(lower.charAt(i)) >= 0;
            if (isVowel && !previousWasVowel) {
                count++;
            }
            previousWasVowel = isVowel;
        }
        // A trailing silent "e" does not usually carry a syllable of its own.
        if (lower.endsWith("e") && count > 1) {
            count--;
        }
        return Math.max(1, count);
    }

    /** Returns a copy snapped to a beat position. */
    public LyricWord snappedTo(double beat) {
        return new LyricWord(text, startSeconds, endSeconds, Optional.of(beat),
                hyphenatedToNext, melisma, confidence);
    }

    /** Returns a copy with corrected text, keeping all timing. */
    public LyricWord withText(String newText) {
        return new LyricWord(newText, startSeconds, endSeconds, startBeat,
                hyphenatedToNext, melisma, confidence);
    }

    /** Returns a copy that joins the following syllable with a hyphen, or stops doing so. */
    public LyricWord withHyphenToNext(boolean hyphenated) {
        return new LyricWord(text, startSeconds, endSeconds, startBeat,
                hyphenated, melisma, confidence);
    }

    /** Returns a copy marked, or unmarked, as sung over more than one note. */
    public LyricWord withMelisma(boolean sungAsMelisma) {
        return new LyricWord(text, startSeconds, endSeconds, startBeat,
                hyphenatedToNext, sungAsMelisma, confidence);
    }
}
