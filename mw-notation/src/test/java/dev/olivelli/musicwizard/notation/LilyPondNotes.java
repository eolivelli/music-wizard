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
     * An exact length in quarter-note beats, as a reduced fraction.
     *
     * <p>Exact because the check this feeds compares a bar against its meter
     * for equality, and a triplet bar summed in doubles does not reach its
     * meter: a bracket holding a partial group -- which only a pickup produces
     * -- comes out an ulp short, and LilyPond engraves it perfectly happily.
     * Loosening the comparison instead would blunt the one assertion that
     * survives a golden-file regeneration, so the arithmetic is made exact
     * rather than the question made vaguer.
     */
    record Quarters(long numerator, long denominator) {

        static final Quarters ZERO = new Quarters(0, 1);

        Quarters {
            if (denominator <= 0) {
                throw new IllegalArgumentException("denominator must be positive: " + denominator);
            }
            long divisor = gcd(Math.abs(numerator), denominator);
            if (divisor > 1) {
                numerator /= divisor;
                denominator /= divisor;
            }
        }

        Quarters plus(Quarters other) {
            return new Quarters(numerator * other.denominator + other.numerator * denominator,
                    denominator * other.denominator);
        }

        /** Scaled by {@code by/over}, which is what a tuplet bracket does to its contents. */
        Quarters scaledBy(long by, long over) {
            return new Quarters(numerator * by, denominator * over);
        }

        double toDouble() {
            return (double) numerator / denominator;
        }

        @Override
        public String toString() {
            return denominator == 1 ? String.valueOf(numerator) : numerator + "/" + denominator;
        }

        private static long gcd(long a, long b) {
            return b == 0 ? a : gcd(b, a % b);
        }
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
     * <p>The bracket is scaled once, over its whole contents, rather than token
     * by token -- not for speed but because {@code 3 * (1/3)} of a beat is a
     * beat and three thirds of a beat added up are not.
     *
     * @throws IllegalArgumentException if a bracket is malformed or unclosed
     */
    static Quarters quartersOfBar(List<String> tokens) {
        Quarters total = Quarters.ZERO;
        Quarters bracket = Quarters.ZERO;
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
                bracket = Quarters.ZERO;
                i += 3;
                continue;
            }
            if (token.equals("}")) {
                if (actual == 0) {
                    throw new IllegalArgumentException(
                            "closing brace with no tuplet open: " + tokens);
                }
                total = total.plus(bracket.scaledBy(normal, actual));
                actual = 0;
                i++;
                continue;
            }
            Quarters length = exactQuartersOf(token);
            if (actual == 0) {
                total = total.plus(length);
            } else {
                bracket = bracket.plus(length);
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
        return exactQuartersOf(token).toDouble();
    }

    /**
     * The same, exactly.
     *
     * <p>A LilyPond duration is a fraction and nothing else: four over the
     * denominator, times {@code (2^(dots+1) - 1) / 2^dots} for the dots, times
     * whatever {@code *n/d} scales it by. So it is read as one, and the only
     * division performed is the one that reduces it.
     */
    static Quarters exactQuartersOf(String token) {
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
        long scaleNumerator = 1;
        long scaleDenominator = 1;
        int times = duration.indexOf('*');
        if (times >= 0) {
            base = duration.substring(0, times);
            String scale = duration.substring(times + 1);
            int slash = scale.indexOf('/');
            scaleNumerator = Long.parseLong(slash < 0 ? scale : scale.substring(0, slash));
            scaleDenominator = slash < 0 ? 1 : Long.parseLong(scale.substring(slash + 1));
        }
        int dots = 0;
        while (base.endsWith(".")) {
            base = base.substring(0, base.length() - 1);
            dots++;
        }
        // A dot adds half of what precedes it, a second dot a quarter, and so
        // on: n dots multiply the value by (2^(n+1) - 1) / 2^n.
        long dotted = (1L << (dots + 1)) - 1;
        long halves = 1L << dots;
        return new Quarters(4 * dotted * scaleNumerator,
                Long.parseLong(base) * halves * scaleDenominator);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
