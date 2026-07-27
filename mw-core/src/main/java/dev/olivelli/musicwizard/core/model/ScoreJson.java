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

package dev.olivelli.musicwizard.core.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes a {@link Score} as JSON.
 *
 * <p>Exists so that the mapper is configured in exactly one place. Two settings
 * are load-bearing rather than incidental: {@link Jdk8Module} is required for
 * the model's {@code Optional} fields, and unknown properties must be ignored
 * so that a score written by a newer build still opens.
 */
public final class ScoreJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ScoreJson() {
    }

    /** The configured mapper, for callers that need to embed a score in a larger document. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Score score) {
        try {
            return MAPPER.writeValueAsString(score);
        } catch (IOException e) {
            throw new UncheckedIOException("could not serialize score", e);
        }
    }

    public static Score fromJson(String json) {
        try {
            return MAPPER.readValue(json, Score.class);
        } catch (IOException e) {
            throw new UncheckedIOException("could not parse score", e);
        }
    }

    public static void write(Path file, Score score) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, toJson(score));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write score to " + file, e);
        }
    }

    public static Score read(Path file) {
        try {
            return fromJson(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read score from " + file, e);
        }
    }
}
