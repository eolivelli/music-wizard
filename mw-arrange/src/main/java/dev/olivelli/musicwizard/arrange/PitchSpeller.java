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
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
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
 * Decides how each sounding pitch is written on the staff.
 *
 * <p>A MIDI number cannot be engraved. Pitch 61 is both C sharp and D flat, and
 * choosing wrongly gives a score that is arithmetically correct and visibly
 * wrong to a musician -- an A flat bass line printed as G sharps, a Db chorus
 * spelled in sharps. So spelling is a decision, made here and carried on the
 * note beside the pitch it does not replace.
 *
 * <h2>The rule</h2>
 *
 * <p>Two stages, in order, which is stage one of ps13 and reaches roughly 99%
 * on tonal material:
 *
 * <ol>
 *   <li><b>The sounding chord wins.</b> If the pitch is a tone of the chord
 *       under it, it is spelled the way that chord spells it: the third of Ab
 *       is C, the seventh of B diminished is A flat. This is what makes a flat
 *       key print flat, because the chord symbols already do.
 *   <li><b>Otherwise, distance along the line of fifths</b> from the local
 *       tonic. Every pitch class has spellings twelve fifths apart -- E flat and
 *       D sharp, C flat and B -- and the one nearer the key's own region of the
 *       line is the one a reader expects.
 * </ol>
 *
 * <p>What this deliberately does not do is look at the notes on either side.
 * ps13's second stage corrects a spelling from its melodic neighbours, which is
 * what distinguishes an ascending chromatic run (sharps) from a descending one
 * (flats). Without it, an ascending chromatic passing tone in a very flat key
 * comes out as, say, F flat rather than E natural: flat, which is what the key
 * calls for, but not what the direction calls for.
 */
public final class PitchSpeller {

    /**
     * Where a major key's own notes sit on the line of fifths, relative to its
     * key signature.
     *
     * <p>C major spans F(-1) to B(5) and is centred on D(2), and every major key
     * is that shape shifted by its signature. Measuring distance from the centre
     * rather than from the tonic is what makes the two spellings of a pitch
     * class comparable at all.
     */
    private static final double MAJOR_CENTRE_OFFSET = 2.0;

    /**
     * The same for a minor key, pulled sharp by three quarters of a fifth.
     *
     * <p>A minor key in practice includes its raised leading tone, which sits
     * seven fifths above the seventh degree and is not in the signature. Ignoring
     * it centres A minor exactly where C major is and spells the leading tone of
     * A minor as A flat, which is wrong in a way every musician notices. The
     * offset is the centroid of the natural minor scale plus that one raised
     * degree.
     */
    private static final double MINOR_CENTRE_OFFSET = 2.75;

    /**
     * How much sharper the notes of a key are than its chord roots.
     *
     * <p>Only used when no key has been detected and the centre has to be
     * guessed from the chord symbols. A I-V-vi-IV in C has roots averaging 0.75
     * on the line of fifths while its notes centre on 2, so the roots understate
     * the centre by a little over one fifth. Approximate on purpose: it is a
     * fallback for a stage that has not run yet, not a key detector.
     */
    private static final double CHORD_ROOT_CENTRE_OFFSET = 1.0;

    /** The centre used when there is neither a key nor a chord: C major. */
    private static final double DEFAULT_CENTRE = MAJOR_CENTRE_OFFSET;

    /** Fifths from C for each note letter, in {@link NoteLetter} order. */
    private static final int[] LETTER_FIFTHS = {0, 2, 4, -1, 1, 3, 5};

    /**
     * The range of the line of fifths that a legal {@link Accidental} can reach:
     * F double flat at -15 through B double sharp at 19.
     *
     * <p>Package-private because {@link ChordSpeller} searches the same band for
     * a region, for the same reason: a region outside it cannot be reached by
     * any spelling that can be printed.
     */
    static final int MIN_FIFTHS = -15;
    static final int MAX_FIFTHS = 19;

    /** Accidentals a spelling can carry at all: a double sharp or a double flat. */
    static final int MAX_WRITABLE_ACCIDENTALS = 2;

