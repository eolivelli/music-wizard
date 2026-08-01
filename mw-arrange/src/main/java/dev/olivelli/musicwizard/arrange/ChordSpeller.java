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
 * <p>Every root is written from one <b>region</b> of the line of fifths, and the
 * region is chosen to make the roots the piece actually contains cheapest to
 * write. That is deliberately not the same as counting accidentals, which does
 * not decide the case this exists for: A sharp and B flat each carry exactly one
 * accidental, as do D sharp and E flat, so the sharp chart above and the flat one
 * tie at two. What separates them is how far apart the roots land once written --
 * {@code A# F G D#} occupies eleven steps of the line of fifths and
 * {@code Bb F G Eb} occupies four -- and it is that spread a reader feels as "a
 * key". Choosing one region for the whole piece also rules out a chart that mixes
 * sharps with flats, which counting each root on its own would allow.
 *
 * <p>Nothing here detects a key, and it does not need to: a key and its relative
 * minor share a region, and the region is all that spelling turns on. When a key
 * <em>has</em> been detected -- the MIDI path, where it comes from a written key
 * signature -- that is better evidence than counting, so it is used instead.
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
 */
public final class ChordSpeller {

    /**
     * Where a key's chord roots sit on the line of fifths, relative to its key
     * signature.
     *
     * <p>Not {@link PitchSpeller}'s centre, which is where a key's <em>notes</em>
     * sit -- two fifths above the signature -- and which spells a flat second in
     * C major as C sharp. Roots sit lower: a I-V-vi-IV in C is C(0), G(1), A(3),
     * F(-1), averaging 0.75 over a signature of 0, and the borrowed roots a chart
     * reaches for next are flatter still. At 0.75 the flat second comes out D flat
     * (-5, distance 5.75) rather than C sharp (7, distance 6.25) and the raised
     * fourth comes out F sharp (6, distance 5.25) rather than G flat (-6, distance
     * 6.75); both are what a chart wants, and at 2.0 the first is wrong.
     */
    private static final double ROOT_CENTRE_OFFSET = 0.75;

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
     * <p>A detected key answers it outright. Otherwise it is found by asking
     * which region makes the sounding roots cheapest to write -- see
     * {@link #cheapestRegion}.
     */
    private static double region(ChordProgression chords, Optional<Key> key) {
        if (key.isPresent()) {
            return key.get().keySignatureAccidentals() + ROOT_CENTRE_OFFSET;
        }
        List<Integer> sounding = new ArrayList<>();
        for (Chord chord : chords.chords()) {
            if (chord.isNoChord()) {
                continue;
            }
            price(chord.root(), sounding);
            chord.bass().ifPresent(bass -> price(bass, sounding));
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
     * The point on the line of fifths that the given sounding pitches are
     * cheapest to write from.
     *
     * <p>A miniature key finder and deliberately no more than that. For each
     * candidate region it asks {@link PitchSpeller#onLineOfFifths} how it would
     * write each pitch there and sums how far the answers land from the region
     * itself; the smallest total wins. Exhaustive over the band a writable
     * spelling can occupy, which is thirty-five candidates against a handful of
     * roots -- cheaper than the closed form would be worth.
     *
     * <p>Each root is counted once per span it sounds in rather than once per
     * distinct pitch class. A chart that sits on B flat for two minutes and
     * touches one chromatic passing chord should be written from B flat, and
     * counting the two equally lets the passing chord move every other symbol on
     * the page.
     *
     * <p><b>Cheapest, and then nearest natural.</b> The cost alone does not
     * decide, because it repeats every twelve: move the region one whole turn of
     * the circle and every root moves with it, so C major is exactly as cheap
     * written from -12, where its roots are D double flat and A double flat, as
     * from 0. Scanning for the cheapest alone would return the flattest of the
     * equal minima and print a chart nobody could read.
     */
    private static int cheapestRegion(List<Integer> sounding) {
        int best = 0;
        double bestCost = Double.MAX_VALUE;
        int bestMagnitude = Integer.MAX_VALUE;
        for (int region = PitchSpeller.MIN_FIFTHS; region <= PitchSpeller.MAX_FIFTHS; region++) {
            double cost = 0;
            for (int pitch : sounding) {
                cost += Math.abs(
                        PitchSpeller.fifthsOf(PitchSpeller.onLineOfFifths(pitch, region)) - region);
            }
            int magnitude = Math.abs(region);
            // Scanned flat to sharp and compared strictly, so a region equally
            // cheap and equally far either way keeps the flatter one -- the same
            // tie-break PitchSpeller applies to a single pitch.
            if (cost < bestCost || (cost == bestCost && magnitude < bestMagnitude)) {
                bestCost = cost;
                bestMagnitude = magnitude;
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
        Optional<PitchSpelling> bass = chord.bass().map(written -> rewrite(written, region));
        if (root.equals(chord.root()) && bass.equals(chord.bass())) {
            return chord;
        }
        return new Chord(root, chord.quality(), bass, chord.startSeconds(), chord.endSeconds(),
                chord.startBeat(), chord.endBeat(), chord.confidence());
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
                ? PitchSpeller.onLineOfFifths(written.midiPitch(), region)
                : written;
    }

    /** Whether a written pitch is one the line of fifths can be asked about. */
    private static boolean isWritable(PitchSpelling written) {
        int pitch = written.midiPitch();
        return pitch >= 0 && pitch <= 127;
    }
}
