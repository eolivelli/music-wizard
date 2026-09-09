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
package dev.olivelli.musicwizard.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.olivelli.musicwizard.android.mw.SheetDocuments;
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
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/** What goes into the PDF is decided on the JVM; only the drawing needs a phone. */
public class SheetPdfTest {

    private static Score chart() {
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8)
                .withChords(new ChordProgression(List.of(
                        Chord.ofSeconds(PitchSpelling.parse("A4"), ChordQuality.MINOR_SEVENTH,
                                0, 4, Confidence.CERTAIN),
                        Chord.ofSeconds(PitchSpelling.parse("D4"), ChordQuality.MAJOR,
                                4, 8, Confidence.CERTAIN)), Confidence.CERTAIN));
    }

    private static Score sung() {
        List<Note> notes = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            notes.add(Note.ofSeconds(i * 0.5, 0.45, 69 + i % 5, Confidence.CERTAIN));
        }
        return chart().withTrack(
                new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN));
    }

    @Test
    public void thePlayablePartIsTheDocumentWhenHeard() {
        byte[] chart = SheetDocuments.chart(sung());
        SheetPdf.Document document = SheetPdf.document(chart, sung(), true);
        assertTrue(document.part());
        assertNull(document.omitted());
        String text = new String(document.musicXml(), StandardCharsets.UTF_8);
        assertTrue(text, text.contains("<pitch>"));
        assertFalse(java.util.Arrays.equals(chart, document.musicXml()));
    }

    @Test
    public void notAskedForMeansChartAloneAndNothingToSay() {
        SheetPdf.Document document =
                SheetPdf.document(SheetDocuments.chart(sung()), sung(), false);
        assertFalse(document.part());
        assertNull(document.omitted());
    }

    @Test
    public void anUntrackedMelodyIsLeftOutAndSaid() {
        SheetPdf.Document document =
                SheetPdf.document(SheetDocuments.chart(chart()), chart(), true);
        assertFalse(document.part());
        assertTrue(document.omitted(), document.omitted().contains("not tracked"));
    }
}
