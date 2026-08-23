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
                new ChromaTrace.Fit(22050, 8192, 1024, 21.533203125, 172, 3, 21, 96, 45, 57, 84),
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
    @DisplayName("a span with no chord to name it is refused")
    void aSpanNamesItsChord() {
        // The page prints the label, so an unnamed span would abort the render
        // rather than cost a picture.
        assertThatThrownBy(() -> new ChromaTrace.Span(0, 1, 0, 2, null,
                reading(0.4), reading(0.4), reading(0.4), reading(0.4)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a fit whose fields a later build renamed is dropped, not stated")
    void aFitThatDescribesNoModelIsDropped() {
        // Unknown properties are ignored and missing ones are zero, so a record
        // from a build that named these differently parses cleanly -- and the
        // page would state what it read.
        String renamedRegisters = "{\"fit\":{\"sampleRate\":22050,\"windowSize\":8192,"
                + "\"hopSize\":1024,\"frameRate\":21.5,\"frames\":172,"
                + "\"binsPerSemitone\":3,\"lowestNoteMidi\":21,\"highestNoteMidi\":96,"
                + "\"bassBelowMidi\":45,\"trebleAboveMidi\":57}}";
        String renamedTransform = "{\"fit\":{\"rate\":22050,\"window\":8192,\"hop\":1024,"
                + "\"lowestNoteMidi\":21,\"highestNoteMidi\":96,\"crossfadeLowMidi\":45,"
                + "\"crossfadeHighMidi\":57,\"trebleRollOffMidi\":84}}";

        for (String written : List.of(renamedRegisters, renamedTransform)) {
            ChromaTrace read = RunTraceJson.fromJson(
                    "{\"schemaVersion\":1,\"traces\":{\"chroma\":" + written + "}}")
                    .trace(ChromaTrace.STAGE, ChromaTrace.class).orElseThrow();
            assertThat(read.fit()).as(written).isNull();
        }
    }

    @Test
    @DisplayName("a model with no roll-off, and one that gave no frame, are both kept")
    void aModelThisBuildCouldHaveWrittenIsKept() {
        // Neither is malformed: a fold that keeps its top octave rolls off
        // where it ends, and a clip shorter than one analysis window gives no
        // frame at all.
        ChromaTrace.Fit noRollOff =
                new ChromaTrace.Fit(22050, 8192, 1024, 21.5, 172, 3, 21, 96, 45, 57, 96);
        ChromaTrace.Fit noFrames =
                new ChromaTrace.Fit(22050, 8192, 1024, 21.5, 0, 3, 21, 96, 45, 57, 84);

        assertThat(new ChromaTrace(0, false, noRollOff, List.of()).fit()).isEqualTo(noRollOff);
        assertThat(new ChromaTrace(0, false, noFrames, List.of()).fit()).isEqualTo(noFrames);
    }

    @Test
    @DisplayName("a trace written by a build whose shape has moved on reads as absent")
    void anUnreadableTraceIsAbsent() {
        RunTraces narrow = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"chroma\":{\"spans\":[{\"chord\":\"C\",\"combined\":[1.0]}]}}}");
        RunTraces renamed = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"chroma\":{\"spans\":[{\"symbol\":\"C\"}]}}}");

        assertThat(narrow.trace(ChromaTrace.STAGE, ChromaTrace.class)).isEmpty();
        assertThat(renamed.trace(ChromaTrace.STAGE, ChromaTrace.class)).isEmpty();
    }
}
