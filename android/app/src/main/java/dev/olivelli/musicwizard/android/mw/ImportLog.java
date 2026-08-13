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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What happened during an import, in a form safe to paste into a chat.
 *
 * <p>An import that fails on a phone the developer cannot see is otherwise one
 * sentence chosen from a taxonomy, and the sentence is a guess about a cause
 * nobody measured. This holds the rest: which stage, which range, which status,
 * how many attempts.
 *
 * <p><strong>Every line is scrubbed, and that is the point rather than a
 * courtesy.</strong> A googlevideo URL carries the phone's public IP in its
 * {@code ip} parameter, the session's {@code visitorData}, and per-request
 * signatures; the whole purpose of this class is that its contents get sent to
 * someone else. {@link #scrub} is applied on the way in, so there is no way to
 * add a line that skips it — a redaction the caller has to remember is one the
 * caller eventually forgets.
 *
 * <p>Written by the import worker and read by the screen, so it is
 * synchronized. Bounded, because a twenty-minute fetch is hundreds of ranges
 * and a panel nobody can scroll to the end of is no better than none.
 */
public final class ImportLog {

    /** Enough for a long fetch's stages and the tail of its ranges. */
    private static final int MAX_LINES = 400;

    /** Query parameters that identify the phone, the session, or the request. */
    private static final Pattern SECRETS = Pattern.compile(
            "(?i)([?&](?:ip|ipbits|sig|lsig|lsparams|sparams|spc|ei|bui|vprv|txp|pcm2cms"
                    + "|met|mh|mm|mn|ms|rms|id|key|c|cver|cpn|n|pot)=)[^&\\s]*");

    /**
     * A bare address, wherever it turns up outside a parameter.
     *
     * <p>No leading {@code \b}: percent-encoding puts a word character in front
     * of the first digit ({@code %3D203.0.113.47}) and a boundary there would
     * step over it. The IPv6 half allows {@code ::} compression, which is the
     * form a phone on mobile data actually reports, and is case-insensitive
     * like its two siblings.
     */
    private static final Pattern BARE_IP = Pattern.compile(
            "(?i)(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])"
                    + "|(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}"
                    + "(?::\\d{1,3}(?:\\.\\d{1,3}){3})?(?![0-9a-f:])");

    /**
     * The edge that served this phone, with or without a scheme in front of it.
     *
     * <p>Names like {@code rr1---sn-uxaxpu5ap5-ca9l.googlevideo.com} are chosen
     * per session from where the request came from, so they carry a hint of the
     * network and the region the way an address does. The scheme is optional
     * because the lines that carry a host name it on its own.
     */
    private static final Pattern MEDIA_HOST = Pattern.compile(
            "(?i)(?:https://)?[a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)*\\.googlevideo\\.com(?![a-z0-9.\\-])");

    private final Deque<String> lines = new ArrayDeque<>();
    private int dropped;
    private int revision;

    /** Adds one line, scrubbed. */
    public synchronized void add(String line) {
        lines.add(scrub(line));
        revision++;
        while (lines.size() > MAX_LINES) {
            lines.poll();
            dropped++;
        }
    }

    /** The log as text, oldest first. */
    public synchronized String text() {
        StringBuilder out = new StringBuilder();
        if (dropped > 0) {
            out.append("… ").append(dropped).append(" earlier lines dropped\n");
        }
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    public synchronized boolean isEmpty() {
        return lines.isEmpty();
    }

    public synchronized void clear() {
        lines.clear();
        dropped = 0;
        revision++;
    }

    /**
     * Changes whenever the text does.
     *
     * <p>The decode reports progress once per output buffer — thousands of times
     * for one track, tens of thousands for a long one — and the log usually says
     * nothing new between them. A screen that redrew on each would rebuild a
     * selectable {@code TextView} inside a {@code ScrollView} every time, and
     * drop the user's selection with it.
     */
    public synchronized int revision() {
        return revision;
    }

    /**
     * Removes everything that identifies the phone, the session or the request.
     *
     * <p>An allow-list would be safer than this deny-list and is not available:
     * the lines are prose, not structured records. The lines this app composes
     * name the host and the format rather than a URL — but a transport failure
     * carries whatever the transport chose to say, and Android's names both
     * endpoints while a refused redirect carries the whole signed URL. Those
     * are what this is for.
     */
    public static String scrub(String line) {
        if (line == null) {
            return "";
        }
        String out = SECRETS.matcher(line).replaceAll("$1SCRUBBED");
        out = MEDIA_HOST.matcher(out).replaceAll("an-edge.googlevideo.invalid");
        Matcher ip = BARE_IP.matcher(out);
        return ip.replaceAll("0.0.0.0");
    }
}
