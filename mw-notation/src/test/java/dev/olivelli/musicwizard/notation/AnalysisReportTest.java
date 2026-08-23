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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