    /**
     * Octaves a spelling may be placed in: the band
     * {@link PitchSpelling#parse} accepts.
     *
     * <p>Needed because an enharmonic can cross an octave boundary. In a key
     * with five sharps, MIDI 0 is nearer B sharp than C on the line of fifths,
     * and B sharp sounding as MIDI 0 sits in octave -2 -- a legal record that
     * {@code parse} refuses, so it would not survive a config value, a CLI
     * argument or an advisor's reply. A candidate that lands outside the band is
     * passed over for the enharmonic that does not, and the check lives in
     * {@code atOctave} so that every route to a spelling passes through it.
     */
    private static final int MIN_OCTAVE = -1;
    private static final int MAX_OCTAVE = 9;

    private PitchSpeller() {
    }

    /**
     * Spells every note of every track, using each note's own sounding chord and
     * local key.
     *
     * <p>Any spelling already on a note is replaced. Spelling is derived, not
     * observed -- MIDI does not carry it and audio certainly does not -- so a
     * spelling present before this runs is an earlier and worse guess, made
     * before the key was known.
     */
    public static Score spell(Score score) {
        Objects.requireNonNull(score, "score");
        double fallbackCentre = centreFromChords(score);
        List<NoteTrack> spelled = new ArrayList<>(score.tracks().size());
        for (NoteTrack track : score.tracks()) {
            spelled.add(spell(track, score, fallbackCentre));
        }
        return new Score(score.title(), score.artist(), score.tempoMap(), score.beatGrid(),
                score.keys(), score.sections(), spelled, score.chords(), score.lyrics(),
                score.durationSeconds());
    }

