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

/** A written accidental, expressed as a semitone alteration. */
public enum Accidental {
    DOUBLE_FLAT(-2, "eses", "bb"),
    FLAT(-1, "es", "b"),
    NATURAL(0, "", ""),
    SHARP(1, "is", "#"),
    DOUBLE_SHARP(2, "isis", "##");

    private final int alteration;
    private final String lilyPondSuffix;
    private final String displaySuffix;

    Accidental(int alteration, String lilyPondSuffix, String displaySuffix) {
        this.alteration = alteration;
        this.lilyPondSuffix = lilyPondSuffix;
        this.displaySuffix = displaySuffix;
    }

    /** Semitone offset applied to the natural pitch. */
    public int alteration() {
        return alteration;
    }

    /** Suffix used in LilyPond note names, e.g. {@code is} in {@code cis}. */
    public String lilyPondSuffix() {
        return lilyPondSuffix;
    }

    /** Suffix used in human-readable chord and note names, e.g. {@code #}. */
    public String displaySuffix() {
        return displaySuffix;
    }

    public static Accidental ofAlteration(int alteration) {
        for (Accidental a : values()) {
            if (a.alteration == alteration) {
                return a;
            }
        }
        throw new IllegalArgumentException(
                "no accidental for alteration " + alteration + " (supported range is -2..2)");
    }
}
