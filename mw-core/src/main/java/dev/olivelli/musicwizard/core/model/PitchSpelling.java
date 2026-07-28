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

    /** The scientific octave LilyPond writes with no {@code '} or {@code ,} mark. */
    private static final int LILYPOND_UNMARKED_OCTAVE = 3;

    /** Octaves {@link #parse} accepts: the range scientific pitch notation uses. */
    private static final int MIN_PARSED_OCTAVE = -1;
    private static final int MAX_PARSED_OCTAVE = 9;

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

    /**
     * Returns a copy moved by whole octaves, keeping letter and accidental.
     *
     * <p>The only transposition that is safe to apply without knowing the key:
     * an octave never changes how a note is spelled, whereas any other interval
     * does.
     */
    public PitchSpelling transposedByOctaves(int octaves) {
        return new PitchSpelling(letter, accidental, octave + octaves);
    }

    /**
     * The LilyPond note name without an octave mark, e.g. {@code cis} or
     * {@code bes}.
     *
     * <p>This is the form {@code \chordmode} wants. For a note on a staff use
     * {@link #lilyPondAbsoluteName()}, which adds the octave.
     */
    public String lilyPondName() {
        return letter.name().toLowerCase(java.util.Locale.ROOT) + accidental.lilyPondSuffix();
    }

    /**
     * The LilyPond note name in absolute octave notation, e.g. {@code cis'} for
     * C sharp 4.
     *
     * <p>LilyPond's unmarked octave is the one below middle C, so C4 is
     * {@code c'} and C3 is bare {@code c}; higher octaves add apostrophes and
     * lower ones commas.
     */
    public String lilyPondAbsoluteName() {
        int marks = octave - LILYPOND_UNMARKED_OCTAVE;
        return lilyPondName() + String.valueOf(marks >= 0 ? '\'' : ',').repeat(Math.abs(marks));
    }

    /**
     * Human-readable name such as {@code F#4} or {@code Bb3}.
     *
     * <p>{@link #parse} inverts this for every octave in
     * {@value #MIN_PARSED_OCTAVE}..{@value #MAX_PARSED_OCTAVE}, which is every
     * octave a spelling of a real pitch has. Outside that band the name is still
     * produced but will not parse back, deliberately: an octave of two hundred
     * million is a bug in whatever built the spelling, and the parser is where
     * untrusted text is stopped rather than round-tripped.
     */
    public String displayName() {
        return letter.name() + accidental.displaySuffix() + octave;
    }

    /**
     * Parses the form {@link #displayName()} produces: {@code F#4}, {@code Bb3},
     * {@code F##4}, {@code Ebb2}, {@code C-1}.
     *
     * <p>Exists so that a spelling can survive a config value, a CLI argument or
     * an advisor's reply, none of which can carry a record. The letter may be
     * given in either case; {@code bb3} is B flat 3, which is unambiguous
     * because the letter is always exactly one character.
     *
     * <p>Because that input is untrusted, this is stricter than the record: only
     * ASCII digits count as an octave, and the octave must lie within
     * {@value #MIN_PARSED_OCTAVE}..{@value #MAX_PARSED_OCTAVE}. That band is the
     * range of scientific pitch notation, not of MIDI, so the result can still
     * sound outside 0..127 — {@code Cb-1} and {@code B#9} both do — and a caller
     * needing a playable pitch must range-check {@link #midiPitch()} exactly as
     * that method already says.
     *
     * @throws IllegalArgumentException if the text is not a pitch in that form
     */
    public static PitchSpelling parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("pitch must not be null");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("pitch must not be blank");
        }
        NoteLetter parsedLetter;
        try {
            parsedLetter = NoteLetter.valueOf(trimmed.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "pitch must start with a note letter A-G, got: " + text, e);
        }
        // The accidental runs up to the octave, which is the first ASCII digit or
        // the sign in front of it. Splitting there keeps "Bb3" unambiguous: the
        // letter is exactly one character, so the rest can only be an accidental.
        // Deliberately not Character.isDigit, which is true of every decimal
        // digit in Unicode -- and Integer.parseInt accepts those too, so an
        // Arabic-Indic digit would otherwise parse to a value nobody typed.
        int octaveStart = 1;
        while (octaveStart < trimmed.length()
                && trimmed.charAt(octaveStart) != '-'
                && !isAsciiDigit(trimmed.charAt(octaveStart))) {
            octaveStart++;
        }
        if (octaveStart >= trimmed.length()) {
            throw new IllegalArgumentException("pitch must carry an octave, got: " + text);
        }
        Accidental parsedAccidental = accidentalOfDisplaySuffix(
                trimmed.substring(1, octaveStart), text);
        int parsedOctave = parseOctave(trimmed.substring(octaveStart), text);
        return new PitchSpelling(parsedLetter, parsedAccidental, parsedOctave);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Reads the octave, rejecting anything outside scientific pitch notation.
     *
     * <p>Bounded because the octave is unbounded arithmetic everywhere it is
     * used: an octave of two hundred million overflows {@link #midiPitch()} into
     * a negative number without complaint, and
     * {@link #lilyPondAbsoluteName()} would build a string with one octave mark
     * per octave. {@code parse} is the door untrusted text comes through -- a
     * config value, a CLI argument, an advisor's reply -- so it is the place to
     * shut that off. Direct construction still allows any octave, since an
     * extreme spelling is legitimate as an intermediate value.
     */
    private static int parseOctave(String digits, String whole) {
        int from = digits.charAt(0) == '-' ? 1 : 0;
        if (from >= digits.length()) {
            throw new IllegalArgumentException("pitch has an unreadable octave: " + whole);
        }
        for (int i = from; i < digits.length(); i++) {
            if (!isAsciiDigit(digits.charAt(i))) {
                throw new IllegalArgumentException("pitch has an unreadable octave: " + whole);
            }
        }
        int octave;
        try {
            octave = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("pitch has an unreadable octave: " + whole, e);
        }
        if (octave < MIN_PARSED_OCTAVE || octave > MAX_PARSED_OCTAVE) {
            throw new IllegalArgumentException(
                    "octave must be within " + MIN_PARSED_OCTAVE + ".." + MAX_PARSED_OCTAVE
                            + ", got: " + whole);
        }
        return octave;
    }

    private static Accidental accidentalOfDisplaySuffix(String suffix, String whole) {
        for (Accidental candidate : Accidental.values()) {
            if (candidate.displaySuffix().equals(suffix)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "unknown accidental \"" + suffix + "\" in pitch: " + whole);
    }

    @Override
    public String toString() {
        return displayName();
    }
}
