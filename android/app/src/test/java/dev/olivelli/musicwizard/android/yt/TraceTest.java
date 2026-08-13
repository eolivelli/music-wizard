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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * That the log says what happened, which is the half of the feature the
 * scrubber's own tests do not touch.
 *
 * <p>A scrubber that redacts perfectly and a fetch that reports nothing produce
 * an empty panel and a user who still cannot say what went wrong. These assert
 * the lines exist and carry the facts a report needs: which stage, which range,
 * which status, how many attempts.
 */
public class TraceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String SHARE = "https://youtu.be/dQw4w9WgXcQ";
    private static final int FIXTURE_BYTES = 3_449_447;

    private final List<String> lines = new ArrayList<>();
    private final Trace trace = lines::add;

    private String log() {
        return String.join("\n", lines);
    }

    private static void queueDownload(FakeHttp http, int total, int status) {
        int chunk = StreamDownload.CHUNK_BYTES;
        for (int sent = 0; sent < total; sent += chunk) {
            http.content(status, Map.of(), new byte[Math.min(chunk, total - sent)]);
        }
    }

    /** The shape a working fetch leaves behind, so a failing one can be read against it. */
    @Test
    public void aSuccessfulFetchSaysWhatItResolvedAndWhatItTook() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        queueDownload(http, FIXTURE_BYTES, 206);

        new Fetch(http, trace).run(SHARE, folder.newFolder("ok"), (d, t) -> { }, () -> false);

        assertTrue(log(), log().contains("player call 1 for dQw4w9WgXcQ"));
        assertTrue(log(), log().contains("playability OK"));
        // Every format offered, so a report shows what was available and not
        // only what was taken.
        assertTrue(log(), log().contains("offered itag 139"));
        assertTrue(log(), log().contains("offered itag 251"));
        assertTrue(log(), log().contains("chose itag 140"));
        assertTrue(log(), log().contains("range 0-1048575 -> HTTP 206"));
        assertTrue(log(), log().contains("fetched " + FIXTURE_BYTES + " bytes"));
    }

    /**
     * The case the panel was built for: a media host refusing this phone.
     *
     * <p>The status, the offset and the attempt count are exactly what the
     * one-sentence failure could not say, and what separates a rate limit from a
     * link that has gone bad.
     */
    @Test
    public void arefusedDownloadSaysWhichRangeAndWhichStatus() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        for (int i = 0; i < 3; i++) {
            http.content(403, Map.of(), new byte[0]);
        }
        http.reply(200, FakeHttp.fixture("player-ok.json"));
        for (int i = 0; i < 3; i++) {
            http.content(403, Map.of(), new byte[0]);
        }

        assertThrows(ExtractionException.class, () -> new Fetch(
                new InnerTube(http, trace), new StreamDownload(http, trace, 0, 0), trace)
                .run(SHARE, folder.newFolder("refused"), (d, t) -> { }, () -> false));

        assertTrue(log(), log().contains("range 0-1048575 -> HTTP 403"));
        assertTrue("the retries are what say it was not a one-off",
                log().contains("attempt 1 failed") && log().contains("attempt 2 failed"));
        assertTrue("and that a second resolve was tried",
                log().contains("resolving again"));
        assertTrue(log(), log().contains("player call 1 for dQw4w9WgXcQ"));
    }

    /** A refusal at the player stage reads differently from one at the media stage. */
    @Test
    public void aRefusalAtTheResolveIsDistinguishableFromOneAtTheDownload() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(200, FakeHttp.fixture("player-login-required.json"))
                .reply(200, FakeHttp.fixture("player-login-required.json"));

        assertThrows(ExtractionException.class,
                () -> new InnerTube(http, trace).resolve("dQw4w9WgXcQ"));

        assertTrue(log(), log().contains("playability LOGIN_REQUIRED"));
        assertTrue("the session state is what says whether the bootstrap ran",
                log().contains("with no session") && log().contains("with a session"));
        assertFalse("nothing was downloaded, so nothing should claim to be",
                log().contains("range "));
    }

    /** A redirect is followed and said so, since a dropped Range shows up here. */
    @Test
    public void aRedirectIsReported() throws Exception {
        FakeHttp http = new FakeHttp()
                .content(302, Map.of("Location", "https://other.example.invalid/v"), new byte[0])
                .content(206, Map.of(), new byte[1024]);

        new StreamDownload(http, trace, 0, 0).to(folder.newFile("r.m4a"),
                new AudioStream(140, "audio/mp4", "https://media.example.invalid/v",
                        130_677, 1024, 44_100, 2, 213_000),
                (d, t) -> { }, () -> false);

        assertTrue(log(), log().contains("-> HTTP 302"));
        assertTrue(log(), log().contains("redirected to other.example.invalid"));
        assertTrue("the retried hop is numbered so a loop is visible",
                log().contains("(hop 1)"));
    }

    /** Both halves are wired: a Fetch built from an Http must trace both stages. */
    @Test
    public void oneTraceReachesTheResolveAndTheDownload() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        queueDownload(http, FIXTURE_BYTES, 206);

        new Fetch(http, trace).run(SHARE, folder.newFolder("both"), (d, t) -> { }, () -> false);

        assertTrue("the resolve did not reach the trace", log().contains("player call"));
        assertTrue("the download did not reach the trace", log().contains("range 0-"));
    }

    /** The default says nothing, so nothing else in the app pays for this. */
    @Test
    public void theDefaultTraceIsSilent() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        queueDownload(http, FIXTURE_BYTES, 206);

        new Fetch(http).run(SHARE, folder.newFolder("quiet"), (d, t) -> { }, () -> false);

        assertEquals(0, lines.size());
    }

    /** No line this package composes carries a URL; the host is named instead. */
    @Test
    public void theLinesNameTheHostRatherThanTheUrl() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        queueDownload(http, FIXTURE_BYTES, 206);

        new Fetch(http, trace).run(SHARE, folder.newFolder("hosts"), (d, t) -> { }, () -> false);

        for (String line : lines) {
            assertFalse("a composed line carried a URL: " + line,
                    line.contains("://") || line.contains("videoplayback"));
        }
    }
}
