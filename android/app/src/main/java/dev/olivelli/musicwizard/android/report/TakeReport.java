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

package dev.olivelli.musicwizard.android.report;

import dev.olivelli.musicwizard.android.mw.RecordingStore;

/**
 * One take as it will read on the inbox issue.
 *
 * @param takeName        the take's name in the library
 * @param durationSeconds its length
 * @param comment         what the player typed; may be blank
 * @param chart           the chart the phone produced, or null if it has not
 *                        analysed this take
 * @param appVersion      this app's {@code versionName}
 * @param platform        Android version and device, one line
 */
public record TakeReport(String takeName, double durationSeconds, String comment,
                         String chart, String appVersion, String platform) {

    /**
     * The comment body, in GitHub-flavoured markdown.
     *
     * <p>The player's own words are blockquoted and nothing else is, so a reader
     * scrolling the inbox can tell at a glance which lines are the ground truth
     * being filed and which the phone's guess at it. Everything else — the link,
     * the chart, the versions — is context for reproducing the take on the
     * desktop.
     */
    public String body(String assetName, String assetUrl) {
        StringBuilder out = new StringBuilder();
        out.append("**").append(takeName).append("** · ")
                .append(RecordingStore.formatDuration(durationSeconds))
                .append(" · app ").append(appVersion)
                .append(" · ").append(platform).append("\n\n");

        String said = comment == null ? "" : comment.trim();
        if (said.isEmpty()) {
            out.append("_The player left no comment._\n\n");
        } else {
            for (String line : said.split("\n", -1)) {
                out.append("> ").append(line).append('\n');
            }
            out.append('\n');
        }

        out.append('[').append(assetName).append("](").append(assetUrl).append(")\n\n");

        if (chart == null) {
            out.append("The phone has not analysed this take.\n");
        } else {
            out.append("The phone's chart:\n\n```text\n").append(chart);
            if (!chart.endsWith("\n")) {
                out.append('\n');
            }
            out.append("```\n");
        }
        return out.toString();
    }
}
