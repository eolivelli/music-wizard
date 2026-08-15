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

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Moves a whole score by an interval.
 *
 * <p>Symbolic, so it lives here rather than beside the emitters #129 named: the
 * same operation serves the text chart, the engraved page, MusicXML and MIDI, and
 * written once per emitter it is a way for them to disagree about the key.
 *
 * <h2>The part that is not arithmetic</h2>
 *
 * <p>Shifting MIDI numbers by three and spelling the results from a pitch-class
 * table turns C major into D sharp major: arithmetically right, and a page no
 * musician would accept. So the shift is applied as one constant displacement
 * along the <b>line of fifths</b>, where every written pitch has a position -- F
 * is -1, C is 0, G is 1, and a sharp is seven steps up. Adding one number to
 * every position is what transposing by an interval does, and because one number
 * moves everything, no two written pitches can come out disagreeing about the
 * interval between them.
 *
 * <p>Which number is the whole question. The candidates that sound alike are
 * twelve apart -- one turn of the circle of fifths, which is where the two
 * spellings of a black key come from -- and the one taken is the one that leaves
 * <em>what will be printed</em> nearest natural, ties going flat as the rest of
 * the spelling layer's do. With a key that is its signature, so C major up three
 * is E flat major and not D sharp major; with no key at all it is the region the
 * chord symbols are counted from, which is {@link ChordSpeller}'s own
 * fallback rather than a second copy of it.
 *
 * <h2>What this does not decide</h2>
 *
 * <p>How the harmony is <em>written</em> is {@link ChordSpeller}'s, before and
 * after this runs alike. This moves each root's sounding pitch and displaces its
 * spelling so the result stands on its own; {@code render} then re-spells, and
 * the region it re-spells from is the transposed key, so the two cannot
 * disagree. A second region search here, with a centre constant of its own, would
 * be a chart whose spelling depended on whether it had been transposed.
 *
 * <p>A note's spelling is displaced and never re-derived. A chromatic note the
 * source spelled deliberately -- a MIDI key signature, or a user's correction --
 * means something that re-deriving would guess at again.
 */
public final class Transposer {

    /**
     * The widest shift accepted, in semitones.
     *
     * <p>Bounded because the interval, unlike a pitch, wraps: pitch classes
     * repeat every twelve, so a chart transposed by 50 is a chart transposed by 2
     * that a reader cannot tell from one transposed by 2. Left unbounded, a typo
     * in the flag is a confidently wrong answer rather than a refusal. Two
     * octaves is past any real instrument transposition.
     */
    public static final int MAX_SEMITONES = 24;

    /** Positions on the line of fifths in one turn of the circle. */
    private static final int FIFTHS_PER_TURN = 12;

    /** Steps along the line of fifths in one semitone: C to C sharp is seven. */
    private static final int FIFTHS_PER_SEMITONE = 7;

    private static final int SEMITONES_PER_OCTAVE = 12;

    /** The MIDI pitches a note may sound. */
    private static final int MIN_PITCH = 0;
    private static final int MAX_PITCH = 127;

    /** MIDI C4, the octave every estimator writes a chord root in. */
    private static final int SYMBOL_OCTAVE = 60;

    private Transposer() {
    }

    /**
     * Whether a shift is one this will perform.
     *
     * <p>Exposed so that the bound is compared in one place. {@code render}
     * refuses an out-of-range shift as a usage error and needs its own wording
     * for that -- but not its own comparison, which is how the two come apart.
     * Written the obvious way, as
     * {@code Math.abs(semitones) > MAX_SEMITONES}, both let {@link
     * Integer#MIN_VALUE} through: its absolute value is itself, and is negative.
     * That one input printed a chart in a key nobody asked for and exited 0.
     */
    public static boolean isWithinRange(int semitones) {
        return semitones >= -MAX_SEMITONES && semitones <= MAX_SEMITONES;
    }

    /**
     * A transposed score, and any part the shift could not move.
     *
     * <p>The two travel together because the caller has to be able to report the
     * second. A part is left out rather than half-moved: {@link Note} refuses a
     * pitch outside 0..127, which is the range check #57 left to the caller, and
     * every alternative is worse than a named absence. Leaving the one note where
     * it was gives a page correct everywhere except there; dropping the note
     * alone gives a melody with a silent hole in it.
     *
     * @param score        the moved score, without the parts named below
     * @param partsLeftOut complete lines, already worded for a user, no prefix
     */
    public record Result(Score score, List<String> partsLeftOut) {
        public Result {
            Objects.requireNonNull(score, "score");
            Objects.requireNonNull(partsLeftOut, "partsLeftOut");
            partsLeftOut = List.copyOf(partsLeftOut);
        }
    }

