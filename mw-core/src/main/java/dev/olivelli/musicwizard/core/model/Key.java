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
 * A key: a tonic and a mode, valid over a span of the piece.
 *
 * <p>Estimated most reliably from the chord sequence rather than from raw
 * chroma, and used to pick the key signature and to drive enharmonic spelling.
 *
 * <p>Like {@link Note} and {@link Chord}, a key carries wall-clock timing from
 * analysis and <em>optional</em> musical timing once quantization has run. A key
 * change is engraved on a bar line, so the notation stage needs the position the
 * quantizer settled on rather than one re-derived from seconds; deriving it
 * again would round independently and could put the change one bar away from the
 * chord that motivated it. The bar itself stays out of the model and is read
 * from {@link TempoMap#toMusicalTime(double)}, which is the only sanctioned
 * conversion.
 *
 * @param tonic        the tonic, written
 * @param mode         major or minor
 * @param startSeconds when this key takes effect
 * @param endSeconds   when it stops
 * @param startBeat    quantized start in quarter-note beats, once decided
 * @param endBeat      quantized end in quarter-note beats, once decided
 * @param confidence   how much the pipeline trusts this key
 */
public record Key(
        PitchSpelling tonic,
        Mode mode,
        double startSeconds,
        double endSeconds,
        Optional<Double> startBeat,
        Optional<Double> endBeat,
        Confidence confidence) {

    public Key {
        Objects.requireNonNull(tonic, "tonic");
        Objects.requireNonNull(mode, "mode");
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
        // Checked here rather than only in quantizedTo, because deserialization
        // and direct construction both bypass the factory methods.
        if (startBeat.isPresent() != endBeat.isPresent()) {
            throw new IllegalArgumentException(
                    "a key must carry both startBeat and endBeat or neither");
        }
        if (startBeat.isPresent()) {
            double from = startBeat.get();
            double to = endBeat.get();
            if (!Double.isFinite(from) || from < 0) {
                throw new IllegalArgumentException("startBeat must be finite and non-negative, got: " + from);
            }
            if (!Double.isFinite(to) || to <= from) {
                throw new IllegalArgumentException(
                        "endBeat must be finite and after startBeat; got start=" + from + " end=" + to);
            }
        }
    }

    /** A key known only in wall-clock terms, as detection first produces it. */
    public static Key ofSeconds(PitchSpelling tonic, Mode mode,
                                double startSeconds, double endSeconds, Confidence confidence) {
        return new Key(tonic, mode, startSeconds, endSeconds,
                Optional.empty(), Optional.empty(), confidence);
    }

    /** True once this key carries quantized musical timing. */
    @JsonIgnore
    public boolean isQuantized() {
        return startBeat.isPresent() && endBeat.isPresent();
    }

    /** Returns a copy carrying quantized musical timing. */
    public Key quantizedTo(double newStartBeat, double newEndBeat) {
        if (!(newEndBeat > newStartBeat)) {
            throw new IllegalArgumentException(
                    "endBeat must be after startBeat; got start=" + newStartBeat + " end=" + newEndBeat);
        }
        return new Key(tonic, mode, startSeconds, endSeconds,
                Optional.of(newStartBeat), Optional.of(newEndBeat), confidence);
    }

    /**
     * Number of sharps (positive) or flats (negative) in the key signature.
     *
     * <p>Derived from position on the circle of fifths, so it naturally yields
     * the conventional signature for both modes.
     */
    public int keySignatureAccidentals() {
        // Fifths from C for each natural letter, then adjust for the accidental.
        int[] fifthsFromC = {0, 2, 4, -1, 1, 3, 5}; // C D E F G A B
        int fifths = fifthsFromC[tonic.letter().diatonicStep()]
                + tonic.accidental().alteration() * 7;
        // A minor key shares its signature with the major a minor third above.
        return mode == Mode.MINOR ? fifths - 3 : fifths;
    }

    /**
     * The tonic a key signature names, written.
     *
     * <p>The inverse of {@link #keySignatureAccidentals()}, and walked round the
     * circle of fifths rather than looked up in a table so that the two run the
     * same arithmetic in opposite directions and cannot drift apart. Each step
     * round the circle moves four letters up the ladder and every seventh step
     * adds an accidental; a minor key sits three steps further round than the
     * major sharing its signature, which is what makes A minor and C major both
     * zero sharps.
     *
     * <p>The octave is arbitrary because {@code Key} reads only the letter and
     * the accidental from its tonic; 4 is the octave of middle C.
     *
     * @param sharpsOrFlats sharps positive, flats negative
     * @throws IllegalArgumentException if the signature needs a double accidental
     */
    public static PitchSpelling tonicOf(int sharpsOrFlats, Mode mode) {
        Objects.requireNonNull(mode, "mode");
        int fifths = mode == Mode.MINOR ? sharpsOrFlats + 3 : sharpsOrFlats;
        NoteLetter letter = NoteLetter.ofDiatonicStep(Math.floorMod(fifths * 4, 7));
        int alteration = Math.floorDiv(fifths + 1, 7);
        if (alteration < -1 || alteration > 1) {
            throw new IllegalArgumentException(
                    "a key signature of " + sharpsOrFlats + " cannot be spelled");
        }
        return new PitchSpelling(letter, Accidental.ofAlteration(alteration), 4);
    }

    /** True when the key signature is written with flats. */
    @JsonIgnore
    public boolean isFlatKey() {
        return keySignatureAccidentals() < 0;
    }

    /** Name such as {@code F# minor}. */
    public String displayName() {
        return tonic.letter().name() + tonic.accidental().displaySuffix() + " " + mode.name().toLowerCase();
    }

    @Override
    public String toString() {
        return displayName();
    }
}
