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

/**
 * How many cells a string occupies when printed in a fixed-width font.
 *
 * <p>For laying out a plain-text sheet, where a chord symbol has to sit above
 * the word it is sung on. {@code String.length()} counts UTF-16 code units and
 * gets that wrong twice over: a CJK character is one unit and two cells, and an
 * emoji is two units and two cells.
 *
 * <p><b>Not the same measure as {@link LilyPondComplaints} takes</b>, which
 * counts code points because that is what LilyPond's own column counter counts.
 * A file can need both: one asks what a terminal will draw, the other what
 * another program already counted. Neither is {@code length()}, and they agree
 * only on ASCII.
 *
 * <p>The rules, from Unicode Annex #11 and the general category:
 *
 * <ul>
 *   <li>a combining mark or a format character takes no cell of its own — it is
 *       drawn on the character before it, so {@code e} plus a combining acute is
 *       one cell, the same as the composed {@code é};
 *   <li>East Asian Wide and Fullwidth take two, which is the CJK case and the
 *       emoji case;
 *   <li>everything else takes one.
 * </ul>
 *
 * <p><b>No dependency and no vendored file.</b> The whole of Annex #11's wide
 * property is 122 ranges once coalesced, so it is carried as {@link #WIDE}
 * rather than by pulling in a Unicode library for one column count — the trade
 * this project makes for its DSP and again for {@code Hyphenator}. The general
 * category comes from the JDK, which already has it.
 */
final class DisplayWidth {

