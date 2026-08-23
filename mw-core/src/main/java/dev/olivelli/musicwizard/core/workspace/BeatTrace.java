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

package dev.olivelli.musicwizard.core.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * What the beat tracker chose between, as opposed to what it chose (#675).
 *
 * <p>A record and never an input: nothing reads it back into an analysis, so a
 * run that writes none tracks the same beats.
 *
 * @param agreedPulse    the rate the windows' seeds agreed on, in pulses a
 *                       minute, before the register was consulted
 * @param referencePulse the rate every window was then folded onto, which
 *                       differs from {@code agreedPulse} exactly when the
 *                       register moved the octave
 * @param octave         how the register was read, or null where there was no
 *                       register to read or the halved rate lay outside the
 *                       tracker's range
 * @param windows        one entry per analysis window, in time order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BeatTrace(
        double agreedPulse,
        double referencePulse,
        Octave octave,
        List<Window> windows) {

    /** The stage this trace belongs to, which is also its report phase. */
    public static final String STAGE = "beats";

    public BeatTrace {
        windows = windows == null ? List.of() : List.copyOf(windows);
    }

    /** Whether the register moved the pulse the seeds had settled on. */
    public boolean octaveMoved() {
        return octave != null && octave.halved();
    }

    /**
     * What the bass register said about whether the seeds' pulse is the stated
     * beat or a subdivision of it, and what that was taken to mean.
     *
     * <p>The three readings are conjoined, so one of them alone says nothing:
     * {@code MarkedPulse} carries which way each has to fall.
     *
     * @param halved              whether the pulse was halved on this reading
     * @param contrast            how far the register's louder half-beats stand
     *                            above its quieter ones
     * @param parity              how evenly the two halves are marked, where an
     *                            even split is a pulse that is already stated
     * @param statedShare         the share of the louder half's beats the
     *                            register actually marks
     * @param windowsRead         how many windows contributed a reading
     * @param windowsRefused      how many were dropped for marking too few beats
     * @param envelopePrefersHalf whether the envelope's own ranking puts the
     *                            halved rate above the seeds'
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Octave(
            boolean halved,
            double contrast,
            double parity,
            double statedShare,
            int windowsRead,
            int windowsRefused,
            boolean envelopePrefersHalf) {
    }

    /**
     * One window of the recording, seeded on its own and then folded onto the
     * pulse the recording as a whole settled on.
     *
     * @param fromSeconds  where the window starts
     * @param toSeconds    where it ends
     * @param voted        whether it was one of the windows the reference pulse
     *                     was taken from
     * @param seedPulse    the rate its own sweep chose, in pulses a minute
     * @param periodicity  the share of the window's energy that rate explains
     * @param peakiness    how concentrated the window's attacks are
     * @param trackedPulse the rate the beats in it were actually tracked at,
     *                     which is the seed with any subdivision divided out
     * @param candidates   the rates its sweep weighed, strongest first
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Window(
            double fromSeconds,
            double toSeconds,
            boolean voted,
            double seedPulse,
            double periodicity,
            double peakiness,
            double trackedPulse,
            List<Candidate> candidates) {

        public Window {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * One rate the sweep weighed, with the score it was weighed at. Scores are
     * comparable within a window and not between windows.
     *
     * @param chosen whether this is the rate the window was seeded with
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(double beatsPerMinute, double score, boolean chosen) {
    }

    /** Every rate every window weighed, in no particular order. */
    public List<Candidate> everyCandidate() {
        return windows.stream().flatMap(window -> window.candidates().stream()).toList();
    }
}
