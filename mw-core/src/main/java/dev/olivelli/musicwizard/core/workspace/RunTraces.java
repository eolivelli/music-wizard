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
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The structured evidence stages leave behind, beside the one line each writes
 * into the {@link RunManifest} (#675).
 *
 * <p>The manifest's facts are a stage's own headline, one label to one value; a
 * candidate list or a per-frame reading is neither, and putting either there
 * would cost the manifest the property that makes it worth having, that a
 * person can read it. So a trace is a document of the stage's own shape, held
 * here under the stage's name and parsed by whatever draws it.
 *
 * <p>Nothing here enumerates the stages there are, and a stage this build has
 * never heard of is carried rather than dropped, so a workspace stays readable
 * across builds in both directions. Like the manifest it is a record and never
 * an input: no cache key is computed from it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunTraces(int schemaVersion, Map<String, JsonNode> traces) {

    /** Current trace schema version. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunTraces {
        traces = traces == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(traces));
    }

    /** No stage recorded anything, which is every workspace analysed before #675. */
    public static RunTraces empty() {
        return new RunTraces(CURRENT_SCHEMA_VERSION, Map.of());
    }

    /**
     * A named stage's trace as the type that stage records, or empty where the
     * stage left none <em>or</em> left one this build cannot read.
     *
     * <p>The two are not distinguished on purpose. A reader has the same
     * nothing to draw either way, and every caller treats the trace as optional
     * — which is what keeps a trace a record rather than something an analysis
     * depends on.
     */
    public <T> Optional<T> trace(String stage, Class<T> type) {
        JsonNode node = traces.get(stage);
        return node == null ? Optional.empty() : RunTraceJson.read(node, type);
    }
}
