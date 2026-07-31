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

package dev.olivelli.musicwizard.notation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Where a chord chart repeats itself, at the granularity a reader already
 * reads it in: {@link ChordChart}'s own four-bar line.
 *
 * <p>This does not know "chorus" from "verse" -- nothing downstream of the
 * chord estimate does, since neither lyrics nor a listen is available to it
 * (#218). What it knows is exact repetition: two lines whose bars hold the
 * same chord symbols, in the same order, are the same section, and get the
 * same label. A line that never recurs gets none, on purpose -- printing a
 * label for every line would be the chatter #212 already flagged for chords,
 * arriving by a different door, and a label on a line that is not actually
 * evidence of structure is a confidence the estimate has not earned.
 *
 * <p><b>Exact match only.</b> A real recording's chord estimate is noisy
 * bar to bar -- the same chorus can print {@code Em C} the first time and
 * {@code Em C D} the second, one passing chord the estimator heard once and
 * not the other time -- and exact matching misses that repeat entirely. That
 * is a real and known limitation, not an oversight: a fuzzy match would find
 * more repeats but would also risk stitching two different sections together
 * on a coincidence, which is the worse of the two mistakes on a chart nobody
 * has proof-read yet. See #218 for the fuzzy-match alternative, left for a
 * follow-up once there is a recording to measure it against.
 */
final class SectionLayout {

    private SectionLayout() {
    }

    /**
     * One label per line of {@code barsPerLine} bars -- {@link Optional#empty()}
     * where that line does not open a labelled section, either because its
     * content never recurs elsewhere in the chart or because it is a plain
     * continuation of the section the previous line already opened.
     *
     * <p>Labels are assigned in the order their content is first seen --
     * {@code Section A}, {@code Section B}, and onward past {@code Z} the way
     * a spreadsheet names columns -- and the same content always gets the same
     * label, however far apart its occurrences are. That is what lets a reader
     * spot a returning chorus: its label reappears verbatim.
     *
     * @param bars        the chart's bars, in order, as {@link ChartLayout}
     *                    decided them
     * @param barsPerLine bars in one printed line; the block a repeat is
     *                    measured over
     * @return one entry per line, {@code ceil(bars.size() / barsPerLine)} long
     */
    static List<Optional<String>> labelsPerLine(List<ChartLayout.Bar> bars, int barsPerLine) {
        if (barsPerLine <= 0) {
            throw new IllegalArgumentException("barsPerLine must be positive: " + barsPerLine);
        }
        List<String> signatures = lineSignatures(bars, barsPerLine);

        Map<String, Integer> occurrences = new HashMap<>();
        for (String signature : signatures) {
            occurrences.merge(signature, 1, Integer::sum);
        }

        // Insertion order is first-appearance order, which is what makes the
        // labels count up the way a reader meets them rather than in some
        // internal hash order.
        Map<String, String> labelOf = new LinkedHashMap<>();
        List<Optional<String>> result = new ArrayList<>(signatures.size());
        String previousLabel = null;
        for (String signature : signatures) {
            if (occurrences.get(signature) < 2) {
                result.add(Optional.empty());
                previousLabel = null;
                continue;
            }
            String label = labelOf.computeIfAbsent(signature, s -> columnLabel(labelOf.size()));
            // Consecutive lines of identical content are one continuing
            // section, not a second occurrence of it -- so the label is only
            // printed where it changes, exactly like the chord chart's own "%"
            // continuation marker one level up.
            result.add(label.equals(previousLabel) ? Optional.empty() : Optional.of(label));
            previousLabel = label;
        }
        return result;
    }

    /**
     * The chart's harmonic content, one string per line, independent of how
     * {@link ChartLayout} decided to print it.
     *
     * <p>Built from every cell's symbol, not only the ones {@link
     * ChartLayout.Cell#named()} marks for printing. {@code named} answers
     * "does this bar's own {@code %} rule say to name this cell", which
     * depends on whatever chord came immediately before it -- so two
     * harmonically identical lines could disagree on which of their cells are
     * named, purely because of what precedes each one, and a signature built
     * from names alone would call them different lines. The chord symbols
     * themselves carry no such dependency.
     */
    private static List<String> lineSignatures(List<ChartLayout.Bar> bars, int barsPerLine) {
        List<String> signatures = new ArrayList<>();
        for (int start = 0; start < bars.size(); start += barsPerLine) {
            int end = Math.min(start + barsPerLine, bars.size());
            signatures.add(bars.subList(start, end).stream()
                    .map(SectionLayout::barSignature)
                    .collect(Collectors.joining("|")));
        }
        return signatures;
    }

    private static String barSignature(ChartLayout.Bar bar) {
        return bar.cells().stream()
                .map(ChartLayout.Cell::symbol)
                .collect(Collectors.joining(" "));
    }

    /**
     * The label at zero-based {@code index}: {@code A}, {@code B}, ...,
     * {@code Z}, {@code AA}, {@code AB}, and onward -- the same bijective
     * base-26 a spreadsheet names its columns with, so a chart with more than
     * twenty-six distinct repeated sections still gets a distinct label
     * instead of running out or colliding.
     */
    private static String columnLabel(int index) {
        StringBuilder letters = new StringBuilder();
        int n = index;
        do {
            letters.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return "Section " + letters;
    }
}
