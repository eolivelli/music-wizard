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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
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

    /** The media host refused a segment, so the whole road is closed. */
    public static final class RefusedException extends IOException {

        private static final long serialVersionUID = 1L;

        RefusedException(String message) {
            super(message);
        }
    }

    private final Http http;
    private final Trace trace;

    public HlsAudio(Http http) {
        this(http, Trace.NONE);
    }

    public HlsAudio(Http http, Trace trace) {
        this.http = http;
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
        Rendition rendition = chooseAudio(text(manifestUrl, "the HLS manifest"), manifestUrl);
        trace.line("hls audio group " + rendition.group + " " + rendition.codec);
        List<String> segments = segmentUrls(
                text(rendition.url, "the HLS audio playlist"), rendition.url);
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
                    out.write(adtsFrames(segment(segments.get(i), i + 1, cancelled), i + 1));
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

    private String text(String url, String what) throws IOException {
        Http.Response reply = http.send(new Http.Request("GET", url, new LinkedHashMap<>(), null));
        trace.line("hls " + what + " -> HTTP " + reply.status() + " from " + hostOf(url));
        if (!reply.isSuccess()) {
            throw new IOException(what + " answered HTTP " + reply.status());
        }
        return reply.body();
    }

    private byte[] segment(String url, int number, BooleanSupplier cancelled) throws IOException {
        try (Http.Content content =
                http.open(new Http.Request("GET", url, new LinkedHashMap<>(), null))) {
            int status = content.status();
            if (status != 200) {
                trace.line("hls segment " + number + " -> HTTP " + status + " from " + hostOf(url));
                if (status == 403 || status == 410) {
                    throw new RefusedException(
                            "the media host refused HLS segment " + number + " (HTTP " + status + ")");
                }
                throw new IOException("HLS segment " + number + " answered HTTP " + status);
            }
            return readAll(content.stream(), cancelled);
        }
    }

    private static byte[] readAll(InputStream in, BooleanSupplier cancelled) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 14];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedIOException("the download was cancelled");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
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
     * The audio-only rendition to fetch: AAC-LC when the manifest offers it.
     *
     * <p>The rendition lines name no codec; the variant lines that reference
     * them do. HE-AAC is passed over because its decoders report their output
     * format twice, which the decode path handles least well.
     */
    static Rendition chooseAudio(String master, String masterUrl) throws IOException {
        Map<String, String> uris = new LinkedHashMap<>();
        Map<String, String> codecs = new LinkedHashMap<>();
        for (String line : master.split("\r?\n")) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> attributes = attributes(line.substring("#EXT-X-MEDIA:".length()));
                if ("AUDIO".equals(attributes.get("TYPE")) && attributes.containsKey("URI")) {
                    uris.put(attributes.getOrDefault("GROUP-ID", ""), attributes.get("URI"));
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
        try {
            URI resolved = new URI(base).resolve(reference);
            if (!"https".equalsIgnoreCase(resolved.getScheme())) {
                throw new IOException("refusing an HLS address that is not https: " + resolved);
            }
            return resolved.toString();
        } catch (URISyntaxException | IllegalArgumentException unreadable) {
            throw new IOException("the HLS playlist holds an unreadable address", unreadable);
        }
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "?" : host;
        } catch (URISyntaxException unreadable) {
            return "?";
        }
    }
}