    /**
     * The score moved by the given number of semitones.
     *
     * <p>A shift of zero returns the score untouched. A whole number of octaves
     * moves every note and nothing else: it is pinned to a displacement of zero
     * fifths rather than sent through the rule below, so a piece written in C
     * sharp major moved up twelve stays in C sharp major instead of being quietly
     * "simplified" to D flat, which is a correction nobody asked for.
     *
     * @param semitones how far to move, positive up
     * @throws IllegalArgumentException if the shift is beyond {@link
     *                                  #MAX_SEMITONES} either way
     */
    public static Result transpose(Score score, int semitones) {
        Objects.requireNonNull(score, "score");
        if (!isWithinRange(semitones)) {
            throw new IllegalArgumentException("a transposition of " + semitones
                    + " semitones is beyond the " + MAX_SEMITONES + " this accepts");
        }
        if (semitones == 0) {
            return new Result(score, List.of());
        }
        boolean pureOctave = Math.floorMod(semitones, SEMITONES_PER_OCTAVE) == 0;
        int displacement = pureOctave ? 0 : displacement(score, semitones);
        List<String> leftOut = new ArrayList<>();
        List<NoteTrack> tracks = new ArrayList<>(score.tracks().size());
        for (NoteTrack track : score.tracks()) {
            Optional<String> refusal = unmovableNote(track, semitones);
            if (refusal.isPresent()) {
                leftOut.add(refusal.get());
            } else {
                tracks.add(move(track, semitones, displacement));
            }
        }
        Score moved = new Score(score.title(), score.artist(), score.tempoMap(),
                score.beatGrid(), pureOctave ? score.keys() : move(score.keys(), semitones),
                score.sections(),
                List.copyOf(tracks),
                score.chords().withChords(move(score.chords().chords(), semitones, displacement)),
                score.lyrics(), score.durationSeconds());
        return new Result(moved, leftOut);
    }

    // ------------------------------------------------------------ the interval

    /**
     * How far along the line of fifths the shift moves every written pitch.
     *
     * <p>Seven steps per semitone, then whichever turn of the circle leaves what
     * the page shows nearest natural. What the page shows is the key signature
     * when there is a key -- the printed thing a reader counts -- and the region
     * the chord symbols sit in when there is not.
     *
     * <p>Taken from the <em>primary</em> key on a score that modulates, while
     * each key re-derives its own signature, so the two can differ by a turn of
     * the circle. That needs a source score holding both a plain key and one
     * written from the far side of the circle, and there the far section's notes
     * are written a turn away from its own new signature. Its chord symbols are
     * not, because {@code render} re-spells those from the key in force under
     * them.
     *
     * <p>The keyless branch has a second gap of the same shape and is #299.
     * "Nearest natural" is not quite the rank {@code ChordSpeller.cheapestRegion}
     * applies, so at the F sharp against G flat boundary the two can choose
     * regions a turn apart -- and there a note comes out written {@code Cb} under
     * a symbol reading {@code B}. Only a score carrying spelled notes and no key
     * reaches it, which no stage produces today.
     */
    private static int displacement(Score score, int semitones) {
        int source = score.primaryKey()
                .map(Key::keySignatureAccidentals)
                // The region ChordSpeller would count for a keyless score, so
                // that the interval and the re-spelling that follows it come
                // from one reading of where the piece sits rather than from two.
                // Integral, because that is what cheapestRegion searches over.
                .orElseGet(() -> (int) Math.round(ChordSpeller.countedRegion(
                        score.chords().chords(), OptionalDouble.empty())));
        return nearestNatural(source + FIFTHS_PER_SEMITONE * semitones) - source;
    }

    /**
     * The position within one turn of the circle that is cheapest to write.
     *
     * <p>A tie -- six either way, which is F sharp major against G flat major --
     * goes flat, as every other tie in the spelling layer does.
     */
    private static int nearestNatural(int fifths) {
        return Math.floorMod(fifths + FIFTHS_PER_TURN / 2, FIFTHS_PER_TURN) - FIFTHS_PER_TURN / 2;
    }

    // ---------------------------------------------------------------- the move

    /**
     * Every key, with its signature re-derived rather than displaced.
     *
     * <p>Displacing a tonic would carry a source key written from the far side of
     * the circle -- a C flat major that is B major with seven flats -- into a
     * target key written from further out still. Each key lands nearest natural
     * on its own, which preserves the interval between two keys of any ordinary
     * score, since folding into one turn of the circle keeps the difference
     * between signatures less than a turn apart.
     */
    private static List<Key> move(List<Key> keys, int semitones) {
        List<Key> moved = new ArrayList<>(keys.size());
        for (Key key : keys) {
            int signature = nearestNatural(
                    key.keySignatureAccidentals() + FIFTHS_PER_SEMITONE * semitones);
            moved.add(new Key(Key.tonicOf(signature, key.mode()), key.mode(),
                    key.startSeconds(), key.endSeconds(), key.startBeat(), key.endBeat(),
                    key.confidence(), key.signatureConfidence(), key.tonicConfidence()));
        }
        return List.copyOf(moved);
    }

