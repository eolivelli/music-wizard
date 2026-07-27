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

/** The seven note letters, carrying their natural pitch class. */
public enum NoteLetter {
    C(0), D(2), E(4), F(5), G(7), A(9), B(11);

    private final int naturalPitchClass;

    NoteLetter(int naturalPitchClass) {
        this.naturalPitchClass = naturalPitchClass;
    }

    /** Pitch class of this letter with no accidental, where C is 0. */
    public int naturalPitchClass() {
        return naturalPitchClass;
    }

    /** Position on the diatonic staff ladder, where C is 0 and B is 6. */
    public int diatonicStep() {
        return ordinal();
    }

    public static NoteLetter ofDiatonicStep(int step) {
        return values()[Math.floorMod(step, 7)];
    }
}
