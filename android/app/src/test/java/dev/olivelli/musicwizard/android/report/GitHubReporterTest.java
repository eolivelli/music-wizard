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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The three requests, and what each answer is read for.
 *
 * <p>A wrong URL or a missing header comes back from GitHub as a status that
 * looks like something else entirely — a 404 for a request the token could not
 * make reads exactly like a release that is not there — so the requests
 * themselves are what is asserted here.
 */
public class GitHubReporterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String UPLOAD_URL =
            "https://uploads.github.com/repos/o/r/releases/42/assets";
    private static final String ASSET_URL =
            "https://github.com/o/r/releases/download/field-takes/take.flac";
    private static final String COMMENT_URL = "https://github.com/o/r/issues/7#issuecomment-1";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Records what was asked and answers from a script. */
    private static final class FakeHttp implements Http {

        final List<Request> sent = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        final Deque<Response> replies = new ArrayDeque<>();

        FakeHttp(Response... scripted) {
            for (Response reply : scripted) {
                replies.add(reply);
            }
        }

        @Override
        public Response send(Request request) throws IOException {
            sent.add(request);
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            if (request.body() != null) {
                request.body().writeTo(captured);
            }
            bodies.add(captured.toByteArray());
            if (replies.isEmpty()) {
                throw new AssertionError("unscripted request: " + request.url());
            }
            return replies.removeFirst();
        }
    }

    private static Http.Response ok(String body) {
        return new Http.Response(200, body);
    }

    private static TakeReport report() {
        return new TakeReport("take", 12, "G C D", null, "0.1", "Android 15");
    }

    private File takeOf(byte[] audio) throws IOException {
        File file = folder.newFile("take.flac");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(audio);
        }
        return file;
    }

    /** The whole send: look the release up by tag, upload, comment. */
    @Test
    public void theThreeRequestsAreAddressedAndAuthenticated() throws IOException {
        byte[] audio = {1, 2, 3, 4, 5};
        FakeHttp http = new FakeHttp(
                ok("{\"upload_url\":\"" + UPLOAD_URL + "{?name,label}\"}"),
                new Http.Response(201, "{\"browser_download_url\":\"" + ASSET_URL + "\"}"),
                new Http.Response(201, "{\"html_url\":\"" + COMMENT_URL + "\"}"));

        GitHubReporter.Sent sent = new GitHubReporter(http, "o", "r", "tok")
                .send("field-takes", 7, "take.flac",
                        Http.Body.file(takeOf(audio), "audio/flac"), report());

        assertEquals(ASSET_URL, sent.assetUrl());
        assertEquals(COMMENT_URL, sent.commentUrl());
        assertEquals(3, http.sent.size());

        Http.Request lookup = http.sent.get(0);
        assertEquals("GET", lookup.method());
        assertEquals("https://api.github.com/repos/o/r/releases/tags/field-takes", lookup.url());

        // The template is cut off, or the asset is named "{?name,label}?name=…".
        Http.Request upload = http.sent.get(1);
        assertEquals("POST", upload.method());
        assertEquals(UPLOAD_URL + "?name=take.flac", upload.url());
        assertEquals("audio/flac", upload.body().contentType());
        assertEquals(audio.length, upload.body().length());
        assertArrayEqualsBytes(audio, http.bodies.get(1));

        Http.Request comment = http.sent.get(2);
        assertEquals("POST", comment.method());
        assertEquals("https://api.github.com/repos/o/r/issues/7/comments", comment.url());
        assertTrue(comment.body().contentType().startsWith("application/json"));

        for (Http.Request request : http.sent) {
            assertEquals("Bearer tok", request.headers().get("Authorization"));
            assertEquals("application/vnd.github+json", request.headers().get("Accept"));
            assertEquals("2022-11-28", request.headers().get("X-GitHub-Api-Version"));
            // GitHub refuses a request with no User-Agent outright.
            assertNotNull(request.headers().get("User-Agent"));
        }
    }

    /**
     * The comment is JSON-encoded, not pasted into a string.
     *
     * <p>What a player types is free text and routinely holds a double quote, a
     * newline or a backslash; the chart holds newlines by construction. Hand-built
     * JSON would truncate the take's ground truth at the first one.
     */
    @Test
    public void thePlayersTextSurvivesJsonEncoding() throws IOException {
        String typed = "he said \"G\", then\na C\\D thing";
        FakeHttp http = new FakeHttp(
                ok("{\"upload_url\":\"" + UPLOAD_URL + "\"}"),
                ok("{\"browser_download_url\":\"" + ASSET_URL + "\"}"),
                ok("{\"html_url\":\"" + COMMENT_URL + "\"}"));

        TakeReport report = new TakeReport("take", 12, typed, "| G |", "0.1", "Android 15");
        new GitHubReporter(http, "o", "r", "tok").send("field-takes", 7, "take.flac",
                Http.Body.file(takeOf(new byte[] {9}), "audio/flac"), report);

        JsonNode payload = JSON.readTree(new String(http.bodies.get(2), StandardCharsets.UTF_8));
        String body = payload.get("body").asText();
        assertTrue(body, body.contains("> he said \"G\", then\n> a C\\D thing"));
        assertTrue(body, body.contains("```text\n| G |\n```"));
    }

    /** GitHub's own words reach the screen, because they name the thing to fix. */
    @Test
    public void aRefusedRequestIsReportedWithGitHubsReason() throws IOException {
        FakeHttp http = new FakeHttp(new Http.Response(401, "{\"message\":\"Bad credentials\"}"));
        try {
            new GitHubReporter(http, "o", "r", "tok").send("field-takes", 7, "take.flac",
                    Http.Body.file(takeOf(new byte[] {1}), "audio/flac"), report());
            fail("expected a 401 to be reported");
        } catch (GitHubReporter.Failure e) {
            assertEquals(401, e.status());
            assertTrue(e.getMessage(), e.getMessage().contains("Bad credentials"));
            assertTrue(e.getMessage(), e.getMessage().contains("field-takes release"));
        }
        assertEquals("nothing is uploaded once the lookup fails", 1, http.sent.size());
    }

    /** A reply that is not JSON at all still produces a sentence, not a parse error. */
    @Test
    public void anUnparseableReplyIsStillReportedByStatus() throws IOException {
        FakeHttp http = new FakeHttp(new Http.Response(502, "<html>bad gateway</html>"));
        try {
            new GitHubReporter(http, "o", "r", "tok").send("field-takes", 7, "take.flac",
                    Http.Body.file(takeOf(new byte[] {1}), "audio/flac"), report());
            fail("expected a 502 to be reported");
        } catch (GitHubReporter.Failure e) {
            assertEquals(502, e.status());
            assertTrue(e.getMessage(), e.getMessage().contains("502"));
        }
    }

    /**
     * A failed upload never leaves a comment pointing at nothing.
     *
     * <p>The comment is the corpus record; a dead link in it is worse than no
     * entry, because nothing says the audio is missing.
     */
    @Test
    public void noCommentIsPostedWhenTheUploadFails() throws IOException {
        FakeHttp http = new FakeHttp(
                ok("{\"upload_url\":\"" + UPLOAD_URL + "\"}"),
                new Http.Response(422, "{\"message\":\"Validation Failed\"}"));
        try {
            new GitHubReporter(http, "o", "r", "tok").send("field-takes", 7, "take.flac",
                    Http.Body.file(takeOf(new byte[] {1}), "audio/flac"), report());
            fail("expected the upload's 422 to be reported");
        } catch (GitHubReporter.Failure e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Validation Failed"));
        }
        assertEquals(2, http.sent.size());
    }

    /** No token means no request at all, and a sentence that says where to put one. */
    @Test
    public void sendingWithNoTokenNeverTouchesTheNetwork() throws IOException {
        FakeHttp http = new FakeHttp();
        File take = takeOf(new byte[] {1});
        for (String none : new String[] {null, "", "   "}) {
            try {
                new GitHubReporter(http, "o", "r", none).send("field-takes", 7, "take.flac",
                        Http.Body.file(take, "audio/flac"), report());
                fail("expected a missing token to be refused");
            } catch (GitHubReporter.Failure e) {
                assertEquals(0, e.status());
                assertTrue(e.getMessage(), e.getMessage().contains("token"));
            }
        }
        assertTrue(http.sent.isEmpty());
    }

    /** A reply that parses but lacks the field is a failure, not a null link. */
    @Test
    public void aReplyMissingTheFieldIsAFailure() throws IOException {
        FakeHttp http = new FakeHttp(ok("{\"id\":42}"));
        try {
            new GitHubReporter(http, "o", "r", "tok").send("field-takes", 7, "take.flac",
                    Http.Body.file(takeOf(new byte[] {1}), "audio/flac"), report());
            fail("expected a reply with no upload_url to be refused");
        } catch (GitHubReporter.Failure e) {
            assertTrue(e.getMessage(), e.getMessage().contains("upload_url"));
        }
    }

    /**
     * Asset names are unique per send and hold only what GitHub keeps verbatim.
     *
     * <p>A release refuses a second asset of the same name with a 422, and
     * re-sending a take is a thing someone will do.
     */
    @Test
    public void assetNamesAreSafeAndUniquePerSend() {
        Instant sent = Instant.parse("2026-08-02T15:11:22Z");
        assertEquals("2026-08-01_21-34-05-20260802T151122Z.flac",
                GitHubReporter.assetName("2026-08-01_21-34-05", sent, ".flac"));
        // Spaces, accents and emoji are all legal in a take name.
        assertEquals("kitchen-blues-caf-20260802T151122Z.flac",
                GitHubReporter.assetName("kitchen blues (café) 🎸", sent, ".flac"));
        assertEquals("take-20260802T151122Z.wav",
                GitHubReporter.assetName("   ", sent, ".wav"));

        String longName = GitHubReporter.assetName("x".repeat(300), sent, ".flac");
        assertEquals(80 + "-20260802T151122Z.flac".length(), longName.length());
        assertFalse(longName, longName.contains("--"));

        // A second send of the same take, a second later, is a different asset.
        assertFalse(GitHubReporter.assetName("t", sent, ".flac")
                .equals(GitHubReporter.assetName("t", sent.plusSeconds(1), ".flac")));
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("byte " + i, expected[i], actual[i]);
        }
    }
}
