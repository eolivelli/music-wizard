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
 * so the breaks are read from it the way a listener hears them — a pause.
 * Every sung stretch starts a new line, because the stretches are cut at the
 * silences between phrases; on the first real recording through this path a
 * gap rule alone merged whole verses into one line, the stretch padding
 * having shrunk each pause below any workable threshold. Within a stretch, a
 * gap of {@link #LINE_GAP_SECONDS} or more breaks again — which the shipped
 * provider can never trigger, since it spreads words contiguously across the
 * stretch; the rule is here for a provider whose words carry measured gaps,
 * which the SPI allows.
 *
 * <p>Line and sheet confidence are the minimum over what they contain, the
 * same floor-not-average the aligner reports: one guessed word in a line
 * makes the line a guess.
 */
final class TranscribedLines {

    static final double LINE_GAP_SECONDS = 1.0;

    private TranscribedLines() {
    }

    /** One list of words per sung stretch, as lyrics; empty when none sang. */
    static Lyrics grouped(List<List<LyricWord>> stretches, String language) {
        List<LyricLine> lines = new ArrayList<>();
        for (List<LyricWord> words : stretches) {
            List<LyricWord> current = new ArrayList<>();
            for (LyricWord word : words) {
                if (!current.isEmpty()
                        && word.startSeconds() - current.getLast().endSeconds()
                                >= LINE_GAP_SECONDS) {
                    addMonotone(lines, current);
                    current = new ArrayList<>();
                }
                current.add(word);
            }
            if (!current.isEmpty()) {
                addMonotone(lines, current);
            }
        }
        Confidence overall = lines.stream()
                .map(LyricLine::confidence)
                .min(Comparator.comparingDouble(Confidence::value))
                .orElse(Confidence.of(0));
        return new Lyrics(List.copyOf(lines), language, overall);
    }

    /**
     * Adds the line, shifted whole to start no earlier than the previous line
     * ended. Stretches cut from one long run share a boundary that two float
     * routes place apart by an ulp, and the sheet's chord cursor depends on
     * lines that never overlap. {@link AnalyzeCommand#shiftedAfter} is the
     * enforcement the aligned path already wears at its own assembly point;
     * a first draft here clamped only the first word instead, which the
     * line's own start-order sorting turned into reordered words.
     */
    private static void addMonotone(List<LyricLine> lines, List<LyricWord> words) {
        double previousEnd = lines.isEmpty() ? 0
                : lines.getLast().endSeconds();
        lines.add(AnalyzeCommand.shiftedAfter(line(words), previousEnd));
    }

    private static LyricLine line(List<LyricWord> words) {
        Confidence confidence = words.stream()
                .map(LyricWord::confidence)
                .min(Comparator.comparingDouble(Confidence::value))
                .orElseThrow();
        return new LyricLine(List.copyOf(words), confidence);
    }
}
