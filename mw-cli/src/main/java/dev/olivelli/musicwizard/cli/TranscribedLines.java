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

package dev.olivelli.musicwizard.cli;

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Transcribed words into the lines a lyric sheet is made of.
 *
 * <p>An LRC file states its line breaks; a transcription has only the singing,
 * so the breaks are read from it the way a listener hears them — a pause. A
 * gap of {@link #LINE_GAP_SECONDS} or more starts a new line; segment
 * boundaries arrive as gaps too, since segments are separated by at least
 * that much silence.
 *
 * <p>Line and sheet confidence are the minimum over what they contain, the
 * same floor-not-average the aligner reports: one guessed word in a line
 * makes the line a guess.
 */
final class TranscribedLines {

    static final double LINE_GAP_SECONDS = 1.0;

    private TranscribedLines() {
    }

    /** Words (in time order) as lyrics, or empty lyrics when there are none. */
    static Lyrics grouped(List<LyricWord> words, String language) {
        List<LyricLine> lines = new ArrayList<>();
        List<LyricWord> current = new ArrayList<>();
        for (LyricWord word : words) {
            if (!current.isEmpty()
                    && word.startSeconds() - current.getLast().endSeconds()
                            >= LINE_GAP_SECONDS) {
                lines.add(line(current));
                current = new ArrayList<>();
            }
            current.add(word);
        }
        if (!current.isEmpty()) {
            lines.add(line(current));
        }
        Confidence overall = lines.stream()
                .map(LyricLine::confidence)
                .min(Comparator.comparingDouble(Confidence::value))
                .orElse(Confidence.of(0));
        return new Lyrics(List.copyOf(lines), language, overall);
    }

    private static LyricLine line(List<LyricWord> words) {
        Confidence confidence = words.stream()
                .map(LyricWord::confidence)
                .min(Comparator.comparingDouble(Confidence::value))
                .orElseThrow();
        return new LyricLine(List.copyOf(words), confidence);
    }
}
