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

package dev.olivelli.musicwizard.core.model;

import java.util.Objects;

/**
 * A position expressed the way a musician reads it: bar, then beat within bar.
 *
 * <p>Kept alongside wall-clock seconds rather than replacing them. Analysis
 * produces seconds, quantization fills in musical time, and notation consumes
 * musical time. Conflating the two is the single easiest way to produce a score
 * that drifts, so the model keeps them distinct and converts only through
 * {@link TempoMap}.
 *
 * @param bar           zero-based bar index
 * @param beatInBar     quarter-note beats elapsed within the bar
 * @param timeSignature the signature in force in this bar
 */
public record MusicalTime(int bar, double beatInBar, TimeSignature timeSignature)
        implements Comparable<MusicalTime> {

    public MusicalTime {
        if (bar < 0) {
            throw new IllegalArgumentException("bar must be non-negative, got: " + bar);
        }
        if (!Double.isFinite(beatInBar) || beatInBar < 0) {
            throw new IllegalArgumentException("beatInBar must be finite and non-negative, got: " + beatInBar);
        }
        Objects.requireNonNull(timeSignature, "timeSignature");
        if (beatInBar >= timeSignature.quarterBeatsPerBar()) {
            throw new IllegalArgumentException(
                    "beatInBar " + beatInBar + " does not fit in a " + timeSignature + " bar"
                            + " (which holds " + timeSignature.quarterBeatsPerBar() + " quarter beats)");
        }
    }

    /** One-based bar number, as printed on a score. */
    public int barNumber() {
        return bar + 1;
    }

    /** True when this position falls exactly on the downbeat of its bar. */
    public boolean isDownbeat() {
        return beatInBar == 0.0;
    }

    @Override
    public int compareTo(MusicalTime other) {
        int byBar = Integer.compare(bar, other.bar);
        return byBar != 0 ? byBar : Double.compare(beatInBar, other.beatInBar);
    }

    @Override
    public String toString() {
        return "bar " + barNumber() + " beat " + (beatInBar + 1);
    }
}
