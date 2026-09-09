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
import dev.olivelli.musicwizard.android.mw.SheetDocuments;
import dev.olivelli.musicwizard.android.mw.SheetPaginator;
import dev.olivelli.musicwizard.android.mw.SheetRenderer;
import dev.olivelli.musicwizard.core.model.Score;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the engraving as a vector PDF: every chunk alphaTab draws is
 * recorded as a picture and replayed onto A4 pages, in order, breaking where
 * the next does not fit. Text stays text, so it prints at any size. The
 * chart comes first; the playable part, when asked for and heard, starts a
 * page of its own.
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

    /**
     * Renders and writes, replacing whatever was at {@code target} in one
     * move; a failure leaves the previous file in place and nothing
     * half-written. The chart is the document: without it nothing is
     * written, while a playable part that cannot be made is left out and
     * said.
     *
     * @param playable whether to add the playable part after the chart
     * @return why the playable part is not in the file, or null when it is
     *         or was not asked for
     * @throws IOException with the export's or alphaTab's reason, or the filesystem's
     */
    static String write(Score score, boolean playable, File target) throws IOException {
        byte[] chart;
        try {
            chart = SheetDocuments.chart(score);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IOException(reasonOf(e), e);
        }
        return write(chart, score, playable, target);
    }

    /** The same over a chart already exported, so a caller that also bundles it exports once. */
    static String write(byte[] chart, Score score, boolean playable, File target)
            throws IOException {
        List<byte[]> documents = new ArrayList<>();
        documents.add(chart);
        String omitted = null;
        if (playable) {
            try {
                documents.add(SheetDocuments.playable(score));
            } catch (IllegalArgumentException | IllegalStateException e) {
                omitted = reasonOf(e);
            }
        }
        synchronized (WRITING) {
            String failed = engrave(documents, target);
            return omitted != null ? omitted : failed;
        }
    }

    private static String reasonOf(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /**
     * Draws the documents in order, each from a new page and each drawn before
     * the next is rendered, so only one document's pictures are alive at a
     * time. Returns why a later document was dropped, or null.
     */
    private static String engrave(List<byte[]> documents, File target) throws IOException {
        File tmp = File.createTempFile(target.getName(), ".tmp", target.getParentFile());
        PdfDocument document = new PdfDocument();
        String dropped = null;
        try {
            int pageNumber = 0;
            for (byte[] musicXml : documents) {
                SheetRenderer.Result result = SheetRenderer.render(musicXml,
                        SheetRenderer.ENGINE_PICTURE, PAGE_WIDTH - 2 * MARGIN, 1);
                if (!result.succeeded()) {
                    if (pageNumber == 0) {
                        throw new IOException(result.failure());
                    }
                    dropped = result.failure();
                    break;
                }
                List<SheetRenderer.Partial> chunks = result.partials();
                double[] heights = new double[chunks.size()];
                for (int i = 0; i < heights.length; i++) {
                    heights[i] = chunks.get(i).height();
                }
                for (List<Integer> page : SheetPaginator.paginate(heights,
                        PAGE_HEIGHT - 2 * MARGIN)) {
                    pageNumber++;
                    PdfDocument.Page drawn = document.startPage(
                            new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber)
                                    .create());
                    Canvas canvas = drawn.getCanvas();
                    double y = MARGIN;
                    for (int index : page) {
                        SheetRenderer.Partial chunk = chunks.get(index);
                        canvas.save();
                        canvas.translate((float) (MARGIN + chunk.x()), (float) y);
                        canvas.drawPicture((Picture) chunk.result());
                        canvas.restore();
                        y += chunk.height();
                    }
                    document.finishPage(drawn);
                }
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
        return dropped;
    }
}
