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
package dev.olivelli.musicwizard.android.mw;

import dev.olivelli.musicwizard.arrange.PitchSpeller;
import dev.olivelli.musicwizard.arrange.PlayableMelody;
import dev.olivelli.musicwizard.arrange.QuantizationSettings;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.Quantizer;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.notation.MusicXmlExport;
import java.nio.charset.StandardCharsets;

/** The MusicXML documents the phone engraves, each the desktop's twin of the same part. */
public final class SheetDocuments {

    private SheetDocuments() {
    }

    /** @throws IllegalArgumentException if the score holds no chords to chart */
    public static byte[] chart(Score score) {
        return MusicXmlExport.chordChart(score).getBytes(StandardCharsets.UTF_8);
    }

    /** Whether the analysis heard a melody, which is what the playable part is reduced from. */
    public static boolean hasMelody(Score score) {
        return score.track(PartRole.LEAD_VOCAL).map(track -> !track.isEmpty()).orElse(false);
    }

    /**
     * The lead sheet over the playable part, as {@code mw render --parts
     * playable} writes it.
     *
     * @throws IllegalArgumentException if no melody was heard
     * @throws IllegalStateException if the export refuses the score
     */
    public static byte[] playable(Score score) {
        if (!hasMelody(score)) {
            throw new IllegalArgumentException("no melody was heard in this take");
        }
        Score reduced = PitchSpeller.spell(score.withTrack(PlayableMelody.reduce(score)));
        QuantizedScore quantized = Quantizer.quantize(reduced, QuantizationSettings.READING);
        NoteTrack part = quantized.score().track(PartRole.LEAD_VOCAL).orElseThrow(
                () -> new IllegalStateException("the playable part vanished in quantization"));
        return MusicXmlExport.leadSheet(quantized, part).getBytes(StandardCharsets.UTF_8);
    }
}
