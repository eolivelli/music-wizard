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

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * <p>Musical timing is a span, not a point, and follows the same all-or-nothing
 * rule as {@link Note} and {@link Chord}. It carries an end as well as a start
 * because the alternative — reading a syllable's extent off the next syllable's
 * start — is only sound if the words are ordered, all snapped, and none share a
 * beat, and nothing can promise all three: stages snap words a segment at a
 * time, and the last syllable of a line has no successor at all. A melisma's
 * extent is the one measurement the notation stage cannot do without, so it is
 * recorded where it is decided.
 *
 * <p>{@code endBeat} is the offset of a half-open span, the same as everywhere
 * else in the model, so it feeds {@link NoteTrack#notesBetweenBeats} directly
 * and returns exactly the notes the syllable is sung on. A syllable on a single
 * note is not zero-length: it lasts that note.
 *
 * @param text              the word, or one syllable of it, as written
 * @param startSeconds      approximate start
 * @param endSeconds        approximate end
 * @param startBeat         beat-snapped start, once decided
 * @param endBeat           beat-snapped end, once decided
 * @param hyphenatedToNext  true when this syllable joins the next one with a hyphen
 * @param melisma           true when this syllable is sung over more than one note
 * @param confidence        how much the pipeline trusts this word
 */
public record LyricWord(
        String text,
        double startSeconds,
        double endSeconds,
        Optional<Double> startBeat,
        Optional<Double> endBeat,
        boolean hyphenatedToNext,
        boolean melisma,
        Confidence confidence) {

    public LyricWord {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(startBeat, "startBeat");
        Objects.requireNonNull(endBeat, "endBeat");
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
        // Checked here rather than only in snappedTo, because deserialization
        // and direct construction both bypass the factory methods. Without the
        // finiteness check a NaN beat reaches the score file, where Jackson
        // writes it as the string "NaN" -- which is not a JSON number, so the
        // file stops being readable by anything stricter than Jackson.
        if (startBeat.isPresent() != endBeat.isPresent()) {
            throw new IllegalArgumentException(
                    "a lyric word must carry both startBeat and endBeat or neither");
        }
        if (startBeat.isPresent()) {
            double from = startBeat.get();
            double to = endBeat.get();
            if (!Double.isFinite(from) || from < 0) {
                throw new IllegalArgumentException("startBeat must be finite and non-negative, got: " + from);
            }
            // After, not merely not-before, and for the same reason as Chord and
            // Section: endBeat is the offset of a half-open span, so a syllable
            // with equal ends covers no notes at all. A syllable sung on one note
            // is not zero-length -- it lasts that note -- and letting the two look
            // alike would make a one-note syllable and a mistake indistinguishable
            // to NoteTrack.notesBetweenBeats, which is the consumer this span
            // exists for.
            if (!Double.isFinite(to) || to <= from) {
                throw new IllegalArgumentException(
                        "endBeat must be finite and after startBeat; got start=" + from + " end=" + to);
            }
        }
    }

    /** A plain word as recognition first produces it: no beat, no engraving marks. */
    public static LyricWord ofSeconds(String text, double startSeconds, double endSeconds,
                                      Confidence confidence) {
        return new LyricWord(text, startSeconds, endSeconds, Optional.empty(), Optional.empty(),
                false, false, confidence);
    }

    /** True once this word carries beat-snapped musical timing. */
    @JsonIgnore
    public boolean isQuantized() {
        return startBeat.isPresent() && endBeat.isPresent();
    }

    /** How long the syllable is held in quarter-note beats, once snapped. */
    public Optional<Double> durationBeats() {
        return startBeat.map(from -> endBeat.orElseThrow() - from);
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

    /**
     * Returns a copy snapped to a beat span.
     *
     * <p>Takes both ends rather than just the start: a syllable's extent is what
     * tells the notation stage how many notes a melisma covers, and the aligner
     * that snaps the start is the only stage that still knows it.
     *
     * <p>The beat span must be non-empty even though the seconds span may be, so
     * a word whose own {@code startSeconds} and {@code endSeconds} are equal
     * cannot simply have both ends converted: snap it to the note it is sung on.
     * A syllable always occupies at least one note, whatever the recognizer's
     * timing says.
     */
    public LyricWord snappedTo(double newStartBeat, double newEndBeat) {
        if (!Double.isFinite(newStartBeat) || newStartBeat < 0) {
            throw new IllegalArgumentException(
                    "startBeat must be finite and non-negative, got: " + newStartBeat);
        }
        if (!Double.isFinite(newEndBeat) || newEndBeat <= newStartBeat) {
            throw new IllegalArgumentException(
                    "endBeat must be finite and after startBeat; got start=" + newStartBeat
                            + " end=" + newEndBeat);
        }
        return new LyricWord(text, startSeconds, endSeconds,
                Optional.of(newStartBeat), Optional.of(newEndBeat),
                hyphenatedToNext, melisma, confidence);
    }

    /** Returns a copy with corrected text, keeping all timing. */
    public LyricWord withText(String newText) {
        return new LyricWord(newText, startSeconds, endSeconds, startBeat, endBeat,
                hyphenatedToNext, melisma, confidence);
    }

    /** Returns a copy that joins the following syllable with a hyphen, or stops doing so. */
    public LyricWord withHyphenToNext(boolean hyphenated) {
        return new LyricWord(text, startSeconds, endSeconds, startBeat, endBeat,
                hyphenated, melisma, confidence);
    }

    /** Returns a copy marked, or unmarked, as sung over more than one note. */
    public LyricWord withMelisma(boolean sungAsMelisma) {
        return new LyricWord(text, startSeconds, endSeconds, startBeat, endBeat,
                hyphenatedToNext, sungAsMelisma, confidence);
    }
}
