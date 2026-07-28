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

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.transcribe.SymbolicChordEstimator.BassLine;
import dev.olivelli.musicwizard.transcribe.SymbolicChordEstimator.Span;
import dev.olivelli.musicwizard.transcribe.SymbolicChordEstimator.Voiced;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bass sweep, checked against the obvious slow way of getting the same
 * answer.
 *
 * <p>{@link BassLine} replaced a per-span scan that was quadratic in the notes
 * sounding at once, and it is the one part of the estimator that has already
 * shipped a defect: an early draft never enqueued a started note, so the sweep
 * never retired one either and every bar reported the lowest pitch in the whole
 * piece as its bass. That was caught by a fixture, which is luck. This is not.
 *
 * <p>The reference implementation is deliberately stupid: cut the span at every
 * note edge, and for each piece scan every note for the lowest one sounding.
 * Both are meant to answer "how long does each pitch class spend at the bottom
 * of the texture", and they are quadratic and linear respectively, so agreeing
 * on random input is worth something.
 */
class BassLineTest {

    /** How long each pitch class is the lowest sounding one, the slow way. */
    private static double[] reference(List<Voiced> notes, Span span) {
        List<Double> edges = new ArrayList<>();
        edges.add(span.startBeat());
        edges.add(span.endBeat());
        for (Voiced note : notes) {
            for (double edge : new double[] {note.startBeat(), note.endBeat()}) {
                if (edge > span.startBeat() && edge < span.endBeat()) {
                    edges.add(edge);
                }
            }
        }
        edges.sort(Comparator.naturalOrder());

        double[] weight = new double[12];
        for (int i = 1; i < edges.size(); i++) {
            double from = edges.get(i - 1);
            double to = edges.get(i);
            if (!(to > from)) {
                continue;
            }
            int lowest = Integer.MAX_VALUE;
            for (Voiced note : notes) {
                if (note.startBeat() <= from && note.endBeat() >= to) {
                    lowest = Math.min(lowest, note.midiPitch());
                }
            }
            if (lowest != Integer.MAX_VALUE) {
                weight[Math.floorMod(lowest, 12)] += to - from;
            }
        }
        return weight;
    }

    private static List<Voiced> randomNotes(Random random, int count) {
        List<Voiced> notes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Quarter-beat positions on purpose: coincident starts and ends, and
            // notes handed over exactly at an edge, are the cases the sweep has
            // to get right and a continuous random position almost never
            // produces.
            double start = random.nextInt(0, 32) * 0.25;
            double length = (1 + random.nextInt(0, 12)) * 0.25;
            // A narrow pitch range so that duplicates -- the same pitch sounding
            // twice at once, which the sounding count has to handle as a multiset
            // -- happen often rather than never.
            notes.add(new Voiced(start, start + length, 36 + random.nextInt(0, 8), false));
        }
        notes.sort(Comparator.comparingDouble(Voiced::startBeat));
        return notes;
    }

    @Test
    @DisplayName("agrees with a brute-force sweep over random note sets")
    void agreesWithTheSlowWay() {
        Random random = new Random(20260728L);
        for (int trial = 0; trial < 2_000; trial++) {
            List<Voiced> notes = randomNotes(random, 1 + random.nextInt(0, 12));
            BassLine line = BassLine.of(notes);
            for (int beat = 0; beat < 10; beat++) {
                Span span = new Span(beat, beat + 1.0, beat % 4 == 0);
                double[] actual = new double[12];
                double total = line.weighOver(span, actual);
                double[] expected = reference(notes, span);

                assertThat(actual).as("trial %d beat %d of %s", trial, beat, notes)
                        .containsExactly(expected, org.assertj.core.data.Offset.offset(1e-9));
                double expectedTotal = 0;
                for (double value : expected) {
                    expectedTotal += value;
                }
                assertThat(total).isCloseTo(expectedTotal,
                        org.assertj.core.data.Offset.offset(1e-9));
            }
        }
    }

    @Test
    @DisplayName("answers the same whatever order the spans are asked for in")
    void isAFunctionOfItsArgument() {
        // The cursor makes the walk linear by only moving forward, which is fine
        // while the one caller asks in order and a trap for the next one. Asked
        // backwards, an unguarded cursor answers about whatever it happened to
        // be pointing at.
        Random random = new Random(4711L);
        for (int trial = 0; trial < 200; trial++) {
            List<Voiced> notes = randomNotes(random, 1 + random.nextInt(0, 8));
            List<Span> spans = new ArrayList<>();
            for (int beat = 0; beat < 8; beat++) {
                spans.add(new Span(beat, beat + 1.0, beat % 4 == 0));
            }

            BassLine ascending = BassLine.of(notes);
            List<double[]> forwards = new ArrayList<>();
            for (Span span : spans) {
                double[] weight = new double[12];
                ascending.weighOver(span, weight);
                forwards.add(weight);
            }

            BassLine descending = BassLine.of(notes);
            for (int i = spans.size() - 1; i >= 0; i--) {
                double[] weight = new double[12];
                descending.weighOver(spans.get(i), weight);
                assertThat(weight).as("trial %d span %d backwards", trial, i)
                        .containsExactly(forwards.get(i),
                                org.assertj.core.data.Offset.offset(1e-9));
            }
        }
    }

    @Test
    @DisplayName("a span before anything sounds, and one after everything has, are empty")
    void spansOutsideTheMusicAreSilent() {
        List<Voiced> notes = List.of(new Voiced(4, 6, 40, false), new Voiced(6, 8, 43, false));
        BassLine line = BassLine.of(notes);

        double[] before = new double[12];
        assertThat(line.weighOver(new Span(0, 4, true), before)).isZero();
        double[] after = new double[12];
        assertThat(line.weighOver(new Span(8, 12, true), after)).isZero();
        // And the span in between still answers, so the empty ones did not
        // leave the cursor somewhere useless.
        double[] middle = new double[12];
        assertThat(line.weighOver(new Span(4, 8, true), middle)).isEqualTo(4.0);
        assertThat(middle[4]).isEqualTo(2.0);
        assertThat(middle[7]).isEqualTo(2.0);
    }
}
