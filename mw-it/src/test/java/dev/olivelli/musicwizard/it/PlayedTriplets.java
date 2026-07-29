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

package dev.olivelli.musicwizard.it;

import dev.olivelli.musicwizard.arrange.PitchSpeller;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.Quantizer;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.transcribe.MidiTranscriber;
import java.util.Random;
import javax.sound.midi.Sequence;

/**
 * The reproduction from #92, as a fixture two suites share.
 *
 * <p>It is <em>played</em> rather than typed: the onsets carry 25 ms of Gaussian
 * timing, exactly as the reproduction on the issue does, and nothing that uses it
 * tells the quantizer which bars are triplet bars. That is what makes it a test
 * of the join between {@code MidiTranscriber} (#89), {@code Quantizer} (#91) and
 * {@code StaffNotation} (#90) rather than of any one of them.
 *
 * <p>It lives in its own class because the two things asked of it now run in
 * different tiers. {@link TupletEngravingIT} engraves it and wants LilyPond;
 * {@link StaffNotationOverloadTest} only reads the emitted text and wants
 * nothing, so #155 moved it into {@code mvn verify}. Copying the fixture into
 * both would let the two drift, and a played fixture that has drifted is a
 * fixture whose two suites are no longer talking about the same music.
 */
final class PlayedTriplets {

    /** Onset spread of a decent human player, which is what the fixture plays with. */
    private static final double JITTER_SECONDS = 0.025;

    private static final double TEMPO_BPM = 120;

    private PlayedTriplets() {
    }

    /**
     * D flat major: eighths in bars 1 and 2, triplet eighths in bars 3 and 4, a
     * chromatic run in bar 5.
     *
     * <p>The chromatic run is not decoration. It is what makes the page worth
     * looking at as music rather than as arithmetic: in D flat it has to spell
     * all flats, and a triplet bar engraved correctly beside a run spelled
     * wrongly is still not a lead sheet.
     *
     * @param seed fixed per call, so a failure is reproducible and a marginal
     *             pass is visible rather than intermittent
     */
    static Sequence sequence(long seed) {
        Random random = new Random(seed);
        int ticks = MidiFixtures.TICKS_PER_QUARTER;
        MidiFixtures.SequenceBuilder.PartBuilder part = MidiFixtures.sequence(ticks)
                .name("Triplets").tempo(TEMPO_BPM).timeSignature(4, 4)
                .keySignature(-5, Mode.MAJOR)
                .part("Voice", 0).program(0);

        int[] eighths = {61, 63, 65, 68, 70, 68, 65, 63};
        for (int bar = 0; bar < 2; bar++) {
            for (int i = 0; i < 8; i++) {
                part.note(played(bar * 4 + i * 0.5, random, ticks), 0.45, eighths[i]);
            }
        }
        int[] triplets = {61, 63, 65, 68, 70, 68, 65, 63, 61, 63, 65, 68};
        for (int bar = 2; bar < 4; bar++) {
            for (int i = 0; i < 12; i++) {
                part.note(played(bar * 4 + i / 3.0, random, ticks), 0.30, triplets[i]);
            }
        }
        int[] chromatic = {60, 61, 62, 63, 64, 65, 66, 67};
        for (int i = 0; i < 8; i++) {
            part.note(played(16 + i * 0.5, random, ticks), 0.45, chromatic[i]);
        }
        return part.end().build();
    }

    /**
     * A nominal beat position as it was actually played, rounded to the file's
     * own tick grid.
     *
     * <p>Truncated at three sigma so one draw cannot invent a note somewhere
     * else entirely, and rounded to ticks because that is the only resolution a
     * MIDI file has — a fixture asking for a position between two ticks would be
     * silently moved and would stop being ground truth.
     */
    private static double played(double nominalBeat, Random random, int ticksPerQuarter) {
        double sigma = Math.clamp(random.nextGaussian(), -3, 3);
        double beat = nominalBeat + sigma * JITTER_SECONDS * TEMPO_BPM / 60.0;
        return Math.max(0, Math.round(beat * ticksPerQuarter)) / (double) ticksPerQuarter;
    }

    /** Import, quantize and spell, which is what a symbolic run of the pipeline is. */
    static QuantizedScore transcribed(long seed) {
        Score imported = new MidiTranscriber().transcribe(sequence(seed));
        QuantizedScore quantized = Quantizer.quantize(imported);
        return quantized.withScore(PitchSpeller.spell(quantized.score()));
    }
}
