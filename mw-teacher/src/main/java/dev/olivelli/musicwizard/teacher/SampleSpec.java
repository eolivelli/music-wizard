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

import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A parsed synthetic-sample spec: everything the generator needs, and the
 * ground truth everything else is judged against.
 *
 * <p>The spec file is the one committed source; the MIDI is compiled from it
 * deterministically (same spec, same bytes), so regenerating a package can
 * never quietly change what its grid claims.
 */
public record SampleSpec(
        String title,
        String genre,
        Style style,
        double tempoBpm,
        TimeSignature meter,
        String keyTonic,
        Mode mode,
        long seed,
        Integer melodyProgram,
        List<Bar> bars) {

    /** One bar of the grid: a whole-bar chord, or two half-bar chords. */
    public record Bar(ChordSymbol first, ChordSymbol second) {

        public Bar {
            Objects.requireNonNull(first, "first");
        }

        /** The chord sounding at an offset in quarter beats from the bar line. */
        public ChordSymbol chordAt(double offsetBeats, TimeSignature meter) {
            if (second == null) {
                return first;
            }
            return offsetBeats < meter.quarterBeatsPerBar() / 2.0 ? first : second;
        }
    }

    /** Arrangement styles the generator knows, each with a default melody voice. */
    public enum Style {
        POP_BALLAD("pop-ballad", 73),
        POP_ROCK("pop-rock", 73),
        HIPHOP_BOOM_BAP("hiphop-boom-bap", 0),
        ROCKNROLL_SHUFFLE("rocknroll-shuffle", 66);

        private final String id;
        private final int defaultMelodyProgram;

        Style(String id, int defaultMelodyProgram) {
            this.id = id;
            this.defaultMelodyProgram = defaultMelodyProgram;
        }

        public String id() {
            return id;
        }

        public int defaultMelodyProgram() {
            return defaultMelodyProgram;
        }

        public static Style byId(String id) {
            for (Style style : values()) {
                if (style.id.equals(id)) {
                    return style;
                }
            }
            throw new IllegalArgumentException("unknown style: '" + id + "'");
        }
    }

    /**
     * Melody instruments a spec may name, as General MIDI programs. {@code none}
     * omits the melody part, for packages that teach comping alone.
     */
    public static final Map<String, Integer> MELODY_INSTRUMENTS = Map.of(
            "piano", 0,
            "epiano", 4,
            "vibraphone", 11,
            "oboe", 68,
            "clarinet", 71,
            "flute", 73,
            "trumpet", 56,
            "tenor-sax", 66);

    private static final Map<String, Integer> MAJOR_SIGNATURES = Map.ofEntries(
            Map.entry("C", 0), Map.entry("G", 1), Map.entry("D", 2), Map.entry("A", 3),
            Map.entry("E", 4), Map.entry("B", 5), Map.entry("F#", 6), Map.entry("C#", 7),
            Map.entry("F", -1), Map.entry("Bb", -2), Map.entry("Eb", -3), Map.entry("Ab", -4),
            Map.entry("Db", -5), Map.entry("Gb", -6), Map.entry("Cb", -7));

    private static final Map<String, Integer> MINOR_SIGNATURES = Map.ofEntries(
            Map.entry("A", 0), Map.entry("E", 1), Map.entry("B", 2), Map.entry("F#", 3),
            Map.entry("C#", 4), Map.entry("G#", 5), Map.entry("D#", 6), Map.entry("A#", 7),
            Map.entry("D", -1), Map.entry("G", -2), Map.entry("C", -3), Map.entry("F", -4),
            Map.entry("Bb", -5), Map.entry("Eb", -6), Map.entry("Ab", -7));

    /** Sharps (positive) or flats (negative) of the spec's key signature. */
    public int sharpsOrFlats() {
        Map<String, Integer> table = mode == Mode.MINOR ? MINOR_SIGNATURES : MAJOR_SIGNATURES;
        Integer count = table.get(keyTonic);
        if (count == null) {
            throw new IllegalArgumentException(
                    "no key signature for " + keyTonic + " " + mode);
        }
        return count;
    }

    /** The tonic as a pitch class. */
    public int tonicPitchClass() {
        return ChordSymbol.parse(keyTonic).rootPitchClass();
    }

    /** The key's diatonic scale as pitch classes, tonic first. */
    public int[] scalePitchClasses() {
        int[] steps = mode == Mode.MINOR
                ? new int[] {0, 2, 3, 5, 7, 8, 10}
                : new int[] {0, 2, 4, 5, 7, 9, 11};
        int tonic = tonicPitchClass();
        int[] out = new int[steps.length];
        for (int i = 0; i < steps.length; i++) {
            out[i] = (tonic + steps[i]) % 12;
        }
        return out;
    }
}
