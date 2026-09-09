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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.notation.MusicXmlExport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/**
 * The engraver on the JVM, through its SVG engine: the same alphaTab the phone
 * runs, reading the same MusicXML the notation module writes.
 */
public class SheetRendererTest {

    /** Four bars of chords, as the phone's analysis produces them. */
    private static byte[] chart() {
        List<Chord> chords = List.of(
                chord("A4", ChordQuality.MINOR_SEVENTH, 0, 2),
                chord("D4", ChordQuality.MINOR_SEVENTH, 2, 4),
                chord("G4", ChordQuality.DOMINANT_SEVENTH, 4, 6),
                chord("C4", ChordQuality.MAJOR_SEVENTH, 6, 8));
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8)
                .withChords(new ChordProgression(chords, Confidence.CERTAIN))
                .withMetadata("Chart Practice", "Anonymous");
        return MusicXmlExport.chordChart(score).getBytes(StandardCharsets.UTF_8);
    }

    private static Chord chord(String root, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality, from, to, Confidence.CERTAIN);
    }

    @Test
    public void engravesTheChartAsSvg() {
        SheetRenderer.Result result =
                SheetRenderer.render(chart(), SheetRenderer.ENGINE_SVG, 1200, 1);

        assertNull(result.warnings().toString(), result.failure());
        assertTrue(result.totalHeight() > 0);
        // The chart lays out on one system; the title and footer are chunks
        // of their own and hold no bar.
        assertEquals(1, result.partials().stream().filter(SheetRenderer.Partial::isSystem).count());
        String svg = result.partials().stream()
                .map(partial -> String.valueOf(partial.result()))
                .reduce("", String::concat);
        assertTrue(svg, svg.contains("<svg"));
        // The chord symbols, as the chart names them; none is a word a font
        // name or a style rule could carry.
        for (String symbol : List.of("Am7", "Dm7", "G7", "Cmaj7")) {
            assertTrue(symbol + " in " + svg, svg.contains(symbol));
        }
        // The document itself raised no complaint; what the SVG engine says
        // about measuring text without the "skia" natives is not about it.
        assertTrue(result.warnings().toString(),
                result.warnings().stream().noneMatch(w -> w.startsWith("MusicXML")));
    }

    @Test
    public void noChordIsPrintedAsTheChartPrintsIt() {
        List<Chord> chords = List.of(
                Chord.noChord(0, 2, Confidence.CERTAIN),
                chord("A4", ChordQuality.MINOR_SEVENTH, 2, 4));
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4)
                .withChords(new ChordProgression(chords, Confidence.CERTAIN));
        byte[] musicXml = MusicXmlExport.chordChart(score).getBytes(StandardCharsets.UTF_8);

        SheetRenderer.Result result =
                SheetRenderer.render(musicXml, SheetRenderer.ENGINE_SVG, 1200, 1);

        assertNull(result.failure());
        String svg = result.partials().stream()
                .map(partial -> String.valueOf(partial.result()))
                .reduce("", String::concat);
        assertTrue(svg, svg.contains("N.C."));
        assertFalse(svg, svg.contains("CN.C."));
    }

    /** Unregistered, alphaTab would fall back to its default engine and draw SVG for a PDF. */
    @Test
    public void saysWhyWhenThePictureEngineIsNotInitialized() {
        SheetRenderer.Result result =
                SheetRenderer.render(chart(), SheetRenderer.ENGINE_PICTURE, 1200, 1);
        assertFalse(result.succeeded());
        assertTrue(result.failure(), result.failure().contains("not initialized"));
    }

    @Test
    public void saysWhyWhenTheEngineIsUnknown() {
        SheetRenderer.Result result = SheetRenderer.render(chart(), "default", 1200, 1);

        assertNotNull(result.failure());
        assertTrue(result.failure(), result.failure().contains("default"));
    }

    @Test
    public void saysWhyWhenThereIsNoScale() {
        SheetRenderer.Result result =
                SheetRenderer.render(chart(), SheetRenderer.ENGINE_SVG, 1200, 0);

        assertNotNull(result.failure());
    }

    @Test
    public void rendersFromSeveralThreadsAtOnce() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<SheetRenderer.Result>> runs = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                runs.add(pool.submit(() -> SheetRenderer.render(chart(), SheetRenderer.ENGINE_SVG, 1200, 1)));
            }
            for (java.util.concurrent.Future<SheetRenderer.Result> run : runs) {
                assertNull(run.get().failure());
            }
        } finally {
            pool.shutdownNow();
        }
        // The logger is back to what it was before the first render.
        assertFalse(String.valueOf(alphaTab.Logger.Companion.getLog()),
                alphaTab.Logger.Companion.getLog().getClass().getName().contains("SheetRenderer"));
    }

    @Test
    public void saysWhyWhenTheDocumentIsNotAScore() {
        SheetRenderer.Result result = SheetRenderer.render(
                "not a score".getBytes(StandardCharsets.UTF_8), SheetRenderer.ENGINE_SVG, 1200, 1);

        assertNotNull(result.failure());
        assertTrue(result.partials().isEmpty());
    }

    @Test
    public void saysWhyWhenThereIsNoWidth() {
        SheetRenderer.Result result =
                SheetRenderer.render(chart(), SheetRenderer.ENGINE_SVG, 0, 1);

        assertNotNull(result.failure());
        assertEquals(0, result.partials().size());
    }
}
