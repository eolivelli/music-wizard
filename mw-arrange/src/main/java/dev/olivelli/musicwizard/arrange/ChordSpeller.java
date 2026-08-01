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
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides how a piece's chord symbols are written.
 *
 * <p>The pipeline estimates <em>which</em> chord sounds and leaves <em>how it is
 * written</em> to a fixed table: {@code ChordEstimator.spell} gives every black
 * key a sharp, and its own javadoc calls that provisional pending a re-spelling
 * stage. This is that stage. Without it a chart whose harmony is plainly B flat
 * major prints as {@code A# F Gm D#} -- two accidentals, so no cheaper than
 * {@code Bb F Gm Eb}, and yet a page no musician would accept (#227).
 *
 * <h2>The rule</h2>
 *
 * <p>Every root is written from one <b>region</b> of the line of fifths. When the
 * score carries a key, the region is the key's, half a fifth sharp of its tonic
 * -- see {@link #TONIC_TO_ROOT_CENTRE}, which is the middle of the window a
 * chart's roots occupy rather than a fitted number.
 *
 * <p>When it does not, which is every score the audio path produces, the region
 * is counted from the roots themselves: fewest accidentals first, which is the
 * criterion #227 asks for with naturals free, and then least spread on the line
 * of fifths. The count alone does not decide the case this exists for -- A sharp
 * and B flat each carry one accidental, as do D sharp and E flat, so the sharp
 * chart above and the flat one tie at two -- and the spread is what separates
 * them: {@code Bb F G Eb} occupies four steps where {@code A# F G D#} occupies
 * eleven. Ranking the two the other way round is wrong, and only a run showed it;
 * see {@link #cheapestRegion}.
 *
 * <p>No root is ever written with a double accidental. A note on a staff may need
 * one and reads as one, because the key signature and its neighbours say what it
 * is; a chord symbol standing over a bar has neither, so {@code Bbb7} is a chord
 * nobody plays at sight. That cap is a property of {@link
 * PitchSpeller#onLineOfFifths(int, double, int)}, not a check applied afterwards.
 *
 * <p>What this does <em>not</em> rule out is a chart mixing sharps with flats.
 * A pitch class six fifths from the region is the same distance either way, and
 * the flat wins -- which is why an A major chart prints its Neapolitan as
 * {@code Bb} rather than {@code A#}, and that is the spelling a reader wants.
 *
 * <h2>What is left alone</h2>
 *
 * <p>A no-chord span carries a placeholder root that means nothing, so it is
 * neither priced nor rewritten. Everything else about a chord -- its quality, its
 * timing on both axes, its confidence -- is carried through untouched: this
 * changes how the harmony is spelled and never what it is.
 *
 * <p>One region serves the whole piece, so a song that modulates from a flat key
 * to a sharp one is written from wherever the count lands, which is #228. That is
 * the same simplification the chart header already makes in naming one key and
 * one meter, and it is a great deal closer than a fixed table of sharps.
 *
 * <p>And with no key, the count finds where the roots sit rather than where the
 * music's home is, which are half a fifth apart for a full chart and further for
 * a sparse one. A chromatic root exactly six fifths from the counted region is
 * then a coin toss that lands flat -- {@code C F G} plus an {@code F#7} prints
 * {@code Gb7}, where the same chart with an {@code Am} in it prints {@code F#7}.
 * That is #230, and it is why a key is preferred to the count outright.
 */
public final class ChordSpeller {

    /**
     * The widest accidental a chord symbol may carry.
     *
     * <p>One, where a note on a staff may carry two. See the class javadoc: a
     * symbol has no key signature and no neighbours to be read against.
     */
    private static final int MAX_SYMBOL_ACCIDENTALS = 1;

    /**
     * How far sharp of a key's tonic the roots a chart uses are centred.
     *
     * <p>Not a fitted constant: it is the middle of the window those roots
     * occupy. Write the tonic at 0 on the line of fifths, and a chart's roots run
     * from the Neapolitan at -5, through the six diatonic degrees at -1 to +5, to
     * the raised fourth at +6 -- twelve consecutive positions, one per pitch
     * class, whose midpoint is +0.5. Spelling every root from there is exactly
     * what makes a chromatic root come out as the degree a reader expects: in C
     * major the flat second is D flat and the raised fourth is F sharp, and in D
     * major the raised fourth is G sharp rather than the A flat that a centre
     * over the tonic would give.
     *
     * <p>It is measured from the <em>tonic</em> and not from the key signature,
     * which is what makes it hold for a minor key without a second constant. A
     * minor key's window is the same twelve positions about its own tonic -- the
     * Neapolitan of A minor is B flat and its leading-tone chord is G sharp minus
     * nothing -- and its tonic sits three fifths sharp of where its signature
     * would put a major one. Round 1 of review found the version that measured
     * from the signature spelling {@code G#dim} as {@code Abdim} in a chart
     * headed "A minor", and that is the missing three.
     */
    private static final double TONIC_TO_ROOT_CENTRE = 0.5;

    private ChordSpeller() {
    }

    /**
     * Returns the score with every chord root and slash bass written from one
     * region of the line of fifths.
     *
     * <p>The score is not modified and nothing is saved; the caller decides what
     * to do with the result. {@code render} re-spells what it read and engraves
     * that, leaving the workspace's transcription as the estimator produced it,
     * because how a chart is written is a decision about the printed page rather
     * than about the music that was heard.
     */
    public static Score respell(Score score) {
        Objects.requireNonNull(score, "score");
        if (score.chords().isEmpty()) {
            return score;
        }
        return score.withChords(respell(score.chords(), score.primaryKey()));
    }

    /**
     * The same over a progression alone, against the key it sounds in.
     *
     * <p>Separate because the region is a property of the whole progression: one
     * chord cannot be re-spelled on its own without asking what the rest of the
     * piece does, which is the entire point of the exercise.
     *
     * @param chords the progression to re-spell
     * @param key    the key it is in, when one has been detected
     */
    public static ChordProgression respell(ChordProgression chords, Optional<Key> key) {
        Objects.requireNonNull(chords, "chords");
        Objects.requireNonNull(key, "key");
        if (chords.isEmpty()) {
            return chords;
        }
        double region = region(chords, key);
        List<Chord> respelled = new ArrayList<>(chords.size());
        for (Chord chord : chords.chords()) {
            respelled.add(respell(chord, region));
        }
        return chords.withChords(respelled);
    }

    /**
     * The region the whole progression is written from.
     *
     * <p>A detected key answers it outright, from its tonic -- see
     * {@link #TONIC_TO_ROOT_CENTRE}. A written key signature is the only thing in
     * a score that says where the music's <em>home</em> is, and the count below
     * cannot recover it: a chart of D, G and A has its roots centred a fifth flat
     * of its own tonic, which is what made a passing {@code G#dim} come out as
     * {@code Abdim}.
     *
     * <p>Otherwise it is counted. Priced from the pitches this will actually
     * write from the region, which is every root and every bass that is not a
     * tone of its own chord; a bass that <em>is</em> one is written from its
     * chord instead, so it says nothing about where the piece sits. A no-chord
     * span's placeholder root is not priced either.
     */
    private static double region(ChordProgression chords, Optional<Key> key) {
        if (key.isPresent()) {
            return PitchSpeller.fifthsOf(key.get().tonic()) + TONIC_TO_ROOT_CENTRE;
        }
        List<Integer> sounding = new ArrayList<>();
        for (Chord chord : chords.chords()) {
            if (chord.isNoChord()) {
                continue;
            }
            price(chord.root(), sounding);
            chord.bass().filter(bass -> !isChordTone(bass, chord))
                    .ifPresent(bass -> price(bass, sounding));
        }
        return sounding.isEmpty() ? 0 : cheapestRegion(sounding);
    }

    /**
     * Adds a written pitch to what the region is chosen from, if it can be
     * written at all.
     *
     * <p>The same gate as {@link #rewrite}, and it has to be: a root this cannot
     * rewrite must not vote on how the ones it can are written either. Left
     * ungated, a single root sounding above MIDI 127 -- which no spelling of the
     * line of fifths can reach, so the search prices it from whatever few
     * candidates happen to survive the octave filter -- pulled a B flat chart
     * back to A sharp. Found by the test for the rewrite half, which is the point
     * of asking who else reads the value.
     */
    private static void price(PitchSpelling written, List<Integer> sounding) {
        if (isWritable(written)) {
            sounding.add(written.midiPitch());
        }
    }

    /**
     * The point on the line of fifths the given sounding pitches are written
     * from.
     *
     * <p>A miniature key finder and deliberately no more than that. For each
     * candidate region it asks {@link PitchSpeller#onLineOfFifths} how it would
     * write each pitch there, and ranks the region by three things in order.
     * Exhaustive over the band a writable spelling can occupy, which is
     * thirty-five candidates against a handful of roots -- cheaper than the
     * closed form would be worth.
     *
     * <p>What it finds is where the roots <em>sit</em>, which is not quite where
     * the music's home is: a chart of D, G and A centres a fifth flat of D. That
     * is why a detected key is preferred outright and this is the fallback, and
     * it is the residue behind #230.
     *
     * <p><b>Accidentals first</b>, summed over every answer: the criterion #227
     * asks for, with naturals free. Each root counts once per span it sounds in,
     * so a chart that sits on B flat for two minutes weighs more than one
     * chromatic passing chord -- a weighting, not a guarantee.
     *
     * <p><b>Then distance</b>: how far the answers land from the region itself,
     * summed. This is what the count cannot do, and the case this class exists
     * for is exactly it -- {@code A# F Gm D#} and {@code Bb F Gm Eb} carry two
     * accidentals each, and what separates them is that the second occupies four
     * steps of the line of fifths where the first occupies eleven.
     *
     * <p>Ranking them the other way round was tried and is wrong, which only a
     * run showed: distance is measured to the region, so a region can buy
     * closeness with an accidental. An A flat major chart with one A7 in it came
     * back as {@code G# D# E#m C#} -- four accidentals bought to save two steps,
     * including an E sharp minor that no chart of anything writes.
     *
     * <p><b>Then the region nearest natural</b>, which decides between a region
     * and the same one a whole turn of the circle away. That leaves one thing
     * undecided, and deliberately: a region and its mirror -- +6 and -6 -- tie on
     * every rank, and the scan runs flat to sharp and compares strictly, so the
     * flat one wins. F sharp major and G flat major are the same music and one of
     * them has to be printed.
     */
    private static int cheapestRegion(List<Integer> sounding) {
        int best = 0;
        int[] bestRank = null;
        for (int region = PitchSpeller.MIN_FIFTHS; region <= PitchSpeller.MAX_FIFTHS; region++) {
            int accidentals = 0;
            int distance = 0;
            for (int pitch : sounding) {
                PitchSpelling written = PitchSpeller.onLineOfFifths(
                        pitch, region, MAX_SYMBOL_ACCIDENTALS);
                accidentals += Math.abs(written.accidental().alteration());
                distance += Math.abs(PitchSpeller.fifthsOf(written) - region);
            }
            int[] rank = {accidentals, distance, Math.abs(region)};
            if (bestRank == null || Arrays.compare(rank, bestRank) < 0) {
                bestRank = rank;
                best = region;
            }
        }
        return best;
    }

    /** One chord, rewritten from the region; a no-chord span passes through. */
    private static Chord respell(Chord chord, double region) {
        if (chord.isNoChord()) {
            return chord;
        }
        PitchSpelling root = rewrite(chord.root(), region);
        Optional<PitchSpelling> bass = chord.bass()
                .map(written -> rewriteBass(written, chord, root, region));
        if (root.equals(chord.root()) && bass.equals(chord.bass())) {
            return chord;
        }
        return new Chord(root, chord.quality(), bass, chord.startSeconds(), chord.endSeconds(),
                chord.startBeat(), chord.endBeat(), chord.confidence());
    }

    /**
     * A slash chord's bass, written from the chord it is under when it belongs to
     * it and from the region when it does not.
     *
     * <p>Round 1 of review found this: written from the region alone, the bass of
     * E major in a piece that leans flat came out {@code E/Ab}, and A flat is not
     * a note of E major. The chord decides its own tones -- that is what
     * {@link PitchSpeller#asChordTone} is for, and asking it here rather than
     * repeating its ladder is what keeps the bass of a chart and the notes of a
     * staff spelled by one rule.
     *
     * <p>It is asked against a copy of the chord carrying the <em>new</em> root
     * and no bass: the new root because the bass must agree with what will be
     * printed beside it, and no bass because {@code asChordTone} honours a
     * written one, which is exactly the spelling being replaced.
     *
     * <p>There is a third case under the second, and round 2 of review found the
     * javadoc claiming two. A chord tone whose derived spelling needs more than
     * one accidental -- the third of C sharp diminished written from a C flat
     * root would be an A double flat -- is unprintable as a symbol, so it falls
     * back to the region like a foreign bass. It is rare enough that no fixture
     * reached it before the reviewer built one, and the fallback is the same
     * answer {@code asChordTone} itself gives when a tone needs a triple.
     */
    private static PitchSpelling rewriteBass(PitchSpelling written, Chord chord,
                                             PitchSpelling root, double region) {
        if (!isWritable(written) || !isChordTone(written, chord)) {
            return rewrite(written, region);
        }
        Chord derivedFrom = new Chord(root, chord.quality(), Optional.empty(),
                chord.startSeconds(), chord.endSeconds(), chord.startBeat(), chord.endBeat(),
                chord.confidence());
        return PitchSpeller.asChordTone(written.midiPitch(), derivedFrom)
                .filter(tone -> Math.abs(tone.accidental().alteration()) <= MAX_SYMBOL_ACCIDENTALS)
                .orElseGet(() -> rewrite(written, region));
    }

    /**
     * Whether a written pitch is one of the notes its chord sounds.
     *
     * <p>A question about pitch classes and so independent of the region, which
     * is what lets the same answer serve both the pricing above -- a bass the
     * chord will spell says nothing about where the piece sits -- and the
     * rewriting below.
     */
    private static boolean isChordTone(PitchSpelling written, Chord chord) {
        for (int pitchClass : chord.pitchClasses()) {
            if (pitchClass == written.pitchClass()) {
                return true;
            }
        }
        return false;
    }

    /**
     * One written pitch, moved to the spelling of the same sound that the region
     * calls for.
     *
     * <p>A pitch outside MIDI range is returned as it stands. Every entry point
     * of {@link PitchSpeller} refuses one, because at those extremes no spelling
     * lands in a writable octave and the complaint that comes back names the
     * wrong cause. Nothing in the pipeline produces such a root -- estimators
     * write them in octave 4 -- but a score is deserialized from a file, and a
     * render that dies over a chord symbol it could not improve is worse than one
     * that prints the symbol it was given.
     */
    private static PitchSpelling rewrite(PitchSpelling written, double region) {
        return isWritable(written)
                ? PitchSpeller.onLineOfFifths(written.midiPitch(), region, MAX_SYMBOL_ACCIDENTALS)
                : written;
    }

    /** Whether a written pitch is one the line of fifths can be asked about. */
    private static boolean isWritable(PitchSpelling written) {
        int pitch = written.midiPitch();
        return pitch >= 0 && pitch <= 127;
    }
}
