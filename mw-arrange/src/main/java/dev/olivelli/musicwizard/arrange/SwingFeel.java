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

package dev.olivelli.musicwizard.arrange;

import dev.olivelli.musicwizard.core.model.Confidence;
import java.util.Objects;

/**
 * Whether the performance swings its off-beat eighths, and by how much.
 *
 * <p>A shuffle puts the second of each pair of eighths roughly two thirds of the
 * way through the beat instead of halfway. Notating that literally -- as
 * triplets, or worse as a dotted eighth and a sixteenth -- is arithmetically
 * closer to the recording and musically useless: nobody writes a jazz chart that
 * way. So the quantizer removes the swing before snapping, the notes come out on
 * the straight eighth grid, and the feel is reported here for the notation layer
 * to print as a direction and for a MIDI writer to put back.
 *
 * <p>The un-quantized seconds on each {@link dev.olivelli.musicwizard.core.model.Note}
 * are untouched by any of this, so nothing about the performance is lost.
 *
 * @param swung      true when a shuffle was detected
 * @param ratio      where the off-beat sits within the beat, 0.5 being straight
 *                   and 2/3 being the usual triplet shuffle
 * @param confidence how much the detection is trusted
 */
public record SwingFeel(boolean swung, double ratio, Confidence confidence) {

    /** The narrowest and widest shuffle the de-swing map will accept. */
    static final double MIN_RATIO = 0.5;
    static final double MAX_RATIO = 0.8;

    /** No swing: off-beats sit halfway through the beat. */
    public static final SwingFeel STRAIGHT = new SwingFeel(false, 0.5, Confidence.UNKNOWN);

    public SwingFeel {
        Objects.requireNonNull(confidence, "confidence");
        if (!(ratio >= MIN_RATIO && ratio <= MAX_RATIO)) {
            throw new IllegalArgumentException(
                    "ratio must be within " + MIN_RATIO + ".." + MAX_RATIO + ", got: " + ratio);
        }
        if (!swung && ratio != 0.5) {
            throw new IllegalArgumentException(
                    "a straight feel must have ratio 0.5, got: " + ratio);
        }
    }

    /**
     * Moves a position within the beat from where it was played to where it
     * should be written: the played off-beat at {@link #ratio} becomes a written
     * 0.5, and the two halves of the beat are stretched linearly around it.
     *
     * <p>Straight when {@link #swung} is false, so a caller need not branch.
     *
     * @param phase position within the counted beat, in [0,1)
     */
    public double toWrittenPhase(double phase) {
        if (!swung) {
            return phase;
        }
        return phase <= ratio
                ? phase * 0.5 / ratio
                : 0.5 + (phase - ratio) * 0.5 / (1 - ratio);
    }

    /** The nearest conventional description, for a tempo direction or a log line. */
    public String displayName() {
        if (!swung) {
            return "straight";
        }
        return ratio >= 0.71 ? "hard swing" : ratio >= 0.60 ? "swing" : "light swing";
    }

    @Override
    public String toString() {
        return swung ? displayName() + " (" + Math.round(ratio * 100) + "%)" : "straight";
    }
}
