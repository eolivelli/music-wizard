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

package dev.olivelli.musicwizard.teacher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One chord token in a spec's bar grid, in the {@code samples/list.txt}
 * shorthand: {@code 7} dominant, {@code m7} minor seventh, {@code maj7} major
 * seventh, {@code 6} major sixth, {@code m6} minor sixth, {@code m7b5}
 * half-diminished, {@code dim} diminished, {@code m} minor, a plain letter a
 * major triad.
 *
 * <p>The shorthand is shared with the corpus list and the tool's own output on
 * purpose: a spec, its scoring and the chart MW prints all spell a quality the
 * same way, so a grid can move between them without translation.
 */
public record ChordSymbol(String token, int rootPitchClass, Quality quality) {

    /** Chord qualities the grid shorthand can spell, with semitone intervals. */
    public enum Quality {
        MAJOR("", 0, 4, 7),
        MINOR("m", 0, 3, 7),
        DOMINANT_SEVENTH("7", 0, 4, 7, 10),
        MINOR_SEVENTH("m7", 0, 3, 7, 10),
        MAJOR_SEVENTH("maj7", 0, 4, 7, 11),
        SIXTH("6", 0, 4, 7, 9),
        MINOR_SIXTH("m6", 0, 3, 7, 9),
        HALF_DIMINISHED("m7b5", 0, 3, 6, 10),
        DIMINISHED("dim", 0, 3, 6);

        private final String suffix;
        private final int[] intervals;

        Quality(String suffix, int... intervals) {
            this.suffix = suffix;
            this.intervals = intervals;
        }

        public String suffix() {
            return suffix;
        }

        public int[] intervals() {
            return intervals.clone();
        }
    }

    // Longer suffixes first, so "maj7" is not read as "m" + garbage; "6" is a
    // prefix of nothing here and sits beside the other bare digit. The empty
    // alternative last is what spells a plain major triad, which is why parse()
    // must match the whole token: on a partial match an unknown suffix would
    // take that alternative, and a spec asking for a quality would compile
    // silently to a major triad in the MIDI and in the ground truth alike.
    private static final Pattern TOKEN =
            Pattern.compile("([A-G])([#b]?)(maj7|m7b5|m7|m6|dim|m|7|6|)");

    private static final int[] LETTER_PITCH_CLASS = {9, 11, 0, 2, 4, 5, 7}; // A..G

    /** Parses one token, e.g. {@code F#m7}. */
    public static ChordSymbol parse(String token) {
        Matcher m = TOKEN.matcher(token);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a chord token: '" + token + "'");
        }
        int pc = LETTER_PITCH_CLASS[m.group(1).charAt(0) - 'A'];
        pc += switch (m.group(2)) {
            case "#" -> 1;
            case "b" -> -1;
            default -> 0;
        };
        String suffix = m.group(3);
        for (Quality quality : Quality.values()) {
            if (quality.suffix().equals(suffix)) {
                return new ChordSymbol(token, Math.floorMod(pc, 12), quality);
            }
        }
        throw new AssertionError("suffix '" + suffix + "' matched by TOKEN but not a Quality");
    }

    /** The chord's tones as pitch classes, root first. */
    public int[] pitchClasses() {
        int[] intervals = quality.intervals();
        int[] out = new int[intervals.length];
        for (int i = 0; i < intervals.length; i++) {
            out[i] = (rootPitchClass + intervals[i]) % 12;
        }
        return out;
    }

    /** Semitones from root to the chord's third. */
    public int thirdInterval() {
        return quality.intervals()[1];
    }

    /** Semitones from root to the chord's fifth (diminished for dim chords). */
    public int fifthInterval() {
        return quality.intervals()[2];
    }

    @Override
    public String toString() {
        return token;
    }
}
