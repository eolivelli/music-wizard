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

package dev.olivelli.musicwizard.teacher;

import dev.olivelli.musicwizard.testkit.MidiFixtures.SequenceBuilder.PartBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A simple singable melody over the spec's changes: chord tones on the strong
 * beats, stepwise diatonic motion between them, four-bar phrases that end on a
 * held chord tone and a breath.
 *
 * <p>It is deliberately plain. These packages teach harmony and rhythm; a
 * melody that wandered chromatically would blur the chroma evidence the chords
 * are there to provide.
 */
final class MelodyGenerator {

    private static final int LOW = 58;
    private static final int HIGH = 84;

    /** Rhythm templates per style: {offset, duration} pairs in quarter beats. */
    private static final double[][][] STRAIGHT_TEMPLATES = {
            {{0, 1}, {1, 1}, {2, 2}},
            {{0, 2}, {2, 1}, {3, 1}},
            {{0, 1}, {1.5, 0.5}, {2, 1}, {3, 1}},
            {{0, 0.5}, {0.5, 0.5}, {1, 1}, {2, 1}, {3, 1}},
    };

    private static final double[][][] SPARSE_TEMPLATES = {
            {{0, 1}, {2, 1}},
            {{0, 0.5}, {0.5, 0.5}, {2, 1.5}},
            {{0, 2}},
            {{0, 1}, {2, 0.5}, {2.5, 1.5}},
    };

    private static final double[][][] SWUNG_TEMPLATES = {
            {{0, 2.0 / 3}, {2.0 / 3, 1.0 / 3}, {1, 1}, {2, 2.0 / 3}, {2 + 2.0 / 3, 1.0 / 3},
                    {3, 1}},
            {{0, 1}, {1, 2.0 / 3}, {1 + 2.0 / 3, 1.0 / 3}, {2, 2}},
            {{0, 2}, {2, 2.0 / 3}, {2 + 2.0 / 3, 1.0 / 3}, {3, 1}},
    };

    private final SampleSpec spec;
    private final int[] scale;
    private int current;
    private int direction = 1;

    MelodyGenerator(SampleSpec spec) {
        this.spec = spec;
        this.scale = scalePitches(spec);
        // Start near the tonic an octave and a bit above middle C.
        this.current = nearest(scale, 72 - (72 - spec.tonicPitchClass()) % 12);
    }

    void writeBar(PartBuilder part, int bar, Random rng) {
        int phrasePosition = bar % 4;
        if (phrasePosition == 3) {
            // Phrase end: one held chord tone, then a breath.
            if (rng.nextInt(6) == 0) {
                return; // a full bar's rest, occasionally
            }
            current = nearestChordTone(spec.bars().get(bar).first(), current);
            part.note(bar * 4.0, 3, current, humanize(rng, 78));
            return;
        }
        double[][] template = pick(rng);
        for (double[] note : template) {
            double offset = note[0];
            boolean strong = offset == 0 || offset == 2;
            ChordSymbol chord = spec.bars().get(bar).chordAt(offset, spec.meter());
            current = strong ? nearestChordTone(chord, step(rng)) : step(rng);
            part.note(bar * 4.0 + offset, note[1], current,
                    humanize(rng, strong ? 88 : 80));
        }
    }

    private double[][] pick(Random rng) {
        double[][][] templates = switch (spec.style()) {
            case ROCKNROLL_SHUFFLE -> SWUNG_TEMPLATES;
            case HIPHOP_BOOM_BAP -> SPARSE_TEMPLATES;
            default -> STRAIGHT_TEMPLATES;
        };
        return templates[rng.nextInt(templates.length)];
    }

    /** One or two scale steps in the prevailing direction, which sometimes turns. */
    private int step(Random rng) {
        if (rng.nextInt(10) < 3) {
            direction = -direction;
        }
        int index = indexOf(scale, current) + direction * (rng.nextInt(5) == 0 ? 2 : 1);
        if (index < 0 || index >= scale.length) {
            direction = -direction;
            index = Math.clamp(index, 0, scale.length - 1);
        }
        return scale[index];
    }

    /** The chord tone nearest a pitch, searched over the melody's range. */
    private static int nearestChordTone(ChordSymbol chord, int pitch) {
        int best = pitch;
        int bestDistance = Integer.MAX_VALUE;
        for (int candidate = LOW; candidate <= HIGH; candidate++) {
            for (int pc : chord.pitchClasses()) {
                if (candidate % 12 == pc) {
                    int distance = Math.abs(candidate - pitch);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static int[] scalePitches(SampleSpec spec) {
        int[] pcs = spec.scalePitchClasses();
        List<Integer> pitches = new ArrayList<>();
        for (int pitch = LOW; pitch <= HIGH; pitch++) {
            for (int pc : pcs) {
                if (pitch % 12 == pc) {
                    pitches.add(pitch);
                }
            }
        }
        return pitches.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int nearest(int[] sorted, int target) {
        int best = sorted[0];
        for (int value : sorted) {
            if (Math.abs(value - target) < Math.abs(best - target)) {
                best = value;
            }
        }
        return best;
    }

    private static int indexOf(int[] sorted, int value) {
        int bestIndex = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (Math.abs(sorted[i] - value) < Math.abs(sorted[bestIndex] - value)) {
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int humanize(Random rng, int velocity) {
        return Math.clamp(velocity + rng.nextInt(13) - 6, 1, 127);
    }
}
