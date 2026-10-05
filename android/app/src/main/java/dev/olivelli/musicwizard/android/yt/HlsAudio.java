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

package dev.olivelli.musicwizard.android.yt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Fetches a video's audio through its HLS manifest, segment by segment.
 *
 * <p>The road taken when the direct media URLs are refused. YouTube's media
 * host serves those URLs only to a client that presents a proof-of-origin
 * token on some networks, and the shape of that refusal is the opening of the
 * file served and everything after it denied. The HLS rendition of the same
 * audio is served without a token; yt-dlp's client table records the same
 * distinction, and {@code InnerTubeLiveTest} is what checks it still holds.
 *
 * <p>The segments are AAC in ADTS framing, each opening with an ID3 tag that
 * carries timing and nothing a decoder wants. The tag is cut and the frames
 * appended, so the result is one plain ADTS stream that {@code MediaExtractor}
 * reads as a whole. A segment whose first bytes after the tag are not an ADTS
 * sync is refused rather than written: as with the direct download, a file of
 * the right length holding the wrong bytes still decodes, into a chart of a
 * song nobody played.
 */
public final class HlsAudio {

    /** What the written file is, for naming and for the decoder. */
    public static final String MIME_TYPE = "audio/aac";

    /** The media host refused a playlist or a segment after every attempt, so the road is closed. */
    public static final class RefusedException extends IOException {

        private static final long serialVersionUID = 1L;

        RefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final StreamDownload download;
    private final Trace trace;

    /** Every request goes through {@code download}, which retries and follows redirects. */
    public HlsAudio(StreamDownload download) {
        this(download, Trace.NONE);
    }

    public HlsAudio(StreamDownload download, Trace trace) {
        this.download = download;
        this.trace = trace;
    }

    /**
     * Writes the audio behind {@code manifestUrl} to {@code target}, whole or not
     * at all.
     *
     * <p>Progress is in segments. Cancellation surfaces as an
     * {@code InterruptedIOException}; a refusal by the media host as a
     * {@link RefusedException}; anything else as a plain {@code IOException}.
     */
    public void to(File target, String manifestUrl, StreamDownload.Progress progress,
            BooleanSupplier cancelled) throws IOException {
        Rendition rendition = chooseAudio(text(manifestUrl, "manifest", cancelled), manifestUrl);
        trace.line("hls audio group " + rendition.group + " " + rendition.codec);
        List<String> segments = segmentUrls(
                text(rendition.url, "audio playlist", cancelled), rendition.url);
        if (segments.isEmpty()) {
            throw new IOException("the HLS audio playlist lists no segments");
        }
        trace.line("hls " + segments.size() + " segments");

        boolean complete = false;
        try {
            try (OutputStream out = new FileOutputStream(target)) {
                for (int i = 0; i < segments.size(); i++) {
                    if (cancelled.getAsBoolean()) {
                        throw new InterruptedIOException("the download was cancelled");
                    }
                    out.write(adtsFrames(whole(segments.get(i), "segment " + (i + 1), cancelled),
                            i + 1));
                    progress.onProgress(i + 1, segments.size());
                }
            }
            complete = true;
        } finally {
            if (!complete && target.exists() && !target.delete()) {
                target.deleteOnExit();
            }
        }
    }

    private String text(String url, String what, BooleanSupplier cancelled) throws IOException {
        return new String(whole(url, what, cancelled), StandardCharsets.UTF_8);
    }

    private byte[] whole(String url, String what, BooleanSupplier cancelled) throws IOException {
        try {
            return download.whole(url, cancelled);
        } catch (StreamDownload.ExpiredException refused) {
            throw new RefusedException("the media host refused the HLS " + what, refused);
        }
    }

    /** The frames of one segment, with the ID3 tag that opens it cut off. */
    static byte[] adtsFrames(byte[] segment, int number) throws IOException {
        int start = 0;
        if (segment.length >= 10 && segment[0] == 'I' && segment[1] == 'D' && segment[2] == '3') {
            int size = (segment[6] & 0x7F) << 21 | (segment[7] & 0x7F) << 14
                    | (segment[8] & 0x7F) << 7 | (segment[9] & 0x7F);
            start = 10 + size + ((segment[5] & 0x10) != 0 ? 10 : 0);
        }
        if (start + 2 > segment.length
                || (segment[start] & 0xFF) != 0xFF || (segment[start + 1] & 0xF6) != 0xF0) {
            throw new IOException("HLS segment " + number + " is not AAC audio");
        }
        byte[] frames = new byte[segment.length - start];
        System.arraycopy(segment, start, frames, 0, frames.length);
        return frames;
    }

    private static final class Rendition {

        final String group;
        final String codec;
        final String url;

        Rendition(String group, String codec, String url) {
            this.group = group;
            this.codec = codec;
            this.url = url;
        }
    }

    /**
     * The audio-only rendition to fetch: AAC-LC when the manifest offers it, as
     * the direct road chooses, and within a group the entry marked default.
     *
     * <p>The rendition lines name no codec; the variant lines that reference
     * them do.
     */
    static Rendition chooseAudio(String master, String masterUrl) throws IOException {
        Map<String, String> uris = new LinkedHashMap<>();
        Map<String, String> codecs = new LinkedHashMap<>();
        for (String line : master.split("\r?\n")) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> attributes = attributes(line.substring("#EXT-X-MEDIA:".length()));
                String group = attributes.getOrDefault("GROUP-ID", "");
                if ("AUDIO".equals(attributes.get("TYPE")) && attributes.containsKey("URI")
                        && (!uris.containsKey(group) || "YES".equals(attributes.get("DEFAULT")))) {
                    uris.put(group, attributes.get("URI"));
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> attributes =
                        attributes(line.substring("#EXT-X-STREAM-INF:".length()));
                String group = attributes.get("AUDIO");
                String codec = audioCodec(attributes.getOrDefault("CODECS", ""));
                if (group != null && codec != null && !codecs.containsKey(group)) {
                    codecs.put(group, codec);
                }
            }
        }
        if (uris.isEmpty()) {
            throw new IOException("the HLS manifest offers no audio-only rendition");
        }
        String chosen = null;
        for (String group : uris.keySet()) {
            if ("mp4a.40.2".equals(codecs.get(group))) {
                chosen = group;
                break;
            }
        }
        if (chosen == null) {
            chosen = uris.keySet().iterator().next();
        }
        return new Rendition(chosen, codecs.getOrDefault(chosen, "?"),
                resolve(masterUrl, uris.get(chosen)));
    }

    private static String audioCodec(String codecs) {
        for (String codec : codecs.split(",")) {
            if (codec.trim().startsWith("mp4a.")) {
                return codec.trim();
            }
        }
        return null;
    }

    static List<String> segmentUrls(String playlist, String playlistUrl) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : playlist.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                out.add(resolve(playlistUrl, trimmed));
            }
        }
        return out;
    }

    /** Attribute list of one tag, quotes removed; splits on commas outside quotes only. */
    static Map<String, String> attributes(String list) {
        Map<String, String> out = new LinkedHashMap<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i <= list.length(); i++) {
            char c = i < list.length() ? list.charAt(i) : ',';
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                int equals = current.indexOf("=");
                if (equals > 0) {
                    out.put(current.substring(0, equals).trim(), current.substring(equals + 1));
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        return out;
    }

    private static String resolve(String base, String reference) throws IOException {
        return Addresses.resolveHttps(base, reference);
    }
}
