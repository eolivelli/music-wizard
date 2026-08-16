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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading LilyPond's diagnostics for the one complaint that means the engraved
 * music is wrong: a failed bar check. LilyPond reports it as a
 * <em>warning</em> — exit zero, real PDF — so only reading the output tells a
 * right page from a wrong one (#156). Only this one complaint: LilyPond's
 * other diagnostics, some worded with {@code error}, do not mean the page is
 * wrong (#136, #92), and treating them as fatal is how a warning stops being
 * read at all. The spelling varies by version ({@code barcheck} vs
 * {@code bar check}, #145) and both are current in the wild, so the space is
 * optional and the <em>moment</em> is what gets captured.
 *
 * <p><b>The echo is what has to be held out.</b> LilyPond quotes the
 * offending source back after each diagnostic, and an echo is arbitrary user
 * text, so no test on the shape of a line in isolation can exclude it — three
 * rounds of regex tightening found three ways through. Instead the echo is
 * recognised by <em>where it is and how it is laid out</em>, a fact LilyPond
 * states: the source line is split at the reported column, the part before it
 * on one line (printed width {@code C - 1}) and the part from it on another
 * ({@code C - 1} spaces of indent). {@link #failedBarChecksIn} skips the two
 * lines after a located diagnostic only when they are exactly that shape.
 *
 * <p><b>The asymmetry is most of the design</b>: the layout test can only
 * suppress a match, never admit one, so a LilyPond that echoes differently
 * lands on over-reporting rather than blindness. Two guards keep the
 * asymmetry from nearly holding: an unrecognised echo opens a suspect region
 * whose lines are still read but may not trigger a skip of their own —
 * without it a truncated echo's fabricated column swallowed the next real
 * diagnostic — and a skip needs a column of at least 2, because at column 1
 * any unindented line reads as an echo. One truncated echo can therefore
 * inflate the reported count for the rest of the output (#169 tracks the
 * truncation), but nothing can be lost: suppression strictly reduces skips,
 * measured by generation as well as argued
 * ({@code severalDiagnosticsWithEchoesPresentLoseNothing}).
 *
 * <p>The residual: a real diagnostic in either of the two lines after a
 * located one can be lost, given the width or the indentation to match — and
 * the one precondition is that LilyPond emitted no echo there, which no found
 * output does. The location pattern is deliberately loose (a file name may
 * contain a space; column and location optional) so a changed format
 * over-reports rather than goes blind. Rewording the message itself would
 * blind this, accepted on likelihood; #159 tracks collapsing {@code mw-it}'s
 * older copy of this parser into this one so there is a single reader of
 * LilyPond's output.
 *
 * <p>The prefix is English because {@link LilyPondRenderer} pins the child's
 * message locale — read {@code speakEnglish}'s javadoc there. Package-private,
 * reached through {@link LilyPondRenderer.Result}; nothing else may parse
 * that output.
 */
final class LilyPondComplaints {

    /**
     * A failed bar check, either spelling, capturing the moment it failed at.
     *
     * <p>Case-insensitive only because the assertions this grew out of were, not
     * because LilyPond has ever varied the case.
     */
    private static final Pattern FAILED_BAR_CHECK = Pattern.compile(
            "(?:.*:(\\d+):(\\d+): |.*:\\d+: )?warning: bar ?check failed at: (\\S+)\\s*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Every line break Java recognises — a strict superset of what
     * {@code String.lines} breaks on. Splitting more finds more diagnostics,
     * and this class prefers reporting one that was not there to missing one
     * that was.
     */
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    /** Where the column sits in {@link #FAILED_BAR_CHECK}, absent when unlocated. */
    private static final int COLUMN = 2;

    /** Where the moment sits in {@link #FAILED_BAR_CHECK}. */
    private static final int MOMENT = 3;

    /**
     * How far a tab advances — measured as what LilyPond counts, and what
     * makes a reported column disagree with a count of characters.
     */
    private static final int TAB_STOP = 8;

    private LilyPondComplaints() {
    }

    /**
     * The moments at which LilyPond reported a failed bar check, in the order it
     * reported them — empty when it reported none, which is what a part whose
     * bars sum to their meter looks like.
     *
     * <p>Duplicates are kept. Two bars a beat short in the same place are two
     * defects, and a caller counting them is entitled to LilyPond's own count
     * rather than a de-duplicated one.
     *
     * <p>Line by line, and each line matched <em>whole</em>: a diagnostic is one
     * line and nothing else. The two lines after a located diagnostic are skipped
     * when they are that diagnostic's echo, which is what stops LilyPond quoting
     * the offending source back being read as a second failure. See the class
     * javadoc for why the echo is recognised by its layout rather than by what it
     * says.
     */
    static List<String> failedBarChecksIn(String lilypondOutput) {
        List<String> moments = new ArrayList<>();
        // \R rather than lines(): a diagnostic terminated by one of the line
        // breaks lines() ignores would silently lose its moment.
        List<String> lines = List.of(LINE_BREAK.split(lilypondOutput, -1));
        // The last line of a region where an echo was due and was not found.
        // Lines inside it are still read — the over-report this class accepts
        // — but may not trigger an echo skip of their own: a truncated echo's
        // fabricated column once swallowed the next real diagnostic.
        int suspectUntil = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = FAILED_BAR_CHECK.matcher(lines.get(i));
            if (!matcher.matches()) {
                continue;
            }
            moments.add(matcher.group(MOMENT));
            // One rule, not a condition per branch: either this line's echo
            // was recognised and is skipped, or the region it should have
            // occupied becomes suspect — whatever the column, and whether or
            // not this line is itself inside an earlier region; both
            // carve-outs were found leaking.
            if (i > suspectUntil && isEchoOf(lines, i + 1, columnOf(matcher))) {
                i += 2;
            } else {
                // Math.max is defensive: "the region never retreats" is the
                // property meant, and a reader should not have to derive it.
                suspectUntil = Math.max(suspectUntil, i + 2);
            }
        }
        return List.copyOf(moments);
    }

    /**
     * Whether the two lines at {@code first} are the echo of a diagnostic
     * that reported {@code column}. Layout alone, and deliberately the strict
     * half of the decision: saying yes wrongly is the only way to lose a real
     * diagnostic. Both comparisons are exact — leniency about trailing space
     * would be leniency in the direction that skips more.
     */
    private static boolean isEchoOf(List<String> lines, int first, int column) {
        if (first + 1 >= lines.size() || column < 2) {
            return false;
        }
        int upToColumn = column - 1;
        return printedWidth(lines.get(first)) == upToColumn
                && leadingSpaces(lines.get(first + 1)) == upToColumn;
    }

    /**
     * How far a string advances LilyPond's column counter — neither its
     * length nor its character count. A tab advances to the next
     * {@link #TAB_STOP}, and a supplementary character (a clef glyph in a
     * {@code \markup} is one) is two {@code char}s in Java and one column to
     * LilyPond. Counted in code points, which was measured to be the whole of
     * it across normalisation, joining, bidi and East Asian width; combining
     * marks are deliberately <em>not</em> treated as zero width, because
     * LilyPond gives one a column of its own.
     */
    private static int printedWidth(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i += Character.charCount(line.codePointAt(i))) {
            width = line.charAt(i) == '\t'
                    ? (width / TAB_STOP + 1) * TAB_STOP
                    : width + 1;
        }
        return width;
    }

    /**
     * The column a diagnostic reported, or 0 when it named none this parse
     * can use — including one too large to be an {@code int}, which would
     * otherwise escape as an exception after the files were written. A column
     * nothing can parse is one no layout can be checked against: no skip, and
     * the echo is over-reported, the direction everything here degrades in.
     */
    private static int columnOf(Matcher diagnostic) {
        String column = diagnostic.group(COLUMN);
        if (column == null) {
            return 0;
        }
        try {
            return Integer.parseInt(column);
        } catch (NumberFormatException tooManyDigits) {
            return 0;
        }
    }

    /**
     * How many spaces a line starts with.
     *
     * <p>Spaces only, and not whitespace generally: LilyPond pads the second
     * half of an echo with spaces whatever the source contained, so a tab here
     * would mean this is not an echo.
     */
    private static int leadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }
}
