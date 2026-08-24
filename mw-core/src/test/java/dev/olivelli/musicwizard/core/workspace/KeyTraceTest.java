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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the key estimator writes down about its two decisions (#678). */
class KeyTraceTest {

    private static KeyTrace trace() {
        return new KeyTrace(KeyTrace.FROM_CHORDS, 12.5, 16, 0.78,
                List.of(new KeyTrace.Candidate("A minor", 1.083, 2, 6.0, 1, 2.5),
                        new KeyTrace.Candidate("C major", 0.958, 0, 0, 0, 0)),
                new KeyTrace.Decision("A minor", "G major", 0.291, "separated"),
                new KeyTrace.Decision("A minor", "C major", 0.125, "separated"));
    }

    @Test
    @DisplayName("comes back from the file a workspace keeps it in as it went in")
    void survivesTheRoundTrip() {
        RunTraces written = RunTraceJson.of(Map.of(KeyTrace.STAGE, trace()));

        RunTraces read = RunTraceJson.fromJson(RunTraceJson.toJson(written));

        assertThat(read.trace(KeyTrace.STAGE, KeyTrace.class)).contains(trace());
    }

    @Test
    @DisplayName("a declared key round-trips as one that weighed nothing")
    void aDeclaredKeySurvivesTheRoundTrip() {
        RunTraces written = RunTraceJson.of(Map.of(KeyTrace.STAGE, KeyTrace.declared()));

        KeyTrace read = RunTraceJson.fromJson(RunTraceJson.toJson(written))
                .trace(KeyTrace.STAGE, KeyTrace.class).orElseThrow();

        assertThat(read.source()).isEqualTo(KeyTrace.DECLARED);
        assertThat(read.candidates()).isEmpty();
        assertThat(read.signature()).isNull();
        assertThat(read.tonic()).isNull();
    }

    @Test
    @DisplayName("a decision with nothing to draw it by is refused")
    void aDecisionNamesWhatThePagePrints() {
        // The page prints all three, so a decision missing one would abort the
        // render rather than cost a picture.
        assertThatThrownBy(() -> new KeyTrace.Decision(null, "C major", 0.1, "separated"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KeyTrace.Decision("A minor", null, 0.1, "separated"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KeyTrace.Decision("A minor", "C major", 0.1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a trace written by a build whose shape has moved on reads as absent")
    void anUnreadableTraceIsAbsent() {
        // Unknown properties are ignored and missing ones default, so what
        // makes a renamed record absent is the fields the page cannot do
        // without.
        RunTraces renamed = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"key\":{\"source\":\"chords\",\"tonic\":{\"chosen\":\"A minor\"}}}}");
        RunTraces sentence = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"key\":\"a sentence, not a trace\"}}");

        assertThat(renamed.trace(KeyTrace.STAGE, KeyTrace.class)).isEmpty();
        assertThat(sentence.trace(KeyTrace.STAGE, KeyTrace.class)).isEmpty();
    }

    @Test
    @DisplayName("a trace that names no source is refused")
    void theSourceIsNamed() {
        assertThatThrownBy(() -> new KeyTrace(null, 0, 0, 0, List.of(), null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
