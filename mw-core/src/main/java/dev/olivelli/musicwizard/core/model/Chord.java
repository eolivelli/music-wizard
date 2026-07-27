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

package dev.olivelli.musicwizard.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A chord sounding over a span of time.
 *
 * <p>The root is kept as a written spelling rather than a bare pitch class so
 * that a chart reads correctly: in a flat key a chord should print as Db, not
 * C#. The optional bass note carries slash chords such as C/E, which fall out
 * naturally once a separate bass line has been transcribed.
 *
 * @param root          the written root
 * @param quality       the chord quality
 * @param bass          the sounding bass note, when it is not the root
 * @param startSeconds  when the chord starts
 * @param endSeconds    when the chord stops
 * @param startBeat     quantized start in quarter-note beats, once decided
 * @param endBeat       quantized end in quarter-note beats, once decided
 * @param confidence    how much the pipeline trusts this chord
 */
public record Chord(
        PitchSpelling root,
        ChordQuality quality,
        Optional<PitchSpelling> bass,
        double startSeconds,
        double endSeconds,
        Optional<Double> startBeat,
        Optional<Double> endBeat,
        Confidence confidence) {

    public Chord {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(bass, "bass");
        Objects.requireNonNull(startBeat, "startBeat");
        Objects.requireNonNull(endBeat, "endBeat");
        Objects.requireNonNull(confidence, "confidence");
        if (!Double.isFinite(startSeconds) || startSeconds < 0) {
            throw new IllegalArgumentException("startSeconds must be finite and non-negative, got: " + startSeconds);
        }
        if (!Double.isFinite(endSeconds) || endSeconds <= startSeconds) {
            throw new IllegalArgumentException(
                    "endSeconds must be finite and after startSeconds; got start=" + startSeconds
                            + " end=" + endSeconds);
        }
    }

    /** A chord known only in wall-clock terms, as estimation first produces it. */
    public static Chord ofSeconds(PitchSpelling root, ChordQuality quality,
                                  double startSeconds, double endSeconds, Confidence confidence) {
        return new Chord(root, quality, Optional.empty(), startSeconds, endSeconds,
                Optional.empty(), Optional.empty(), confidence);
    }

    /** A span during which no chord sounds. */
    public static Chord noChord(double startSeconds, double endSeconds, Confidence confidence) {
        return ofSeconds(new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4),
                ChordQuality.NONE, startSeconds, endSeconds, confidence);
    }

    public double durationSeconds() {
        return endSeconds - startSeconds;
    }

    public boolean isNoChord() {
        return quality == ChordQuality.NONE;
    }

    /** True when the sounding bass is something other than the root. */
    public boolean isSlashChord() {
        return bass.isPresent() && bass.get().pitchClass() != root.pitchClass();
    }

    /** Pitch classes sounded by this chord, as an ascending set from the root. */
    public int[] pitchClasses() {
        int[] intervals = quality.intervals();
        int[] classes = new int[intervals.length];
        for (int i = 0; i < intervals.length; i++) {
            classes[i] = Math.floorMod(root.pitchClass() + intervals[i], 12);
        }
        return classes;
    }

    /** Returns a copy with an explicit bass note, producing a slash chord. */
    public Chord withBass(PitchSpelling newBass) {
        return new Chord(root, quality, Optional.of(newBass), startSeconds, endSeconds,
                startBeat, endBeat, confidence);
    }

    /** Returns a copy carrying quantized musical timing. */
    public Chord quantizedTo(double newStartBeat, double newEndBeat) {
        if (!(newEndBeat > newStartBeat)) {
            throw new IllegalArgumentException(
                    "endBeat must be after startBeat; got start=" + newStartBeat + " end=" + newEndBeat);
        }
        return new Chord(root, quality, bass, startSeconds, endSeconds,
                Optional.of(newStartBeat), Optional.of(newEndBeat), confidence);
    }

    /**
     * The chart symbol, e.g. {@code Am7} or {@code C/E}.
     */
    public String symbol() {
        if (isNoChord()) {
            return ChordQuality.NONE.symbol();
        }
        String base = root.letter().name() + root.accidental().displaySuffix() + quality.symbol();
        return isSlashChord()
                ? base + "/" + bass.get().letter().name() + bass.get().accidental().displaySuffix()
                : base;
    }

    @Override
    public String toString() {
        return symbol();
    }
}
