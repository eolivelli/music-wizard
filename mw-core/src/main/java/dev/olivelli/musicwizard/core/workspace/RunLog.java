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

import dev.olivelli.musicwizard.core.workspace.RunManifest.Outcome;
import dev.olivelli.musicwizard.core.workspace.RunManifest.StageRun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects what the stages of one run report about themselves, in the order
 * they report it.
 *
 * <p>Handed down the pipeline so that a stage can record its own line without
 * returning it: what a stage did is not part of what it computed, and threading
 * it through return types would put the record in every signature between here
 * and there. A stage that is given no log records nothing and behaves
 * identically, which is why every entry point that takes one has an overload
 * that does not.
 *
 * <p>Not safe for use from more than one thread; a run is one thread's work.
 */
public final class RunLog {

    private final List<StageRun> stages = new ArrayList<>();

    /** A stage about to record what it did. */
    public final class Stage {

        private final String name;
        private final Map<String, String> facts = new LinkedHashMap<>();

        private Stage(String name) {
            this.name = name;
        }

        /**
         * One thing worth writing down. The name is read as a label by
         * whatever renders the manifest, so it is a word rather than an
         * identifier; a null or blank value is dropped, so a caller need not
         * branch on facts it may not have.
         */
        public Stage fact(String name, Object value) {
            String text = value == null ? null : String.valueOf(value);
            if (text != null && !text.isBlank()) {
                facts.put(name, text);
            }
            return this;
        }

        /** It ran here. */
        public void computed() {
            record(Outcome.COMPUTED, null);
        }

        /** It ran here, with something a user would want to know about how. */
        public void computed(String reason) {
            record(Outcome.COMPUTED, reason);
        }

        /** Its answer came from the stage cache, so it did not run again. */
        public void cached(String reason) {
            record(Outcome.CACHED, reason);
        }

        /** It was not asked for, or had nothing to act on. */
        public void skipped(String reason) {
            record(Outcome.SKIPPED, reason);
        }

        /** It could not do its job and the run carried on. */
        public void failed(String reason) {
            record(Outcome.FAILED, reason);
        }

        private void record(Outcome outcome, String reason) {
            put(new StageRun(name, outcome, reason, facts));
        }
    }

    /** A stage's entry, which is added to the log by one of {@link Stage}'s terminals. */
    public Stage stage(String name) {
        return new Stage(Objects.requireNonNull(name, "name"));
    }

    /** Adds entries another log collected, keeping their order. */
    public void recordAll(List<StageRun> entries) {
        entries.forEach(this::put);
    }

    /**
     * One line per stage, and a later line replaces an earlier one where it
     * stood: a stage that reports twice in a run — lyrics kept from a previous
     * analysis and then transcribed over — has said one thing about it, its
     * last.
     */
    private void put(StageRun entry) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).stage().equals(entry.stage())) {
                stages.set(i, entry);
                return;
            }
        }
        stages.add(entry);
    }

    /** What has been recorded so far. */
    public List<StageRun> stages() {
        return List.copyOf(stages);
    }
}
