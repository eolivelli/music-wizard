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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The stage traces as JSON.
 *
 * <p>Reading one stage's trace never fails the file: a trace this build cannot
 * parse costs that stage's picture and leaves the rest of the page standing.
 * Reading the file itself does fail, like the manifest's, and for the same
 * reason — every caller reads it as optional.
 */
public final class RunTraceJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private RunTraceJson() {
    }

    public static String toJson(RunTraces traces) {
        try {
            return MAPPER.writeValueAsString(traces);
        } catch (IOException e) {
            throw new UncheckedIOException("could not serialize the run traces", e);
        }
    }

    public static RunTraces fromJson(String json) {
        try {
            return MAPPER.readValue(json, RunTraces.class);
        } catch (IOException e) {
            throw new UncheckedIOException("could not parse the run traces", e);
        }
    }

    /**
     * What a run collected, as the document it is written as.
     *
     * <p>Takes each stage's own record — or a tree already read from a previous
     * run's file, which is what a cached stage replays — and keeps them in the
     * order the stages recorded them.
     */
    public static RunTraces of(Map<String, ?> collected) {
        Map<String, JsonNode> traces = new LinkedHashMap<>();
        collected.forEach((stage, trace) -> traces.put(stage, MAPPER.valueToTree(trace)));
        return new RunTraces(RunTraces.CURRENT_SCHEMA_VERSION, traces);
    }

    static <T> Optional<T> read(JsonNode node, Class<T> type) {
        try {
            return Optional.ofNullable(MAPPER.treeToValue(node, type));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
