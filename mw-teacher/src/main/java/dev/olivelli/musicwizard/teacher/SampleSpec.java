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
import java.util.Locale;
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
        Integer melodyLevel,
        Accompaniment accompaniment,
        CompVoicing compVoicing,
        List<Bar> bars) {

    /** The difficulty levels a spec may ask its melody for. */
    public static final int MIN_MELODY_LEVEL = 1;

    /** See {@link #MIN_MELODY_LEVEL}. */
    public static final int MAX_MELODY_LEVEL = 4;

    public SampleSpec {
        Objects.requireNonNull(accompaniment, "accompaniment");
        Objects.requireNonNull(compVoicing, "compVoicing");
        if (melodyLevel != null
                && (melodyLevel < MIN_MELODY_LEVEL || melodyLevel > MAX_MELODY_LEVEL)) {
            throw new IllegalArgumentException("melody level out of range: " + melodyLevel);
        }
        if (melodyLevel != null && melodyProgram == null) {
            throw new IllegalArgumentException("melody level given but melody is 'none'");
        }
        // A package with neither is silence with a grid attached, and it is
        // silence that reports itself as normal: the generator prints its bar
        // count and duration, the chord harness skips it for having no
        // accompaniment and the melody harness skips it for having no melody,
        // so it is measured by nothing, twice, without either saying so.
        if (melodyProgram == null && accompaniment == Accompaniment.NONE) {
            throw new IllegalArgumentException(
                    "melody 'none' with accompaniment 'none' would generate silence");
        }
    }

    /**
     * What sounds under the melody.
     *
     * <p>{@link #FULL} is the band. The other two exist so that a melody stage
     * can be measured on a signal it is actually able to read: a monophonic
     * pitch tracker pointed at a full mix is measuring the separation that did
     * not happen in front of it, and its score says nothing about the tracker.
     * {@link #PAD} keeps the harmony audible for the chord stages;
     * {@link #NONE} leaves the melody alone, and a package generated that way
     * carries no evidence for its own chord grid — {@code tools/score-*.py}
     * must not score chords on it.
     */
    public enum Accompaniment {
        FULL,
        PAD,
        NONE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Accompaniment byId(String id) {
            for (Accompaniment value : values()) {
                if (value.id().equals(id)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("unknown accompaniment: '" + id + "'");
        }
    }

    /**
     * How the comping states a chord.
     *
     * <p>{@link #CLOSE} is the default: every chord tone in the comping
     * register, root included. {@link #ROOTLESS_MAJ7} leaves the root of a
     * major seventh to the bass and plays third, fifth and seventh — which is
     * the mediant triad, and is the whole point of the packages that ask for
     * it (#589). It changes major seventh chords and nothing else, so a
     * package stating it can be a minimal pair against a close-voiced twin.
     */
    public enum CompVoicing {
        CLOSE("close"),
        ROOTLESS_MAJ7("rootless-maj7");

        private final String id;

        CompVoicing(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static CompVoicing byId(String id) {
            for (CompVoicing value : values()) {
                if (value.id.equals(id)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("unknown voicing: '" + id + "'");
        }
    }

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
