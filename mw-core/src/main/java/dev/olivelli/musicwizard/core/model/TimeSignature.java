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

import com.fasterxml.jackson.annotation.JsonIgnore;
/**
 * A time signature, such as 4/4 or 6/8.
 *
 * @param numerator   beats per bar as written
 * @param denominator note value that gets one beat, as a power of two
 */
public record TimeSignature(int numerator, int denominator) {

    public static final TimeSignature FOUR_FOUR = new TimeSignature(4, 4);
    public static final TimeSignature THREE_FOUR = new TimeSignature(3, 4);
    public static final TimeSignature SIX_EIGHT = new TimeSignature(6, 8);

    public TimeSignature {
        if (numerator < 1) {
            throw new IllegalArgumentException("numerator must be positive, got: " + numerator);
        }
        if (denominator < 1 || Integer.bitCount(denominator) != 1) {
            throw new IllegalArgumentException(
                    "denominator must be a positive power of two, got: " + denominator);
        }
        // Bounded so that bar-counting loops stay cheap and because nothing
        // beyond a 64th note is musically meaningful as a beat unit.
        if (denominator > 64) {
            throw new IllegalArgumentException(
                    "denominator must be at most 64, got: " + denominator);
        }
        if (numerator > 64) {
            throw new IllegalArgumentException(
                    "numerator must be at most 64, got: " + numerator);
        }
    }

    /**
     * Quarter-note beats per bar. Note this is deliberately not {@link #numerator}:
     * 6/8 has six eighth notes but three quarter-note beats, and the rest of the
     * pipeline counts in quarter notes.
     */
    public double quarterBeatsPerBar() {
        return numerator * 4.0 / denominator;
    }

    /** True for signatures conventionally felt in compound time, such as 6/8 and 12/8. */
    @JsonIgnore
    public boolean isCompound() {
        return denominator >= 8 && numerator % 3 == 0 && numerator > 3;
    }

    @Override
    public String toString() {
        return numerator + "/" + denominator;
    }
}
