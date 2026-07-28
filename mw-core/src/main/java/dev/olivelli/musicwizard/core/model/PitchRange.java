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
 * The span of sounding pitches a part covers, inclusive at both ends.
 *
 * <p>A named pair rather than a two-element array, because the notation layer
 * compares ranges — to pick a clef, to decide whether a piano reduction needs an
 * {@code 8va}, to check a part is singable — and {@code int[]} has no
 * {@code equals}, so two identical ranges would compare unequal and the value
 * would not survive being written to a score file.
 *
 * @param lowest  lowest sounding MIDI pitch, 0..127
 * @param highest highest sounding MIDI pitch, 0..127
 */
public record PitchRange(int lowest, int highest) {

    public PitchRange {
        if (lowest < 0 || lowest > 127) {
            throw new IllegalArgumentException("lowest must be within 0..127, got: " + lowest);
        }
        if (highest < 0 || highest > 127) {
            throw new IllegalArgumentException("highest must be within 0..127, got: " + highest);
        }
        if (highest < lowest) {
            throw new IllegalArgumentException(
                    "highest must not be below lowest; got lowest=" + lowest + " highest=" + highest);
        }
    }

    /** The range covering a single pitch. */
    public static PitchRange of(int midiPitch) {
        return new PitchRange(midiPitch, midiPitch);
    }

    /** Semitones from bottom to top; zero for a single pitch. */
    public int spanSemitones() {
        return highest - lowest;
    }

    /** True when a sounding pitch falls within the range, ends included. */
    public boolean contains(int midiPitch) {
        return midiPitch >= lowest && midiPitch <= highest;
    }

    /** The smallest range covering both this one and another. */
    public PitchRange union(PitchRange other) {
        return new PitchRange(Math.min(lowest, other.lowest), Math.max(highest, other.highest));
    }

    @Override
    public String toString() {
        return PitchSpelling.ofMidiPitchSharp(lowest).displayName()
                + ".." + PitchSpelling.ofMidiPitchSharp(highest).displayName();
    }
}
