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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The run manifest as JSON.
 *
 * <p>Unknown properties are ignored so that a workspace written by a newer
 * build still opens; an outcome that build invented is not, because a reader
 * silently treating it as one it knows would report the wrong thing about the
 * run. Every caller reads the manifest as optional, so failing here costs the
 * record and nothing else.
 */
public final class RunManifestJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT)
            // A file someone reads: a stage with nothing to add says nothing
            // rather than carrying a row of empty fields.
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    private static final TypeReference<List<RunManifest.StageRun>> STAGE_LIST =
            new TypeReference<>() { };

    private RunManifestJson() {
    }

    public static String toJson(RunManifest manifest) {
        try {
            return MAPPER.writeValueAsString(manifest);
        } catch (IOException e) {
            throw new UncheckedIOException("could not serialize the run manifest", e);
        }
    }

    public static RunManifest fromJson(String json) {
        try {
            return MAPPER.readValue(json, RunManifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException("could not parse the run manifest", e);
        }
    }

    /** Stage entries on their own, which is what travels with a cached stage result. */
    public static String stagesToJson(List<RunManifest.StageRun> stages) {
        try {
            return MAPPER.writeValueAsString(stages);
        } catch (IOException e) {
            throw new UncheckedIOException("could not serialize the run manifest", e);
        }
    }

    public static List<RunManifest.StageRun> stagesFromJson(String json) {
        try {
            return MAPPER.readValue(json, STAGE_LIST);
        } catch (IOException e) {
            throw new UncheckedIOException("could not parse the run manifest", e);
        }
    }
}
