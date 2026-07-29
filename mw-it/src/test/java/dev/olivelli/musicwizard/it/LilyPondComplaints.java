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

package dev.olivelli.musicwizard.it;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading LilyPond's diagnostics for the one thing this suite asks it to check.
 *
 * <p>A failed bar check is the complaint the engraving tests exist for: it is
 * how a bar that does not fill its meter — the commonest way an emitter goes
 * wrong, and the one LilyPond will otherwise engrave without a murmur — becomes
 * visible. Two suites reached for it independently ({@link StaffNotationIT}
 * from #90, {@link TupletEngravingIT} from #92) and both spelled the same
 * assertion by hand, so it lives here once.
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
 * the {@code integration} job installs, while Homebrew ships 2.26. Pinning
 * either spelling makes the suite green on one and red on the other, and the
 * red one was CI — a permanently failing check that every merge walks past.
 *
 * <p>So the space is optional and the <em>position</em> is what gets asserted
 * on. That is the substance anyway: a bar check that fires says which moment
 * the bar had reached when it should have been at a bar line, and a test that
 * pins {@code 3/4} is saying the bar was a beat short rather than merely that
 * LilyPond said something. What is deliberately <em>not</em> asserted is how
 * many times it fires: 2.25.6 also stopped reporting more than the first failure
 * per context, and that is exactly the kind of incidental detail this class
 * exists to stop the suite depending on.
 *
 * <p>The {@code warning:} prefix is required rather than incidental, and it is
 * what keeps this from matching a file name or a quoted line of source. It is
 * English because {@code LilyPondRenderer} pins the child's message locale;
 * read the {@code speakEnglish} javadoc there before assuming it always will be.
 */
final class LilyPondComplaints {

    /**
     * A failed bar check, either spelling, capturing the moment it failed at.
     *
     * <p>Case-insensitive only because the assertions this replaced were, not
     * because LilyPond has ever varied the case.
     */
    private static final Pattern FAILED_BAR_CHECK = Pattern.compile(
            "warning: bar ?check failed at: (\\S+)", Pattern.CASE_INSENSITIVE);

    private LilyPondComplaints() {
    }

    /**
     * The moments at which LilyPond reported a failed bar check, in the order it
     * reported them — empty when it reported none, which is what a bar that sums
     * to its meter looks like.
     */
    static List<String> failedBarChecksIn(String lilypondOutput) {
        List<String> moments = new ArrayList<>();
        Matcher matcher = FAILED_BAR_CHECK.matcher(lilypondOutput);
        while (matcher.find()) {
            moments.add(matcher.group(1));
        }
        return moments;
    }
}
