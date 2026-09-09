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

import android.graphics.Canvas;
import android.graphics.Picture;
import android.graphics.pdf.PdfDocument;
import dev.olivelli.musicwizard.android.mw.SheetPaginator;
import dev.olivelli.musicwizard.android.mw.SheetRenderer;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.notation.MusicXmlExport;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes the engraving as a vector PDF: every chunk alphaTab draws is
 * recorded as a picture and replayed onto A4 pages, in order, breaking where
 * the next does not fit. Text stays text, so it prints at any size.
 */
final class SheetPdf {

    /** A4 in PostScript points, which is what {@link PdfDocument} measures in. */
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 36;

    /** The result screen and the bundle builder both write a take's PDF; one at a time. */
    private static final Object WRITING = new Object();

    private SheetPdf() {
    }

    /** {@link #write(byte[], File)} of the score's chord chart. */
    static void write(Score score, File target) throws IOException {
        byte[] musicXml;
        try {
            musicXml = MusicXmlExport.chordChart(score).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
        write(musicXml, target);
    }

    /**
     * Renders and writes, replacing whatever was at {@code target} in one
     * move; a failure leaves the previous file in place and nothing
     * half-written.
     *
     * @throws IOException with alphaTab's reason, or the filesystem's
     */
    static void write(byte[] musicXml, File target) throws IOException {
        synchronized (WRITING) {
            engrave(musicXml, target);
        }
    }

    private static void engrave(byte[] musicXml, File target) throws IOException {
        SheetRenderer.Result result = SheetRenderer.render(musicXml, SheetRenderer.ENGINE_PICTURE,
                PAGE_WIDTH - 2 * MARGIN, 1);
        if (!result.succeeded()) {
            throw new IOException(result.failure());
        }
        List<SheetRenderer.Partial> chunks = result.partials();
        double[] heights = new double[chunks.size()];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = chunks.get(i).height();
        }
        List<List<Integer>> pages = SheetPaginator.paginate(heights, PAGE_HEIGHT - 2 * MARGIN);

        File tmp = File.createTempFile(target.getName(), ".tmp", target.getParentFile());
        PdfDocument document = new PdfDocument();
        try {
            for (int number = 0; number < pages.size(); number++) {
                PdfDocument.Page page = document.startPage(
                        new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number + 1)
                                .create());
                Canvas canvas = page.getCanvas();
                double y = MARGIN;
                for (int index : pages.get(number)) {
                    SheetRenderer.Partial chunk = chunks.get(index);
                    canvas.save();
                    canvas.translate((float) (MARGIN + chunk.x()), (float) y);
                    canvas.drawPicture((Picture) chunk.result());
                    canvas.restore();
                    y += chunk.height();
                }
                document.finishPage(page);
            }
            try (OutputStream out = new FileOutputStream(tmp)) {
                document.writeTo(out);
            }
        } catch (IOException | RuntimeException failure) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw failure;
        } finally {
            document.close();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not move the PDF into place at " + target);
        }
    }
}
