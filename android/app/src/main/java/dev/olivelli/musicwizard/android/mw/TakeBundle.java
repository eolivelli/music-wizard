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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One take as one file: the recording and everything the phone knows about it,
 * zipped so it survives a share sheet, a cloud drive and a download intact.
 *
 * <p>Every entry is named by the take's stem, not a fixed name, so files pulled
 * out of the zip stay identifiable next to other takes' — the zip is a
 * container for transport, not a directory layout anything reads back.
 *
 * <p>Plain {@code java.io} and {@code java.util.zip}, so it is tested on the
 * JVM rather than on a device.
 */
public final class TakeBundle {

    private TakeBundle() {
    }

    /**
     * Writes the bundle, replacing whatever was at {@code zip}.
     *
     * <p>The audio always goes in; the rest goes in as far as it exists. A take
     * that was never analysed bundles as the recording and the info lines, which
     * is still worth sharing — the analysis can be run on the desktop.
     *
     * @param zip       where to write; its parent directory must exist
     * @param takeName  the take's display name, used as the stem of every entry
     * @param wav       the recording
     * @param scoreJson the cached analysis, skipped when null or absent
     * @param chartText the chart as text, skipped when null
     * @param infoText  a few lines about the take, skipped when null
     */
    public static void write(File zip, String takeName, File wav,
                             File scoreJson, String chartText, String infoText)
            throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            if (infoText != null) {
                text(out, takeName + ".info.txt", infoText);
            }
            if (chartText != null) {
                text(out, takeName + ".chords.txt", chartText);
            }
            if (scoreJson != null && scoreJson.isFile()) {
                file(out, takeName + ".score.json", scoreJson);
            }
            file(out, takeName + ".wav", wav);
        } catch (IOException | RuntimeException e) {
            // A half-written zip must not be left behind: the share sheet would
            // offer it, and it opens on the other side or not at all.
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            throw e;
        }
    }

    private static void text(ZipOutputStream out, String name, String content)
            throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static void file(ZipOutputStream out, String name, File source)
            throws IOException {
        out.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(source)) {
            copy(in, out);
        }
        out.closeEntry();
    }

    /** By hand rather than {@code InputStream.transferTo}, which Android only grew in API 33. */
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
        }
    }
}
