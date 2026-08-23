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

package dev.olivelli.musicwizard.transcribe;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.workspace.ChromaTrace;
import dev.olivelli.musicwizard.dsp.Chroma;
import dev.olivelli.musicwizard.dsp.PitchClassAblation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Assembles the chroma front end's record of itself (#676).
 *
 * <p>Summarised over the chord spans rather than over the beats they hold:
 * that is where the quality gates decide, and it is the span a reader is
 * looking at when a chord is wrong. What each beat of a span held, and which
 * of these readings each gate compared against which, stay with the decoder
 * that already computes them (#677).
 */
final class ChromaTracing {

    /**
     * Readings are rounded before they are written down. They are recorded to
     * be read and drawn, not to be recomputed from, and the unrounded figures
     * cost several times the file for digits nothing distinguishes.
     */
    private static final double PRECISION = 1e4;

    private ChromaTracing() {
    }

    /** What the front end can say before any beat is known. */
    static ChromaTrace of(double tuningOffsetSemitones, ChromaTrace.Fit fit) {
        return new ChromaTrace(tuningOffsetSemitones, measured(tuningOffsetSemitones),
                fit, List.of());
    }

    /**
     * The same, with what each chord span was decided over.
     *
     * @param ablation the fit's leave-one-out residual over the same spans, or
     *                 null where none was measured
     */
    static ChromaTrace of(double tuningOffsetSemitones, ChromaTrace.Fit fit,
                          ChordProgression chords, List<Double> beatTimes,
                          Chroma combined, Chroma treble, Chroma bass,
                          PitchClassAblation ablation) {
        if (combined.frameCount() == 0) {
            // No beat span, so no chord span can be summarised over one.
            return of(tuningOffsetSemitones, fit);
        }
        List<ChromaTrace.Span> spans = new ArrayList<>();
        for (Chord chord : chords.chords()) {
            int from = beatAt(beatTimes, chord.startSeconds(), combined.frameCount() - 1);
            int to = Math.clamp(beatAt(beatTimes, chord.endSeconds(), combined.frameCount()),
                    from + 1, combined.frameCount());
            spans.add(new ChromaTrace.Span(chord.startSeconds(), chord.endSeconds(), from, to,
                    chord.symbol(),
                    shares(combined, from, to), shares(treble, from, to),
                    shares(bass, from, to),
                    ablation == null || to > ablation.spanCount()
                            ? List.of() : rounded(ablation.significanceOver(from, to))));
        }
        return new ChromaTrace(tuningOffsetSemitones, measured(tuningOffsetSemitones),
                fit, spans);
    }

    /**
     * Exactly zero is {@code Chroma.estimateTuning}'s "no evidence" answer:
     * every offset it measures is a histogram slot's centre, and no slot centre
     * is zero.
     */
    private static boolean measured(double tuningOffsetSemitones) {
        return tuningOffsetSemitones != 0;
    }

    /** The beat-synchronous span an instant falls in, by the beat nearest it. */
    private static int beatAt(List<Double> beatTimes, double seconds, int highest) {
        int found = Collections.binarySearch(beatTimes, seconds);
        int index = found >= 0 ? found : -(found + 1);
        return Math.clamp(index, 0, Math.max(0, highest));
    }

    /** A register's mass over a span, as the share each pitch class holds of it. */
    private static List<Double> shares(Chroma chroma, int from, int to) {
        double[] summed = new double[12];
        double total = 0;
        for (int span = from; span < to; span++) {
            double[] vector = chroma.vectors()[span];
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                summed[pitchClass] += vector[pitchClass];
                total += vector[pitchClass];
            }
        }
        if (total > 0) {
            for (int pitchClass = 0; pitchClass < 12; pitchClass++) {
                summed[pitchClass] /= total;
            }
        }
        return rounded(summed);
    }

    private static List<Double> rounded(double[] values) {
        List<Double> out = new ArrayList<>(values.length);
        for (double value : values) {
            out.add(Double.isFinite(value)
                    ? Math.round(value * PRECISION) / PRECISION : value);
        }
        return out;
    }
}
