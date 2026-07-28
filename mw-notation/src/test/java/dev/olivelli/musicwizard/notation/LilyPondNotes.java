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
import java.util.List;

/**
 * Reads the length back out of an emitted LilyPond token, for tests.
 *
 * <p>Written independently of the emitter and of {@link LilyPondDuration} — it
 * parses what LilyPond would parse rather than reusing the table that produced
 * it — so that a test asking "does this bar add up" is not asking the code under
 * test to mark its own work.
 */
final class LilyPondNotes {

    private LilyPondNotes() {
    }

    /**
     * Splits a bar of emitted music into tokens.
     *
     * <p>Not a plain whitespace split: a chord is one token and holds spaces
     * between its pitches, so {@code <c e g>2} would otherwise come apart into
     * three things, none of them a duration.
     */
    static List<String> tokenize(String music) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inChord = false;
        for (int i = 0; i < music.length(); i++) {
            char c = music.charAt(i);
            if (c == '<') {
                inChord = true;
            } else if (c == '>') {
                inChord = false;
            }
            if (Character.isWhitespace(c) && !inChord) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * The sounding length of a whole bar of tokens, tuplet brackets included.
     *
     * <p>{@code \tuplet a/b { ... }} scales what is inside it by {@code b/a},
     * which is the definition rather than anything the emitter does, so a bar
     * that adds up here adds up for LilyPond too. Reading the bracket back is
     * the point: without it a triplet bar sums to half again its meter, and a
     * test that simply skipped the bracket tokens would agree with a bar that
     * had lost one.
     *
     * @throws IllegalArgumentException if a bracket is malformed or unclosed
     */
    static double quartersOfBar(List<String> tokens) {
        double total = 0;
        double bracket = 0;
        int actual = 0;
        int normal = 0;
        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (token.equals("\\tuplet")) {
                if (actual != 0) {
                    throw new IllegalArgumentException(
                            "nested tuplet at token " + i + ": " + tokens);
                }
                if (i + 2 >= tokens.size() || !tokens.get(i + 2).equals("{")) {
                    throw new IllegalArgumentException(
                            "malformed tuplet at token " + i + ": " + tokens);
                }
                String ratio = tokens.get(i + 1);
                int slash = ratio.indexOf('/');
                if (slash < 0) {
                    throw new IllegalArgumentException("not a tuplet ratio: " + ratio);
                }
                actual = Integer.parseInt(ratio.substring(0, slash));
                normal = Integer.parseInt(ratio.substring(slash + 1));
                bracket = 0;
                i += 3;
                continue;
            }
            if (token.equals("}")) {
                if (actual == 0) {
                    throw new IllegalArgumentException(
                            "closing brace with no tuplet open: " + tokens);
                }
                // Multiplied before dividing, so that three triplet eighths come
                // to exactly one beat rather than to one beat less a bit. Summing
                // scaled tokens instead leaves the bar a few ulps short, and the
                // caller compares against the meter exactly -- which is the point
                // of the check, and not something to loosen for arithmetic that
                // does not need loosening.
                total += bracket * normal / actual;
                actual = 0;
                i++;
                continue;
            }
            double quarters = quartersOf(token);
            if (actual == 0) {
                total += quarters;
            } else {
                bracket += quarters;
            }
            i++;
        }
        if (actual != 0) {
            throw new IllegalArgumentException("unclosed tuplet: " + tokens);
        }
        return total;
    }

    /**
     * The length in quarter-note beats of one token, e.g. {@code <c' e'>2.~} or
     * {@code R1*5/4}.
     *
     * @throws IllegalArgumentException if the token carries no duration
     */
    static double quartersOf(String token) {
        String text = token.replace("~", "");
        // The duration begins at the first digit after the pitch, and a chord's
        // pitches end at '>'. Note names never contain a digit; octave marks are
        // apostrophes and commas.
        int start = text.lastIndexOf('>') + 1;
        while (start < text.length() && !isDigit(text.charAt(start))) {
            start++;
        }
        if (start == text.length()) {
            throw new IllegalArgumentException("no duration in token: " + token);
        }
        String duration = text.substring(start);
        String base = duration;
        double factor = 1;
        int times = duration.indexOf('*');
        if (times >= 0) {
            base = duration.substring(0, times);
            String scale = duration.substring(times + 1);
            int slash = scale.indexOf('/');
            factor = slash < 0
                    ? Double.parseDouble(scale)
                    : Double.parseDouble(scale.substring(0, slash))
                            / Double.parseDouble(scale.substring(slash + 1));
        }
        int dots = 0;
        while (base.endsWith(".")) {
            base = base.substring(0, base.length() - 1);
            dots++;
        }
        double quarters = 4.0 / Integer.parseInt(base);
        // A dot adds half of what precedes it, a second dot a quarter, and so on.
        double added = quarters;
        for (int i = 0; i < dots; i++) {
            added /= 2;
            quarters += added;
        }
        return quarters * factor;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
