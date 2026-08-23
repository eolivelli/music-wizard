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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one run of the pipeline did, as opposed to what it decided.
 *
 * <p>A record of the run and never an input to one: no stage reads it and no
 * cache key is computed from it, so writing it cannot change what a workspace
 * would otherwise hold.
 *
 * <p>Stages are listed in the order they reported, and each names itself.
 * Nothing here enumerates the stages there are, so a stage begins reporting by
 * recording an entry and a reader shows what it finds — which is what lets a
 * page describe a manifest written by a build that knew more stages than it
 * does.
 *
 * <p>An entry says what the run did with a stage, which is not the same
 * question as whether the stage's output is in the score: a run served a
 * cached answer, or one that kept the words a previous analysis placed, did
 * not run the stage that produced what the score holds.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunManifest(
        int schemaVersion,
        String musicWizardVersion,
        String startedAt,
        String finishedAt,
        Map<String, String> settings,
        List<StageRun> stages) {

    /** Current manifest schema version. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunManifest {
        settings = settings == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    /** How a stage's run ended. */
    public enum Outcome {
        /** It ran here, in this run. */
        COMPUTED,
        /** Its answer came from the stage cache, so it did not run again. */
        CACHED,
        /** It was not asked for, or had nothing it could act on. */
        SKIPPED,
        /** It could not do its job, and the run carried on without it. */
        FAILED
    }

    /**
     * One stage's line in the record.
     *
     * @param stage   the stage's own name for itself, one entry per run
     * @param outcome how its run ended
     * @param reason  the one line a user would want with a non-computed
     *                outcome, or null
     * @param facts   what the stage chose to write down, in the order it wrote
     *                it; the keys are read as labels, so they are words rather
     *                than identifiers
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StageRun(
            String stage,
            Outcome outcome,
            String reason,
            Map<String, String> facts) {

        public StageRun {
            // A line with no stage or no outcome states nothing, and a reader
            // that has to allow for one cannot say anything without a caveat.
            // Refusing here makes a truncated file an unreadable manifest,
            // which every reader already handles, rather than a readable one
            // holding a line that means nothing.
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(outcome, "outcome");
            facts = facts == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        }

        /**
         * The same entry as it is reported by a run that was served the cached
         * answer.
         *
         * <p>Only a computed stage has an answer to be served, so that is the
         * only outcome this changes: a stage that did not run, or that failed,
         * replays as what it was.
         */
        public StageRun asCached() {
            return outcome == Outcome.COMPUTED
                    ? new StageRun(stage, Outcome.CACHED, reason, facts) : this;
        }
    }

    /** The entry a named stage left, if it left one. */
    public Optional<StageRun> stage(String name) {
        return stages.stream().filter(entry -> entry.stage().equals(name)).findFirst();
    }
}
