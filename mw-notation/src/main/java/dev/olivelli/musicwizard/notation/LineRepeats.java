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
import java.util.Objects;
import java.util.Optional;

/**
 * Which of a chord chart's printed lines are printed more than once.
 *
 * <p>The input is the lines themselves, as {@link ChordChart#linesOf} renders
 * them, and identity is string equality on them. That is the whole of the rule,
 * and it is what makes every tag something a reader can check on the page: two
 * lines carrying the same tag are the same run of characters. Deriving the tag
 * instead from the chords behind the line -- which is what a first version of
 * this did -- lets the page contradict itself, because the chart prints only
 * the cells {@link ChartLayout.Cell#named()} marks, so a mid-bar change it
 * cannot show can make two identical-looking lines differ underneath. What the
 * line does not print is not read here either: two lines of the same chords in
 * different meters are tagged alike, because the chart states one meter for the
 * whole of itself (#191).
 *
 * <p><b>A tag is a claim about its own line and nothing further.</b> Every
 * occurrence carries it, and none of them says a section starts, ends, or runs
 * on: "Section A" printed once over a returning chorus is a claim about every
 * bar up to the next such heading, and nothing looked at the bars in between
 * (#218). What the estimate supports is smaller and is stated instead -- this
 * line is printed elsewhere too.
 *
 * <p><b>Exact match only.</b> A real recording's chord estimate is noisy bar to
 * bar, and the chart's bar axis is one constant bar length over a recording
 * that does not hold one (#187), so a returning chorus often prints differently
 * enough not to match. Nothing is tagged then, which is the failure worth
 * having: a chart that says nothing about its structure asks a reader to find
 * it, and one that says the wrong thing asks them to un-learn it. A tolerant
 * match would find more repeats and would also stitch two different sections
 * together on a coincidence.
 */
final class LineRepeats {

    private LineRepeats() {
    }

    /**
     * One tag per line, in the order given, {@link Optional#empty()} for a line
     * that appears only once.
     *
     * <p>Tags are assigned in first-appearance order -- {@code A}, {@code B},
     * and past {@code Z} the way a spreadsheet names its columns -- so a
     * returning line's tag reappears verbatim however far away it fell.
     */
    static List<Optional<String>> tagsOf(List<String> printedLines) {
        Objects.requireNonNull(printedLines, "printedLines");

        Map<String, Integer> occurrences = new HashMap<>();
        for (String line : printedLines) {
            occurrences.merge(line, 1, Integer::sum);
        }

        // Insertion order is first-appearance order, which is what makes the
        // tags count up the way a reader meets them rather than in hash order.
        Map<String, String> tagOf = new LinkedHashMap<>();
        List<Optional<String>> tags = new ArrayList<>(printedLines.size());
        for (String line : printedLines) {
            tags.add(occurrences.get(line) < 2
                    ? Optional.empty()
                    // computeIfAbsent reads the size before its own insert, so
                    // the index is the number of tags already handed out.
                    : Optional.of(tagOf.computeIfAbsent(line, l -> tag(tagOf.size()))));
        }
        return tags;
    }

    /**
     * The tag at zero-based {@code index}: {@code A}, {@code B}, ..., {@code Z},
     * {@code AA}, {@code AB} -- bijective base 26, so a chart with more than
     * twenty-six distinct repeated lines still gets a distinct tag for each
     * rather than running out or colliding.
     */
    private static String tag(int index) {
        StringBuilder letters = new StringBuilder();
        int n = index;
        do {
            letters.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return letters.toString();
    }
}