    /**
     * Spells one track against the harmony of a score it need not belong to.
     *
     * <p>The separate track argument is for the notation and arrangement stages,
     * which build a part -- a piano right hand, a transposed line -- and then
     * need it spelled against the same harmony as the rest of the piece.
     */
    public static NoteTrack spell(NoteTrack track, Score harmony) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(harmony, "harmony");
        return spell(track, harmony, centreFromChords(harmony));
    }

    private static NoteTrack spell(NoteTrack track, Score harmony, double fallbackCentre) {
        List<Note> spelled = new ArrayList<>(track.notes().size());
        for (Note note : track.notes()) {
            Optional<Chord> chord = chordUnder(harmony, note);
            Optional<Key> key = keyUnder(harmony, note);
            double centre = key.map(PitchSpeller::centreOf).orElse(fallbackCentre);
            spelled.add(note.spelledAs(spell(note.midiPitch(), chord, centre)));
        }
        return track.withNotes(spelled);
    }

    /**
     * Spells one pitch from the chord under it, falling back to the key.
     *
     * <p>Both arguments are optional because both come from lookups that
     * legitimately find nothing: a note in an intro before the first chord, a
     * score whose key detection has not run. With neither, the answer is what C
     * major would say.
     */
    public static PitchSpelling spell(int midiPitch, Optional<Chord> soundingChord,
                                      Optional<Key> localKey) {
        Objects.requireNonNull(localKey, "localKey");
        requireMidiPitch(midiPitch);
        return spell(midiPitch, soundingChord,
                localKey.map(PitchSpeller::centreOf).orElse(DEFAULT_CENTRE));
    }

    /** Spells one pitch from a key alone, ignoring any harmony. */
    public static PitchSpelling spellFromKey(int midiPitch, Key key) {
        Objects.requireNonNull(key, "key");
        requireMidiPitch(midiPitch);
        return onLineOfFifths(midiPitch, centreOf(key));
    }

    /**
     * Rejects a pitch outside MIDI range.
     *
     * <p>Checked at the public entry points rather than left to fail later. A
     * pitch of 200 has spellings on the line of fifths like any other, but every
     * one of them lands outside a writable octave, so the octave filter empties
     * the candidate list and {@link #onLineOfFifths} complains that a pitch
     * class has no spelling -- which is the wrong cause, and sends whoever reads
     * it looking at the wrong table.
     */
    private static void requireMidiPitch(int midiPitch) {
        if (midiPitch < 0 || midiPitch > 127) {
            throw new IllegalArgumentException(
                    "midiPitch must be within 0..127, got: " + midiPitch);
        }
    }

    private static PitchSpelling spell(int midiPitch, Optional<Chord> soundingChord,
                                       double centre) {
        Objects.requireNonNull(soundingChord, "soundingChord");
        if (soundingChord.isPresent()) {
            Optional<PitchSpelling> fromChord = asChordTone(midiPitch, soundingChord.get());
            if (fromChord.isPresent()) {
                return fromChord.get();
            }
        }
        return onLineOfFifths(midiPitch, centre);
    }

    // ---------------------------------------------------------------- chord tones

    /**
     * Spells a pitch as a tone of a chord, or empty if it is not one.
     *
     * <p>The letter comes from the chord's own root letter stepped up the
     * diatonic ladder -- a third is two letters up whether it is major or minor
     * -- and the accidental is whatever makes that letter sound right. That is
     * what gives B diminished seventh the A flat it needs rather than the G
     * sharp a pitch-class table would produce.
     *
     * <p>Package-private because {@link ChordSpeller} needs the same answer for a
     * slash chord's bass. Written on its own from the line of fifths, the bass of
     * E major in a flat-leaning piece comes out A flat -- a note that is not in
     * the chord it is printed under. Callers wanting the bass <em>derived</em>
     * rather than honoured must pass a chord with no bass on it, since the branch
     * below returns a written bass unchanged.
     */
    static Optional<PitchSpelling> asChordTone(int midiPitch, Chord chord) {
        if (chord.isNoChord()) {
            return Optional.empty();
        }
        int pitchClass = Math.floorMod(midiPitch, 12);
        // A slash chord's bass is spelled on the symbol already, so honour it
        // before deriving anything.
        if (chord.bass().isPresent() && chord.bass().get().pitchClass() == pitchClass) {
            Optional<PitchSpelling> bass = atOctave(chord.bass().get().letter(),
                    chord.bass().get().accidental(), midiPitch);
            if (bass.isPresent()) {
                return bass;
            }
        }
        int[] intervals = chord.quality().intervals();
        int[] steps = diatonicSteps(chord.quality());
        PitchSpelling root = chord.root();
        for (int i = 0; i < intervals.length && i < steps.length; i++) {
            if (Math.floorMod(root.pitchClass() + intervals[i], 12) != pitchClass) {
                continue;
            }
            NoteLetter letter = NoteLetter.ofDiatonicStep(root.letter().diatonicStep() + steps[i]);
            int alteration = alterationFor(letter, pitchClass);
            if (alteration < -2 || alteration > 2) {
                // A chord like F double flat diminished seventh wants a triple
                // flat on one tone. Nothing can print that, so fall through to
                // the line of fifths rather than emit an impossible accidental.
                return Optional.empty();
            }
            return atOctave(letter, Accidental.ofAlteration(alteration), midiPitch);
        }
        return Optional.empty();
    }

    /**
     * Diatonic steps above the root for each of a quality's intervals, parallel
     * to {@link ChordQuality#intervals()}.
     *
     * <p>Written out rather than derived from the semitone count, because the
     * semitones do not determine the step: nine semitones is the major sixth of
     * a 6 chord and the diminished seventh of a dim7, and spelling both as a
     * sixth would print C dim7 with an A in it.
     */
    private static int[] diatonicSteps(ChordQuality quality) {
        return switch (quality) {
            case MAJOR, MINOR, DIMINISHED, AUGMENTED -> new int[] {0, 2, 4};
            case SUSPENDED_SECOND -> new int[] {0, 1, 4};
            case SUSPENDED_FOURTH -> new int[] {0, 3, 4};
            case DOMINANT_SEVENTH, MAJOR_SEVENTH, MINOR_SEVENTH, MINOR_MAJOR_SEVENTH,
                 HALF_DIMINISHED_SEVENTH, DIMINISHED_SEVENTH -> new int[] {0, 2, 4, 6};
            case SIXTH, MINOR_SIXTH -> new int[] {0, 2, 4, 5};
            case NONE -> new int[] {};
        };
    }

    // ---------------------------------------------------------------- line of fifths

    /**
     * The point on the line of fifths a key's own notes centre on.
     *
     * <p>Derived from the key signature rather than from the tonic letter, so
     * that a key and its relative share a centre the way they share a signature.
     */
    static double centreOf(Key key) {
        return key.keySignatureAccidentals()
                + (key.mode() == Mode.MINOR ? MINOR_CENTRE_OFFSET : MAJOR_CENTRE_OFFSET);
    }

    /**
     * The centre implied by a score's chord symbols, for when key detection has
     * not run.
     *
     * <p>The chord roots are already spelled -- the estimator picked them, and a
     * user or the advisor may have corrected them -- so their average position
     * on the line of fifths says which region of it the piece lives in without
     * committing to a tonic.
     */
    private static double centreFromChords(Score score) {
        Optional<Key> primary = score.primaryKey();
        if (primary.isPresent()) {
            return centreOf(primary.get());
        }
        double total = 0;
        int counted = 0;
        for (Chord chord : score.chords().chords()) {
            if (chord.isNoChord()) {
                continue;
            }
            total += fifthsOf(chord.root());
            counted++;
        }
        return counted == 0 ? DEFAULT_CENTRE : total / counted + CHORD_ROOT_CENTRE_OFFSET;
    }

    /**
     * The spelling of a pitch nearest a point on the line of fifths.
     *
     * <p>Ties are broken by fewer accidentals first and by flatness second, and
     * both halves earn their place. In C major, A flat and G sharp sit exactly
     * as far from the centre as each other and the flat is the one that gets
     * written -- that is the second half. In D flat major, A natural and B
     * double flat also tie, and only the first half stops a plain A from being
     * printed as a double flat.
     */
    static PitchSpelling onLineOfFifths(int midiPitch, double centre) {
        return onLineOfFifths(midiPitch, centre, MAX_WRITABLE_ACCIDENTALS);
    }

    /**
     * The same, refusing any spelling that needs more than a given number of
     * accidentals.
     *
     * <p>For {@link ChordSpeller}, which caps a chord symbol at one. A note on a
     * staff may legitimately need a double sharp, and reads as one because the
     * key signature and the notes around it say what it is; a chord symbol has
     * neither, and {@code Bbb7} standing alone over a bar is a chord nobody can
     * play at sight. Every pitch class can meet a cap of one -- only D, G and A
     * have a single such spelling, and the other nine have two.
     *
     * @param maxAccidentals the widest accidental the answer may carry
     */
    static PitchSpelling onLineOfFifths(int midiPitch, double centre, int maxAccidentals) {
        int pitchClass = Math.floorMod(midiPitch, 12);
        int best = Integer.MIN_VALUE;
        double bestDistance = Double.MAX_VALUE;
        int bestAccidentals = Integer.MAX_VALUE;
        for (int fifths = MIN_FIFTHS; fifths <= MAX_FIFTHS; fifths++) {
            if (Math.floorMod(fifths * 7, 12) != pitchClass) {
                continue;
            }
            if (Math.abs(Math.floorDiv(fifths + 1, 7)) > maxAccidentals) {
                continue;
            }
            if (spellingOf(fifths, midiPitch).isEmpty()) {
                continue;
            }
            double distance = Math.abs(fifths - centre);
            int accidentals = Math.abs(Math.floorDiv(fifths + 1, 7));
            // Scanned flat to sharp and compared strictly, so an exact tie on
            // both keys keeps the flatter spelling.
            boolean better = distance < bestDistance
                    || (distance == bestDistance && accidentals < bestAccidentals);
            if (better) {
                bestDistance = distance;
                bestAccidentals = accidentals;
                best = fifths;
            }
        }
        if (best == Integer.MIN_VALUE) {
            throw new IllegalStateException(
                    "no spelling within two accidentals for pitch class " + pitchClass);
        }
        return spellingOf(best, midiPitch).orElseThrow();
    }

    /** The spelling at a point on the line of fifths, if it is writable. */
    private static Optional<PitchSpelling> spellingOf(int fifths, int midiPitch) {
        int alteration = Math.floorDiv(fifths + 1, 7);
        return atOctave(letterOfFifths(fifths - 7 * alteration),
                Accidental.ofAlteration(alteration), midiPitch);
    }

    /**
     * Position of a written pitch on the line of fifths, C being zero.
     *
     * <p>Package-private rather than private because {@link ChordSpeller} asks
     * where a spelling landed in order to price it. Sharing this one is the
     * point: a second copy of the mapping could disagree about where B sharp is,
     * and the two would then spell the same music differently.
     */
    static int fifthsOf(PitchSpelling spelling) {
        return LETTER_FIFTHS[spelling.letter().diatonicStep()] + 7 * spelling.accidental().alteration();
    }

    private static NoteLetter letterOfFifths(int naturalFifths) {
        for (NoteLetter letter : NoteLetter.values()) {
            if (LETTER_FIFTHS[letter.diatonicStep()] == naturalFifths) {
                return letter;
            }
        }
        throw new IllegalStateException("not a natural position on the line of fifths: " + naturalFifths);
    }

    /** The accidental, as a semitone alteration, that makes a letter sound as a pitch class. */
    private static int alterationFor(NoteLetter letter, int pitchClass) {
        int raw = Math.floorMod(pitchClass - letter.naturalPitchClass(), 12);
        // Fold into -5..6 so that a letter a semitone above the target reads as a
        // flat rather than as eleven sharps. Which end keeps the tritone does not
        // matter: six either way is outside the two accidentals anything can
        // print, and the caller rejects it.
        return raw > 6 ? raw - 12 : raw;
    }

    /**
     * Places a letter and accidental in the octave that makes it sound as the
     * given MIDI pitch.
     *
     * <p>The division is exact because the caller has already matched pitch
     * classes, and it is what puts C flat in the octave above the B it sounds
     * as: C flat 4 sounds as MIDI 59, which is B 3.
     */
    private static Optional<PitchSpelling> atOctave(NoteLetter letter, Accidental accidental,
                                                    int midiPitch) {
        int octave = Math.floorDiv(
                midiPitch - 12 - letter.naturalPitchClass() - accidental.alteration(), 12);
        // The one gate, rather than one per caller. There are three routes to a
        // spelling -- a chord tone, a slash chord's written bass, and the line
        // of fifths -- and guarding two of them left the third printing B sharp
        // in octave -2 through the public entry point. A check that has to be
        // repeated is a check that will be forgotten.
        return octave < MIN_OCTAVE || octave > MAX_OCTAVE
                ? Optional.empty()
                : Optional.of(new PitchSpelling(letter, accidental, octave));
    }

    // ---------------------------------------------------------------- lookups

    /**
     * The chord sounding under a note.
     *
     * <p>Asked on the beat axis once both the note and the progression are
     * quantized, and only otherwise in seconds. Nothing downstream of the beat
     * grid works in seconds, and it matters here specifically: the accidental
     * printed on a note and the chord symbol printed above it have to agree, and
     * they only reliably do if both were placed by the same axis the engraver
     * reads. A note rushed against a chord change is spelled from the chord it
     * was played under rather than the one it will be printed under.
     *
     * <p>The beat branch fires as of #103: {@link Quantizer} now places a
     * {@link ChordProgression} on the beat axis alongside the notes, so
     * spelling a score it has been through takes the stronger guarantee. It
     * still does not fire in the <em>pipeline</em>, for a different reason --
     * nothing on the CLI path calls the quantizer at all yet -- so a score that
     * reaches here straight from transcription is spelled from seconds, which is
     * weaker by exactly that rounding.
     *
     * <p>A progression is quantized as one verdict, never partly, so the branch
     * cannot see a mixture and the {@code orElseThrow}s below cannot fire. A
     * {@link dev.olivelli.musicwizard.core.model.Key} list <em>can</em> be a
     * mixture -- one too short to sit between two bar lines keeps its seconds --
     * which is why {@code keyUnder} asks for all of them rather than for the one
     * it wants.
     */
    private static Optional<Chord> chordUnder(Score score, Note note) {
        if (note.isQuantized() && score.chords().isQuantized() && !score.chords().isEmpty()) {
            double beat = note.onsetBeat().orElseThrow();
            return score.chords().chords().stream()
                    .filter(c -> beat >= c.startBeat().orElseThrow()
                            && beat < c.endBeat().orElseThrow())
                    .findFirst();
        }
        return score.chords().chordAt(note.onsetSeconds());
    }

    /** The key in force under a note, on the same axis and for the same reason. */
    private static Optional<Key> keyUnder(Score score, Note note) {
        if (note.isQuantized() && !score.keys().isEmpty()
                && score.keys().stream().allMatch(Key::isQuantized)) {
            double beat = note.onsetBeat().orElseThrow();
            return score.keys().stream()
                    .filter(k -> beat >= k.startBeat().orElseThrow()
                            && beat < k.endBeat().orElseThrow())
                    .findFirst();
        }
        return score.keyAt(note.onsetSeconds());
    }
}
