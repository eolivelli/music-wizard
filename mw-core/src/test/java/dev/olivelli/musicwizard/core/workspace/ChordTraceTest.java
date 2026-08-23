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

/** What the chord decoder writes down about its own reasoning (#677). */
class ChordTraceTest {

    private static ChordTrace trace() {
        return new ChordTrace(
                List.of(new ChordTrace.Span(0, 2, 0, 4, "F#m", "F#m7", "thirds",
                        new ChordTrace.Candidate("F#7", -9.21),
                        new ChordTrace.Candidate("B", -10.04),
                        "F#", -1.5, 0,
                        List.of(new ChordTrace.Gate("major third", "the minor third",
                                        0.03, 0.11, false),
                                new ChordTrace.Gate("minor third", "share of the root",
                                        0.11, 0.01, true)))),
                List.of(new ChordTrace.Root("F#",
                        new ChordTrace.Count(6, 8, "as read", 0),
                        new ChordTrace.Count(1, 8, "withdrawn", 1))));
    }

    @Test
    @DisplayName("comes back from the file a workspace keeps it in as it went in")
    void survivesTheRoundTrip() {
        RunTraces written = RunTraceJson.of(Map.of(ChordTrace.STAGE, trace()));

        RunTraces read = RunTraceJson.fromJson(RunTraceJson.toJson(written));

        assertThat(read.trace(ChordTrace.STAGE, ChordTrace.class)).contains(trace());
    }

    @Test
    @DisplayName("a span with nothing to draw it by is refused")
    void aSpanNamesWhatThePagePrints() {
        // The page prints all three, so a span missing one would abort the
        // render rather than cost a picture.
        ChordTrace.Candidate held = new ChordTrace.Candidate("C", -8.0);
        assertThatThrownBy(() -> new ChordTrace.Span(0, 1, 0, 2, null, "C", "decoder",
                held, null, null, 0, 0, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChordTrace.Span(0, 1, 0, 2, "C", null, "decoder",
                held, null, null, 0, 0, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChordTrace.Span(0, 1, 0, 2, "C", "C", "decoder",
                null, null, null, 0, 0, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a span the decoder had nothing to compare against keeps its own answer")
    void aSpanWithNoRivalIsKept() {
        ChordTrace.Span alone = new ChordTrace.Span(0, 1, 0, 2, "C", "C", "decoder",
                new ChordTrace.Candidate("C", -8.0), null, null, 0, 0, null);

        assertThat(alone.runnerUp()).isNull();
        assertThat(alone.gates()).isEmpty();
    }

    @Test
    @DisplayName("a trace written by a build whose shape has moved on reads as absent")
    void anUnreadableTraceIsAbsent() {
        // Unknown properties are ignored and missing ones default, so what
        // makes a renamed record absent is the fields the page cannot do
        // without.
        RunTraces renamed = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"chords\":{\"spans\":[{\"symbol\":\"C\"}]}}}");
        RunTraces sentence = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"chords\":\"a sentence, not a trace\"}}");

        assertThat(renamed.trace(ChordTrace.STAGE, ChordTrace.class)).isEmpty();
        assertThat(sentence.trace(ChordTrace.STAGE, ChordTrace.class)).isEmpty();
    }

    @Test
    @DisplayName("a decode that named nothing is a trace, not an absent one")
    void anEmptyDecodeIsStillATrace() {
        RunTraces written = RunTraceJson.of(
                Map.of(ChordTrace.STAGE, new ChordTrace(null, null)));

        ChordTrace read = RunTraceJson.fromJson(RunTraceJson.toJson(written))
                .trace(ChordTrace.STAGE, ChordTrace.class).orElseThrow();

        assertThat(read.spans()).isEmpty();
        assertThat(read.roots()).isEmpty();
    }
}
