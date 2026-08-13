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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where a take came from, in one grammar three programs agree on.
 *
 * <p>The app writes it beside the take, the bundle carries it into
 * {@code <take>.info.txt}, and the desktop's take-importer greps it. That last
 * reader is the reason this is a class rather than a string built where it is
 * needed: a take fetched from YouTube is commercial audio, and the desktop has
 * to keep it out of the committed corpus without a person having to remember
 * which takes were which. The token it looks for is pinned by a test.
 *
 * <p>A microphone take writes no file at all today, so absence means either
 * "recorded in a room" or "written by an older version of the app", and nothing
 * distinguishes them. {@link #microphone()} exists so that the readers are
 * already right if that changes: absence is parsed <em>as</em> a microphone
 * take, and every reader asks {@link #isCommercial()} rather than asking whether
 * a file is there.
 */
public final class TakeSource {

    /** A take made with the phone's own microphone. */
    public static final String MICROPHONE = "microphone";

    /** A take fetched from YouTube. Commercial audio, whatever it sounds like. */
    public static final String YOUTUBE = "youtube";

    private final String kind;
    private final String url;
    private final String title;
    private final String imported;

    private TakeSource(String kind, String url, String title, String imported) {
        this.kind = kind;
        this.url = url;
        this.title = title;
        this.imported = imported;
    }

    public static TakeSource microphone() {
        return new TakeSource(MICROPHONE, "", "", "");
    }

    /** @param imported a timestamp for the reader, not for arithmetic */
    public static TakeSource youtube(String url, String title, String imported) {
        return new TakeSource(YOUTUBE, url, title, imported);
    }

    public String kind() {
        return kind;
    }

    public String url() {
        return url;
    }

    public String title() {
        return title;
    }

    /** Whether this take may be committed to the corpus, licence aside. */
    public boolean isCommercial() {
        return YOUTUBE.equals(kind);
    }

    /**
     * The file's text.
     *
     * <p>Newlines in a value are replaced rather than escaped: a video title can
     * hold anything, the format is one field per line, and a title that spanned
     * two lines would read back as a field nobody wrote.
     */
    public String toText() {
        StringBuilder out = new StringBuilder();
        out.append("source: ").append(oneLine(kind)).append('\n');
        if (!url.isEmpty()) {
            out.append("url: ").append(oneLine(url)).append('\n');
        }
        if (!title.isEmpty()) {
            out.append("title: ").append(oneLine(title)).append('\n');
        }
        if (!imported.isEmpty()) {
            out.append("imported: ").append(oneLine(imported)).append('\n');
        }
        return out.toString();
    }

    /**
     * The line that goes into the bundle's {@code info.txt}.
     *
     * <p>One line, because that file is a list of one-line facts and the desktop
     * reads it with a grep.
     */
    public String infoLine() {
        StringBuilder out = new StringBuilder("source: ").append(oneLine(kind));
        if (!url.isEmpty()) {
            out.append(' ').append(oneLine(url));
        }
        return out.toString();
    }

    /**
     * Reads back what {@link #toText} wrote.
     *
     * <p>Unreadable text becomes a microphone take rather than an exception: the
     * audio is still a take, and the caller decides what an unknown provenance
     * means. It never invents a YouTube source, which is the direction that
     * would matter.
     */
    public static TakeSource parse(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (text != null) {
            for (String line : text.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    fields.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }
        }
        String kind = fields.getOrDefault("source", MICROPHONE);
        return new TakeSource(kind.isEmpty() ? MICROPHONE : kind,
                fields.getOrDefault("url", ""),
                fields.getOrDefault("title", ""),
                fields.getOrDefault("imported", ""));
    }

    private static String oneLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
