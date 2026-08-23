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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the chroma front end writes down about itself (#676). */
class ChromaTraceTest {

    private static List<Double> reading(double first) {
        List<Double> values = new ArrayList<>();
        values.add(first);
        while (values.size() < 12) {
            values.add(0.0);
        }
        return values;
    }

    private static ChromaTrace trace() {
        return new ChromaTrace(-0.0625, true,
                new ChromaTrace.Fit(22050, 8192, 1024, 21.533203125, 172, 3, 21, 96, 45, 57),
                List.of(new ChromaTrace.Span(0, 2, 0, 4, "Cm7",
                        reading(0.41), reading(0.38), reading(0.52), reading(1.07))));
    }

    @Test
    @DisplayName("comes back from the file a workspace keeps it in as it went in")
    void survivesTheRoundTrip() {
        RunTraces written = RunTraceJson.of(Map.of(ChromaTrace.STAGE, trace()));

        RunTraces read = RunTraceJson.fromJson(RunTraceJson.toJson(written));

        assertThat(read.trace(ChromaTrace.STAGE, ChromaTrace.class)).contains(trace());
    }

    @Test
    @DisplayName("a reading that is not one per pitch class is refused")
    void aReadingIsTwelveWideOrAbsent() {
        // Refused here rather than drawn short, which is what turns a file a
        // later build wrote into an absent trace instead of a half figure.
        assertThatThrownBy(() -> new ChromaTrace.Span(0, 1, 0, 2, "C",
                List.of(1.0), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one per pitch class");
    }

    @Test
    @DisplayName("a run that measured no residual leaves no reading, not a blank one")
    void anUnmeasuredResidualIsEmpty() {
        ChromaTrace.Span span = new ChromaTrace.Span(0, 1, 0, 2, "C",
                reading(0.4), reading(0.4), reading(0.4), null);

        assertThat(span.significance()).isEmpty();
    }

    @Test
    @DisplayName("a trace written by a build whose shape has moved on reads as absent")
    void anUnreadableTraceIsAbsent() {
        RunTraces read = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"chroma\":{\"spans\":[{\"combined\":[1.0]}]}}}");

        assertThat(read.trace(ChromaTrace.STAGE, ChromaTrace.class)).isEmpty();
    }
}
