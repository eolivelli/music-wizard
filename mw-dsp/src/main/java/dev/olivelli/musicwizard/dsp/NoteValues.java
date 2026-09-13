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
 * written in, or the line's shortest value.
 *
 * <p>A melody's notes lifted into the envelope ({@link OnsetEnvelope#withNoteOnsets})
 * are a train of equal events, and a train correlates most strongly at its
 * own unit, whatever the beat is. On a band the bass register
 * ({@link MarkedPulse}) and the harmonic rhythm can overrule that; a line
 * alone states neither. What it does state is its note values: a pulse at
 * which most of the notes are a half or longer, with nothing shorter than
 * the beat, is the unit rather than the beat (#844).
 *
 * <p><b>The notes may restore a rate the sweep ranked, never invent one</b>:
 * the half is taken only where most windows' sweeps listed it as a
 * candidate. And only this direction: a sung line's segmented notes are
 * mostly shorter than the beat at any pulse, so the mirror would double
 * singing.
 */
final class NoteValues {

    /** Below this many beats a note is a subdivision of the pulse. */
    private static final double SUBDIVISION_BEATS = 0.75;

    /** From this many beats a note is a half or longer. */
    private static final double HALF_BEATS = 1.75;

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
     * @param notes            how many notes were read
     * @param subdivisionShare the share of them shorter than a beat at that rate
     * @param longShare        the share a half or longer
     * @param halfRanked       whether most windows' sweeps listed the half
     */
    record Reading(int notes, double subdivisionShare, double longShare, boolean halfRanked) {

        boolean callsForHalving() {
            return halfRanked && notes >= MIN_NOTES
                    && subdivisionShare < COMMON_SHARE && longShare > 0.5;
        }
    }

    /** The decision with the reading behind it; the reading is null where none was taken. */
    record Octave(double rate, boolean halved, Reading reading) {
    }

    /**
     * The rate halved where the line's values call for it, and unchanged
     * otherwise — including where there is no melody, or the half would leave
     * {@link TempoEstimator}'s range.
     *
     * @param rate   the pulse the envelope, the prior and the register settled on
     * @param melody the notes lifted into the envelope, or null where none were
     * @param seeds  every analysis window's estimate, whose candidates say
     *               whether the sweep ranked the half at all
     */
    static Octave resolve(double rate, NoteTrack melody, List<TempoEstimator.Estimate> seeds) {
        Objects.requireNonNull(seeds, "seeds");
        if (melody == null || melody.isEmpty() || !(rate / 2 >= TempoEstimator.MIN_TEMPO)) {
            return new Octave(rate, false, null);
        }
        Reading reading = read(rate, melody, seeds);
        boolean halve = reading.callsForHalving();
        return new Octave(halve ? rate / 2 : rate, halve, reading);
    }

    static Reading read(double rate, NoteTrack melody, List<TempoEstimator.Estimate> seeds) {
        double beatsPerSecond = rate / 60.0;
        int subdivisions = 0;
        int halves = 0;
        List<Note> notes = melody.notes();
        for (Note note : notes) {
            double beats = note.durationSeconds() * beatsPerSecond;
            if (beats < SUBDIVISION_BEATS) {
                subdivisions++;
            } else if (beats >= HALF_BEATS) {
                halves++;
            }
        }
        int ranked = 0;
        for (TempoEstimator.Estimate seed : seeds) {
            if (ranksAsCandidate(seed, rate / 2)) {
                ranked++;
            }
        }
        return new Reading(notes.size(), subdivisions / (double) notes.size(),
                halves / (double) notes.size(), ranked * 2 > seeds.size());
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
