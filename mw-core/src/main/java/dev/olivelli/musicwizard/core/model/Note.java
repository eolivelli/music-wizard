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

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;
import java.util.Optional;

/**
 * A single sounded note.
 *
 * <p>Carries both wall-clock timing (produced by analysis) and, once
 * quantization has run, musical timing (consumed by notation). The musical
 * fields are optional precisely so that an un-quantized note is representable
 * and cannot be mistaken for a quantized one.
 *
 * @param onsetSeconds    when the note starts, in seconds
 * @param durationSeconds how long it sounds, in seconds
 * @param midiPitch       sounding pitch, 0..127
 * @param velocity        loudness 0..127, defaulting to a neutral value
 * @param spelling        how the pitch should be written, once decided
 * @param onsetBeat       quantized onset in quarter-note beats, once decided
 * @param durationBeats   quantized duration in quarter-note beats, once decided
 * @param confidence      how much the pipeline trusts this note
 */
public record Note(
        double onsetSeconds,
        double durationSeconds,
        int midiPitch,
        int velocity,
        Optional<PitchSpelling> spelling,
        Optional<Double> onsetBeat,
        Optional<Double> durationBeats,
        Confidence confidence) {

    public static final int DEFAULT_VELOCITY = 80;

    public Note {
        if (!Double.isFinite(onsetSeconds) || onsetSeconds < 0) {
            throw new IllegalArgumentException("onsetSeconds must be finite and non-negative, got: " + onsetSeconds);
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be finite and positive, got: " + durationSeconds);
        }
        if (midiPitch < 0 || midiPitch > 127) {
            throw new IllegalArgumentException("midiPitch must be within 0..127, got: " + midiPitch);
        }
        if (velocity < 0 || velocity > 127) {
            throw new IllegalArgumentException("velocity must be within 0..127, got: " + velocity);
        }
        Objects.requireNonNull(spelling, "spelling");
        Objects.requireNonNull(onsetBeat, "onsetBeat");
        Objects.requireNonNull(durationBeats, "durationBeats");
        Objects.requireNonNull(confidence, "confidence");
        // Checked here rather than only in quantizedTo, because deserialization
        // and direct construction both bypass the factory methods.
        if (onsetBeat.isPresent() != durationBeats.isPresent()) {
            throw new IllegalArgumentException(
                    "a note must carry both onsetBeat and durationBeats or neither");
        }
        if (onsetBeat.isPresent()) {
            double beat = onsetBeat.get();
            double beats = durationBeats.get();
            if (!Double.isFinite(beat) || beat < 0) {
                throw new IllegalArgumentException("onsetBeat must be finite and non-negative, got: " + beat);
            }
            if (!Double.isFinite(beats) || beats <= 0) {
                throw new IllegalArgumentException("durationBeats must be finite and positive, got: " + beats);
            }
        }
    }

    /** A note known only in wall-clock terms, as analysis first produces it. */
    public static Note ofSeconds(double onsetSeconds, double durationSeconds, int midiPitch,
                                 Confidence confidence) {
        return new Note(onsetSeconds, durationSeconds, midiPitch, DEFAULT_VELOCITY,
                Optional.empty(), Optional.empty(), Optional.empty(), confidence);
    }

    /** When the note stops sounding, in seconds. */
    public double offsetSeconds() {
        return onsetSeconds + durationSeconds;
    }

    /** True once both quantized onset and duration are present. */
    @JsonIgnore
    public boolean isQuantized() {
        return onsetBeat.isPresent() && durationBeats.isPresent();
    }

    /** Pitch class 0..11 of the sounding pitch. */
    public int pitchClass() {
        return Math.floorMod(midiPitch, 12);
    }

    /** Returns a copy carrying quantized musical timing. */
    public Note quantizedTo(double onsetBeat, double durationBeats) {
        if (!Double.isFinite(onsetBeat) || onsetBeat < 0) {
            throw new IllegalArgumentException("onsetBeat must be finite and non-negative, got: " + onsetBeat);
        }
        if (!Double.isFinite(durationBeats) || durationBeats <= 0) {
            throw new IllegalArgumentException("durationBeats must be finite and positive, got: " + durationBeats);
        }
        return new Note(onsetSeconds, durationSeconds, midiPitch, velocity,
                spelling, Optional.of(onsetBeat), Optional.of(durationBeats), confidence);
    }

    /** Returns a copy with an explicit written spelling. */
    public Note spelledAs(PitchSpelling newSpelling) {
        Objects.requireNonNull(newSpelling, "newSpelling");
        return new Note(onsetSeconds, durationSeconds, midiPitch, velocity,
                Optional.of(newSpelling), onsetBeat, durationBeats, confidence);
    }

    /** Returns a copy transposed by a number of semitones, dropping any spelling. */
    public Note transposedBy(int semitones) {
        int shifted = midiPitch + semitones;
        if (shifted < 0 || shifted > 127) {
            throw new IllegalArgumentException(
                    "transposing by " + semitones + " puts pitch " + midiPitch + " out of MIDI range");
        }
        return new Note(onsetSeconds, durationSeconds, shifted, velocity,
                Optional.empty(), onsetBeat, durationBeats, confidence);
    }

    /** The spelling if one was chosen, otherwise a sharp-preferring default. */
    public PitchSpelling spellingOrDefault() {
        return spelling.orElseGet(() -> PitchSpelling.ofMidiPitchSharp(midiPitch));
    }
}
