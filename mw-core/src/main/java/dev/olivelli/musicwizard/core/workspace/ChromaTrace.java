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
import java.util.List;
import java.util.Objects;

/**
 * What the chroma front end read, as opposed to the twelve numbers a beat came
 * out as (#676).
 *
 * <p>A record and never an input: nothing reads it back into an analysis, so a
 * run that writes none folds the same chroma.
 *
 * @param tuningOffsetSemitones how far the recording sits from concert pitch,
 *                              which the whole harmony path and the melody's
 *                              spelling are both corrected by
 * @param tuningMeasured        false where the spectrum offered no peaks to
 *                              measure, so concert pitch was assumed rather
 *                              than found
 * @param fit                   the model the spectrum was explained with, or
 *                              null where the front end recorded none
 * @param spans                 one entry per chord span, in time order, or
 *                              empty where nothing was folded onto spans
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChromaTrace(
        double tuningOffsetSemitones,
        boolean tuningMeasured,
        Fit fit,
        List<Span> spans) {

    /** The stage this trace belongs to, which is also its report phase. */
    public static final String STAGE = "chroma";

    public ChromaTrace {
        spans = spans == null ? List.of() : List.copyOf(spans);
    }

    /**
     * The transform and the note model the fit ran on, which together say what
     * the front end was able to see.
     *
     * @param sampleRate         the rate the analysis ran at
     * @param windowSize         transform length in samples, which sets how far
     *                           a chord change is smeared
     * @param hopSize            samples between frames
     * @param frameRate          frames a second
     * @param frames             how many the recording gave
     * @param binsPerSemitone    resolution of the grid the spectrum is resampled
     *                           onto
     * @param lowestNoteMidi     lowest note the dictionary models
     * @param highestNoteMidi    highest note it models; a partial above this has
     *                           no column of its own, and the treble fold has
     *                           faded to nothing by it
     * @param crossfadeLowMidi   at and below this a note is all bass
     * @param crossfadeHighMidi  at and above this it is all treble, the two
     *                           registers cross-fading between
     * @param trebleRollOffMidi  where the treble fold starts fading out again
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fit(
            int sampleRate,
            int windowSize,
            int hopSize,
            double frameRate,
            int frames,
            int binsPerSemitone,
            int lowestNoteMidi,
            int highestNoteMidi,
            int crossfadeLowMidi,
            int crossfadeHighMidi,
            int trebleRollOffMidi) {
    }

    /**
     * What the front end left over one chord span: the chroma each decision
     * below reads, and how much of the spectrum only one pitch class can
     * explain.
     *
     * <p>The span is the chord span as it was finally named. Within it the beats
     * can disagree, and this cannot show that.
     *
     * @param fromSeconds   where the span starts
     * @param toSeconds     where it ends
     * @param fromBeat      first beat-synchronous span it covers
     * @param toBeat        one past the last
     * @param chord         the symbol the span was named with
     * @param combined      both registers, which the root is decoded from
     * @param treble        the chordal register alone, which the quality is
     *                      decided from
     * @param bass          the register below it, which is the prior over roots
     * @param significance  relative increase in squared residual when a pitch
     *                      class is deleted from the dictionary and the span is
     *                      fitted again, or empty where no residual was measured
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Span(
            double fromSeconds,
            double toSeconds,
            int fromBeat,
            int toBeat,
            String chord,
            List<Double> combined,
            List<Double> treble,
            List<Double> bass,
            List<Double> significance) {

        public Span {
            // Guarded here for the same reason a reading's width is: a span
            // whose label a later build renamed is a span this one cannot
            // draw, and refusing it costs the picture rather than the render.
            Objects.requireNonNull(chord, "chord");
            combined = pitchClasses(combined, "combined");
            treble = pitchClasses(treble, "treble");
            bass = pitchClasses(bass, "bass");
            significance = pitchClasses(significance, "significance");
        }
    }

    /**
     * A reading per pitch class, or none at all. A reading of any other width
     * is not one, and refusing it here is what turns a file this build cannot
     * read into an absent trace rather than a half-drawn picture.
     */
    private static List<Double> pitchClasses(List<Double> values, String name) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() != 12) {
            throw new IllegalArgumentException(name + " holds " + values.size()
                    + " values, but a reading is one per pitch class");
        }
        return List.copyOf(values);
    }
}
