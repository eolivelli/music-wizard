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
 * How a pitch is written on the staff: a letter, an accidental and an octave.
 *
 * <p>This exists because a MIDI number is not enough to engrave a note. Pitch 61
 * is both C sharp and D flat, and choosing wrongly produces a score that is
 * technically correct and visibly wrong to any musician. Spelling is decided
 * from the surrounding harmony and key, so it is carried separately from the
 * MIDI pitch all the way to the notation layer.
 *
 * <p>Octaves use scientific pitch notation, where middle C is C4 and equals MIDI
 * pitch 60.
 *
 * @param letter     the note letter
 * @param accidental the written accidental
 * @param octave     scientific-pitch octave number
 */
public record PitchSpelling(NoteLetter letter, Accidental accidental, int octave) {

    private static final int MIDI_C0 = 12;

    public PitchSpelling {
        if (letter == null) {
            throw new IllegalArgumentException("letter must not be null");
        }
        if (accidental == null) {
            throw new IllegalArgumentException("accidental must not be null");
        }
    }

    /**
     * The MIDI pitch this spelling sounds as.
     *
     * <p>Note that this can legitimately fall outside 0..127 for extreme
     * spellings such as B sharp in octave 9; callers that need a playable pitch
     * should range-check the result.
     */
    public int midiPitch() {
        return MIDI_C0 + octave * 12 + letter.naturalPitchClass() + accidental.alteration();
    }

    /** Pitch class 0..11, where C is 0. */
    public int pitchClass() {
        return Math.floorMod(letter.naturalPitchClass() + accidental.alteration(), 12);
    }

    /**
     * Position on the diatonic staff ladder, counting C0 as 0 and rising by one
     * per letter. Two notes share a staff line if and only if this value matches,
     * which is what the notation layer uses for vertical placement.
     */
    public int diatonicPosition() {
        return octave * 7 + letter.diatonicStep();
    }

    /**
     * Default spelling for a MIDI pitch, preferring sharps.
     *
     * <p>This is only a fallback for when no harmonic context is available. Real
     * spelling decisions should come from the key and the sounding chord, since
     * that is what produces a readable score.
     */
    public static PitchSpelling ofMidiPitchSharp(int midiPitch) {
        return ofMidiPitch(midiPitch, true);
    }

    /** Default spelling for a MIDI pitch, preferring flats. */
    public static PitchSpelling ofMidiPitchFlat(int midiPitch) {
        return ofMidiPitch(midiPitch, false);
    }

    private static PitchSpelling ofMidiPitch(int midiPitch, boolean preferSharps) {
        int octave = Math.floorDiv(midiPitch - MIDI_C0, 12);
        int pitchClass = Math.floorMod(midiPitch - MIDI_C0, 12);

        // Naturals map straight onto a letter; the five black keys need a choice.
        for (NoteLetter candidate : NoteLetter.values()) {
            if (candidate.naturalPitchClass() == pitchClass) {
                return new PitchSpelling(candidate, Accidental.NATURAL, octave);
            }
        }
        if (preferSharps) {
            NoteLetter below = NoteLetter.values()[0];
            for (NoteLetter candidate : NoteLetter.values()) {
                if (candidate.naturalPitchClass() == pitchClass - 1) {
                    below = candidate;
                    break;
                }
            }
            return new PitchSpelling(below, Accidental.SHARP, octave);
        }
        for (NoteLetter candidate : NoteLetter.values()) {
            if (candidate.naturalPitchClass() == pitchClass + 1) {
                return new PitchSpelling(candidate, Accidental.FLAT, octave);
            }
        }
        throw new IllegalStateException("unreachable: no spelling for pitch class " + pitchClass);
    }

    /** Human-readable name such as {@code F#4} or {@code Bb3}. */
    public String displayName() {
        return letter.name() + accidental.displaySuffix() + octave;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
