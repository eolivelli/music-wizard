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

import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.Objects;
import java.util.Optional;

/**
 * The grid one bar was quantized against.
 *
 * <p>Carries the bar's start on the beat axis rather than leaving a consumer to
 * derive it from the tempo map a second time. Re-deriving is how two stages end
 * up disagreeing by a rounding step, and it is the notation layer -- which has
 * to place a bar line and a tuplet bracket at the same position the quantizer
 * chose -- that would notice.
 *
 * @param bar           zero-based bar index
 * @param startBeat     the bar's downbeat in quarter-note beats
 * @param resolution    how finely the bar was divided
 * @param timeSignature the signature in force in this bar
 */
public record BarGrid(int bar, double startBeat, GridResolution resolution,
                      TimeSignature timeSignature) {

    public BarGrid {
        if (bar < 0) {
            throw new IllegalArgumentException("bar must be non-negative, got: " + bar);
        }
        if (!Double.isFinite(startBeat) || startBeat < 0) {
            throw new IllegalArgumentException(
                    "startBeat must be finite and non-negative, got: " + startBeat);
        }
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(timeSignature, "timeSignature");
    }

    /** Length of one grid step in quarter-note beats. */
    public double stepQuarters() {
        return resolution.stepQuarters(timeSignature);
    }

    /** Grid positions in this bar. */
    public int divisions() {
        return resolution.divisionsPerBar(timeSignature);
    }

    /** The next bar's downbeat in quarter-note beats. */
    public double endBeat() {
        return startBeat + timeSignature.quarterBeatsPerBar();
    }

    /** The tuplet bracket this grid needs in this meter, if any. */
    public Optional<GridResolution.Tuplet> tuplet() {
        return resolution.tupletIn(timeSignature);
    }

    /** Snaps a position within the bar, in quarter beats, to the nearest step. */
    public double snapWithinBar(double beatInBar) {
        double step = stepQuarters();
        double units = Math.rint(beatInBar / step);
        return Math.clamp(units, 0, divisions()) * step;
    }

    @Override
    public String toString() {
        return "bar " + (bar + 1) + " " + resolution + " (" + timeSignature + ")";
    }
}
