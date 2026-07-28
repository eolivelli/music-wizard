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

import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.Section;
import dev.olivelli.musicwizard.core.model.TempoMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Builds a hand-constructed {@link Score} whose notes were <em>played</em>
 * rather than typed in.
 *
 * <p>A fixture whose notes already sit on the grid proves nothing about a
 * quantizer: every candidate grid scores a deviation of zero and only the
 * complexity penalty is being tested. So everything here is perturbed, and this
 * is exactly what is perturbed:
 *
 * <ul>
 *   <li><b>Onset</b>: Gaussian, {@value #ONSET_JITTER_SECONDS} s standard
 *       deviation, truncated at three sigma. That is the spread reported for
 *       skilled players on a steady pulse, and at 120 BPM it is a twentieth of a
 *       beat -- comfortably inside a sixteenth-note grid cell and comfortably
 *       outside the noise floor.
 *   <li><b>Release</b>: every note is let go early, by a factor drawn uniformly
 *       from {@value #MIN_ARTICULATION} to {@value #MAX_ARTICULATION} of its
 *       written length, with the same Gaussian jitter on top. Uniformly early,
 *       not symmetrically: players detach, and a fixture that releases exactly
 *       on the beat would never exercise the articulation allowance.
 *   <li><b>Nothing else.</b> No tempo drift, no rubato, no swing unless a test
 *       asks for it. Those are separate failure modes and each gets its own
 *       fixture rather than being stirred into a general-purpose one.
 * </ul>
 *
 * <p>The randomness is seeded per fixture, so a failure is reproducible and a
 * marginal pass is visible rather than intermittent.
 */
final class Performance {

    /** Onset timing spread of a decent human player, in seconds. */
    static final double ONSET_JITTER_SECONDS = 0.025;

    /** How much of its written length a note is actually held for. */
    static final double MIN_ARTICULATION = 0.80;
    static final double MAX_ARTICULATION = 0.98;

    private static final double MAX_SIGMA = 3.0;

    private final TempoMap tempoMap;
    private final Random random;
    private final double jitterSeconds;
    private final List<Note> notes = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();

    Performance(TempoMap tempoMap, long seed) {
        this(tempoMap, seed, ONSET_JITTER_SECONDS);
    }

    Performance(TempoMap tempoMap, long seed, double jitterSeconds) {
        this.tempoMap = tempoMap;
        this.random = new Random(seed);
        this.jitterSeconds = jitterSeconds;
    }

    /**
     * Plays a run of notes at nominal beat positions, each lasting until the
     * next and the last lasting {@code lengthBeats}.
     */
    Performance run(int midiPitch, double lengthBeats, double... nominalBeats) {
        for (int i = 0; i < nominalBeats.length; i++) {
            double length = i + 1 < nominalBeats.length
                    ? nominalBeats[i + 1] - nominalBeats[i]
                    : lengthBeats;
            note(midiPitch, nominalBeats[i], length);
        }
        return this;
    }

    /** Plays one note at a nominal beat position for a nominal number of beats. */
    Performance note(int midiPitch, double nominalBeat, double nominalLengthBeats) {
        double nominalOnset = tempoMap.beatsToSeconds(nominalBeat);
        double nominalEnd = tempoMap.beatsToSeconds(nominalBeat + nominalLengthBeats);
        double onset = Math.max(0, nominalOnset + jitter());
        double articulation = MIN_ARTICULATION
                + random.nextDouble() * (MAX_ARTICULATION - MIN_ARTICULATION);
        double end = onset + (nominalEnd - nominalOnset) * articulation + jitter();
        // A release cannot precede its own onset, whatever the dice say.
        double duration = Math.max(end - onset, 0.01);
        notes.add(Note.ofSeconds(onset, duration, midiPitch, Confidence.CERTAIN));
        return this;
    }

    /**
     * Plays notes exactly where they are written, for the cases where the point
     * is a boundary rather than a performance.
     */
    Performance exact(int midiPitch, double nominalBeat, double nominalLengthBeats) {
        double onset = tempoMap.beatsToSeconds(nominalBeat);
        double end = tempoMap.beatsToSeconds(nominalBeat + nominalLengthBeats);
        notes.add(Note.ofSeconds(onset, end - onset, midiPitch, Confidence.CERTAIN));
        return this;
    }

    /** Marks a section boundary at a nominal beat position. */
    Performance section(double startBeat, double endBeat) {
        sections.add(Section.unlabelled(
                tempoMap.beatsToSeconds(startBeat), tempoMap.beatsToSeconds(endBeat),
                null, Confidence.CERTAIN));
        return this;
    }

    List<Note> notes() {
        return List.copyOf(notes);
    }

    Score score() {
        return score(ChordProgression.empty(), List.of());
    }

    Score score(ChordProgression chords) {
        return score(chords, List.of());
    }

    Score score(ChordProgression chords, List<dev.olivelli.musicwizard.core.model.Key> keys) {
        double duration = notes.stream().mapToDouble(Note::offsetSeconds).max().orElse(1.0) + 1.0;
        NoteTrack track = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN);
        return new Score(Optional.empty(), Optional.empty(), tempoMap, Optional.empty(),
                keys, List.copyOf(sections), List.of(track), chords, Lyrics.empty(), duration);
    }

    /** Gaussian onset noise, truncated so one draw cannot invent a whole beat. */
    private double jitter() {
        double sigma = Math.clamp(random.nextGaussian(), -MAX_SIGMA, MAX_SIGMA);
        return sigma * jitterSeconds;
    }

    /** Beat positions of {@code count} equal divisions of {@code beatsPerBar} bars. */
    static double[] evenly(int bars, double beatsPerBar, int divisionsPerBar) {
        double[] beats = new double[bars * divisionsPerBar];
        for (int i = 0; i < beats.length; i++) {
            beats[i] = i * beatsPerBar / divisionsPerBar;
        }
        return beats;
    }
}
