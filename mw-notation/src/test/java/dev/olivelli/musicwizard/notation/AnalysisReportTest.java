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

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.RunManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The analysis report against its golden files, and against the two properties
 * a golden file cannot state.
 *
 * <p>The first is that the page is <em>self-contained</em>: it has to open on a
 * phone with no network, so a URL that reaches one is a defect however good the
 * page looks. The second is that a page describing a workspace where only some
 * stages ran says so for each — which is checked by rendering exactly that.
 */
class AnalysisReportTest {

    private static final AnalysisReport.Recording RECORDING =
            new AnalysisReport.Recording("fixture.mp3",
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    "2026-01-01T00:00:00Z");

    @Test
    @DisplayName("a workspace every stage wrote to")
    void everyStage() {
        Goldens.assertGolden("report-full", ".html",
                AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING));
    }

    @Test
    @DisplayName("a workspace analysed without melody or lyrics")
    void chordsOnly() {
        Goldens.assertGolden("report-chords-only", ".html",
                AnalysisReport.toHtml(ReportFixtures.chordsOnly(), RECORDING));
    }

    @Test
    @DisplayName("a score carrying nothing but a tempo map")
    void bare() {
        Goldens.assertGolden("report-bare", ".html",
                AnalysisReport.toHtml(ReportFixtures.bare(), AnalysisReport.Recording.unknown()));
    }

    @Test
    @DisplayName("a workspace whose run recorded itself")
    void withARunManifest() {
        Goldens.assertGolden("report-with-manifest", ".html", AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run()));
    }

    @Test
    @DisplayName("a workspace with no record of its run says so rather than inventing one")
    void withoutARunManifest() {
        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING);

        assertThat(page).contains("recorded nothing about its own run");
        // The four the record would answer keep saying they are unanswered.
        assertThat(page).contains(
                "does not say which of the two happened",
                "does not say whether a separator ran",
                "does not say which signal these notes were read from",
                "does not say whether the words were supplied or transcribed");
        assertThat(statusOf(page, "decode")).isEqualTo("no trace kept");
        assertThat(page).doesNotContain("Last run");
    }

    @Test
    @DisplayName("what the run did and what the score holds are two statements, not one")
    void theRunAndTheScoreAreStatedSeparately() {
        String page = AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run());

        // Where the score cannot speak for a stage, the run's outcome is what
        // the page has.
        assertThat(statusOf(page, "decode")).isEqualTo("ran");
        assertThat(statusOf(page, "separation")).isEqualTo("failed");
        // Where it can, the badge stays the score's, because the phase goes on
        // to draw what the score holds -- and the run's line says what the run
        // did with it, which is a different question and here a different
        // answer.
        assertThat(statusOf(page, "beats")).isEqualTo("output on disk");
        assertThat(statusOf(page, "melody")).isEqualTo("output on disk");
        assertThat(page).contains("Last run</span>beats: from the cache");
        assertThat(page).contains("Last run</span>melody: did not run — not asked for");
        // Every gap the record does not fill is still stated.
        assertThat(page).contains("(#675)", "(#676)", "(#677)", "(#678)", "(#679)",
                "(#680)", "(#684)");
    }

    @Test
    @DisplayName("reading a MIDI file symbolically is what the decode phase says happened")
    void theSymbolicPathAnswersTheDecodePhase() {
        // A MIDI workspace decodes nothing and its record names no decode, so
        // the page must not say the record is silent about which of the two
        // happened. The record says exactly which.
        RunManifest readSymbolically = new RunManifest(
                RunManifest.CURRENT_SCHEMA_VERSION, "1.2.3-test",
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z", Map.of(),
                List.of(new RunManifest.StageRun("read-midi",
                        RunManifest.Outcome.COMPUTED, null, Map.of("tracks", "4"))));

        String page = AnalysisReport.toHtml(
                ReportFixtures.chordsOnly(), RECORDING, readSymbolically);

        assertThat(statusOf(page, "decode")).isEqualTo("ran");
        assertThat(page).contains("Last run</span>read midi: ran");
        assertThat(page).doesNotContain("does not say which of the two happened");
        assertThat(page).doesNotContain("recorded nothing about its own run");
    }

    @Test
    @DisplayName("a stage that ran and found nothing is not badged as one that did not run")
    void aStageThatRanAndFoundNothingIsNotCalledSkipped() {
        // The badge is the score's statement and the line under it is the
        // run's, so the two must be worded on their own axes: a tracker that
        // ran and found no pulse leaves a score with no grid, and neither
        // sentence may deny the other.
        RunManifest foundNothing = new RunManifest(
                RunManifest.CURRENT_SCHEMA_VERSION, "1.2.3-test",
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z", Map.of(),
                List.of(new RunManifest.StageRun("beats", RunManifest.Outcome.COMPUTED,
                        "no pulse was found", Map.of())));

        String page = AnalysisReport.toHtml(ReportFixtures.bare(), RECORDING, foundNothing);

        assertThat(statusOf(page, "beats")).isEqualTo("nothing in the score");
        assertThat(page).contains("Last run</span>beats: ran — no pulse was found");
        assertThat(page).doesNotContain("<span class=\"status\">did not run</span>");
    }

    @Test
    @DisplayName("a stage this build has no phase for is still reported")
    void anUnknownStageIsNotDropped() {
        // What a workspace analysed by a newer build looks like. The page has
        // no phase to put it under and must not swallow it.
        assertThat(AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run()))
                .contains("hummed bass");
    }

    @Test
    @DisplayName("the stages that left nothing behind are named, not left out")
    void theMissingStagesAreStated() {
        String page = AnalysisReport.toHtml(ReportFixtures.chordsOnly(), RECORDING);
        // The phases exist whatever the workspace holds: an absent stage that
        // simply vanished from the page would read as a stage that never was.
        assertThat(page).contains("id=\"phase-melody\"", "id=\"phase-lyrics\"",
                "id=\"phase-reduction\"");
        assertThat(page).contains("This score holds no melody",
                "This score holds no lyrics",
                "There is no melody to reduce");
        assertThat(page).contains("No lane was drawn for: Melody, Playable part, Syllables");
    }

    @Test
    @DisplayName("no stage is asserted to have run")
    void noStageIsClaimedToHaveRun() {
        // A score read from a MIDI file decodes no samples, separates nothing
        // and fits no chroma, and the workspace does not record which path
        // produced it -- so the page may describe those stages and may not
        // claim them. Their status label is the one that says so.
        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING);
        for (String phase : new String[] {"decode", "separation", "chroma"}) {
            assertThat(statusOf(page, phase)).isEqualTo("no trace kept");
        }
        assertThat(page).contains("These are the stages of the audio pipeline");
        assertThat(page).contains(
                "A score read from a MIDI file has its chords named from the notes",
                "A score read from a MIDI file takes the key its own meta event declares");
    }

    @Test
    @DisplayName("an absent key names both ways of not having one")
    void theKeysAbsenceIsWordedForBothPaths() {
        // A MIDI file that declares no key signature carries none however much
        // of it sounds, so "no chord sounds" is not the only way to get here.
        assertThat(AnalysisReport.toHtml(ReportFixtures.bare(), RECORDING))
                .contains("a MIDI file that declares no key signature leaves none either")
                .doesNotContain("which is what happens when no chord sounds.");
    }

    @Test
    @DisplayName("the melody's absence is worded as the renderer words it (#500)")
    void theMelodyAdviceIsNotRestated() {
        // "--melody is what writes one" is advice this page cannot keep either:
        // on a MIDI workspace the flag does nothing and no melody role is ever
        // assigned.
        assertThat(AnalysisReport.toHtml(ReportFixtures.chordsOnly(), RECORDING))
                .contains("This score holds no melody part; see --melody on analyze.")
                .doesNotContain("is what writes one");
    }

    @Test
    @DisplayName("the last syllable to finish is the one reported, not the last to start")
    void theLastSyllableIsTheLatestEnd() {
        // Sung spans overlap, and the words are ordered by where they start.
        assertThat(AnalysisReport.toHtml(ReportFixtures.overlappingSyllables(), RECORDING))
                .contains("<dt>Last sung at</dt><dd>0:07</dd>");
    }

    @Test
    @DisplayName("nothing on the page reaches the network")
    void thePageIsSelfContained() {
        for (Score score : new Score[] {ReportFixtures.everything(),
                ReportFixtures.chordsOnly(), ReportFixtures.bare()}) {
            String page = AnalysisReport.toHtml(score, RECORDING);
            // The SVG namespace is a name rather than a fetch -- no browser
            // resolves it -- so it is the one URL allowed through.
            String withoutNamespace = page.replace("http://www.w3.org/2000/svg", "");
            assertThat(withoutNamespace)
                    .as("a report that fetches anything cannot open from a phone")
                    .doesNotContain("http://", "https://", "//cdn", "src=", "@import", "url(");
        }
    }

    @Test
    @DisplayName("the inlined resources cannot end their own element")
    void theInlinedResourcesAreInert() {
        // A style sheet or a script holding its own closing tag ends the element
        // early and spills the rest of itself into the document as text.
        assertThat(resource("report.css")).doesNotContain("</style", "</script");
        assertThat(resource("report.js")).doesNotContain("</script", "</style");
    }

    @Test
    @DisplayName("user text is escaped wherever it lands")
    void userTextIsEscaped() {
        Score score = ReportFixtures.everything()
                .withMetadata("<script>alert(1)</script>", "A & B \"quoted\"");
        String page = AnalysisReport.toHtml(score, new AnalysisReport.Recording(
                "<img src=x>.mp3", "&amp;", "'"));
        assertThat(page).doesNotContain("<script>alert(1)</script>", "<img src=x>");
        assertThat(page).contains("&lt;script&gt;alert(1)&lt;/script&gt;",
                "A &amp; B \"quoted\"", "&lt;img src=x&gt;.mp3", "&amp;amp;");
        // One script element, the report's own, and no second opener smuggled
        // through a title.
        assertThat(count(page, "<script")).isEqualTo(1);
    }

    @Test
    @DisplayName("the two views stack the same lanes, and the strip is never the narrower")
    void theTwoViewsAgree() {
        // A short clip, where the strip's own scale already fits a page, and a
        // recording long enough that it cannot: the overview must summarise in
        // the second case and must not magnify in the first.
        double[] widths = viewWidths(ReportFixtures.everything());
        assertThat(widths[1]).isEqualTo(widths[0]);
        double[] longer = viewWidths(Score.empty(TempoMap.constant(120), 200));
        assertThat(longer[1]).isGreaterThan(longer[0]);
    }

    /** The overview's width then the strip's, having checked they stack alike. */
    private static double[] viewWidths(Score score) {
        String page = AnalysisReport.toHtml(score, RECORDING);
        Matcher views = Pattern.compile("<svg class=\"(mw-overview|mw-strip)\" "
                + "viewBox=\"0 0 ([0-9.]+) ([0-9.]+)\"").matcher(page);
        assertThat(views.find()).isTrue();
        assertThat(views.group(1)).isEqualTo("mw-overview");
        double overview = Double.parseDouble(views.group(2));
        String height = views.group(3);
        assertThat(views.find()).isTrue();
        assertThat(views.group(1)).isEqualTo("mw-strip");
        assertThat(views.group(3)).as("the two views stack the same lanes").isEqualTo(height);
        return new double[] {overview, Double.parseDouble(views.group(2))};
    }

    /** The status label a phase carries, read out of its heading. */
    private static String statusOf(String page, String phase) {
        Matcher heading = Pattern.compile("id=\"phase-" + phase
                + "\">\\s*<h3>.*?<span class=\"status\">([^<]*)</span>", Pattern.DOTALL)
                .matcher(page);
        assertThat(heading.find()).as("the %s phase is on the page", phase).isTrue();
        return heading.group(1);
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }

    private static String resource(String name) {
        try (InputStream in = AnalysisReport.class.getResourceAsStream("/report/" + name)) {
            assertThat(in).as("the report resource %s ships with the module", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read the report resource " + name, e);
        }
    }
}
