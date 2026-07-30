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
import java.util.Locale;
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
 * <p>So the shift is described as a displacement along the <em>line of
 * fifths</em>. Every written pitch has a position there -- F is -1, C is 0, G is
 * 1, and a sharp is seven steps up -- and adding one number to every position is
 * exactly what transposing by an interval does. Up a perfect fourth is -1 on
 * that line and up an augmented third is +11; both sound five semitones higher,
 * and the first is the one anybody means.
 *
 * <p>Which of them is meant is decided by where the piece already sits on the
 * line. Of the candidate displacements that sound right -- they differ by
 * twelve, the length of one turn of the circle of fifths -- this takes the one
 * that lands nearest the natural end of it. C major up five semitones gives F
 * major, one flat, rather than E sharp major with eleven sharps. Ties go to the
 * flat side, as they do in {@link PitchSpeller}: six semitones up from C major is
 * G flat rather than F sharp.
 *
 * <h2>What is displaced and what is re-derived</h2>
 *
 * <p>The two are not the same, and the first round of review on #129 is why.
 *
 * <p>A <b>key tonic</b> and a <b>note spelling</b> are displaced: the same number
 * is added to their position on the line, so the interval between any two of them
 * is written exactly as it was written before. A tonic comes from a MIDI key
 * signature and means something; a note spelling comes from {@link PitchSpeller},
 * which chose it from the chord and the key.
 *
 * <p>A <b>chord root</b> is re-derived instead: the sounding root moves, and the
 * way it is written is decided afresh from the region of the line the piece lands
 * in. That is because no chord root in this pipeline carries a considered
 * spelling. {@code ChordEstimator} spells every black key as a sharp from a fixed
 * table and says in its own javadoc that the key estimator re-spells the
 * progression afterwards -- and that stage does not exist. {@code
 * SymbolicChordEstimator} does better but still only chooses all-sharps or
 * all-flats for the whole piece. Displacing those produced measurably wrong
 * charts: a I-V-vi-IV in E flat, spelled by the audio path as {@code D# A# Cm G#}
 * and moved up two, came out as {@code F C Ebbm Bb} -- an E double flat minor
 * chord on an engraved page, exit 0. Roughly one audio chart in eighteen carried
 * a double accidental and one in thirteen mixed sharps with flats, against a
 * baseline of none, because a spelling that carries no intent was being read as
 * intent.
 *
 * <p>Re-deriving costs a deliberately spelled chromatic root, of which this
 * pipeline produces none: a Neapolitan written D flat in C major would come back
 * as whatever the region prefers. That is a real loss and it is the smaller one.
 *
 * <h2>What it does not do</h2>
 *
 * <p>Timing, dynamics, lyrics, sections and the tempo map are untouched;
 * transposition is a pitch operation. A capo is <em>not</em> transposition -- it
 * changes the printed symbols while the sounding pitch stays put -- and is not
 * implemented (#181).
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
     * Where a key's chord roots sit on the line of fifths, relative to its key
     * signature.
     *
     * <p>Not the same as {@code PitchSpeller}'s centre, which is where a key's
     * <em>notes</em> sit -- two fifths above the signature -- and using that one
     * here spells a Neapolitan in C major as C sharp rather than D flat. Roots
     * sit lower: a I-V-vi-IV in C is C(0), G(1), A(3), F(-1), averaging 0.75 over
     * a signature of 0, and the borrowed roots a chart reaches for next are
     * flatter still.
     *
     * <p>Checked against the two decisions it actually settles, both in C major:
     * at 0.75 the flat second comes out D flat (-5, distance 5.75) rather than C
     * sharp (7, distance 6.25), and the raised fourth comes out F sharp (6,
     * distance 5.25) rather than G flat (-6, distance 6.75). Both are what a
     * chart wants. At 2.0 the first is wrong and at 0.0 the second is.
     */
    private static final double ROOT_CENTRE_OFFSET = 0.75;

    /** Diatonic steps in a perfect fifth, modulo the octave: C to G is four. */
    private static final int STEPS_PER_FIFTH = 4;

    /** Positions on the line of fifths in one turn of the circle. */
    private static final int FIFTHS_PER_TURN = 12;

    /**
     * How far either way to look for the displacement, in turns of the circle.
     *
     * <p>Three is generous. The winning displacement is the one putting the
     * target region within half a turn of natural, so it is within one turn of
     * the source region -- and a region reaches double figures only for a key
     * written with double accidentals.
     */
    private static final int TURNS_SEARCHED = 3;

    /**
     * The band of the line of fifths a written pitch can occupy: F double flat at
     * -15 through B double sharp at 19.
     *
     * <p>Used only as the range to search for a region, since a region outside
     * the band could not be the home of any writable spelling.
     */
    private static final int MIN_FIFTHS = -15;
    private static final int MAX_FIFTHS = 19;

    private Transposer() {
    }

    /**
     * A transposed score, and any part the shift could not move.
     *
     * <p>The two travel together because a caller has to be able to report the
     * second. A part is left out rather than half-moved: {@link Note} refuses a
     * shift that leaves MIDI 0..127, which is the behaviour #57 chose
     * deliberately, and the alternatives are all worse than a named absence.
     * Leaving the one note where it was gives a page correct everywhere except
     * there, which nobody would spot; dropping the note alone gives a melody with
     * a silent hole in it.
     *
     * @param score       the moved score, without the parts named below
     * @param partsLeftOut complete lines, already worded for a user, no prefix
     */
    public record Result(Score score, List<String> partsLeftOut) {

        public Result {
            Objects.requireNonNull(score, "score");
            partsLeftOut = List.copyOf(Objects.requireNonNull(partsLeftOut, "partsLeftOut"));
        }
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
     * @throws IllegalArgumentException if the shift exceeds {@link #MAX_SEMITONES}
     */
    public static Result transpose(Score score, int semitones) {
        Objects.requireNonNull(score, "score");
        if (semitones < -MAX_SEMITONES || semitones > MAX_SEMITONES) {
            throw new IllegalArgumentException("transposition must be within -"
                    + MAX_SEMITONES + ".." + MAX_SEMITONES + " semitones, got: " + semitones);
        }
        if (semitones == 0) {
            // Not merely an optimisation: a no-op must be exactly a no-op, and
            // running the machinery below would re-derive every chord root from
            // the estimated region and could change one.
            return new Result(score, List.of());
        }
        double region = sourceRegion(score);
        Shift shift = new Shift(semitones, displacement(semitones, region), region);

        List<Key> keys = new ArrayList<>(score.keys().size());
        for (Key key : score.keys()) {
            keys.add(new Key(shift.displace(key.tonic()), key.mode(),
                    key.startSeconds(), key.endSeconds(),
                    key.startBeat(), key.endBeat(), key.confidence()));
        }

        List<Chord> chords = new ArrayList<>(score.chords().size());
        for (Chord chord : score.chords().chords()) {
            if (chord.isNoChord()) {
                // A rest has no root. Chord.noChord parks a placeholder there,
                // and moving a placeholder would only invent a fact.
                chords.add(chord);
                continue;
            }
            chords.add(new Chord(shift.rederive(chord.root()), chord.quality(),
                    chord.bass().map(shift::rederive),
                    chord.startSeconds(), chord.endSeconds(),
                    chord.startBeat(), chord.endBeat(), chord.confidence()));
        }

        List<NoteTrack> tracks = new ArrayList<>(score.tracks().size());
        List<String> leftOut = new ArrayList<>();
        for (NoteTrack track : score.tracks()) {
            Optional<NoteTrack> moved = transposedTrack(track, shift, leftOut);
            moved.ifPresent(tracks::add);
        }

        return new Result(new Score(score.title(), score.artist(), score.tempoMap(),
                score.beatGrid(), keys, score.sections(), tracks,
                score.chords().withChords(chords), score.lyrics(), score.durationSeconds()),
                leftOut);
    }

    /**
     * One track, moved, or empty when the shift cannot move all of it.
     *
     * <p>The refusal is scoped to the part rather than to the run, which round 1
     * of review found the hard way: {@code render --parts chords --transpose 12}
     * failed outright over a MIDI 120 note in an unclassified track that no
     * implemented part would ever have engraved. Up an octave for a singer is the
     * commonest request there is, and a MIDI file reaching past 115 is ordinary.
     * The chart the user asked for is produced, and the part that could not come
     * with it is named.
     */
    private static Optional<NoteTrack> transposedTrack(NoteTrack track, Shift shift,
                                                       List<String> leftOut) {
        List<Note> moved = new ArrayList<>(track.notes().size());
        for (Note note : track.notes()) {
            int pitch = note.midiPitch() + shift.semitones();
            if (pitch < 0 || pitch > 127) {
                leftOut.add(String.format(Locale.ROOT,
                        "the %s part was left out: it holds MIDI pitch %d at %.3fs, which %+d"
                                + " semitones would move outside the playable range 0..127",
                        track.name(), note.midiPitch(), note.onsetSeconds(), shift.semitones()));
                return Optional.empty();
            }
            Note shifted = note.transposedBy(shift.semitones());
            // Only where the source had one. An un-spelled note is one the
            // pipeline has not decided about yet, and inventing a spelling here
            // would pre-empt PitchSpeller with a worse guess -- it has the
            // sounding chord to consult and this does not.
            moved.add(note.spelling().isPresent()
                    ? shifted.spelledAs(shift.displace(note.spelling().get()))
                    : shifted);
        }
        return Optional.of(track.withNotes(moved));
    }

    /**
     * One transposition: a sounding interval, its written form, and where it
     * lands on the line of fifths.
     *
     * @param semitones    how far the pitch moves
     * @param fifths       how far a displaced spelling moves along the line
     * @param sourceRegion where the piece sat on the line before the shift
     */
    private record Shift(int semitones, int fifths, double sourceRegion) {

        /** Where the piece sits on the line of fifths after the shift. */
        double targetRegion() {
            return sourceRegion + fifths;
        }

        /**
         * How many letters up the diatonic ladder a displaced spelling moves.
         *
         * <p>Modulo seven, because the octave is recovered from the sounding
         * pitch rather than counted: that is what puts C flat in the octave above
         * the B it sounds as.
         */
        int diatonicSteps() {
            return Math.floorMod(fifths * STEPS_PER_FIFTH, 7);
        }

        /**
         * A spelling moved by the interval, keeping how it was written.
         *
         * <p>The letter comes from the diatonic step count and the accidental is
         * then whatever makes that letter sound at the new pitch -- which is the
         * same displacement along the line of fifths, arrived at from the staff
         * side rather than the arithmetic side.
         *
         * <p>Two things can make the answer unwritable, and both fall back to
         * {@link #rederive}. An accidental beyond a double cannot be printed;
         * round 1 of review found the other, which is that a legal spelling can
         * land outside the octave band {@link PitchSpelling#parse} accepts --
         * B sharp sounding as MIDI 0 sits in octave -2. {@code PitchSpeller}
         * centralised that check in {@code atOctave} precisely so no route could
         * skip it, and this is a new route, so it asks.
         */
        PitchSpelling displace(PitchSpelling from) {
            int pitch = from.midiPitch() + semitones;
            NoteLetter letter = NoteLetter.ofDiatonicStep(
                    from.letter().diatonicStep() + diatonicSteps());
            int alteration = PitchSpeller.alterationFor(letter, Math.floorMod(pitch, 12));
            if (alteration >= -2 && alteration <= 2) {
                Optional<PitchSpelling> written = PitchSpeller.atOctave(
                        letter, Accidental.ofAlteration(alteration), pitch);
                if (written.isPresent()) {
                    return written.get();
                }
            }
            return rederive(from);
        }

        /**
         * A spelling moved by the interval, written afresh from where the piece
         * lands.
         *
         * <p>What chord roots get, and what an unwritable displacement degrades
         * to. The sounding pitch is the same either way; only the choice between
         * its enharmonics differs, and this one asks the target region rather than
         * the source spelling.
         */
        PitchSpelling rederive(PitchSpelling from) {
            return PitchSpeller.onLineOfFifths(from.midiPitch() + semitones, targetRegion());
        }
    }

    /**
     * How far along the line of fifths a shift of this many semitones moves a
     * displaced spelling.
     *
     * <p>Seven fifths make an octave plus a semitone, so a displacement of
     * {@code d} sounds {@code 7d} semitones higher and the candidates for a shift
     * of {@code s} are the {@code d} with {@code 7d == s} modulo twelve. They lie
     * twelve apart -- one turn of the circle of fifths, which is where the two
     * spellings of a black key come from -- and the one taken is the one leaving
     * the piece nearest the natural end of the line.
     *
     * <p>Whole octaves are excluded from that choice and pinned to zero. An
     * octave changes neither letter nor accidental, so a piece in C sharp major
     * moved up an octave must stay in C sharp major; letting the search run would
     * notice that D flat major is simpler and respell a page the user only asked
     * to move.
     */
    private static int displacement(int semitones, double region) {
        if (semitones % 12 == 0) {
            return 0;
        }
        int base = Math.floorMod(semitones * 7, FIFTHS_PER_TURN);
        int best = base;
        double bestDistance = Double.MAX_VALUE;
        for (int turn = -TURNS_SEARCHED; turn <= TURNS_SEARCHED; turn++) {
            int candidate = base + FIFTHS_PER_TURN * turn;
            double distance = Math.abs(region + candidate);
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
     * Where the piece's chord roots already sit on the line of fifths.
     *
     * <p>From the detected key when there is one. A key tonic is read from a MIDI
     * key signature rather than guessed, so its signature is exact, and
     * {@link Key#keySignatureAccidentals()} gets both modes right where averaging
     * roots does not -- the roots of A minor average two fifths sharp of its
     * signature of zero.
     *
     * <p>Otherwise from the chord roots, and from their <em>sounding pitches</em>
     * rather than from how they happen to be written. That is round 1's finding:
     * on the audio path every black-key root is a sharp from a fixed table, so
     * reading the spellings puts a piece in E flat at nine fifths sharp of where
     * it is. Asking instead which region makes the whole set cheapest to write
     * ignores the table entirely -- E flat's {@code D# A# Cm G#} and a properly
     * spelled {@code Eb Bb Cm Ab} give the same answer, which is the point.
     *
     * <p>With neither a key nor a chord there is nothing to go on and the natural
     * region is assumed. That is a real limitation rather than a rounding: a score
     * holding only notes is judged as though it were in C, and it costs at most
     * one turn of the circle.
     */
    private static double sourceRegion(Score score) {
        Optional<Key> primary = score.primaryKey();
        if (primary.isPresent()) {
            return primary.get().keySignatureAccidentals() + ROOT_CENTRE_OFFSET;
        }
        List<Integer> roots = new ArrayList<>();
        for (Chord chord : score.chords().chords()) {
            if (!chord.isNoChord()) {
                roots.add(chord.root().midiPitch());
            }
        }
        return roots.isEmpty() ? 0 : cheapestRegion(roots);
    }

    /**
     * The point on the line of fifths that the given sounding roots are cheapest
     * to write from.
     *
     * <p>A miniature key finder, and deliberately no more than that. For each
     * candidate region it asks {@link PitchSpeller#onLineOfFifths} how it would
     * write each root there and sums how far the answers land from the region
     * itself; the region where that total is smallest is the one the piece lives
     * in. Ties go to the flatter candidate, since the scan runs flat to sharp and
     * compares strictly.
     *
     * <p>Exhaustive over the band a writable spelling can occupy, which is
     * thirty-five candidates against a handful of roots -- cheaper than the
     * closed form would be worth. It does not distinguish major from minor and
     * does not need to: what it is asked for is the region, and a key and its
     * relative minor share one.
     *
     * <p><b>Cheapest, and then nearest natural.</b> The cost alone does not
     * decide, because it repeats every twelve: move the region one whole turn of
     * the circle and every root moves with it, so C major looks exactly as cheap
     * written from -12, where its roots are D double flat and A double flat, as
     * from 0. Scanning for the cheapest alone therefore returned the flattest of
     * the equal minima, and a chart transposed by an octave -- where there is no
     * displacement to cancel it -- came out as {@code Dbb Abb Bbbm Gbb}. Found by
     * sweeping every key against every shift while fixing round 1's finding, not
     * by any fixture; the ordinary shifts hide it, because the displacement is
     * chosen from the same wrong region and lands back in the right place.
     */
    private static double cheapestRegion(List<Integer> roots) {
        int best = 0;
        double bestCost = Double.MAX_VALUE;
        int bestMagnitude = Integer.MAX_VALUE;
        for (int region = MIN_FIFTHS; region <= MAX_FIFTHS; region++) {
            double cost = 0;
            for (int pitch : roots) {
                cost += Math.abs(
                        PitchSpeller.fifthsOf(PitchSpeller.onLineOfFifths(pitch, region))
                                - region);
            }
            int magnitude = Math.abs(region);
            // Scanned flat to sharp and compared strictly, so a region equally
            // cheap and equally far either way keeps the flatter one.
            if (cost < bestCost || (cost == bestCost && magnitude < bestMagnitude)) {
                bestCost = cost;
                bestMagnitude = magnitude;
                best = region;
            }
        }
        return best;
    }
}
