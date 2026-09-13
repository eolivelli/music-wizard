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

package dev.olivelli.musicwizard.dsp;

import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import java.util.List;
import java.util.Objects;

/**
 * Whether a pulse tracked from a line alone is the beat its notes are
 * written in, or a value of the line's an octave from it.
 *
 * <p>A melody's notes lifted into the envelope ({@link OnsetEnvelope#withNoteOnsets})
 * are a train of equal events, and a train correlates most strongly at its
 * own unit, whatever the beat is. On a band the bass register
 * ({@link MarkedPulse}) and the harmonic rhythm can overrule that; a line
 * alone states neither. What it does state is its note values. Where the
 * tracked pulse is the line's shortest common value, the envelope's ranking
 * of that pulse over its half is the train's and not the music's, and the
 * perceptual prior decides between the two (#844). Where a common value is
 * a quarter of the pulse, the line is written in sixteenths of it, and the
 * prior decides between the pulse and its double (#851) — that and not a
 * share of short notes, since a sung line's segmented notes are mostly
 * shorter than the beat at any pulse. A slow line whose sixteenths are
 * common reads the same and is doubled too; no solo row measures it (#853).
 *
 * <p><b>The notes may restore a rate the sweep ranked, never invent one</b>:
 * the half or the double is taken only where most windows' sweeps listed
 * it as a candidate.
 */
final class NoteValues {

    /**
     * Below this many beats a note is a subdivision of the pulse; below half
     * of it, a subdivision of the pulse's half.
     */
    private static final double SUBDIVISION_BEATS = 0.75;

    /** A value held by a smaller share of the notes is not one the line has. */
    private static final double COMMON_SHARE = 0.2;

    private static final int MIN_NOTES = 8;

    /** How far a window's candidate may sit from the half and still be it. */
    private static final double CANDIDATE_TOLERANCE = 0.05;

    private NoteValues() {
    }

    /**
     * What the line's values say about one rate.
     *
     * @param notes              how many notes were read
     * @param subdivisionShare   the share of them shorter than a beat at that rate
     * @param quarterShare       the share of them that subdivide half a beat
     * @param halfRanked         whether most windows' sweeps listed the half
     * @param doubleRanked       whether most windows' sweeps listed the double
     * @param priorPrefersHalf   whether the perceptual prior puts the half above the rate
     * @param priorPrefersDouble whether it puts the double above the rate
     */
    record Reading(int notes, double subdivisionShare, double quarterShare, boolean halfRanked,
                   boolean doubleRanked, boolean priorPrefersHalf, boolean priorPrefersDouble) {

        /** Whether the rate is the line's shortest common value. */
        boolean unit() {
            return notes >= MIN_NOTES && subdivisionShare < COMMON_SHARE;
        }

        /** Whether a quarter of the rate is a common value of the line. */
        boolean inQuarters() {
            return notes >= MIN_NOTES && quarterShare >= COMMON_SHARE;
        }

        boolean callsForHalving() {
            return unit() && halfRanked && priorPrefersHalf;
        }

        boolean callsForDoubling() {
            return inQuarters() && doubleRanked && priorPrefersDouble;
        }
    }

    /** The decision with the reading behind it; the reading is null where none was taken. */
    record Octave(double rate, boolean halved, boolean doubled, Reading reading) {
    }

    /**
     * The rate halved or doubled where the line's values call for it, and
     * unchanged otherwise, including where there is no melody. A rate the
     * register halved is not doubled back: its double is the pulse the
     * seeds agreed on, which their sweeps rank by construction, so the
     * candidate gate would say nothing there.
     *
     * @param rate           the pulse the envelope, the prior and the register settled on
     * @param registerHalved whether the register halved it — see {@link MarkedPulse}
     * @param melody         the notes lifted into the envelope, or null where none were
     * @param seeds          every analysis window's estimate, whose candidates say
     *                       whether the sweep ranked the half or the double at all
     */
    static Octave resolve(double rate, boolean registerHalved, NoteTrack melody,
                          List<TempoEstimator.Estimate> seeds) {
        Objects.requireNonNull(seeds, "seeds");
        if (melody == null || melody.isEmpty()) {
            return new Octave(rate, false, false, null);
        }
        Reading reading = read(rate, melody, seeds);
        boolean halve = rate / 2 >= TempoEstimator.MIN_TEMPO && reading.callsForHalving();
        boolean twice = !registerHalved && rate * 2 <= TempoEstimator.MAX_TEMPO
                && reading.callsForDoubling();
        return new Octave(halve ? rate / 2 : twice ? rate * 2 : rate, halve, twice, reading);
    }

    static Reading read(double rate, NoteTrack melody, List<TempoEstimator.Estimate> seeds) {
        double beatsPerSecond = rate / 60.0;
        int subdivisions = 0;
        int quarters = 0;
        List<Note> notes = melody.notes();
        for (Note note : notes) {
            double beats = note.durationSeconds() * beatsPerSecond;
            if (beats < SUBDIVISION_BEATS) {
                subdivisions++;
            }
            if (beats < SUBDIVISION_BEATS / 2) {
                quarters++;
            }
        }
        int halfRanked = 0;
        int doubleRanked = 0;
        for (TempoEstimator.Estimate seed : seeds) {
            if (ranksAsCandidate(seed, rate / 2)) {
                halfRanked++;
            }
            if (ranksAsCandidate(seed, rate * 2)) {
                doubleRanked++;
            }
        }
        double prior = TempoEstimator.perceptualWeight(rate);
        return new Reading(notes.size(), subdivisions / (double) notes.size(),
                quarters / (double) notes.size(),
                halfRanked * 2 > seeds.size(), doubleRanked * 2 > seeds.size(),
                TempoEstimator.perceptualWeight(rate / 2) > prior,
                TempoEstimator.perceptualWeight(rate * 2) > prior);
    }

    private static boolean ranksAsCandidate(TempoEstimator.Estimate seed, double beatsPerMinute) {
        for (TempoEstimator.Candidate candidate : seed.candidates()) {
            if (Math.abs(candidate.beatsPerMinute() / beatsPerMinute - 1) <= CANDIDATE_TOLERANCE) {
                return true;
            }
        }
        return false;
    }
}
