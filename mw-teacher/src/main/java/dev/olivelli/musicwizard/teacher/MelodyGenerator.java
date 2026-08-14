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
 *
 * <p>A spec that names a {@code melody-level} gets one of four graded melodies
 * instead of its style's own rhythms — see {@link Level}. The ramp exists so
 * that a melody stage which scores badly can be asked <em>on what</em>: a
 * tracker that reads level one and fails level two is failing on rhythm, and
 * one that fails level one is not reading pitch at all. A single realistic
 * melody cannot separate those.
 */
final class MelodyGenerator {

    private static final int LOW = 58;
    private static final int HIGH = 84;

    /** Beats, so an exact tie is not read as an overlap by a rounding error. */
    private static final double EPSILON = 1e-9;

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

    /**
     * The difficulty ramp. Each level adds exactly one thing to the one below,
     * so a score that falls between two of them names what it fell over.
     */
    private enum Level {
        /** A chord tone on every beat, one octave, no rests. Nothing to get wrong but pitch. */
        ONE(67, 79, 1, true, LEVEL_ONE_BARS, null),
        /** Eighth notes and rests arrive; strong beats are still chord tones. */
        TWO(65, 81, 1, false, LEVEL_TWO_BARS, LEVEL_TWO_ENDINGS),
        /** Notes held across the bar line, so a bar is no longer a unit on its own. */
        THREE(62, 83, 1, false, LEVEL_THREE_BARS, LEVEL_THREE_ENDINGS),
        /**
         * Syncopation and leaps, over a range wider than a singer's comfortable
         * octave. The bottom is D4 rather than the C4 the range would otherwise
         * reach: C4 is the literal lowest note of a flute and the weakest sample
         * in a General MIDI bank, and a level-four loss concentrated down there
         * would be the instrument, not the syncopation this level is testing.
         */
        FOUR(62, 86, 3, false, LEVEL_FOUR_BARS, LEVEL_FOUR_ENDINGS);

        private final int low;
        private final int high;
        private final int maxLeap;
        private final boolean chordTonesOnly;
        private final double[][][] bars;
        private final double[][][] endings;

        Level(int low, int high, int maxLeap, boolean chordTonesOnly,
              double[][][] bars, double[][][] endings) {
            this.low = low;
            this.high = high;
            this.maxLeap = maxLeap;
            this.chordTonesOnly = chordTonesOnly;
            this.bars = bars;
            this.endings = endings;
        }
    }

    /** Level one: nothing but a chord tone on every beat. */
    private static final double[][][] LEVEL_ONE_BARS = {
            {{0, 1}, {1, 1}, {2, 1}, {3, 1}},
    };

    private static final double[][][] LEVEL_TWO_BARS = {
            {{0, 1}, {1, 1}, {2, 1}, {3, 1}},
            {{0, 1}, {1, 0.5}, {1.5, 0.5}, {2, 1}, {3, 1}},
            {{0, 0.5}, {0.5, 0.5}, {1, 1}, {2, 2}},
            {{0, 1}, {2, 1}, {3, 1}},
            {{0, 2}, {2, 0.5}, {2.5, 0.5}, {3, 1}},
    };

    private static final double[][][] LEVEL_TWO_ENDINGS = {
            {{0, 2}, {2, 2}},
            {{0, 3}},
            {{0, 1}, {1, 1}, {2, 2}},
    };

    /** Level three: the last note of some bars runs past the bar line. */
    private static final double[][][] LEVEL_THREE_BARS = {
            {{0, 1}, {1, 1}, {2, 1.5}, {3.5, 1.5}},
            {{0, 1.5}, {1.5, 0.5}, {2, 1}, {3, 2}},
            {{0, 2}, {2, 1}, {3, 1}},
            {{0, 0.5}, {0.5, 1.5}, {2, 3}},
            {{0, 1}, {1, 1}, {2, 2}},
    };

    private static final double[][][] LEVEL_THREE_ENDINGS = {
            {{0, 2}, {2, 3}},
            {{0, 4}},
            {{0, 1.5}, {1.5, 2.5}},
    };

