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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads lyrics from an LRC file.
 *
 * <p>LRC has no standards body and no registered media type; it is a convention
 * that grew from a 1998 player, so this reads what the format is used for rather
 * than what any one document says. Two dialects matter here and both are
 * accepted: plain LRC times a whole line, and the "A2" extension times each word
 * inside it with {@code <mm:ss.xx>} tags.
 *
 * <p>Which one a file uses decides how much this can promise. Word tags are read
 * as given. A plain line has one timestamp and several words, so the words are
 * spread across it by {@link LyricWord#syllableEstimate()} — the same
 * apportioning that type exists for, and the reason it does not need a
 * pronunciation dictionary to be useful. A word placed that way is a guess
 * inside a known line, which is why the two dialects are given different
 * confidences rather than the same one.
 *
 * <p>Times are approximate either way and stay in seconds. Nothing here touches
 * the beat axis: putting a syllable on a beat needs the note it is sung on, and
 * that is #150.
 */
public final class LrcLyrics {

    /**
     * A word tag states the word's own start, so the only error is the file's.
     * Not {@link Confidence#CERTAIN}: that is reserved for a value read from a
     * source that cannot be wrong about it, and a hand-timed lyric file can be.
     */
    private static final Confidence TIMED_WORD = Confidence.of(0.9);

    /**
     * A word spread across a plain line by syllable count. The line's own timing
     * is as good as the file; where the word sits inside it is arithmetic.
     */
    private static final Confidence SPREAD_WORD = Confidence.of(0.5);

    /** How long a line is taken to last when the file gives nothing to learn from. */
    private static final double NOMINAL_LINE_SECONDS = 4.0;

    /** {@code [mm:ss.xx]}, also accepting {@code :} for the fraction and 1-3 digits. */
    private static final Pattern LINE_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    /** {@code <mm:ss.xx>}, the A2 word tag. */
    private static final Pattern WORD_TAG =
            Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");

    /** {@code [ti:...]} and friends. The key is non-numeric, which is what separates it. */
    private static final Pattern ID_TAG =
            Pattern.compile("\\[([a-zA-Z#][a-zA-Z0-9_#]*):(.*)]");

    private LrcLyrics() {
    }

    /**
     * Parses an LRC document.
     *
     * @param text            the file's contents
     * @param recordingSeconds how long the recording is, which bounds the last
     *                         line; a value that cannot be right — not positive,
     *                         or before that line starts — is ignored, because a
     *                         line ending before it began would not construct
     * @return the lyrics, empty when the file times nothing
     */
    public static Lyrics parse(String text, double recordingSeconds) {
        Objects.requireNonNull(text, "text");

        double offsetSeconds = 0;
        // A byte order mark is not whitespace, so strip() leaves it on the first
        // line and the tag there stops matching at position zero. The line is
        // then dropped in silence -- and if it was the [offset:] tag, the whole
        // lyric moves. Editors on Windows write this mark by default and LRC is
        // largely authored on them.
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<Timed> timed = new ArrayList<>();

        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Double offset = offsetOf(line);
            if (offset != null) {
                offsetSeconds = offset;
                continue;
            }
            // Every leading [mm:ss.xx] on one source line repeats the same text,
            // which is how an LRC writes a chorus without copying it out.
            Matcher tag = LINE_TAG.matcher(line);
            List<Double> starts = new ArrayList<>();
            int consumed = 0;
            // Spaces between the repeats are tolerated. Requiring them to abut
            // left the second tag inside the lyric text, so the sheet printed
            // the timestamp as though it were a word.
            while (tag.find(consumed) && line.substring(consumed, tag.start()).isBlank()) {
                starts.add(secondsOf(tag.group(1), tag.group(2), tag.group(3)));
                consumed = tag.end();
            }
            if (starts.isEmpty()) {
                continue;
            }
            String body = line.substring(consumed).strip();
            for (double start : starts) {
                timed.add(new Timed(start, body));
            }
        }

        if (timed.isEmpty()) {
            return Lyrics.empty();
        }
        // Sorted before the ends are read off, because a repeated chorus puts
        // its later timestamps on an earlier source line, and because a bare
        // timestamp only bounds the line before it once the two are in order.
        timed.sort((a, b) -> Double.compare(a.start(), b.start()));

        double shift = offsetSeconds;
        List<LyricLine> lines = new ArrayList<>();
        for (int i = 0; i < timed.size(); i++) {
            Timed entry = timed.get(i);
            if (entry.body().isBlank()) {
                // A timestamp with no text tells a player to clear the display.
                // It is not a line; it ends the one before it, which the lookup
                // below picks up because that end is simply the next timestamp.
                continue;
            }
            double start = Math.max(0, entry.start() - shift);
            double next = i + 1 < timed.size()
                    ? Math.max(start, timed.get(i + 1).start() - shift)
                    : Double.POSITIVE_INFINITY;
            double end = Math.min(next,
                    plausibleEnd(start, lastTagIn(entry.body(), shift),
                            typicalLine(timed), recordingSeconds));
            List<LyricWord> words = wordsOf(entry.body(), start, end, shift);
            if (!words.isEmpty()) {
                lines.add(new LyricLine(words, weakest(words)));
            }
        }
        if (lines.isEmpty()) {
            return Lyrics.empty();
        }
        // The weakest word, not an average: a file whose last line is untagged is
        // a file whose word timings cannot all be relied on, and saying so is the
        // point of carrying the number at all.
        Confidence overall = lines.stream()
                .map(LyricLine::confidence)
                .min(Confidence::compareTo)
                .orElse(Confidence.UNKNOWN);
        return new Lyrics(lines, "und", overall);
    }

    private static Confidence weakest(List<LyricWord> words) {
        return words.stream()
                .map(LyricWord::confidence)
                .min(Confidence::compareTo)
                .orElse(Confidence.UNKNOWN);
    }

    /**
     * The longest a line can plausibly last, which nothing in the file states.
     *
     * <p>An LRC times where each line <em>begins</em> and nothing else, so the
     * obvious reading — a line runs until the next one starts, and the last runs
     * to the end of the recording — makes every instrumental stretch part of the
     * line before it. That is harmless to a player, which only wants to know
     * what to display; it is wrong for anything asking which chords fall inside
     * a line, because the answer becomes all of them. A verse line before a solo
     * would carry the whole solo's harmony, and the closing line of a file
     * covering one verse would carry the rest of the song.
     *
     * <p>So a line lasts at most as long as a line typically does in this file,
     * measured from the last moment the line itself times, and never runs past
     * the recording. The caller takes the earlier of this and the next line's
     * start, so an ordinary line is still bounded by its successor and only an
     * implausibly long one is cut — leaving the remainder as the instrumental it
     * is.
     */
    private static double plausibleEnd(double start, double lastTag, double typicalLine,
                                       double recordingSeconds) {
        // Measured from the last thing the line itself times, not from its
        // start: a word tag is the file stating that the line was still being
        // sung then, so a nominal length counted from the start could end before
        // the line's own last word begins.
        double end = Math.max(start, lastTag) + typicalLine;
        boolean bounded = Double.isFinite(recordingSeconds) && recordingSeconds > start;
        return bounded ? Math.min(end, recordingSeconds) : end;
    }

    /** The last word tag in a line's text, or the line's start when it has none. */
    private static double lastTagIn(String body, double shift) {
        double last = Double.NEGATIVE_INFINITY;
        Matcher tag = WORD_TAG.matcher(body);
        while (tag.find()) {
            last = Math.max(last, Math.max(0,
                    secondsOf(tag.group(1), tag.group(2), tag.group(3)) - shift));
        }
        return last;
    }

    /**
     * How long a line lasts in this file: the median gap between timestamps.
     *
     * <p>The median rather than the mean, because an LRC's gaps include the
     * instrumental ones — the pause between verse and chorus is a gap like any
     * other — and one long break should not stretch every line's estimate.
     */
    private static double typicalLine(List<Timed> timed) {
        if (timed.size() < 2) {
            return NOMINAL_LINE_SECONDS;
        }
        double[] gaps = new double[timed.size() - 1];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = timed.get(i + 1).start() - timed.get(i).start();
        }
        java.util.Arrays.sort(gaps);
        double median = gaps[gaps.length / 2];
        return median > 0 ? median : NOMINAL_LINE_SECONDS;
    }

    /** A run of text with the time it starts at, and whether a tag said so. */
    private record Run(double start, boolean tagged, String text) {
    }

    /**
     * The words of one line, timed by their own tags where the file has them.
     *
     * <p>A file may tag some words and not others — text before the first tag is
     * the common case — so the line is cut into runs, each anchored either by the
     * tag that introduces it or, for a leading run, by the line's own start. A
     * partly-tagged line therefore degrades to the plain behaviour only for the
     * part that is untagged.
     */
    private static List<LyricWord> wordsOf(String body, double lineStart, double lineEnd,
                                           double shift) {
        List<Run> runs = new ArrayList<>();
        Matcher tag = WORD_TAG.matcher(body);
        int from = 0;
        double at = lineStart;
        boolean tagged = false;
        while (tag.find()) {
            String text = body.substring(from, tag.start());
            if (!text.isBlank()) {
                runs.add(new Run(at, tagged, text));
            }
            at = Math.max(0, secondsOf(tag.group(1), tag.group(2), tag.group(3)) - shift);
            tagged = true;
            from = tag.end();
        }
        String tail = body.substring(from);
        if (!tail.isBlank()) {
            runs.add(new Run(at, tagged, tail));
        }

        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            double end = i + 1 < runs.size() ? runs.get(i + 1).start() : lineEnd;
            words.addAll(spread(run.text(), run.start(), Math.max(run.start(), end),
                    run.tagged() ? TIMED_WORD : SPREAD_WORD));
        }
        return words;
    }

    /** Splits a run of text into words and shares its span out by syllable count. */
    private static List<LyricWord> spread(String chunk, double start, double end,
                                          Confidence confidence) {
        List<String> tokens = new ArrayList<>();
        for (String token : chunk.strip().split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        List<LyricWord> words = new ArrayList<>(tokens.size());
        if (tokens.isEmpty()) {
            return words;
        }
        if (tokens.size() == 1) {
            words.add(LyricWord.ofSeconds(tokens.get(0), start, end, confidence));
            return words;
        }
        int[] weights = new int[tokens.size()];
        int total = 0;
        for (int i = 0; i < tokens.size(); i++) {
            weights[i] = LyricWord.ofSeconds(tokens.get(i), 0, 0, confidence).syllableEstimate();
            total += weights[i];
        }
        double at = start;
        int carried = 0;
        for (int i = 0; i < tokens.size(); i++) {
            carried += weights[i];
            // Accumulated from the line's own ends rather than stepped, so the
            // last word finishes exactly at the line's end however the weights
            // divide.
            double to = i == tokens.size() - 1 ? end : start + (end - start) * carried / total;
            words.add(LyricWord.ofSeconds(tokens.get(i), at, Math.max(at, to), confidence));
            at = Math.max(at, to);
        }
        return words;
    }

    /**
     * The {@code [offset:]} tag, in seconds, or null when this is not one.
     *
     * <p>Milliseconds in the file, and a positive value means the lyrics should
     * show <em>sooner</em> — so it is subtracted from every timestamp. The tag is
     * one of the format's genuinely ambiguous corners and players disagree about
     * its sign; this follows the reading the tag's own documentation gives.
     */
    private static Double offsetOf(String line) {
        Matcher id = ID_TAG.matcher(line);
        if (!id.matches() || !id.group(1).equalsIgnoreCase("offset")) {
            return null;
        }
        try {
            double offset = Double.parseDouble(id.group(2).strip().replace("+", "")) / 1000.0;
            // A finite shift or none. Double.parseDouble accepts "NaN" and
            // "-Infinity", and an overlong decimal overflows to one, and a
            // non-finite shift reaches LyricWord's constructor, which rejects it
            // -- out of a public parser, past the caller's read guard, after the
            // analysis it was decorating had already succeeded.
            return Double.isFinite(offset) ? offset : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double secondsOf(String minutes, String seconds, String fraction) {
        double value = Integer.parseInt(minutes) * 60 + Integer.parseInt(seconds);
        if (fraction != null && !fraction.isEmpty()) {
            value += Integer.parseInt(fraction) / Math.pow(10, fraction.length());
        }
        return value;
    }

    /** One timestamped source line before its end is known. */
    private record Timed(double start, String body) {
    }

    /**
     * Whether the document carries a timestamp at all.
     *
     * <p>Used to tell "this is not an LRC file" from "this LRC file has no
     * lyrics in it", which are different mistakes and want different advice.
     */
    public static boolean looksLikeLrc(String text) {
        return LINE_TAG.matcher(Objects.requireNonNull(text, "text")).find();
    }
}
