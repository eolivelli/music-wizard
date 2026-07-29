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
 * music is wrong.
 *
 * <p>A failed bar check is that complaint. {@link StaffNotation} ends every bar
 * with a {@code |} precisely so that a bar which does not fill its meter — the
 * commonest way an emitter goes wrong, and the one LilyPond will otherwise
 * engrave without a murmur — is reported rather than drawn. LilyPond reports it
 * as a <em>warning</em>: it exits zero and writes a real PDF, so nothing but
 * reading the output can tell the difference between a right page and a wrong
 * one (#156).
 *
 * <p><b>Only this one.</b> LilyPond emits plenty of other diagnostics that do
 * not mean the page is wrong. One is measured as reachable from this project's
 * own output: {@code programming error: not enough space for tuplet number
 * against beam} is a placement complaint about a page whose music is correct,
 * and #136 found it in roughly one staff in eighty of ordinary 4/4. A reviewer
 * on #92 separately established that LilyPond words benign diagnostics with
 * {@code error} in them, without measuring how often this project's output
 * reaches one. Treating any diagnostic as fatal would therefore cry wolf on
 * correct output, which is how a warning stops being read at all.
 *
 * <p><b>LilyPond does not spell it the same way in every version, and that is
 * what #145 was.</b> The message is one string in the source, and it was
 * rewritten when bar checks moved from {@code Bar_check_iterator} into
 * {@code Timing_translator}:
 *
 * <table border="1">
 * <caption>Wording by version</caption>
 * <tr><th>Versions</th><th>Message</th></tr>
 * <tr><td>up to and including 2.25.5</td><td>{@code barcheck failed at: %s}</td></tr>
 * <tr><td>2.25.6 onwards, so every 2.26 and later</td>
 *     <td>{@code bar check failed at: %s}</td></tr>
 * </table>
 *
 * <p>Both are current in the wild: Ubuntu 24.04 packages 2.24.3, which is what
 * CI's {@code integration} job installs, while Homebrew ships 2.26. Pinning
 * either spelling reads one and is blind to the other, and being blind is the
 * failure mode that matters here — a check that has silently stopped checking
 * looks exactly like output that is correct.
 *
 * <p>So the space is optional and the <em>position</em> is what gets captured.
 * That is the substance anyway: a bar check that fires says which moment the bar
 * had reached when it should have been at a bar line, so {@code 3/4} in 4/4 says
 * the bar was a beat short. What is deliberately <em>not</em> relied on is how
 * many times it fires — 2.25.6 also stopped reporting more than the first
 * failure per context, which is exactly the kind of incidental detail nothing
 * here should depend on.
 *
 * <p><b>What has to be held out is the echo, and it took two rounds to work out
 * what actually holds it out.</b> LilyPond quotes the offending source line back
 * after each diagnostic, verbatim and with no prefix of its own, so a {@code
 * warning:} requirement alone does not exclude it: an echo of a line containing
 * the phrase carries the prefix along with it. Round 1 of review on #156
 * measured that against 2.26.0 —
 *
 * <pre>
 * echo.ly:2:83: warning: bar check failed at: 3/4
 * \score { \new Staff { \time 4/4 c4 c4 c4 %{ warning: bar check failed at: 99/9 %}
 * </pre>
 *
 * <p>— one real failure, two moments reported.
 *
 * <p>Round 1 answered that by anchoring to the start of a line, on the reasoning
 * that an echo begins with the source's own text. <b>Round 2 measured that
 * reasoning and it is false</b>, for the reason set out below: the first half of
 * an echo is unindented whatever the column, so it begins at a line start too.
 *
 * <p>So the match is anchored at <em>both</em> ends. A diagnostic is generated
 * from a format string whose last token is the moment — {@code strings} on the
 * 2.26.0 binary gives {@code bar check failed at: %s} — so nothing follows it,
 * while an echo is a fragment of a source line and usually carries the rest of
 * that line with it. Every diagnostic line in review round 2's captured corpus,
 * 2.24.3 and 2.26.0 alike, ends immediately after the moment.
 *
 * <p><b>Neither anchor was enough, and the reason is the shape of the echo
 * itself.</b> LilyPond splits it into <em>two</em> lines — the text before the
 * failing column, then the text from it — and rounds 1 and 2 each reasoned about
 * only one of them. The first begins at a line start whatever the column, and
 * the second ends at the end of the source line whatever the column, so a line
 * with a diagnostic-shaped head walked past the closing anchor and one with a
 * diagnostic-shaped tail walked past the opening one. Both measured on 2.26.0,
 * both reported as two moments for one real failure, and the second of them
 * fabricated a moment with a stray quote in it: {@code [3/4, 9/9"]}.
 *
 * <p>The lesson was not that the anchors were in the wrong place. It was that
 * <b>no test on the shape of a line in isolation can work</b>: an echo is
 * arbitrary user text, so any shape a diagnostic has, an echo can have. Three
 * rounds of tightening found three ways through, which is the point at which
 * this project's own rule says to change the layer rather than make the edit
 * again.
 *
 * <p><b>So the parse stopped trying to do it by shape at all.</b> The echo is
 * recognised by <em>where it is and how it is laid out</em>, which is a fact
 * LilyPond states rather than one that has to be guessed from the text: it
 * splits the offending source line at the column the diagnostic itself reports,
 * printing the part before that column as one line and the part from it as
 * another, indented to line up. Measured on 2.26.0, for a diagnostic reporting
 * column {@code C}:
 *
 * <pre>
 * line 1   the source up to C, so its printed width is C - 1
 * line 2   C - 1 spaces, then the rest of the source line
 * </pre>
 *
 * <p>{@link #failedBarChecksIn} therefore walks the output a line at a time and,
 * after a diagnostic that named a column, skips the next two lines <em>only if
 * they are that shape</em>. Anything else — one echo line, none, a truncated one
 * — fails the test and is not skipped.
 *
 * <p><b>That asymmetry is most of the design.</b> The layout test can only
 * <em>suppress</em> a match, never admit one, so a LilyPond that echoes
 * differently, or not at all, lands on the behaviour this class had before it:
 * over-reporting. That is why this is a change of layer rather than a fourth
 * round of tightening the regex.
 *
 * <p>It is <em>most</em> and not all, because suppression is not free: skipping
 * is the one thing here that can lose a real diagnostic, and review round 7
 * found an over-report chaining into exactly that. The two guards below are what
 * make the asymmetry hold rather than nearly hold, and neither is decoration.
 *
 * <p>Two other ways of writing it were tried and rejected, and are named so
 * nobody reaches for them. Skipping a fixed number of lines under-reports the
 * moment LilyPond emits a different number. Binding the location to the file
 * name passed to the binary goes blind on an {@code \include} — measured,
 * {@code \include "part.ily"} reports {@code part.ily:1:42: warning: ...},
 * naming the included file and not the one handed over.
 *
 * <p>The reported column is a <em>column</em> and not a character offset, so a
 * tab advances to the next eight-column stop. Measured: a line of 41 characters
 * holding three tabs is reported at column 52. Widths are compared as printed
 * for that reason — a reader counting characters would see 41 against 51, fail
 * to recognise a perfectly ordinary echo, and report its text as a second
 * failure.
 *
 * <p><b>What it still does not close</b>, measured rather than assumed: where
 * LilyPond cannot reproduce that layout, the pair is not recognised and an echo
 * whose text is diagnostic-shaped is still counted. The case measured is a very
 * long source line, whose echo LilyPond truncates — 989 characters printed for a
 * column of 2842 — after which the layout no longer describes what was written.
 * <b>A truncated echo is one line, not two</b>, which is why the suspect
 * region is opened by every diagnostic rather than only by ones outside an
 * existing region: a two-line region overshoots a one-line echo onto whatever
 * follows it.
 * A genuine column-1 echo is the other, by the floor described below. Not
 * reachable from what this project emits: {@link ChordChart} writes no
 * {@code |} at all, and {@link StaffNotation} puts each bar on its own short
 * line, ending in the bar check rather than starting with it. #169 tracks the
 * remainder.
 *
 * <p><b>An unrecognised echo used to be worse than an over-report.</b> Read as a
 * diagnostic in its own right, its fabricated column then drove a skip of its
 * own, and that skip could swallow the <em>next</em> real diagnostic — an
 * over-report chaining into a loss, which is the one thing this design says
 * cannot happen. So a line inside a region where an echo was due and was not
 * found is still read, but may not trigger a skip. It can only ever skip less.
 *
 * <p><b>And a skip needs a column of 2 or more.</b> At column 1 "indented by
 * {@code C - 1} spaces" is satisfied by any unindented line whatsoever, so a
 * blank line followed by a real diagnostic read as an echo and swallowed it.
 * Round 7's generator, whose columns ran 1 to 60 and whose file names began with
 * spaces a quarter of the time, put every loss it found there. That is a
 * statement about that population and not about the shape space: round 8 built
 * one at column 3.
 * The floor costs a genuine column-1 echo, whose text is then over-reported —
 * measured, and the direction this class prefers.
 *
 * <p>The location is deliberately loose — {@code anything:line[:column]: },
 * optional — and each part of that is a measured decision rather than a guess:
 *
 * <ul>
 * <li><b>Not a run of non-space</b>, because a file name may contain a space.
 *     {@code my song.ly:2:42: warning: bar check failed at: 3/4} is what 2.26.0
 *     prints, and a pattern that stopped reading at the space would go silent on
 *     the exact input it was written for.</li>
 * <li><b>The column is optional</b>, and <b>so is the whole location</b>,
 *     because a LilyPond that dropped either must make this over-report rather
 *     than go blind. Neither shape occurs in any output either version produces
 *     today; they cost nothing to accept and they remove two ways to stop
 *     checking silently.</li>
 * </ul>
 *
 * <p><b>Rewording the message blinds this, and there is more than one way to do
 * it.</b> Measured against the shipped pattern, all of these read as nothing at
 * all: a trailing context note ({@code … at: 3/4 (Staff)}), a location moved
 * after the {@code warning:}, a bare {@code lilypond: warning:} prefix, a column
 * range ({@code 5:17-19}), and a third rewording of the phrase itself
 * ({@code failed at moment:}) — #145 was the second.
 *
 * <p>Accepted on likelihood, and on the shape of the string rather than on a
 * hope: the moment is the last token of the format string in LilyPond's own
 * source — {@code strings} on the 2.26.0 binary gives
 * {@code bar check failed at: %s} — so any of those would be a deliberate change
 * rather than drift.
 *
 * <p><b>Accepted on likelihood alone, and not because anything would notice.</b>
 * An earlier version of this paragraph said {@code mw-it} engraves deliberately
 * short bars on every integration run and so would turn red. Review round 6
 * measured that and it is false: {@code mw-it} reads its own copy of this
 * parser, which is unanchored and matched with {@code find()}, so it is immune
 * to the very drift that would blind this one. Fed
 * {@code bar.ly:5:17: warning: bar check failed at: 3/4 (context Staff)}, the
 * {@code mw-it} parser returns {@code [3/4]} and this one returns nothing, and
 * every integration assertion stays green. The residual is <em>undetected</em>
 * until #159 collapses the two parsers into this one — which is the strongest
 * argument for doing it, and is recorded there.
 *
 * <p><b>The echo skip adds one, and three attempts to state it have each been
 * wrong in the same way.</b> Two lines are skipped and they are tested
 * differently, so a residual described in terms of one of them describes half
 * of it. The first is tested by its printed width alone; the second by its
 * indentation alone.
 *
 * <ul>
 * <li>Round 5 was told "only a column-1 diagnostic can be swallowed, since a
 *     diagnostic never begins with a space". False: LilyPond prints the location
 *     verbatim from the name it was handed, so a file called {@code "  short.ly"}
 *     yields a diagnostic that does begin with spaces.</li>
 * <li>Round 6 was told it also needs a whitespace-prefixed file name. Also
 *     false, and false for the <em>first</em> of the two lines, which needs no
 *     indentation at all — only a printed width of {@code C - 1}. Measured: a
 *     43-column diagnostic sitting under a diagnostic that reported column 44 is
 *     swallowed, ordinary file name and all.</li>
 * </ul>
 *
 * <p>So the honest statement is the short one. <b>Any real diagnostic in either
 * of the two lines after a located diagnostic can be lost, given the width or
 * the indentation to match — and the one precondition is that LilyPond emitted
 * no echo there.</b>
 *
 * <p>That the precondition is the <em>whole</em> of it is checked by generation
 * rather than argued, since arguing it has now been wrong three times: 20 000
 * randomly built outputs in which every located diagnostic is followed by a
 * well-formed echo, with whitespace-prefixed file names and diagnostic-shaped
 * echo text mixed in, lose nothing at all. The same generator with echoes
 * omitted at random loses 36 moments. A smaller seeded run of it is
 * {@code thePreconditionIsTheWholeOfTheResidual}.
 *
 * <p>And the precondition holds: every located diagnostic 2.26.0 produces is
 * followed by its echo, including from stdin and from music built inside a
 * Scheme function. No trigger has been found; the defence is that precondition
 * and nothing smaller.
 *
 * <p>It is not closed, and the guard that looks as though it would close it —
 * refusing to skip a line that itself matches — is measured and does not work.
 * <em>Both</em> echo halves are whole-line matches: the greedy {@code .*} in the
 * location absorbs the padding and the source text, so round 3's
 * {@code <spaces>| c4 … % a:1:2: warning: … 9/9} and round 4's
 * {@code \score { … c4^"a:1:2: warning: … 9/9"} both return {@code true}. That
 * guard would reopen both bypasses this design exists to fix.
 *
 * <p>The prefix is English because {@link LilyPondRenderer} pins the child's
 * message locale; read the {@code speakEnglish} javadoc there before assuming it
 * always will be.
 *
 * <p>Package-private, and reached through {@link LilyPondRenderer.Result} rather
 * than directly. {@code mw-it} has a copy of this parser in test scope, which
 * predates this one and is what #148 built; #159 tracks deleting it in favour of
 * the accessor, so that there is one reader of LilyPond's output rather than
 * two. Nothing else may parse that output.
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
     * Every line break Java recognises.
     *
     * <p>A strict superset of what {@code String.lines} breaks on, and — round 5
     * of review caught the third revision of this comment asserting equality
     * again — a strict superset of what {@code (?m)$} treated as an end of line
     * too: {@code \R} also matches a vertical tab and a form feed, which
     * {@code $} does not. Superset in both directions is the right way round.
     * Splitting more finds more diagnostics, and this class prefers reporting
     * one that was not there to missing one that was.
     */
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    /** Where the column sits in {@link #FAILED_BAR_CHECK}, absent when unlocated. */
    private static final int COLUMN = 2;

    /** Where the moment sits in {@link #FAILED_BAR_CHECK}. */
    private static final int MOMENT = 3;

    /**
     * How far a tab advances, which is what makes a reported column and a count
     * of characters disagree.
     *
     * <p>Eight because that is what LilyPond counts, measured: a line of 41
     * characters holding three tabs was reported at column 52.
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
        // Split on \R rather than with lines(), because they disagree and the
        // disagreement is in the direction that matters. lines() breaks only on
        // \n, \r\n and \r; \R also breaks on U+0085, U+2028 and U+2029, which
        // (?m)$ treated as line ends as well. Java's \s is ASCII-only, so under
        // lines() a diagnostic terminated by one of those fails to match at all
        // and the moment is silently lost -- a blindness path introduced by the
        // rewrite rather than by anything LilyPond does. It emits none of them;
        // the point is that not emitting them is not this class's to rely on.
        List<String> lines = List.of(LINE_BREAK.split(lilypondOutput, -1));
        // The last line of a region where an echo was due and was not found.
        // Lines inside it are still read -- that is the over-report this class
        // accepts -- but they may not trigger an echo skip of their own, because
        // they are the likeliest lines in the whole output not to be diagnostics
        // at all. Without this, an echo LilyPond had truncated was read as a
        // diagnostic and its fabricated column then swallowed the *next* real
        // one: an over-report chaining into a loss, which is the one thing this
        // design says cannot happen. Review round 7.
        int suspectUntil = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = FAILED_BAR_CHECK.matcher(lines.get(i));
            if (!matcher.matches()) {
                continue;
            }
            moments.add(matcher.group(MOMENT));
            // One rule, not a condition per branch. Either this line's echo was
            // recognised and is skipped, or it was not and the region it should
            // have occupied becomes suspect -- whatever the column was, and
            // whether or not this line is itself inside an earlier suspect
            // region. Round 8 found both of those carve-outs leaking: a
            // diagnostic with no column opened no region and its echo text then
            // skipped a real diagnostic, and a diagnostic *inside* a region
            // opened none either, so a one-line truncated echo let a two-line
            // region overshoot onto the next one.
            if (i > suspectUntil && isEchoOf(lines, i + 1, columnOf(matcher))) {
                i += 2;
            } else {
                suspectUntil = Math.max(suspectUntil, i + 2);
            }
        }
        return List.copyOf(moments);
    }

    /**
     * Whether the two lines at {@code first} are the echo of a diagnostic that
     * reported {@code column}.
     *
     * <p>The test is on layout alone, and is deliberately the strict half of the
     * decision: saying no leaves the lines to be read as ordinary output, which
     * is what this class did before the echo was handled at all. Saying yes
     * wrongly is the only way to lose a real diagnostic, and the class javadoc
     * records why that needs a coincidence rather than a change of format.
     *
     * <p>Both comparisons are exact, and the first is <em>not</em> forgiving
     * about trailing space even though a trailing space is the easiest thing in
     * the world for a capture path to eat — a Java text block strips them
     * silently, which is how review round 4 found this project's own fixture off
     * by one. Being forgiving there would be leniency in the direction that
     * skips more, and skipping is the only thing here that can lose a
     * diagnostic. Exact means that a path which ate the space stops recognising
     * echoes and over-reports, which is the failure this class prefers.
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
     * How far a string advances LilyPond's column counter.
     *
     * <p>Neither its length nor its character count. <b>Two things make a
     * printed width differ from {@code String.length()}, and the first fix here
     * reached only one of them.</b> A tab advances to the next
     * {@link #TAB_STOP}; and a supplementary character — anything outside the
     * basic plane — is two {@code char}s in Java and one column to LilyPond.
     *
     * <p>The second was found by review round 5 and is not exotic for this
     * project of all projects: {@code \markup} carrying a clef or a note glyph
     * is astral, and {@code c4^"𝄞"} before the failing column made every width
     * after it one too large, so the echo was not recognised and its text was
     * reported as a second failed bar check. Measured on 2.26.0 and 2.24.3
     * alike, and in a random sweep every inversion found was a surrogate pair.
     *
     * <p>Counted in code points for that reason, and code points are the whole
     * of it — measured across every family that could have been a further
     * layer, column against code points on 2.26.0:
     *
     * <pre>
     * plain ASCII        48 / 47      astral treble clef  46 / 45
     * NFC e-acute        46 / 45      NFD e + combining   47 / 46
     * zero-width joiner  48 / 47      variation selector  47 / 46
     * bidi mark          48 / 47      CJK wide            46 / 45
     * </pre>
     *
     * <p>The column is code points plus one in every case, so normalisation,
     * joining, variation selection, bidi and East Asian width are all
     * irrelevant here. <b>Combining marks in particular are deliberately not
     * treated as zero width</b>, which is the obvious "correct" thing to do and
     * would be wrong: LilyPond gives one a column of its own, so counting
     * graphemes would reintroduce the divergence in the other direction.
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
     * The column a diagnostic reported, or 0 when it named none this parse can
     * use.
     *
     * <p>Zero for a column too large to be an {@code int} as well as for one
     * that was absent, and that is not a detail: {@link Integer#parseInt} throws,
     * and a {@link NumberFormatException} escaping here would leave the caller
     * with neither an over-report nor a silence but an exception, after the
     * files have already been written. Review round 5 produced one from
     * {@code x.ly:1:99999999999999: warning: ...}. A column nothing can parse is
     * a column this cannot check a layout against, so it is treated as no column
     * at all — no skip, and the echo is over-reported, which is the direction
     * everything else here degrades in.
     *
     * <p>Note for a later mutation sweep: {@link #isEchoOf} refuses to skip below
     * column 2, and nothing else reads this value, so returning 0 and returning
     * 1 are now indistinguishable and a mutant swapping them is equivalent.
     *
     * <p>That was claimed one commit too early and was wrong when it was made:
     * the suspect region was then opened by an {@code else if (column >= 1)}
     * that read the same value, so the swap lost a real diagnostic and round 8
     * measured it doing so. Opening the region unconditionally is what makes the
     * equivalence true — recorded this way round, because a note saying
     * "equivalent" is exactly what stops the next sweep from looking.
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
