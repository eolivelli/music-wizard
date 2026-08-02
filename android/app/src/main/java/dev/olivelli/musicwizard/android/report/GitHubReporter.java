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

package dev.olivelli.musicwizard.android.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Filing one take on GitHub: the audio as a release asset, the account of it as
 * a comment on the standing inbox issue.
 *
 * <p>Three calls, in this order, because each needs the one before it:
 *
 * <ol>
 *   <li>the release is looked up <em>by tag</em>, for its {@code upload_url} —
 *       the upload endpoint is addressed by release id, and a tag is the part
 *       of that a human can put in a resource file and re-create later;</li>
 *   <li>the audio goes up as an asset, in one request, raw;</li>
 *   <li>the comment goes on the inbox issue, carrying the asset's link.</li>
 * </ol>
 *
 * <p>Comment last on purpose. A comment naming an asset that failed to upload
 * is a dead link in the corpus record; audio with no comment is still a file
 * on a release that can be described by hand.
 *
 * <p>There is no API for attaching a file to an issue — that is web-UI only —
 * which is what shapes the whole design; see #291.
 */
public final class GitHubReporter {

    private static final String API = "https://api.github.com";

    /** The version the request shapes here were written against. */
    private static final String API_VERSION = "2022-11-28";

    /**
     * Send time, in the asset name.
     *
     * <p>A release refuses a second asset of the same name with a 422, and
     * re-sending a take — after a failed attempt, or with a better description
     * of what was played — is a thing someone will do.
     */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    /** Long enough for any name the library allows, short of GitHub's 255. */
    private static final int MAX_STEM_LENGTH = 80;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A request GitHub answered, but not with what was asked for. */
    public static final class Failure extends IOException {

        private static final long serialVersionUID = 1L;

        private final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }

        /** The HTTP status, or 0 when the request was never made. */
        public int status() {
            return status;
        }
    }

    /** Where the take ended up. */
    public record Sent(String assetUrl, String commentUrl) {
    }

    private final Http http;
    private final String owner;
    private final String repo;
    private final String token;

    public GitHubReporter(Http http, String owner, String repo, String token) {
        this.http = http;
        this.owner = owner;
        this.repo = repo;
        this.token = token;
    }

    /**
     * The name an asset is uploaded under: the take, then when it was sent.
     *
     * <p>Reduced to the characters GitHub keeps verbatim in an asset name, so
     * that the link written into the comment is the link the asset actually
     * gets. A take name is typed by a person and the library allows spaces,
     * accents and emoji in one.
     */
    public static String assetName(String takeName, Instant sentAt, String extension) {
        String raw = takeName == null ? "" : takeName;
        StringBuilder stem = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && stem.length() < MAX_STEM_LENGTH; i++) {
            char c = raw.charAt(i);
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            // Runs collapse, so a name of nothing but spaces cannot become a
            // string of separators with the stamp lost in the middle of it.
            if (safe) {
                stem.append(c);
            } else if (stem.length() > 0 && stem.charAt(stem.length() - 1) != '-') {
                stem.append('-');
            }
        }
        while (stem.length() > 0 && stem.charAt(stem.length() - 1) == '-') {
            stem.setLength(stem.length() - 1);
        }
        String name = stem.length() == 0 ? "take" : stem.toString();
        return name + "-" + STAMP.format(sentAt) + extension;
    }

    /**
     * Uploads the audio and comments on the inbox issue.
     *
     * @param releaseTag the tag of the rolling release the asset goes on
     * @param inboxIssue the standing issue the comment goes on
     * @param assetName  from {@link #assetName}
     * @param audio      the take, already encoded
     * @param report     everything but the asset's link, which is known only
     *                   once the upload has happened
     */
    public Sent send(String releaseTag, int inboxIssue, String assetName, Http.Body audio,
                     TakeReport report) throws IOException {
        if (token == null || token.trim().isEmpty()) {
            throw new Failure(0, "no GitHub token — paste one in the token screen");
        }
        String uploadUrl = uploadUrlFor(releaseTag);
        String assetUrl = uploadAsset(uploadUrl, assetName, audio);
        String commentUrl = comment(inboxIssue, report.body(assetName, assetUrl));
        return new Sent(assetUrl, commentUrl);
    }

    /** The release's {@code upload_url}, with its {@code {?name,label}} template cut off. */
    private String uploadUrlFor(String releaseTag) throws IOException {
        String url = API + "/repos/" + owner + "/" + repo + "/releases/tags/" + releaseTag;
        Http.Response response = http.send(new Http.Request("GET", url, headers(), null));
        if (!response.isSuccess()) {
            throw failure(response, "looking up the " + releaseTag + " release");
        }
        String template = text(response, "upload_url", "the " + releaseTag + " release");
        int brace = template.indexOf('{');
        return brace < 0 ? template : template.substring(0, brace);
    }

    /** Puts the bytes on the release and returns the asset's public link. */
    private String uploadAsset(String uploadUrl, String assetName, Http.Body audio)
            throws IOException {
        String url = uploadUrl + "?name=" + encode(assetName);
        Http.Response response = http.send(new Http.Request("POST", url, headers(), audio));
        if (!response.isSuccess()) {
            throw failure(response, "uploading " + assetName);
        }
        return text(response, "browser_download_url", "the uploaded asset");
    }

    /** Posts the body on the inbox issue and returns the comment's link. */
    private String comment(int inboxIssue, String markdown) throws IOException {
        String url = API + "/repos/" + owner + "/" + repo + "/issues/" + inboxIssue + "/comments";
        ObjectNode payload = JSON.createObjectNode();
        payload.put("body", markdown);
        Http.Body body;
        try {
            body = Http.Body.json(JSON.writeValueAsString(payload));
        } catch (RuntimeException e) {
            throw new Failure(0, "could not build the comment: " + e);
        }
        Http.Response response = http.send(new Http.Request("POST", url, headers(), body));
        if (!response.isSuccess()) {
            throw failure(response, "commenting on issue #" + inboxIssue);
        }
        return text(response, "html_url", "the posted comment");
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token.trim());
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", API_VERSION);
        // GitHub refuses a request with no User-Agent outright.
        headers.put("User-Agent", "music-wizard-android");
        return headers;
    }

    /** Reads one string field, or says which reply did not carry it. */
    private static String text(Http.Response response, String field, String what)
            throws IOException {
        JsonNode node = parse(response.body());
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new Failure(response.status(),
                    "GitHub's description of " + what + " carried no " + field);
        }
        return value.asText();
    }

    /**
     * The sentence the screen shows for a refused request.
     *
     * <p>GitHub's own {@code message} where there is one — "Bad credentials",
     * "Not Found", "Validation Failed" — because it names the thing to fix, and
     * the status alone does not: a 404 here is a token without access to the
     * repository just as often as it is a missing release.
     */
    private static Failure failure(Http.Response response, String doing) {
        JsonNode body = parse(response.body());
        JsonNode message = body == null ? null : body.get("message");
        String said = message != null && message.isTextual() ? message.asText() : "";
        return new Failure(response.status(), said.isEmpty()
                ? "GitHub answered " + response.status() + " while " + doing
                : "GitHub answered " + response.status() + " while " + doing + ": " + said);
    }

    /** The reply as a tree, or null when it was not JSON at all. */
    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is required of every JVM and of Android.
            throw new IllegalStateException(e);
        }
    }
}
