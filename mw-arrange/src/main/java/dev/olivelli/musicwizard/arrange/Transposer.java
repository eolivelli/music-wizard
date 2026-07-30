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

package dev.olivelli.musicwizard.arrange;

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Moves a whole score to another key.
 *
 * <p>Symbolic, and here rather than in {@code mw-notation} for that reason. A
 * transposed score is a score: its chord symbols, its key and its note pitches
 * all move, and they move the same way whether the result is engraved, exported
 * to MusicXML, written to MIDI or printed as a text chart. Put in an emitter it
 * would have to be written once per emitter, and the text chart and the engraved
 * page could then disagree about what key the music is in.
 *
 * <h2>Transposition is by an interval, not by a semitone count</h2>
 *
 * <p>This is the whole difficulty, and it is what {@link PitchSpelling} exists
 * for. Shifting MIDI numbers by three and spelling the results from a table
 * turns C major into D sharp major: arithmetically correct, and a page no
 * musician would accept, because the answer is E flat major. Pitch 61 is both C
 * sharp and D flat and the interval decides which.
 *
 * <p>So the shift is applied as a constant displacement along the <em>line of
 * fifths</em>. Every written pitch has a position there -- F is -1, C is 0, G is
 * 1, and a sharp is seven steps up -- and adding one number to every position is
 * exactly what transposing by an interval does. Up a perfect fourth is -1 on
 * that line and up an augmented third is +11; both sound five semitones higher,
 * and the first is the one anybody means.
 *
 * <p>Which of them is meant is decided by the key. Of the candidate
 * displacements that sound right -- they differ by twelve, the length of one
 * turn of the circle of fifths -- this takes the one that leaves the target key
 * signature nearest to no accidentals at all. C major up five semitones gives F
 * major, one flat, rather than E sharp major with eleven sharps. Ties go to the
 * flat side, as they do in {@link PitchSpeller}: six semitones up from C major is
 * G flat rather than F sharp.
 *
 * <p>Because one displacement is applied to everything, the printed key
 * signature, the chord symbols and the note spellings cannot come out
 * disagreeing with each other. That is the reason for doing it this way rather
 * than shifting pitches and calling {@link PitchSpeller} on the result: spelling
 * from the new harmony would re-derive each decision independently, and a
 * chromatic note the source spelled deliberately would be re-guessed.
 *
 * <h2>What it does not do</h2>
 *
 * <p>Timing, dynamics, lyrics, sections and the tempo map are untouched;
 * transposition is a pitch operation. A capo is <em>not</em> transposition -- it
 * changes the printed symbols while the sounding pitch stays put -- and is not
 * implemented (#129).
 */
public final class Transposer {

    /**
     * The widest shift accepted, in semitones.
     *
     * <p>Bounded because the interval, unlike the pitch, wraps: pitch classes
     * repeat every twelve semitones, so a chord chart transposed by 50 is a
     * chart transposed by 2 with a different octave nobody can see on it. Left
     * unbounded, {@code --transpose 50} for {@code --transpose 5} would print a
     * confidently wrong chart rather than being refused, which is the failure
     * this whole change exists to remove. Two octaves is already past any real
     * instrument transposition, so nothing musical is lost by drawing the line
     * there.
     */
    public static final int MAX_SEMITONES = 24;

    /**
     * How much sharper a piece's chord roots sit than its key signature, on the
     * line of fifths.
     *
     * <p>Only used when key detection has not run and the piece's region of the
     * line has to be guessed from the symbols. A I-V-vi-IV in C has roots at 0,
     * 1, 3 and -1, averaging 0.75, over a signature of 0; a ii-V-I in C averages
     * 1.0 over the same signature. So the roots overstate the signature by about
     * one fifth. This is the same relation {@code PitchSpeller}'s own
     * {@code CHORD_ROOT_CENTRE_OFFSET} encodes, measured against a different
     * origin.
     *
     * <p>Approximate on purpose, and it only has to be good enough to pick
     * between candidates twelve apart.
     */
    private static final double ROOT_SHARPER_THAN_SIGNATURE = 1.0;

    /** Diatonic steps in a perfect fifth, modulo the octave: C to G is four. */
    private static final int STEPS_PER_FIFTH = 4;

    /** Positions on the line of fifths in one turn of the circle. */
    private static final int FIFTHS_PER_TURN = 12;

    /**
     * How far either way to look for the displacement, in turns of the circle.
     *
     * <p>Three is generous. The winning displacement is the one putting the
     * target signature within half a turn of zero, so it is within one turn of
     * the source signature -- and a signature reaches double figures only for a
     * key written with double accidentals.
     */
    private static final int TURNS_SEARCHED = 3;

    private Transposer() {
    }

    /**
     * Returns the score moved by a number of semitones.
     *
     * <p>The score itself is not modified and nothing is written; the caller
     * decides what to do with the result. In particular {@code render}
     * transposes what it read and engraves that, leaving the workspace's saved
     * transcription in the key it was analysed in.
     *
     * @param semitones the shift; zero returns the score unchanged
     * @throws IllegalArgumentException if the shift exceeds {@link #MAX_SEMITONES},
     *     or if it would move a note outside MIDI range -- see the note below
     */
    public static Score transpose(Score score, int semitones) {
        Objects.requireNonNull(score, "score");
        if (semitones < -MAX_SEMITONES || semitones > MAX_SEMITONES) {
            throw new IllegalArgumentException("transposition must be within -"
                    + MAX_SEMITONES + ".." + MAX_SEMITONES + " semitones, got: " + semitones);
        }
        if (semitones == 0) {
            // Not merely an optimisation: a no-op must be exactly a no-op, and
            // running the machinery below would re-derive every spelling from
            // the estimated signature and could change one.
            return score;
        }
        int fifths = displacement(semitones, sourceSignature(score));
        Shift shift = new Shift(semitones, fifths);

        List<Key> keys = new ArrayList<>(score.keys().size());
        for (Key key : score.keys()) {
            keys.add(new Key(shift.apply(key.tonic()), key.mode(),
                    key.startSeconds(), key.endSeconds(),
                    key.startBeat(), key.endBeat(), key.confidence()));
        }

        List<Chord> chords = new ArrayList<>(score.chords().size());
        for (Chord chord : score.chords().chords()) {
            chords.add(new Chord(shift.apply(chord.root()), chord.quality(),
                    chord.bass().map(shift::apply),
                    chord.startSeconds(), chord.endSeconds(),
                    chord.startBeat(), chord.endBeat(), chord.confidence()));
        }

        List<NoteTrack> tracks = new ArrayList<>(score.tracks().size());
        for (NoteTrack track : score.tracks()) {
            tracks.add(track.withNotes(transposedNotes(track, shift)));
        }

        return new Score(score.title(), score.artist(), score.tempoMap(), score.beatGrid(),
                keys, score.sections(), tracks, score.chords().withChords(chords),
                score.lyrics(), score.durationSeconds());
    }

    /**
     * The notes of one track, moved.
     *
     * <p>{@link Note#transposedBy(int)} carries the MIDI range check, which is
     * the behaviour #57 chose deliberately: a bass part holding a note above
     * MIDI 115 cannot be moved up an octave, and the model refuses rather than
     * guessing. The refusal is re-thrown here with the note named, because
     * "transposing by 12 puts pitch 120 out of MIDI range" on its own does not
     * tell a user which part to look at, and silently leaving that one note
     * where it was would produce exactly the confidently-wrong page this change
     * removes.
     */
    private static List<Note> transposedNotes(NoteTrack track, Shift shift) {
        List<Note> moved = new ArrayList<>(track.notes().size());
        for (Note note : track.notes()) {
            Note shifted;
            try {
                shifted = note.transposedBy(shift.semitones());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("cannot transpose by " + shift.semitones()
                        + " semitones: the " + track.name() + " part holds MIDI pitch "
                        + note.midiPitch() + " at " + String.format(java.util.Locale.ROOT,
                                "%.3fs", note.onsetSeconds())
                        + ", which the shift would move outside MIDI range 0..127", e);
            }
            // Only where the source had one. An un-spelled note is one the
            // pipeline has not decided about yet, and inventing a spelling here
            // would pre-empt PitchSpeller with a worse guess.
            moved.add(note.spelling().isPresent()
                    ? shifted.spelledAs(shift.apply(note.spelling().get()))
                    : shifted);
        }
        return moved;
    }

    /**
     * One transposition: a sounding interval and its written form.
     *
     * @param semitones how far the pitch moves
     * @param fifths    how far the written spelling moves along the line of fifths
     */
    private record Shift(int semitones, int fifths) {

        /**
         * How many letters up the diatonic ladder the spelling moves.
         *
         * <p>Modulo seven, because the octave is recovered from the sounding
         * pitch rather than counted: that is what puts C flat in the octave above
         * the B it sounds as.
         */
        int diatonicSteps() {
            return Math.floorMod(fifths * STEPS_PER_FIFTH, 7);
        }

        /** True when the target key is written with flats, used only as a fallback. */
        boolean prefersFlats() {
            return fifths < 0;
        }

        /**
         * The written form of one pitch after the shift.
         *
         * <p>The letter comes from the diatonic step count and the accidental is
         * then whatever makes that letter sound at the new pitch -- which is the
         * same displacement along the line of fifths, arrived at from the staff
         * side rather than the arithmetic side.
         *
         * <p>An accidental no staff can carry falls back to a plain enharmonic.
         * Three sharps on one note head is not printable and not readable, and
         * the case needs a source spelling already at a double accidental
         * <em>and</em> a piece whose key signature points the other way -- a
         * B double sharp in a score detected as C flat major. Refusing to
         * transpose over it would be worse than printing the enharmonic, since
         * every other note on the page is fine.
         */
        PitchSpelling apply(PitchSpelling from) {
            int pitch = from.midiPitch() + semitones;
            NoteLetter letter = NoteLetter.ofDiatonicStep(
                    from.letter().diatonicStep() + diatonicSteps());
            int alteration = PitchSpeller.alterationFor(letter, Math.floorMod(pitch, 12));
            if (alteration < -2 || alteration > 2) {
                return prefersFlats()
                        ? PitchSpelling.ofMidiPitchFlat(pitch)
                        : PitchSpelling.ofMidiPitchSharp(pitch);
            }
            // Exact: the pitch class of the letter and accidental already match
            // the pitch, so this only recovers which octave holds it.
            int octave = Math.floorDiv(
                    pitch - 12 - letter.naturalPitchClass() - alteration, 12);
            return new PitchSpelling(letter, Accidental.ofAlteration(alteration), octave);
        }
    }

    /**
     * How far along the line of fifths a shift of this many semitones moves the
     * written spelling.
     *
     * <p>Seven fifths make an octave plus a semitone, so a displacement of
     * {@code d} sounds {@code 7d} semitones higher and the candidates for a shift
     * of {@code s} are the {@code d} with {@code 7d == s} modulo twelve. They lie
     * twelve apart -- one turn of the circle of fifths, which is where the two
     * spellings of a black key come from -- and the one taken is the one leaving
     * the target signature closest to zero accidentals.
     *
     * <p>Whole octaves are excluded from that choice and pinned to zero. An
     * octave changes neither letter nor accidental, so a piece in C sharp major
     * moved up an octave must stay in C sharp major; letting the search run would
     * notice that D flat major is simpler and respell a page the user only asked
     * to move.
     */
    private static int displacement(int semitones, double signature) {
        if (semitones % 12 == 0) {
            return 0;
        }
        int base = Math.floorMod(semitones * 7, FIFTHS_PER_TURN);
        int best = base;
        double bestDistance = Double.MAX_VALUE;
        for (int turn = -TURNS_SEARCHED; turn <= TURNS_SEARCHED; turn++) {
            int candidate = base + FIFTHS_PER_TURN * turn;
            double distance = Math.abs(signature + candidate);
            // Strictly nearer, scanned flat to sharp, so an exact tie -- six
            // semitones from a natural key -- keeps the flatter side.
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Where the piece already sits on the circle of fifths, in sharps (positive)
     * or flats (negative).
     *
     * <p>From the detected key when there is one, since that is the number the
     * key signature is printed from and the target should be judged in the same
     * units.
     *
     * <p>Otherwise from the chord roots, which are already spelled -- the
     * estimator chose them, and a user or the advisor may have corrected them --
     * so their average position says which region of the line the piece lives in
     * without committing to a tonic. With neither a key nor a chord there is
     * nothing to go on and C major is assumed, which for a score holding only
     * notes means the target is judged as though the source were natural. That
     * is a real limitation rather than a rounding: it is only reachable before
     * key detection runs, and it costs at most one turn of the circle.
     */
    private static double sourceSignature(Score score) {
        Optional<Key> primary = score.primaryKey();
        if (primary.isPresent()) {
            return primary.get().keySignatureAccidentals();
        }
        double total = 0;
        int counted = 0;
        for (Chord chord : score.chords().chords()) {
            if (chord.isNoChord()) {
                // A no-chord span carries a placeholder root, so counting it
                // would drag the average towards C whatever the piece is in.
                continue;
            }
            total += PitchSpeller.fifthsOf(chord.root());
            counted++;
        }
        return counted == 0 ? 0 : total / counted - ROOT_SHARPER_THAN_SIGNATURE;
    }
}