    /** Level four: entries off the beat, and leaps rather than steps. */
    private static final double[][][] LEVEL_FOUR_BARS = {
            {{0.5, 1}, {1.5, 0.5}, {2, 1}, {3, 1}},
            {{0, 1}, {1.5, 0.5}, {2, 1.5}, {3.5, 1.5}},
            {{0, 1.5}, {1.5, 1.5}, {3, 1.5}},
            {{0, 0.5}, {0.5, 0.5}, {1, 1}, {2, 0.5}, {2.5, 1.5}},
            {{0.5, 1.5}, {2, 1}, {3, 1}},
    };

    private static final double[][][] LEVEL_FOUR_ENDINGS = {
            {{0, 1.5}, {1.5, 2.5}},
            {{0.5, 3.5}},
            {{0, 3}},
    };

    private final SampleSpec spec;
    private final Level level;
    private final int low;
    private final int high;
    private final int[] scale;
    private final Random ownRng;
    private int current;
    private int direction = 1;
    /** Absolute beat the last note runs to, so a tie over the bar line is not an overlap. */
    private double busyUntil;

    MelodyGenerator(SampleSpec spec) {
        this.spec = spec;
        this.level = spec.melodyLevel() == null
                ? null
                : Level.values()[spec.melodyLevel() - 1];
        // A graded melody draws from its own stream rather than the arrangement's,
        // so that two packages differing only in what plays under the melody get
        // the same melody. Sharing the stream, the pad's velocity draws land ahead
        // of the melody's and shift every note from bar one — which is exactly the
        // comparison those packages exist to make. The style path keeps the shared
        // stream: changing it would recompile every committed package's melody.
        this.ownRng = level == null ? null : new Random(spec.seed() * 31 + 17);
        this.low = level == null ? LOW : level.low;
        this.high = level == null ? HIGH : level.high;
        this.scale = scalePitches(spec, low, high);
        // Start near the tonic an octave and a bit above middle C.
        this.current = nearest(scale, 72 - (72 - spec.tonicPitchClass()) % 12);
    }

    void writeBar(PartBuilder part, int bar, Random rng) {
        if (level != null) {
            writeLeveledBar(part, bar, ownRng);
            return;
        }
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

    private void writeLeveledBar(PartBuilder part, int bar, Random rng) {
        double barBeats = spec.meter().quarterBeatsPerBar();
        double start = bar * barBeats;
        for (double[] note : pickLeveled(rng, bar)) {
            double at = start + note[0];
            if (at < busyUntil - EPSILON) {
                continue; // the note before is still sounding: this is the tie
            }
            ChordSymbol chord = spec.bars().get(bar).chordAt(note[0], spec.meter());
            boolean strong = note[0] == 0 || note[0] == barBeats / 2;
            current = level.chordTonesOnly || strong ? nextChordTone(chord, rng) : step(rng);
            part.note(at, note[1], current, humanize(rng, strong ? 88 : 80));
            busyUntil = at + note[1];
        }
    }

    private double[][] pickLeveled(Random rng, int bar) {
        double[][][] templates = level.endings != null && bar % 4 == 3
                ? level.endings
                : level.bars;
        return templates[rng.nextInt(templates.length)];
    }

    /**
     * The next chord tone along, in the prevailing direction. Moving by an index
     * into the chord's own tones rather than by a distance in semitones is what
     * keeps every level inside its range: the ends of the range are the ends of
     * the array, and the direction turns there.
     */
    private int nextChordTone(ChordSymbol chord, Random rng) {
        int[] tones = pitchesIn(chord.pitchClasses(), low, high);
        int index = indexOf(tones, current);
        if (rng.nextInt(4) == 0) {
            direction = -direction;
        }
        int jump = level.maxLeap > 1 ? 1 + rng.nextInt(level.maxLeap) : 1;
        int next = index + direction * jump;
        if (next < 0 || next >= tones.length) {
            direction = -direction;
            next = index + direction * jump;
        }
        return tones[Math.clamp(next, 0, tones.length - 1)];
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

    private static int[] scalePitches(SampleSpec spec, int low, int high) {
        return pitchesIn(spec.scalePitchClasses(), low, high);
    }

    /** Every pitch in the range whose pitch class is one of these, ascending. */
    private static int[] pitchesIn(int[] pitchClasses, int low, int high) {
        List<Integer> pitches = new ArrayList<>();
        for (int pitch = low; pitch <= high; pitch++) {
            for (int pc : pitchClasses) {
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
