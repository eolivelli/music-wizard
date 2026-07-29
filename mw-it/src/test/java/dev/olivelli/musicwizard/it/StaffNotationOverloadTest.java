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

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.notation.StaffNotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The difference between {@code StaffNotation}'s two overloads, on the fixture
 * that made #92 worth opening.
 *
 * <p>Handed a {@link QuantizedScore} the emitter prints the quantizer's own
 * grid, so a played triplet passage comes out as triplet brackets. Handed a bare
 * {@code Score} it has no grid to print, cannot re-derive one — three onsets a
 * third of a beat apart and three a half beat apart are both legal on the
 * sixth-of-a-beat grid — and writes the tied thirty-second noise the issue
 * reported. Both are accepted by LilyPond, which is the whole problem, so no
 * amount of engraving can tell them apart and only reading the text can.
 *
 * <p><b>No {@code IT} in the name.</b> It shells out to nothing, downloads
 * nothing and takes about fifteen milliseconds; it was behind
 * {@code -Pintegration} only because it was written inside a class named
 * {@code *IT}, which is #155. It is in {@code mw-it} rather than in
 * {@code mw-notation} for the reason {@link MidiRoundTripTest} gives: it needs
 * {@code MidiTranscriber}, and {@code mw-transcribe} pulls in {@code mw-ml},
 * which the notation layer is not allowed to see.
 */
class StaffNotationOverloadTest {

    @Test
    @DisplayName("the same fixture without the grids is still the noise the issue reported")
    void theScoreOverloadStillProducesTheReportedNoise() {
        // Not a check on LilyPond, which accepts both -- that is the whole
        // problem -- but on the difference between the two overloads being real,
        // and being this difference. Pinned character for character against what
        // #92 reported, so a change that quietly made the Score overload guess
        // would show up here rather than in a golden nobody reads as music.
        QuantizedScore quantized = PlayedTriplets.transcribed(4242);
        NoteTrack voice = quantized.score().tracks().getFirst();

        assertThat(StaffNotation.toLilyPond(quantized.score(), voice))
                .doesNotContain("\\tuplet")
                .contains("des'16~ des'64 ees'32.~ ees'32. f'64~ f'16");
    }
}
