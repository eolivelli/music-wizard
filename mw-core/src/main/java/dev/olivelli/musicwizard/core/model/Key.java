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
import java.util.Objects;

/**
 * A key: a tonic and a mode, valid over a span of the piece.
 *
 * <p>Estimated most reliably from the chord sequence rather than from raw
 * chroma, and used to pick the key signature and to drive enharmonic spelling.
 *
 * @param tonic        the tonic, written
 * @param mode         major or minor
 * @param startSeconds when this key takes effect
 * @param endSeconds   when it stops
 * @param confidence   how much the pipeline trusts this key
 */
public record Key(
        PitchSpelling tonic,
        Mode mode,
        double startSeconds,
        double endSeconds,
        Confidence confidence) {

    public Key {
        Objects.requireNonNull(tonic, "tonic");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(confidence, "confidence");
        if (!Double.isFinite(startSeconds) || startSeconds < 0) {
            throw new IllegalArgumentException("startSeconds must be finite and non-negative, got: " + startSeconds);
        }
        if (!Double.isFinite(endSeconds) || endSeconds <= startSeconds) {
            throw new IllegalArgumentException(
                    "endSeconds must be finite and after startSeconds; got start=" + startSeconds
                            + " end=" + endSeconds);
        }
    }

    /**
     * Number of sharps (positive) or flats (negative) in the key signature.
     *
     * <p>Derived from position on the circle of fifths, so it naturally yields
     * the conventional signature for both modes.
     */
    public int keySignatureAccidentals() {
        // Fifths from C for each natural letter, then adjust for the accidental.
        int[] fifthsFromC = {0, 2, 4, -1, 1, 3, 5}; // C D E F G A B
        int fifths = fifthsFromC[tonic.letter().diatonicStep()]
                + tonic.accidental().alteration() * 7;
        // A minor key shares its signature with the major a minor third above.
        return mode == Mode.MINOR ? fifths - 3 : fifths;
    }

    /** True when the key signature is written with flats. */
    @JsonIgnore
    public boolean isFlatKey() {
        return keySignatureAccidentals() < 0;
    }

    /** Name such as {@code F# minor}. */
    public String displayName() {
        return tonic.letter().name() + tonic.accidental().displaySuffix() + " " + mode.name().toLowerCase();
    }

    @Override
    public String toString() {
        return displayName();
    }
}
