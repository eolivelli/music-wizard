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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.olivelli.musicwizard.core.workspace.RunManifest.Outcome;
import dev.olivelli.musicwizard.core.workspace.RunManifest.StageRun;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The record of a run: what a stage may say about itself, and what survives a
 * trip through the file a workspace keeps it in (#674).
 */
class RunManifestTest {

    private static RunManifest manifest(List<StageRun> stages) {
        return new RunManifest(RunManifest.CURRENT_SCHEMA_VERSION, "1.2.3",
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:09Z",
                new LinkedHashMap<>(Map.of("source", "audio")), stages);
    }

    @Nested
    @DisplayName("the log a run writes into")
    class Log {

        @Test
        @DisplayName("keeps the stages in the order they reported")
        void keepsTheOrderTheyReported() {
            RunLog log = new RunLog();
            log.stage("decode").computed();
            log.stage("beats").computed();
            log.stage("melody").skipped("not asked for");

            assertThat(log.stages()).extracting(StageRun::stage)
                    .containsExactly("decode", "beats", "melody");
            assertThat(log.stages().get(2).outcome()).isEqualTo(Outcome.SKIPPED);
            assertThat(log.stages().get(2).reason()).isEqualTo("not asked for");
        }

        @Test
        @DisplayName("keeps one line per stage, its last, where it stood")
        void aStageHasOneLine() {
            // What a run that keeps lyrics from a previous analysis and then
            // transcribes over them does. Two lines would report the same
            // stage twice and let a reader take either.
            RunLog log = new RunLog();
            log.stage("lyrics").computed("kept from the previous analysis");
            log.stage("beats").computed();
            log.stage("lyrics").fact("words from", "the recording").computed();

            assertThat(log.stages()).extracting(StageRun::stage)
                    .containsExactly("lyrics", "beats");
            assertThat(log.stages().get(0).reason()).isNull();
            assertThat(log.stages().get(0).facts())
                    .containsExactly(Map.entry("words from", "the recording"));
        }

        @Test
        @DisplayName("drops a fact it has no value for, so a caller need not branch")
        void dropsEmptyFacts() {
            RunLog log = new RunLog();
            log.stage("decode").fact("format", "MP3").fact("channels", null)
                    .fact("provider", "  ").computed();

            assertThat(log.stages().get(0).facts()).containsOnlyKeys("format");
        }

        @Test
        @DisplayName("records what another log collected under the same rule")
        void recordsAnotherLogsStages() {
            RunLog keyed = new RunLog();
            keyed.stage("decode").computed();
            RunLog run = new RunLog();
            run.stage("separation").skipped("--skip-separation");

            run.recordAll(keyed.stages().stream().map(StageRun::asCached).toList());

            assertThat(run.stages()).extracting(StageRun::stage)
                    .containsExactly("separation", "decode");
            assertThat(run.stages().get(1).outcome()).isEqualTo(Outcome.CACHED);
        }

        @Test
        @DisplayName("a branch keeps its own lines and puts them where they happened")
        void aBranchRecordsIntoBoth() {
            // The stages under a cache key are collected on their own and
            // stored with what they computed, while a stage that is not keyed
            // runs in among them -- so a branch that only merged at the end
            // would report them out of the order they ran in.
            RunLog run = new RunLog();
            RunLog keyed = run.branch();
            keyed.stage("decode").computed();
            run.stage("separation").computed();
            keyed.stage("melody").computed();

            assertThat(keyed.stages()).extracting(StageRun::stage)
                    .containsExactly("decode", "melody");
            assertThat(run.stages()).extracting(StageRun::stage)
                    .containsExactly("decode", "separation", "melody");
        }
    }

    @Nested
    @DisplayName("a stage's line")
    class Line {

        @Test
        @DisplayName("means nothing without a stage and an outcome, so it is refused")
        void refusesALineThatStatesNothing() {
            assertThatThrownBy(() -> new StageRun(null, Outcome.COMPUTED, null, Map.of()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new StageRun("decode", null, null, Map.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("replays as served from the cache only where there was an answer")
        void onlyAnAnswerComesFromTheCache() {
            // A stage that did not run, or that failed, has nothing the cache
            // could have held; saying it came from there would put it in the
            // colour a stage that ran is drawn in.
            assertThat(new StageRun("decode", Outcome.COMPUTED, null, Map.of())
                    .asCached().outcome()).isEqualTo(Outcome.CACHED);
            assertThat(new StageRun("melody", Outcome.SKIPPED, "not asked for", Map.of())
                    .asCached().outcome()).isEqualTo(Outcome.SKIPPED);
            assertThat(new StageRun("separation", Outcome.FAILED, "no model", Map.of())
                    .asCached().outcome()).isEqualTo(Outcome.FAILED);
        }

        @Test
        @DisplayName("keeps its facts in the order the stage wrote them")
        void keepsFactOrder() {
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("format", "MP3");
            facts.put("read as", "mono at 22050 Hz");

            StageRun stage = new StageRun("decode", Outcome.COMPUTED, null, facts);
            facts.put("added later", "no");

            assertThat(stage.facts().keySet())
                    .containsExactly("format", "read as");
        }
    }

    @Nested
    @DisplayName("on disk")
    class OnDisk {

        @Test
        @DisplayName("round-trips every field, in order")
        void roundTrips() {
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("format", "MP3, MPEG-1, Layer 3");
            facts.put("read as", "mono at 22050 Hz");
            RunManifest original = manifest(List.of(
                    new StageRun("decode", Outcome.COMPUTED, null, facts),
                    new StageRun("beats", Outcome.CACHED, "reused", Map.of()),
                    new StageRun("separation", Outcome.FAILED, "no model", Map.of())));

            RunManifest read = RunManifestJson.fromJson(RunManifestJson.toJson(original));

            assertThat(read).isEqualTo(original);
            assertThat(read.stage("decode").orElseThrow().facts().keySet())
                    .containsExactly("format", "read as");
            assertThat(read.stage("nothing-of-the-sort")).isEmpty();
        }

        @Test
        @DisplayName("opens a record written by a build that knew more than this one")
        void ignoresUnknownProperties() {
            String fromTheFuture = """
                    {
                      "schemaVersion": 1,
                      "musicWizardVersion": "9.9.9",
                      "somethingNewEntirely": {"a": 1},
                      "stages": [
                        {"stage": "chroma", "outcome": "COMPUTED", "tuning": -12.5}
                      ]
                    }""";

            RunManifest read = RunManifestJson.fromJson(fromTheFuture);

            assertThat(read.stage("chroma").orElseThrow().outcome())
                    .isEqualTo(Outcome.COMPUTED);
            assertThat(read.settings()).isEmpty();
        }

        @Test
        @DisplayName("refuses an outcome it does not know rather than guessing one")
        void refusesAnUnknownOutcome() {
            // Every reader treats an unreadable record as no record and says
            // so. Reading DEGRADED as one of the four would report the wrong
            // thing about the run instead.
            String fromTheFuture = """
                    {"schemaVersion": 1,
                     "stages": [{"stage": "separation", "outcome": "DEGRADED"}]}""";

            assertThatThrownBy(() -> RunManifestJson.fromJson(fromTheFuture))
                    .hasMessageContaining("run manifest");
        }

        @Test
        @DisplayName("carries stage lines on their own, which is how they travel with a cache entry")
        void stagesTravelOnTheirOwn() {
            List<StageRun> stages = List.of(
                    new StageRun("decode", Outcome.COMPUTED, null,
                            Map.of("read as", "mono at 22050 Hz")),
                    new StageRun("melody", Outcome.SKIPPED, "not asked for", Map.of()));

            assertThat(RunManifestJson.stagesFromJson(RunManifestJson.stagesToJson(stages)))
                    .isEqualTo(stages);
        }
    }
}