    /**
     * East Asian Wide and Fullwidth, as inclusive code point ranges.
     *
     * <p>Pairs, ascending, searched by bisection. <b>Generated from Unicode
     * 16.0.0's {@code EastAsianWidth} property, not written by hand</b> — the
     * first draft of this file listed the blocks a reader would think of and was
     * wrong on 953 code points when swept against the property itself, in both
     * directions. Wideness follows script blocks closely enough to look
     * guessable and not closely enough to be guessed.
     *
     * <p>122 ranges is the whole property, so this is exact rather than an
     * approximation. To move to a later Unicode, regenerate every code point
     * whose east-asian-width is {@code W} or {@code F}, coalesced into runs.
     * {@code DisplayWidthTest} cannot re-derive that without the property file
     * it exists to avoid carrying, so it checks the table's shape — ascending,
     * non-overlapping — and pins the characters a lyric actually contains.
     */
    private static final int[] WIDE = {
        0x1100, 0x115F,
        0x231A, 0x231B,
        0x2329, 0x232A,
        0x23E9, 0x23EC,
        0x23F0, 0x23F0,
        0x23F3, 0x23F3,
        0x25FD, 0x25FE,
        0x2614, 0x2615,
        0x2630, 0x2637,
        0x2648, 0x2653,
        0x267F, 0x267F,
        0x268A, 0x268F,
        0x2693, 0x2693,
        0x26A1, 0x26A1,
        0x26AA, 0x26AB,
        0x26BD, 0x26BE,
        0x26C4, 0x26C5,
        0x26CE, 0x26CE,
        0x26D4, 0x26D4,
        0x26EA, 0x26EA,
        0x26F2, 0x26F3,
        0x26F5, 0x26F5,
        0x26FA, 0x26FA,
        0x26FD, 0x26FD,
        0x2705, 0x2705,
        0x270A, 0x270B,
        0x2728, 0x2728,
        0x274C, 0x274C,
        0x274E, 0x274E,
        0x2753, 0x2755,
        0x2757, 0x2757,
        0x2795, 0x2797,
        0x27B0, 0x27B0,
        0x27BF, 0x27BF,
        0x2B1B, 0x2B1C,
        0x2B50, 0x2B50,
        0x2B55, 0x2B55,
        0x2E80, 0x2E99,
        0x2E9B, 0x2EF3,
        0x2F00, 0x2FD5,
        0x2FF0, 0x303E,
        0x3041, 0x3096,
        0x3099, 0x30FF,
        0x3105, 0x312F,
        0x3131, 0x318E,
        0x3190, 0x31E5,
        0x31EF, 0x321E,
        0x3220, 0x3247,
        0x3250, 0xA48C,
        0xA490, 0xA4C6,
        0xA960, 0xA97C,
        0xAC00, 0xD7A3,
        0xF900, 0xFAFF,
        0xFE10, 0xFE19,
        0xFE30, 0xFE52,
        0xFE54, 0xFE66,
        0xFE68, 0xFE6B,
        0xFF01, 0xFF60,
        0xFFE0, 0xFFE6,
        0x16FE0, 0x16FE4,
        0x16FF0, 0x16FF1,
        0x17000, 0x187F7,
        0x18800, 0x18CD5,
        0x18CFF, 0x18D08,
        0x1AFF0, 0x1AFF3,
        0x1AFF5, 0x1AFFB,
        0x1AFFD, 0x1AFFE,
        0x1B000, 0x1B122,
        0x1B132, 0x1B132,
        0x1B150, 0x1B152,
        0x1B155, 0x1B155,
        0x1B164, 0x1B167,
        0x1B170, 0x1B2FB,
        0x1D300, 0x1D356,
        0x1D360, 0x1D376,
        0x1F004, 0x1F004,
        0x1F0CF, 0x1F0CF,
        0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A,
        0x1F200, 0x1F202,
        0x1F210, 0x1F23B,
        0x1F240, 0x1F248,
        0x1F250, 0x1F251,
        0x1F260, 0x1F265,
        0x1F300, 0x1F320,
        0x1F32D, 0x1F335,
        0x1F337, 0x1F37C,
        0x1F37E, 0x1F393,
        0x1F3A0, 0x1F3CA,
        0x1F3CF, 0x1F3D3,
        0x1F3E0, 0x1F3F0,
        0x1F3F4, 0x1F3F4,
        0x1F3F8, 0x1F43E,
        0x1F440, 0x1F440,
        0x1F442, 0x1F4FC,
        0x1F4FF, 0x1F53D,
        0x1F54B, 0x1F54E,
        0x1F550, 0x1F567,
        0x1F57A, 0x1F57A,
        0x1F595, 0x1F596,
        0x1F5A4, 0x1F5A4,
        0x1F5FB, 0x1F64F,
        0x1F680, 0x1F6C5,
        0x1F6CC, 0x1F6CC,
        0x1F6D0, 0x1F6D2,
        0x1F6D5, 0x1F6D7,
        0x1F6DC, 0x1F6DF,
        0x1F6EB, 0x1F6EC,
        0x1F6F4, 0x1F6FC,
        0x1F7E0, 0x1F7EB,
        0x1F7F0, 0x1F7F0,
        0x1F90C, 0x1F93A,
        0x1F93C, 0x1F945,
        0x1F947, 0x1F9FF,
        0x1FA70, 0x1FA7C,
        0x1FA80, 0x1FA89,
        0x1FA8F, 0x1FAC6,
        0x1FACE, 0x1FADC,
        0x1FADF, 0x1FAE9,
        0x1FAF0, 0x1FAF8,
        0x20000, 0x2FFFD,
        0x30000, 0x3FFFD,
    };

    private DisplayWidth() {
    }

    /** How many cells this string occupies. */
    static int of(String text) {
        int cells = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            cells += of(codePoint);
            i += Character.charCount(codePoint);
        }
        return cells;
    }

    /** How many cells one code point occupies: none, one, or two. */
    static int of(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.FORMAT) {
            // Drawn on the character before it, or not drawn at all. A variation
            // selector and a zero-width joiner are both in here, which is what
            // keeps a multi-code-point emoji from counting several times over.
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    /** Whether this code point is East Asian Wide or Fullwidth. */
    private static boolean isWide(int codePoint) {
        int low = 0;
        int high = WIDE.length / 2 - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (codePoint < WIDE[middle * 2]) {
                high = middle - 1;
            } else if (codePoint > WIDE[middle * 2 + 1]) {
                low = middle + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * Pads a builder with spaces until it occupies at least this many cells.
     *
     * <p>Here rather than at each call site because the loop has to re-measure,
     * and a caller that pads by {@code column - builder.length()} reintroduces
     * exactly the defect this class exists to remove.
     */
    static void padTo(StringBuilder row, int cells) {
        for (int width = of(row.toString()); width < cells; width++) {
            row.append(' ');
        }
    }
}
