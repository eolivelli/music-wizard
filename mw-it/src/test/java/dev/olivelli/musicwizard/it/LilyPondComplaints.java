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

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading LilyPond's diagnostics, and deciding what counts as a clean run.
 *
 * <p>Two questions, and the module needs both. {@link #complaintsIn} is the
 * broad one — every line LilyPond wrote that mentions a warning or an error —
 * and it is what an engraving test asserts is empty. {@link #failedBarChecksIn}
 * is the narrow one, for the tests that need to say <em>which</em> bar was
 * short rather than merely that something was said.
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
 * <p>The {@code warning:} prefix is required rather than incidental, and what
 * it keeps out is a <em>quoted line of source</em> — LilyPond echoes the
 * offending line back at you, so a bar check that failed and was then fixed can
 * sit in a comment in the file being engraved. A file merely <em>named</em>
 * after the phrase is held out by the moment, not by the prefix; round 3 of
 * review measured which half does the work in each case, after round 2
 * corrected the same overclaim one file over and left this one standing.
 *
 * <p>The prefix is English because {@code LilyPondRenderer} pins the child's
 * message locale; read the {@code speakEnglish} javadoc there before assuming
 * it always will be.
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

    /**
     * The one complaint the tuplet suite tolerates, and the reasoning.
     *
     * <p>Every other line mentioning a warning or an error fails
     * {@link #assertEngravedCleanly}, because that is what makes engraving worth
     * doing at all: a bar that does not fill its meter is a {@code warning:}
     * line and nothing else catches it.
     *
     * <p>This one is different in kind and the difference is checkable rather
     * than asserted. It is a complaint about <em>spacing</em> — LilyPond cannot
     * fit the little {@code 3} beside a steeply angled beam — raised from its
     * own layout engine about its own output, not about the source it was given.
     * The music is right, the bar sums, the PDF is produced and the exit status
     * is zero. Nothing this emitter could write differently would avoid it
     * without writing a different rhythm.
     *
     * <p>It is <b>reachable from ordinary material</b>, which is why this is a
     * decision taken deliberately rather than a surprise taken later: round 3 of
     * review on #92 ran real {@code Quantizer} output through the emitter and
     * found it in about one stave in eighty, in 4/4, 3/4, 3/2 and 7/8 — four of
     * the twelve meters this project targets. It needs partial sixteenth-triplet
     * brackets among rests and ties, which is exactly what a quantized
     * performance produces and exactly what a bar with every grid position
     * filled does not.
     * {@link TupletEngravingIT#theToleratedComplaintIsReachableAndIsOnlyThisOne}
     * drives review's own material through this emitter and engraves the result,
     * so the constant is pinned to a case in the repository rather than to a
     * memory of one — and to a case <em>this</em> emitter produces rather than to
     * hand-copied LilyPond.
     * {@link LilyPondComplaintsTest#theToleranceIsNarrowerThanTheWordItContains}
     * pins how little it covers. See #136, which also records that the printed
     * page is fine where it fires: the number is placed above the beam, not lost.
     *
     * <p><b>It is not granted by default</b>, and after round 1 of review on
     * #164 it is not granted by the helper either: a caller that wants it names
     * it, so a chord-chart suite cannot acquire a carve-out nothing it engraves
     * can reach by picking the obviously-named assertion. Only
     * {@link TupletEngravingIT} passes it.
     */
    static final String TOLERATED_COMPLAINT =
            "programming error: not enough space for tuplet number against beam";

    private LilyPondComplaints() {
    }

    /**
     * Every line on which LilyPond complained, in the order it wrote them.
     *
     * <p>Selected case-insensitively on the two words LilyPond prefixes its
     * diagnostics with, which is deliberately over-inclusive: being generous
     * about what counts as a complaint can only produce a loud failure, while
     * being stingy produces a silent pass, and a silent pass is the failure this
     * module keeps finding. It also means a line quoting the offending source
     * counts, which is fine — an engraving with nothing wrong quotes nothing.
     */
    static List<String> complaintsIn(String lilypondOutput) {
        return lilypondOutput.lines()
                .filter(line -> {
                    String lower = line.toLowerCase(Locale.ROOT);
                    return lower.contains("warning") || lower.contains("error");
                })
                .toList();
    }

    /**
     * Fails unless LilyPond produced a page and said nothing about it.
     *
     * <p>One helper rather than the same two lines at each of nine call sites,
     * and it is here rather than in an {@code *IT} so that the guard on how wide
     * the tolerance is can run in {@code mvn verify} — a tolerance whose only
     * test sits behind {@code -Pintegration} can widen without anyone noticing,
     * which is #155 and, one class over, #148.
     *
     * <p><b>Tolerating nothing is the default, and anything tolerated is named
     * at the call site.</b> Round 1 of review on #164 pointed out the hazard in
     * the shape this replaced: a single helper carrying
     * {@link #TOLERATED_COMPLAINT} silently, whose javadoc had to <em>ask</em>
     * chord-chart callers not to use it. A carve-out nothing a suite engraves
     * can reach is the dead carve-out #92 spent two review rounds avoiding, and
     * an argument makes acquiring one a visible choice rather than a default.
     *
     * <p>Tolerated lines are matched exactly, where the selection in
     * {@link #complaintsIn} is case-insensitive. The two directions are
     * deliberately different, for the reason given there. Round 5 of review on
     * #92 found a trailing {@code strip()} on the match inert — LilyPond does not
     * indent its diagnostics — so it is gone rather than kept as reassurance.
     *
     * @param tolerated complaint lines this caller accepts, matched in full;
     *                  empty for every caller but {@link TupletEngravingIT}
     */
    static void assertEngravedCleanly(String name, LilyPondRenderer.Result result,
                                      String... tolerated) {
        assertThat(result.succeeded()).as("%s: %s", name, result.output()).isTrue();
        List<String> allowed = List.of(tolerated);
        List<String> complaints = complaintsIn(result.output()).stream()
                .filter(line -> !allowed.contains(line))
                .toList();
        assertThat(complaints).as("%s engraved with complaints", name).isEmpty();
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
