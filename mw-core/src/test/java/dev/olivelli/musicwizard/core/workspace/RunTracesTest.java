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

package dev.olivelli.musicwizard.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What a stage records beyond its one line, and the file it survives in (#675). */
class RunTracesTest {

    private static BeatTrace beats() {
        return new BeatTrace(240.5, 120.25,
                new BeatTrace.Octave(true, 6.5, 0.04, 0.82, 3, 1, true),
                List.of(new BeatTrace.Window(0, 25, true, 240.5, 0.61, 0.88, 120.25,
                        List.of(new BeatTrace.Candidate(240.5, 0.47, true),
                                new BeatTrace.Candidate(120.25, 0.31, false)))));
    }

    @Nested
    @DisplayName("through the file a workspace keeps it in")
    class RoundTrip {

        @Test
        @DisplayName("a beat trace comes back as it went in")
        void beatTraceSurvives() {
            RunTraces written = RunTraceJson.of(Map.of(BeatTrace.STAGE, beats()));

            RunTraces read = RunTraceJson.fromJson(RunTraceJson.toJson(written));

            assertThat(read.schemaVersion()).isEqualTo(RunTraces.CURRENT_SCHEMA_VERSION);
            assertThat(read.trace(BeatTrace.STAGE, BeatTrace.class)).contains(beats());
        }

        @Test
        @DisplayName("a stage this build has no type for is carried, not dropped")
        void anUnknownStageSurvives() {
            // What a workspace written by a newer build looks like. Nothing
            // enumerates the stages there are, so a reader that dropped one
            // would silently narrow the record every time it rewrote it.
            String json = RunTraceJson.toJson(RunTraceJson.of(
                    Map.of("hummed-bass", Map.of("hummed", true))));

            RunTraces read = RunTraceJson.fromJson(json);

            assertThat(read.traces()).containsKey("hummed-bass");
            assertThat(RunTraceJson.toJson(read)).contains("hummed");
        }

        @Test
        @DisplayName("the stages keep the order they recorded in")
        void orderIsKept() {
            Map<String, Object> collected = new LinkedHashMap<>();
            collected.put("beats", beats());
            collected.put("aardvark", Map.of("first", "alphabetically, not chronologically"));

            RunTraces read = RunTraceJson.fromJson(
                    RunTraceJson.toJson(RunTraceJson.of(collected)));

            assertThat(read.traces().keySet()).containsExactly("beats", "aardvark");
        }
    }

    @Test
    @DisplayName("a trace this build cannot read is an absent trace, not a thrown one")
    void anUnreadableTraceIsAbsent() {
        // A reader has the same nothing to draw either way, and every caller
        // treats the trace as optional -- so failing here would cost the page
        // over a stage whose schema has moved on.
        RunTraces read = RunTraceJson.fromJson(
                "{\"schemaVersion\":1,\"traces\":{\"beats\":\"a sentence, not a trace\"}}");

        assertThat(read.trace(BeatTrace.STAGE, BeatTrace.class)).isEmpty();
        assertThat(read.trace("never-ran", BeatTrace.class)).isEmpty();
    }

    @Nested
    @DisplayName("collected by the log a stage writes its line through")
    class Collection {

        @Test
        @DisplayName("the trace is recorded with the line, and replaces it with the line")
        void theTraceFollowsTheLine() {
            // A stage that reports twice has said one thing about it, its last
            // -- and the trace has to move with the line, or the page shows a
            // second line's outcome under the first line's evidence.
            RunLog log = new RunLog();
            log.stage("beats").trace(beats()).computed();
            assertThat(log.traces()).containsKey("beats");

            log.stage("beats").skipped("thought better of it");

            assertThat(log.stages()).singleElement()
                    .extracting(RunManifest.StageRun::outcome)
                    .isEqualTo(RunManifest.Outcome.SKIPPED);
            assertThat(log.traces()).isEmpty();
        }

        @Test
        @DisplayName("a trace recorded before a terminal is not recorded at all")
        void anUnfinishedStageRecordsNothing() {
            RunLog log = new RunLog();
            log.stage("beats").trace(beats());

            assertThat(log.traces()).isEmpty();
        }

        @Test
        @DisplayName("a branch's traces are the parent's too")
        void aBranchReachesTheParent() {
            // The stages under a cache key are collected separately so their
            // lines keep their own order, and the run still has to write all
            // of them out.
            RunLog run = new RunLog();
            RunLog keyed = run.branch();

            keyed.stage("beats").trace(beats()).computed();

            assertThat(keyed.traces()).containsOnlyKeys("beats");
            assertThat(run.traces()).containsOnlyKeys("beats");
        }
    }
}
