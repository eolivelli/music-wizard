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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Asks YouTube's player endpoint what a video's audio formats are.
 *
 * <p>Two things make this work, and both are easy to lose in a tidy-up.
 *
 * <p><strong>The client is a headset.</strong> {@code ANDROID_VR} is one of the
 * few clients that still answers with a plain {@code url} on each adaptive
 * format instead of a SABR streaming endpoint, and — measured, not assumed —
 * those URLs carry neither a {@code pot} proof-of-origin token nor an {@code n}
 * throttling parameter. That is what spares this app a WebView running Google's
 * BotGuard and an embedded JavaScript interpreter to descramble {@code n},
 * either of which would dwarf everything else here. yt-dlp reaches the same
 * conclusion; its no-JavaScript client list is {@code ('visionos',
 * 'android_vr')}. Do not raise {@link #CLIENT_VERSION} past 1.65 to look
 * current: newer versions have been observed answering SABR-only.
 *
 * <p><strong>The session is bootstrapped from its own rejection.</strong> A call
 * carrying no {@code visitorData} is usually refused with {@code LOGIN_REQUIRED}
 * and "Sign in to confirm you're not a bot" — 7 of 8 videos in one run, 0 of 1
 * in another, so it varies by session and cannot be predicted. But the refusal
 * still carries a {@code responseContext.visitorData}, and replaying that on the
 * retry passes. One bootstrapped value then served 20 consecutive videos, which
 * is why it is cached for the life of the instance.
 *
 * <p>Neither fact is durable. yt-dlp already records selective proof-of-origin
 * enforcement on this client, so the day will come when the reply is
 * {@link ExtractionException.Reason#SABR_ONLY} for everything; that reason
 * exists so the app can say the build is out of date rather than blame the
 * network. When it does, the fix starts by diffing this constants block against
 * yt-dlp's {@code INNERTUBE_CLIENTS}.
 */
public final class InnerTube {

    /** No API key: the endpoint has not required one for a long time. */
    private static final String ENDPOINT =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

    private static final String CLIENT_NAME = "ANDROID_VR";

    /** Pinned deliberately. Newer has been seen returning SABR-only streams. */
    private static final String CLIENT_VERSION = "1.65.10";

    /** The numeric client id that goes in the header beside the name. */
    private static final String CLIENT_ID = "28";

    /** The session held, a bare bootstrap, and the value that bootstrap offered. */
    private static final int MAX_CALLS = 3;

    private static final String USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/" + CLIENT_VERSION
                    + " (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

    private final Http http;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The session, harvested from the first reply that offers one.
     *
     * <p>Volatile because the fetch runs on a worker thread while the instance is
     * reachable from the one that started it.
     */
    private volatile String visitorData;

    public InnerTube(Http http) {
        this.http = http;
    }

    /** The session id in use, or null before the first call. Visible for tests. */
    String visitorData() {
        return visitorData;
    }

    /**
     * Resolves one video, bootstrapping a session if the first answer refuses.
     *
     * <p>Makes one call when the session already works, two when it has to be
     * bootstrapped. It does not assume the first call fails: the bare call
     * succeeds often enough that spending a round trip to provoke a rejection
     * would be waste.
     */
    public PlayerInfo resolve(String videoId) throws ExtractionException, IOException {
        String session = visitorData;
        // True once a call has gone out carrying no session, so the bootstrap is
        // never attempted twice — the second one would be the same request.
        boolean triedBare = false;
        JsonNode reply;
        String status;

        // At most three calls: the session already held, a bare one if that
        // session has gone stale, and one carrying what the refusal offered.
        for (int attempt = 1; ; attempt++) {
            triedBare |= session == null;
            reply = call(videoId, session);
            status = status(reply);

            if (!"LOGIN_REQUIRED".equals(status)) {
                // Committed only now, having been proved. Assigning before the
                // retry would cache a value nothing has tested, and would throw
                // away a working one whenever a refusal offered a different.
                visitorData = session != null ? session : offeredSession(reply);
                break;
            }

            String offered = offeredSession(reply);
            if (offered != null && !offered.equals(session)) {
                // The refusal carries the credential that lifts it.
                session = offered;
            } else if (session != null && !triedBare) {
                // The reply offered nothing new, which is what happens when
                // YouTube echoes back the session that was sent: the held one
                // has stopped working. Drop it and bootstrap from scratch,
                // rather than repeating a request that cannot answer differently.
                session = null;
                visitorData = null;
            } else {
                break;
            }

            if (attempt >= MAX_CALLS) {
                break;
            }
        }

        if ("LOGIN_REQUIRED".equals(status)) {
            // Leaving with a session that has just been refused would open the
            // next video by sending it again. Starting clean is not free -- a
            // bare call is itself usually refused -- but it is the same one
            // round trip either way, and this way the refusal is the one that
            // carries a new session rather than one that cannot.
            visitorData = null;
        }

        return interpret(videoId, reply, status);
    }

    /** The session id a reply offers, whether it succeeded or refused. */
    private static String offeredSession(JsonNode reply) {
        String offered = reply.path("responseContext").path("visitorData").asText(null);
        return offered == null || offered.isEmpty() ? null : offered;
    }

    private PlayerInfo interpret(String videoId, JsonNode reply, String status)
            throws ExtractionException {
        String reason = reason(reply);

        if ("LOGIN_REQUIRED".equals(status)) {
            throw new ExtractionException(ExtractionException.Reason.BOT_CHECK,
                    "YouTube would not serve this without signing in."
                            + " Try again, or on another network.");
        }
        if ("ERROR".equals(status)) {
            throw new ExtractionException(ExtractionException.Reason.UNAVAILABLE,
                    reason.isEmpty() ? "YouTube says this video is unavailable." : reason);
        }
        if ("UNPLAYABLE".equals(status)) {
            if (reason.toLowerCase(Locale.ROOT).contains("live stream")) {
                throw new ExtractionException(ExtractionException.Reason.LIVE,
                        "This is a live stream, which has no fixed length to fetch.");
            }
            throw new ExtractionException(ExtractionException.Reason.REFUSED, reason.isEmpty()
                    ? "YouTube will not serve this video to this app."
                    : reason);
        }
        if (!"OK".equals(status)) {
            throw new ExtractionException(ExtractionException.Reason.REFUSED,
                    "YouTube answered with " + status
                            + (reason.isEmpty() ? "" : ": " + reason));
        }

        JsonNode details = reply.path("videoDetails");
        // Both ways a live stream shows up, decided here so one place owns it: a
        // stream in progress is playable and says so in videoDetails, while an
        // ended one is refused above with "This live stream recording is not
        // available." Neither has a fixed length to fetch.
        if (details.path("isLive").asBoolean(false)) {
            throw new ExtractionException(ExtractionException.Reason.LIVE,
                    "This is a live stream, which has no fixed length to fetch.");
        }

        List<AudioStream> audio = audioFormats(reply);
        if (audio.isEmpty()) {
            throw new ExtractionException(ExtractionException.Reason.NO_AUDIO,
                    "YouTube offered no audio track for this video.");
        }
        if (AudioStream.choose(audio) == null) {
            throw new ExtractionException(ExtractionException.Reason.SABR_ONLY,
                    "YouTube changed how it serves this audio."
                            + " This version of the app cannot fetch it.");
        }

        return new PlayerInfo(
                videoId,
                details.path("title").asText(videoId),
                details.path("author").asText(""),
                details.path("lengthSeconds").asLong(0),
                audio);
    }

    /**
     * Every audio-only adaptive format, whether or not it can be fetched.
     *
     * <p>Formats with no URL are kept rather than dropped, so the caller can tell
     * "YouTube offered nothing" from "YouTube offered SABR-only", which are a
     * missing video and an out-of-date app respectively.
     */
    private static List<AudioStream> audioFormats(JsonNode reply) {
        List<AudioStream> out = new ArrayList<>();
        for (JsonNode format : reply.path("streamingData").path("adaptiveFormats")) {
            String mime = format.path("mimeType").asText("");
            if (!mime.startsWith("audio/")) {
                continue;
            }
            // A segmented format has no single container to hand a decoder.
            if (format.path("type").asText("").endsWith("OTF")) {
                continue;
            }
            out.add(new AudioStream(
                    format.path("itag").asInt(0),
                    mime,
                    format.path("url").asText(null),
                    format.path("bitrate").asInt(0),
                    format.path("contentLength").asLong(0),
                    format.path("audioSampleRate").asInt(0),
                    format.path("audioChannels").asInt(0),
                    format.path("approxDurationMs").asLong(0)));
        }
        return out;
    }

    private JsonNode call(String videoId, String session) throws ExtractionException, IOException {
        Http.Response reply = http.send(request(videoId, session));
        if (!reply.isSuccess()) {
            throw new ExtractionException(ExtractionException.Reason.REFUSED,
                    "YouTube answered the player request with HTTP " + reply.status() + ".");
        }
        try {
            return mapper.readTree(reply.body());
        } catch (RuntimeException | LinkageError | IOException failure) {
            // LinkageError as well as the rest: Jackson on Android has surprised
            // this app before, and a reply that cannot be parsed must not look
            // like a reply that refused.
            throw new ExtractionException(ExtractionException.Reason.MALFORMED,
                    "YouTube's answer could not be read.", failure);
        }
    }

    /** The request, built the same way every time so a test can assert it byte for byte. */
    Http.Request request(String videoId, String session) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("User-Agent", USER_AGENT);
        headers.put("X-YouTube-Client-Name", CLIENT_ID);
        headers.put("X-YouTube-Client-Version", CLIENT_VERSION);
        headers.put("Origin", "https://www.youtube.com");
        headers.put("Accept-Encoding", "gzip");
        if (session != null && !session.isEmpty()) {
            // Sent in both places. Either alone is enough; sending both costs
            // nothing and is what yt-dlp does.
            headers.put("X-Goog-Visitor-Id", session);
        }
        return new Http.Request("POST", ENDPOINT, headers, Http.Body.json(body(videoId, session)));
    }

    private String body(String videoId, String session) {
        ObjectNode client = mapper.createObjectNode();
        client.put("clientName", CLIENT_NAME);
        client.put("clientVersion", CLIENT_VERSION);
        client.put("deviceMake", "Oculus");
        client.put("deviceModel", "Quest 3");
        client.put("androidSdkVersion", 32);
        client.put("osName", "Android");
        client.put("osVersion", "12L");
        client.put("userAgent", USER_AGENT);
        client.put("hl", "en");
        client.put("timeZone", "UTC");
        client.put("utcOffsetMinutes", 0);
        if (session != null && !session.isEmpty()) {
            client.put("visitorData", session);
        }

        ObjectNode context = mapper.createObjectNode();
        context.set("client", client);

        ObjectNode playback = mapper.createObjectNode();
        playback.putObject("contentPlaybackContext").put("html5Preference", "HTML5_PREF_WANTS");

        ObjectNode root = mapper.createObjectNode();
        root.set("context", context);
        root.put("videoId", videoId);
        root.set("playbackContext", playback);
        // Both are load-bearing: with them an age-restricted video resolves, and
        // without them it is refused.
        root.put("contentCheckOk", true);
        root.put("racyCheckOk", true);
        return root.toString();
    }

    private static String status(JsonNode reply) {
        return reply.path("playabilityStatus").path("status").asText("");
    }

    /** YouTube's own sentence, which is better than anything this app could invent. */
    private static String reason(JsonNode reply) {
        JsonNode playability = reply.path("playabilityStatus");
        String reason = playability.path("reason").asText("");
        if (!reason.isEmpty()) {
            return reason;
        }
        JsonNode messages = playability.path("messages");
        return messages.isArray() && messages.size() > 0 ? messages.get(0).asText("") : "";
    }
}
