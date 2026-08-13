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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Shared text in, one audio file out — and nothing left behind on any other path. */
public class FetchTest {

    private static final String VIDEO = "dQw4w9WgXcQ";
    private static final String SHARE = "https://youtu.be/" + VIDEO + "?si=abc";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** What itag 140 of the fixture video declares. */
    private static final int FIXTURE_BYTES = 3_449_447;

    private static final java.util.function.BooleanSupplier NEVER_CANCELLED = () -> false;

    private static StreamDownload.Progress ignoringProgress() {
        return (done, total) -> { };
    }

    /** The real retry waits are seconds long and prove nothing here. */
    private static Fetch fetch(FakeHttp http) {
        return new Fetch(new InnerTube(http), new StreamDownload(http, 0, 0));
    }

    /**
     * Queues the replies one download takes.
     *
     * <p>Which is more than one: the fetch is chunked into 1 MiB ranges, so the
     * fixture's 3.4 MB is four requests. Queueing a single reply here would fail
     * for a reason that has nothing to do with what each test is about.
     */
    /** Queues {@code count} rate-limit refusals, which the download retries through. */
    private static void queueRefusals(FakeHttp http, int count) {
        for (int i = 0; i < count; i++) {
            http.content(403, Map.of(), new byte[0]);
        }
    }

    private static void queueDownload(FakeHttp http, int total) {
        int chunk = 1 << 20;
        for (int sent = 0; sent < total; sent += chunk) {
            http.content(206, Map.of(), new byte[Math.min(chunk, total - sent)]);
        }
    }

    /** itag 140 is chosen, so the file is named for the container it actually is. */
    @Test
    public void aSharedLinkBecomesANamedAudioFile() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        queueDownload(http, FIXTURE_BYTES);

        File directory = folder.newFolder("fetch");
        Fetch.Fetched fetched = fetch(http)
                .run(SHARE, directory, ignoringProgress(), NEVER_CANCELLED);

        assertEquals(VIDEO + ".m4a", fetched.file().getName());
        assertTrue(fetched.file().isFile());
        assertEquals(VIDEO, fetched.videoId());
        assertTrue(fetched.title(), fetched.title().contains("Never Gonna Give You Up"));
        assertEquals("Rick Astley", fetched.author());
        assertEquals(213, fetched.lengthSeconds());
        assertEquals("https://www.youtube.com/watch?v=" + VIDEO, fetched.url());

        // The half-written name must not survive alongside the finished one.
        assertFalse(new File(directory, VIDEO + ".part").exists());
    }

    @Test
    public void aShareWithNoVideoNeverReachesTheNetwork() throws Exception {
        FakeHttp http = new FakeHttp();
        File directory = folder.newFolder("none");

        ExtractionException refused = assertThrows(ExtractionException.class,
                () -> fetch(http).run("just some words", directory,
                        ignoringProgress(), NEVER_CANCELLED));

        assertEquals(ExtractionException.Reason.NO_VIDEO, refused.reason());
        assertTrue(http.requests.isEmpty());
    }

    /** Each shape of non-video gets its own sentence, not one shared "bad link". */
    @Test
    public void aPlaylistAndAChannelAreRefusedDifferently() throws Exception {
        File directory = folder.newFolder("shapes");

        ExtractionException playlist = assertThrows(ExtractionException.class,
                () -> fetch(new FakeHttp()).run(
                        "https://www.youtube.com/playlist?list=PLabc", directory,
                        ignoringProgress(), NEVER_CANCELLED));
        assertTrue(playlist.getMessage(), playlist.getMessage().contains("playlist"));

        ExtractionException channel = assertThrows(ExtractionException.class,
                () -> fetch(new FakeHttp()).run(
                        "https://www.youtube.com/@someone", directory,
                        ignoringProgress(), NEVER_CANCELLED));
        assertTrue(channel.getMessage(), channel.getMessage().contains("channel"));
    }

    /**
     * The cap is a memory limit downstream, not a network one, so it is applied
     * before a byte is fetched rather than after a long wait.
     */
    @Test
    public void aVideoPastTheCapIsRefusedBeforeDownloading() throws Exception {
        String tooLong = FakeHttp.fixture("player-ok.json")
                .replace("\"lengthSeconds\": \"213\"", "\"lengthSeconds\": \"7200\"");
        FakeHttp http = new FakeHttp().reply(200, tooLong);

        ExtractionException refused = assertThrows(ExtractionException.class,
                () -> fetch(http).run(SHARE, folder.newFolder("long"),
                        ignoringProgress(), NEVER_CANCELLED));

        assertEquals(ExtractionException.Reason.TOO_LONG, refused.reason());
        // The player call, and nothing else.
        assertEquals(1, http.requests.size());
    }

    /**
     * A confirmation screen left open outlives its URLs, so one expiry resolves
     * again rather than failing.
     */
    @Test
    public void anExpiredLinkIsResolvedAgainOnce() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        queueRefusals(http, 3);
        http.reply(200, FakeHttp.fixture("player-ok.json"));
        queueDownload(http, FIXTURE_BYTES);

        Fetch.Fetched fetched = fetch(http).run(SHARE, folder.newFolder("expired"),
                ignoringProgress(), NEVER_CANCELLED);

        assertTrue(fetched.file().isFile());
        // Resolve, three refusals, resolve again, then the four chunks.
        assertEquals(9, http.requests.size());
    }

    /**
     * Fresh URLs refused too is the media host rate-limiting, not a stale link,
     * and the user is told the difference because only one of them clears by
     * waiting.
     */
    @Test
    public void refusalsThatSurviveAFreshResolveAreReportedAsRateLimiting() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        queueRefusals(http, 3);
        http.reply(200, FakeHttp.fixture("player-ok.json"));
        queueRefusals(http, 3);

        File directory = folder.newFolder("twice");
        ExtractionException limited = assertThrows(ExtractionException.class,
                () -> fetch(http).run(SHARE, directory,
                        ignoringProgress(), NEVER_CANCELLED));

        assertEquals(ExtractionException.Reason.RATE_LIMITED, limited.reason());
        assertTrue(limited.getMessage(), limited.getMessage().contains("try again"));
        assertEquals(0, directory.listFiles().length);
    }

    /** A blank title would give the take an empty name downstream. */
    @Test
    public void aVideoWithNoTitleIsNamedForItsId() throws Exception {
        String untitled = FakeHttp.fixture("player-ok.json").replace(
                "\"title\": \"Rick Astley - Never Gonna Give You Up (Official Video)"
                        + " (4K Remaster)\"",
                "\"title\": \"\"");
        FakeHttp http = new FakeHttp().reply(200, untitled);
        queueDownload(http, FIXTURE_BYTES);

        Fetch.Fetched fetched = fetch(http).run(SHARE, folder.newFolder("untitled"),
                ignoringProgress(), NEVER_CANCELLED);

        assertEquals(VIDEO, fetched.title());
    }

    @Test
    public void aFailedDownloadLeavesTheDirectoryEmpty() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(200, FakeHttp.fixture("player-ok.json"))
                .content(500, Map.of(), new byte[0])
                .content(500, Map.of(), new byte[0])
                .content(500, Map.of(), new byte[0]);

        File directory = folder.newFolder("failed");
        assertThrows(java.io.IOException.class, () -> fetch(http)
                .run(SHARE, directory, ignoringProgress(), NEVER_CANCELLED));

        assertEquals(0, directory.listFiles().length);
    }
}
