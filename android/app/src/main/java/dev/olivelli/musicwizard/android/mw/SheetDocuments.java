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

import dev.olivelli.musicwizard.arrange.ChordSpeller;
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

/**
 * The MusicXML documents the phone engraves, each what {@code mw render}
 * writes for the same part: the score is respelled once, as that command
 * does before every part, so the text chart, the pane and the PDF agree.
 */
public final class SheetDocuments {

    /** What the analysis has to say about a melody. */
    public enum Melody {
        /** Notes were heard, so a playable part can be reduced from them. */
        HEARD,
        /** The stage ran and found nothing to write. */
        UNHEARD,
        /** The stage did not run; another analysis would. */
        UNTRACKED
    }

    private SheetDocuments() {
    }

    /** The score as every page reads it. */
    public static Score page(Score score) {
        return ChordSpeller.respell(score);
    }

    /** @throws IllegalArgumentException if the score holds no chords to chart */
    public static byte[] chart(Score score) {
        return MusicXmlExport.chordChart(page(score)).getBytes(StandardCharsets.UTF_8);
    }

    public static Melody melody(Score score) {
        return score.track(PartRole.LEAD_VOCAL)
                .map(track -> track.isEmpty() ? Melody.UNHEARD : Melody.HEARD)
                .orElse(Melody.UNTRACKED);
    }

    /**
     * The lead sheet over the playable part, as {@code mw render --parts
     * playable} writes it.
     *
     * @throws IllegalArgumentException if there is no melody to reduce, with
     *         which of the two reasons it is
     * @throws IllegalStateException if the export refuses the score
     */
    public static byte[] playable(Score score) {
        switch (melody(score)) {
            case UNHEARD -> throw new IllegalArgumentException("no melody was heard in this take");
            case UNTRACKED -> throw new IllegalArgumentException(
                    "the melody was not tracked; analyze again with the playable part on");
            case HEARD -> { }
        }
        Score spelled = page(score);
        Score reduced = PitchSpeller.spell(spelled.withTrack(PlayableMelody.reduce(spelled)));
        QuantizedScore quantized = Quantizer.quantize(reduced, QuantizationSettings.READING);
        NoteTrack part = quantized.score().track(PartRole.LEAD_VOCAL).orElseThrow(
                () -> new IllegalStateException("the playable part vanished in quantization"));
        return MusicXmlExport.leadSheet(quantized, part).getBytes(StandardCharsets.UTF_8);
    }
}
