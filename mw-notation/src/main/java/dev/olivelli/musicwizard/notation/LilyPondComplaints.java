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
 * not mean the page is wrong, and two of them are known to be reachable from
 * this project's own output: {@code programming error: not enough space for
 * tuplet number against beam} is a placement complaint about a page whose music
 * is correct (#136, measured in roughly one staff in eighty of ordinary 4/4),
 * and #92 found LilyPond writing benign diagnostics that contain the word
 * {@code error} too. Treating any diagnostic as fatal would therefore cry wolf
 * on correct output, which is how a warning stops being read at all.
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
 * <p>The {@code warning:} prefix is required rather than incidental, and what it
 * keeps out is a <em>quoted line of source</em>: LilyPond echoes the offending
 * line back at you, so a file that merely contains the phrase in a comment does
 * not count as having failed one.
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
            "warning: bar ?check failed at: (\\S+)", Pattern.CASE_INSENSITIVE);

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
