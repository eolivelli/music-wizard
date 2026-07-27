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

/** What musical role a note track plays, which decides how it is engraved. */
public enum PartRole {
    /** The sung melody. Treble clef. */
    LEAD_VOCAL,
    /** The bass line. Bass clef, sounding an octave below written. */
    BASS,
    /** The drum kit. Percussion staff; not pitched. */
    DRUMS,
    /** Everything left after vocals, bass and drums are removed. */
    ACCOMPANIMENT,
    /** Right hand of the generated piano reduction. Treble clef. */
    PIANO_RIGHT_HAND,
    /** Left hand of the generated piano reduction. Bass clef. */
    PIANO_LEFT_HAND,
    /** Anything else, including unclassified separated stems. */
    OTHER;

    /** True when this part is conventionally written in bass clef. */
    public boolean prefersBassClef() {
        return this == BASS || this == PIANO_LEFT_HAND;
    }

    /**
     * True when a score may hold more than one track in this role.
     *
     * <p>Only {@link #OTHER} may repeat. The named roles identify a specific
     * part, so two of them would make {@code Score.track(role)} ambiguous;
     * {@code OTHER} is by definition a bag of unclassified parts, and its
     * tracks are distinguished by name instead.
     */
    public boolean allowsMultipleTracks() {
        return this == OTHER;
    }
}
