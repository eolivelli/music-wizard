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

import dev.olivelli.musicwizard.core.workspace.RunManifest;
import java.util.Map;

/**
 * What the run recorded about itself (#674), as the page's "what ran" section.
 *
 * <p>Renders the stages the manifest holds and nothing else. Nothing here
 * knows which stages exist, so a stage that begins recording appears without
 * this class being taught about it, and a workspace analysed before there was
 * a record says that rather than showing an empty table.
 */
final class ReportRun {

    private final RunManifest manifest;
    private final HtmlWriter out = new HtmlWriter();

    ReportRun(RunManifest manifest) {
        this.manifest = manifest;
    }

    /** What a stage's outcome is called on the page. */
    static String label(RunManifest.Outcome outcome) {
        return switch (outcome) {
            case COMPUTED -> "ran";
            case CACHED -> "from the cache";
            case SKIPPED -> "did not run";
            case FAILED -> "failed";
        };
    }

    /** Which of the page's stage colours an outcome is drawn in. */
    static String cssClass(RunManifest.Outcome outcome) {
        return switch (outcome) {
            case COMPUTED, CACHED -> "recorded";
            case SKIPPED -> "absent";
            case FAILED -> "failed";
        };
    }

    String render() {
        out.line("<section id=\"run\">");
        out.element("h2", "What ran").line("");
        if (manifest == null) {
            out.open("p", "class", "lede")
                    .text("This workspace was analysed by a build that recorded nothing"
                            + " about its own run, so this page cannot say which stages"
                            + " ran, what the recording decoded to, or which options"
                            + " steered it. Analysing it again writes that record (#674).")
                    .line("</p>");
            out.line("</section>");
            return out.toString();
        }
        out.open("p", "class", "lede")
                .text("The last analysis of this workspace, as it recorded itself: what"
                        + " each stage did, and the settings it was given. A stage that"
                        + " did not run says so, and one served from the cache says that"
                        + " instead of claiming to have run.")
                .line("</p>");
        provenance();
        settings();
        stages();
        out.line("</section>");
        return out.toString();
    }

    private void provenance() {
        out.line("<dl class=\"facts\">");
        entry("Music Wizard", manifest.musicWizardVersion());
        entry("Analysed", manifest.startedAt());
        entry("Finished", manifest.finishedAt());
        out.line("</dl>");
    }

    private void settings() {
        if (manifest.settings().isEmpty()) {
            return;
        }
        out.element("h3", "What it was told to do").line("");
        out.line("<dl class=\"facts\">");
        for (Map.Entry<String, String> setting : manifest.settings().entrySet()) {
            entry(setting.getKey(), setting.getValue());
        }
        out.line("</dl>");
    }

    private void stages() {
        if (manifest.stages().isEmpty()) {
            out.open("p", "class", "note")
                    .text("The record names no stage at all, which no completed analysis"
                            + " writes.")
                    .line("</p>");
            return;
        }
        out.element("h3", "Stage by stage").line("");
        out.line("<div class=\"run-stages\">");
        for (RunManifest.StageRun stage : manifest.stages()) {
            out.open("div", "class", "run-stage " + cssClass(stage.outcome()));
            out.element("span", stage.stage().replace('-', ' '), "class", "run-stage-name");
            out.element("span", label(stage.outcome()), "class", "status");
            if (stage.reason() != null && !stage.reason().isBlank()) {
                out.element("p", stage.reason(), "class", "run-stage-reason");
            }
            if (!stage.facts().isEmpty()) {
                out.line("<dl class=\"facts\">");
                for (Map.Entry<String, String> fact : stage.facts().entrySet()) {
                    entry(fact.getKey(), fact.getValue());
                }
                out.line("</dl>");
            }
            out.line("</div>");
        }
        out.line("</div>");
    }

    private void entry(String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.element("dt", name);
        out.element("dd", value);
        out.line("");
    }
}
