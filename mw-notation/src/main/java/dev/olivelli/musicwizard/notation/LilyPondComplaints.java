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
 * reasoning and it is false.</b> LilyPond splits the echo <em>at the failing
 * column</em>: the part before the column is printed at column 0 and only the
 * remainder is indented. So an echo does begin at a line start, and a source
 * line carrying anything colon-digits-colon-digits-shaped in front of the phrase
 * walks straight through:
 *
 * <pre>
 * titled.ly:2:105: warning: bar check failed at: 3/4
 * \header { title = "a:1:2: warning: bar check failed at: 9/9" } \score { \new Staff { \time 4/4 c4 c4 c4
 * </pre>
 *
 * <p>Reported as {@code [3/4, 9/9"]} — a count that is wrong and a moment with a
 * stray quote in it. Which is this project's recorded pattern exactly: the first
 * fix reached the layer the bug was noticed at rather than the one it lived at.
 *
 * <p>What separates the two is the <em>end</em> of the line, not the start. A
 * diagnostic is generated from a format string whose last token is the moment,
 * so nothing follows it; an echo is a fragment of a source line, and the phrase
 * inside it is followed by whatever the rest of that line says. The echo above
 * continues past its {@code 9/9} with the rest of the {@code \header} line; every
 * diagnostic line in review round 2's captured corpus, 2.24.3 and 2.26.0 alike,
 * ends immediately after the moment. So the match is anchored at both ends, and
 * it is the closing anchor that does the work.
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
 * <p><b>The residuals, stated rather than implied.</b> Over-reporting survives
 * where an echo fragment happens to end exactly at the phrase's moment, which
 * needs the bar check to fail at that precise column. And appending anything
 * after the moment in a future LilyPond would make this blind — the one
 * direction that matters, accepted here because the moment is the last token of
 * the format string in LilyPond's own source, so text after it would be a
 * deliberate change rather than drift.
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
            "(?m)^(?:.*:\\d+(?::\\d+)?: )?warning: bar ?check failed at: (\\S+)\\s*$",
            Pattern.CASE_INSENSITIVE);

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
     */
    static List<String> failedBarChecksIn(String lilypondOutput) {
        List<String> moments = new ArrayList<>();
        Matcher matcher = FAILED_BAR_CHECK.matcher(lilypondOutput);
        while (matcher.find()) {
            moments.add(matcher.group(1));
        }
        return List.copyOf(moments);
    }
}
