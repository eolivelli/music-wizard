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

package dev.olivelli.musicwizard.dsp;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import java.util.List;
import java.util.Objects;

/**
 * What the estimated chart says a sung note over it is likely to be, used to
 * settle a rounding the pitch alone leaves open.
 *
 * <p>Most sung notes are chord tones of the chord under them (#571), so where a
 * measured pitch sits between two semitones the one the chart names is the
 * better bet. It is a preference and never a rule: the flip is available only
 * inside a band around the semitone boundary, so a note the tracker placed
 * clearly does not move whatever the chart says, and a passing tone sung in
 * tune is kept. {@link #WEIGHT_SEMITONES} is how wide that band is; the
 * baselines under {@code tools/baselines/} carry what it bought.
 *
 * <p><strong>A chart may be wrong, so it must only be listened to where it is
 * evidenced.</strong> Two spans say nothing and are skipped outright: a no-chord
 * span, which names no pitch classes at all, and one whose confidence is under
 * {@link #CONFIDENCE_FLOOR}. That floor is doing more work than it looks: the
 * chart of a recording with no accompaniment is decoded from the melody itself,
 * so leaning the melody on it would be circular in the way #568 records for
 * tuning, and a triad template fits one voice at a time poorly enough that such
 * a chart mostly falls under the floor. It is a trade rather than a separation
 * — {@code tools/MelodyPriorSweep.java} is what re-derives both constants.
 */
public final class HarmonicPrior {

    /**
     * How far the prior may pull a rounding, in semitones.
     *
     * <p>It buys the chord tone this much of the distance to the semitone
     * boundary, so a pitch nearer the boundary than half of what is left over
     * rounds the chart's way instead of the arithmetic's. Zero is no prior at
     * all and half a semitone would round every note the chart's way, which is
     * the hard snap #571 rules out.
     */
    private static final double WEIGHT_SEMITONES = 0.24;

    /**
     * The least a chord span may be trusted before it is allowed to bend a note.
     */
    private static final double CONFIDENCE_FLOOR = 0.45;

    /** The prior of a run with no chart to lean on, which changes no rounding. */
    public static final HarmonicPrior NONE = new HarmonicPrior(List.of(), 0, 1);

    private final List<Chord> chords;
    private final double weightSemitones;
    private final double confidenceFloor;

    private HarmonicPrior(List<Chord> chords, double weightSemitones, double confidenceFloor) {
        this.chords = chords;
        this.weightSemitones = weightSemitones;
        this.confidenceFloor = confidenceFloor;
    }

    /** The prior a chart supports, at the strength the corpus chose. */
    public static HarmonicPrior of(ChordProgression chords) {
        return of(chords, WEIGHT_SEMITONES, CONFIDENCE_FLOOR);
    }

    /**
     * The same at a stated strength, which is what
     * {@code tools/MelodyPriorSweep.java} varies. A weight of zero is
     * {@link #NONE} however good the chart is.
     */
    public static HarmonicPrior of(ChordProgression chords, double weightSemitones,
                                   double confidenceFloor) {
        Objects.requireNonNull(chords, "chords");
        if (!(weightSemitones >= 0) || weightSemitones >= 0.5) {
            throw new IllegalArgumentException(
                    "weightSemitones must be in [0, 0.5), got: " + weightSemitones);
        }
        if (!(confidenceFloor >= 0) || confidenceFloor > 1) {
            throw new IllegalArgumentException(
                    "confidenceFloor must be in [0, 1], got: " + confidenceFloor);
        }
        return new HarmonicPrior(chords.chords(), weightSemitones, confidenceFloor);
    }

    /**
     * The semitone a pitch measured over a span belongs to.
     *
     * <p>The nearest one, unless the chart evidences the other side of the
     * boundary and the pitch is close enough to it to be in doubt.
     *
     * @param semitones a MIDI pitch on the recording's own grid, so the caller
     *                  has already taken its tuning offset out
     */
    public int round(double semitones, double fromSeconds, double toSeconds) {
        int nearest = (int) Math.round(semitones);
        double distance = semitones - nearest;
        if (weightSemitones == 0 || distance == 0) {
            return nearest;
        }
        int alternative = distance > 0 ? nearest + 1 : nearest - 1;
        Chord chord = evidencedChordOver(fromSeconds, toSeconds);
        if (chord == null) {
            return nearest;
        }
        int gain = standing(chord, alternative) - standing(chord, nearest);
        // What moving to the alternative costs in pitch, against what the prior
        // is worth there. Equivalently: the boundary sits this much nearer than
        // the arithmetic puts it, and by one step per tier crossed.
        return gain > 0 && 1 - 2 * Math.abs(distance) < weightSemitones * gain
                ? alternative : nearest;
    }

    /**
     * How well a pitch fits a chord: a tone of it, a degree of the scale it
     * implies, or neither.
     */
    private static int standing(Chord chord, int midiPitch) {
        int degree = Math.floorMod(midiPitch - chord.root().pitchClass(), 12);
        for (int interval : chord.quality().intervals()) {
            if (interval == degree) {
                return 2;
            }
        }
        for (int interval : chord.quality().isMinorish() ? MINOR_SCALE : MAJOR_SCALE) {
            if (interval == degree) {
                return 1;
            }
        }
        return 0;
    }

    private static final int[] MAJOR_SCALE = {0, 2, 4, 5, 7, 9, 11};

    private static final int[] MINOR_SCALE = {0, 2, 3, 5, 7, 8, 10};

    /**
     * The chord this span may lean on: the one covering most of it among those
     * that say anything at all.
     *
     * <p>Most of it rather than the one under its onset, because a note
     * straddling a change is otherwise decided by the chord it is leaving.
     */
    private Chord evidencedChordOver(double fromSeconds, double toSeconds) {
        Chord best = null;
        double bestOverlap = 0;
        for (Chord chord : chords) {
            if (chord.startSeconds() >= toSeconds) {
                break;
            }
            if (chord.isNoChord() || chord.confidence().value() < confidenceFloor) {
                continue;
            }
            double overlap = Math.min(toSeconds, chord.endSeconds())
                    - Math.max(fromSeconds, chord.startSeconds());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = chord;
            }
        }
        return best;
    }
}