    /**
     * Every chord, root and slash bass alike.
     *
     * <p>A no-chord span carries a placeholder root that means nothing, so it is
     * passed through rather than moved -- the same span {@link ChordSpeller}
     * neither prices nor rewrites.
     */
    private static List<Chord> move(List<Chord> chords, int semitones, int displacement) {
        List<Chord> moved = new ArrayList<>(chords.size());
        for (Chord chord : chords) {
            if (chord.isNoChord()) {
                moved.add(chord);
                continue;
            }
            moved.add(new Chord(displaceSymbol(chord.root(), semitones, displacement),
                    chord.quality(),
                    chord.bass().map(bass -> displaceSymbol(bass, semitones, displacement)),
                    chord.startSeconds(), chord.endSeconds(),
                    chord.startBeat(), chord.endBeat(), chord.confidence()));
        }
        return moved;
    }

    /**
     * Every note, its sounding pitch and any spelling it carries.
     *
     * <p>Not {@code Note.transposedBy}, which drops the spelling for any interval
     * but an octave, and says why: choosing the new one needs the key, and a
     * {@link Note} cannot see one. Here the key is known, so the spelling is
     * displaced rather than discarded and re-guessed by whatever spells the part
     * next.
     */
    private static NoteTrack move(NoteTrack track, int semitones, int displacement) {
        List<Note> moved = new ArrayList<>(track.notes().size());
        for (Note note : track.notes()) {
            moved.add(new Note(note.onsetSeconds(), note.durationSeconds(),
                    note.midiPitch() + semitones, note.velocity(),
                    note.spelling().map(spelling ->
                            displace(spelling, note.midiPitch() + semitones, displacement)),
                    note.onsetBeat(), note.durationBeats(), note.confidence()));
        }
        return track.withNotes(moved);
    }

    /**
     * A chord symbol's written pitch, moved.
     *
     * <p>Rewritten into the octave the estimators use when the shift leaves the
     * playable range, where a note's would refuse: an octave is part of what a
     * note means and no part of what a symbol means, since nothing prints the
     * octave of a chord root. Unreachable from the pipeline, which writes every
     * root in octave 4, and reachable from a score read off disk -- a spelling
     * may sound past MIDI 127 and {@link PitchSpelling} says so.
     */
    private static PitchSpelling displaceSymbol(PitchSpelling written, int semitones,
                                                int displacement) {
        int pitch = written.midiPitch() + semitones;
        if (pitch < MIN_PITCH || pitch > MAX_PITCH) {
            pitch = Math.floorMod(pitch, SEMITONES_PER_OCTAVE) + SYMBOL_OCTAVE;
        }
        return displace(written, pitch, displacement);
    }

    /**
     * One written pitch, moved along the line of fifths to sound as the given
     * pitch.
     *
     * <p>The displaced position is used as written whenever it can be, which is
     * what keeps every interval in the source spelled as it was. It cannot be
     * where the source was already at a double accidental and the shift pushes it
     * past what a staff can print, or where the octave the answer would need is
     * off the keyboard; there the nearest printable spelling of the same sound is
     * taken, measured from the position that was wanted, so the answer is as
     * close to the displaced one as a page allows.
     */
    private static PitchSpelling displace(PitchSpelling written, int pitch, int displacement) {
        int moved = PitchSpeller.fifthsOf(written) + displacement;
        if (moved >= PitchSpeller.MIN_FIFTHS && moved <= PitchSpeller.MAX_FIFTHS) {
            // Asked of the answer rather than of a pitch-class table of its own,
            // which would be a second copy of the mapping PitchSpeller warns
            // against keeping two of. The position sounds as the pitch whenever
            // the source spelling sounded as the pitch it was displaced from; a
            // score read off disk can carry one that does not, since nothing
            // validates the two halves of a Note against each other, and taking
            // the position anyway would move the sound as well as the spelling.
            Optional<PitchSpelling> exact = PitchSpeller.spellingOf(moved, pitch)
                    .filter(candidate -> candidate.midiPitch() == pitch);
            if (exact.isPresent()) {
                return exact.get();
            }
        }
        return PitchSpeller.onLineOfFifths(pitch, moved);
    }

    // ------------------------------------------------------------- the refusal

    /**
     * Why this part cannot be moved, or empty when it can.
     *
     * <p>Asked of the whole part before any of it is moved, so that a part is
     * never half-transposed. The note named is the first one that cannot move,
     * which is enough for a user to find the problem and is not a claim that it
     * is the only one.
     */
    private static Optional<String> unmovableNote(NoteTrack track, int semitones) {
        for (Note note : track.notes()) {
            int moved = note.midiPitch() + semitones;
            if (moved < MIN_PITCH || moved > MAX_PITCH) {
                return Optional.of(String.format(Locale.ROOT,
                        "the %s part was left out: it holds MIDI pitch %d at %.3fs, which"
                                + " %+d semitones would move outside the playable range %d..%d",
                        track.name(), note.midiPitch(), note.onsetSeconds(), semitones,
                        MIN_PITCH, MAX_PITCH));
            }
        }
        return Optional.empty();
    }
}
