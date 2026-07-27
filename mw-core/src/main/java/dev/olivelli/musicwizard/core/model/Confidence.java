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

/**
 * How much the pipeline trusts a piece of derived information, on a scale from
 * 0 (a pure guess) to 1 (certain).
 *
 * <p>Confidence is carried on nearly every value in the model rather than being
 * an afterthought, because two consumers depend on it: the Claude advisor layer
 * only overrides estimates whose confidence is low, and the user interface needs
 * to show which parts of a transcription are shaky.
 */
public record Confidence(double value) implements Comparable<Confidence> {

    /** Nothing is known; used for placeholder values. */
    public static final Confidence UNKNOWN = new Confidence(0.0);

    /** Ground truth, e.g. a value read directly from a MIDI file. */
    public static final Confidence CERTAIN = new Confidence(1.0);

    public Confidence {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(
                    "confidence must be within [0,1], got: " + value);
        }
    }

    public static Confidence of(double value) {
        return new Confidence(value);
    }

    /** Clamps out-of-range input rather than rejecting it. */
    public static Confidence clamped(double value) {
        if (Double.isNaN(value)) {
            return UNKNOWN;
        }
        return new Confidence(Math.clamp(value, 0.0, 1.0));
    }

    public boolean isAtLeast(double threshold) {
        return value >= threshold;
    }

    /**
     * Combines independent confidences multiplicatively, which is the right
     * behaviour for a pipeline: a chord derived from an uncertain beat grid can
     * never be more certain than that grid.
     */
    public Confidence and(Confidence other) {
        return new Confidence(value * other.value);
    }

    @Override
    public int compareTo(Confidence other) {
        return Double.compare(value, other.value);
    }
}
