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
import java.util.List;

/**
 * Writes the engraving as a vector PDF: every chunk alphaTab draws is
 * recorded as a picture and replayed onto A4 pages, in order, breaking where
 * the next does not fit. Text stays text, so it prints at any size. The
 * document is the playable part when it was asked for and heard, since the
 * lead sheet carries the chords over the staff; the chart otherwise, and
 * also when the part will not engrave.
 */
final class SheetPdf {

    /** A4 in PostScript points, which is what {@link PdfDocument} measures in. */
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 36;

    /**
     * How large the engraving is drawn, where one is alphaTab's own size,
     * which puts a few bars on a system of this width.
     */
    private static final double PAGE_SCALE = 0.6;

    /** The result screen and the bundle builder both write a take's PDF; one at a time. */
    private static final Object WRITING = new Object();

    private SheetPdf() {
    }

    /**
     * Renders and writes, replacing whatever was at {@code target} in one
     * move; a failure leaves the previous file in place and nothing
     * half-written. A playable part that cannot be made leaves the chart as
     * the document, and is said.
     *
     * @param playable whether the document is the playable part
     * @return why the file holds the chart rather than the playable part, or
     *         null when it holds the part or the part was not asked for
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
        Document document = document(chart, score, playable);
        synchronized (WRITING) {
            String failed = engrave(document.musicXml(), target);
            if (failed == null) {
                return document.omitted();
            }
            if (!document.part()) {
                throw new IOException(failed);
            }
            // The part would not engrave; the chart is the document after all.
            String chartFailed = engrave(chart, target);
            if (chartFailed != null) {
                throw new IOException(chartFailed);
            }
            return failed;
        }
    }

    /**
     * What goes into the file: the part, or the chart, and why it is the chart
     * when the part was wanted.
     */
    record Document(byte[] musicXml, boolean part, String omitted) {
    }

    static Document document(byte[] chart, Score score, boolean playable) {
        if (!playable) {
            return new Document(chart, false, null);
        }
        try {
            return new Document(SheetDocuments.playable(score), true, null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new Document(chart, false, reasonOf(e));
        }
    }

    private static String reasonOf(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /**
     * Draws one document onto pages and moves the file into place. Returns
     * why alphaTab would not engrave it, or null once the file is written;
     * anything the filesystem refuses is thrown.
     */
    private static String engrave(byte[] musicXml, File target) throws IOException {
        SheetRenderer.Result result = SheetRenderer.render(musicXml,
                SheetRenderer.ENGINE_PICTURE, PAGE_WIDTH - 2 * MARGIN, PAGE_SCALE);
        if (!result.succeeded()) {
            return result.failure();
        }
        List<SheetRenderer.Partial> chunks = result.partials();
        double[] heights = new double[chunks.size()];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = chunks.get(i).height();
        }
        File tmp = File.createTempFile(target.getName(), ".tmp", target.getParentFile());
        PdfDocument document = new PdfDocument();
        try {
            int pageNumber = 0;
            for (List<Integer> page : SheetPaginator.paginate(heights, PAGE_HEIGHT - 2 * MARGIN)) {
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
            try (OutputStream out = new FileOutputStream(tmp)) {
                document.writeTo(out);
            }
        } catch (Throwable failure) {
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
        return null;
    }
}
