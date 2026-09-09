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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SheetDocumentsTest {

    /** Four bars of chords with a tune of quarter notes over them, as a solo take analyses. */
    private static Score sungChart() {
        List<Chord> chords = List.of(
                chord("A4", ChordQuality.MINOR_SEVENTH, 0, 2),
                chord("D4", ChordQuality.MINOR_SEVENTH, 2, 4),
                chord("G4", ChordQuality.DOMINANT_SEVENTH, 4, 6),
                chord("C4", ChordQuality.MAJOR_SEVENTH, 6, 8));
        int[] pitches = {69, 71, 72, 74, 76, 74, 72, 71, 69, 71, 72, 74, 76, 74, 72, 71};
        List<Note> notes = new ArrayList<>();
        for (int i = 0; i < pitches.length; i++) {
            notes.add(Note.ofSeconds(i * 0.5, 0.45, pitches[i], Confidence.CERTAIN));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8)
                .withChords(new ChordProgression(chords, Confidence.CERTAIN))
                .withTrack(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN))
                .withMetadata("Chart Practice", "Anonymous");
    }

    private static Chord chord(String root, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality, from, to, Confidence.CERTAIN);
    }

    @Test
    public void thePlayablePartIsALeadSheetAlphaTabEngraves() {
        byte[] musicXml = SheetDocuments.playable(sungChart());
        String text = new String(musicXml, StandardCharsets.UTF_8);
        assertTrue(text, text.contains("<harmony>"));
        assertTrue(text, text.contains("<pitch>"));

        SheetRenderer.Result result =
                SheetRenderer.render(musicXml, SheetRenderer.ENGINE_SVG, 1200, 1);
        assertTrue(result.failure(), result.succeeded());
        assertTrue(result.partials().stream().anyMatch(SheetRenderer.Partial::isSystem));
    }

    @Test
    public void aTakeWithoutAMelodyHasNoPlayablePart() {
        Score chordsOnly = sungChart().withTrack(NoteTrack.empty(PartRole.LEAD_VOCAL, "Voice"));
        assertFalse(SheetDocuments.hasMelody(chordsOnly));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SheetDocuments.playable(chordsOnly));
        assertTrue(refused.getMessage(), refused.getMessage().contains("no melody"));
    }

    @Test
    public void theChartIsUnchangedByTheMelody() {
        String withMelody = new String(SheetDocuments.chart(sungChart()), StandardCharsets.UTF_8);
        assertTrue(withMelody, withMelody.contains("<harmony>"));
        assertFalse(withMelody, withMelody.contains("<pitch>"));
    }
}
