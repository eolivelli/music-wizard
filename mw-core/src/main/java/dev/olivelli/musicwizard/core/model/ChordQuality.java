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
 * The quality of a chord, as a set of semitone intervals above the root.
 *
 * <p>The vocabulary is deliberately limited to what can be recognised reliably
 * and what a guitarist or pianist actually wants on a chart. Chord recognition
 * accuracy falls off a cliff beyond triads and sevenths, so inventing richer
 * labels than the signal supports would be dishonest.
 */
public enum ChordQuality {
    MAJOR("", false, 0, 4, 7),
    MINOR("m", false, 0, 3, 7),
    DIMINISHED("dim", false, 0, 3, 6),
    AUGMENTED("aug", false, 0, 4, 8),
    SUSPENDED_SECOND("sus2", false, 0, 2, 7),
    SUSPENDED_FOURTH("sus4", false, 0, 5, 7),
    DOMINANT_SEVENTH("7", true, 0, 4, 7, 10),
    MAJOR_SEVENTH("maj7", true, 0, 4, 7, 11),
    MINOR_SEVENTH("m7", true, 0, 3, 7, 10),
    MINOR_MAJOR_SEVENTH("mMaj7", true, 0, 3, 7, 11),
    HALF_DIMINISHED_SEVENTH("m7b5", true, 0, 3, 6, 10),
    DIMINISHED_SEVENTH("dim7", true, 0, 3, 6, 9),
    SIXTH("6", false, 0, 4, 7, 9),
    MINOR_SIXTH("m6", false, 0, 3, 7, 9),
    /** No chord sounding: silence, or a purely percussive passage. */
    NONE("N.C.", false);

    private final String symbol;
    private final boolean hasSeventh;
    private final int[] intervals;

    ChordQuality(String symbol, boolean hasSeventh, int... intervals) {
        this.symbol = symbol;
        this.hasSeventh = hasSeventh;
        this.intervals = intervals;
    }

    /** Suffix appended to the root name, e.g. {@code m7} in {@code Am7}. */
    public String symbol() {
        return symbol;
    }

    /** Semitone intervals above the root, ascending. Empty for {@link #NONE}. */
    public int[] intervals() {
        return intervals.clone();
    }

    /** True when this quality has a minor third. */
    public boolean isMinorish() {
        for (int interval : intervals) {
            if (interval == 3) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when this quality carries a seventh.
     *
     * <p>Declared per constant rather than derived from the interval set,
     * because interval 9 is ambiguous: it is the diminished seventh of dim7 and
     * equally the major sixth of a 6 chord. Deriving it would mis-voice every
     * sixth chord.
     */
    public boolean hasSeventh() {
        return hasSeventh;
    }

    /** The triad qualities, which are what a first-pass estimator should consider. */
    public static ChordQuality[] triads() {
        return new ChordQuality[] {MAJOR, MINOR, DIMINISHED, AUGMENTED};
    }
}
