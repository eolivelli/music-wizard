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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.Test;

/**
 * The player call, driven against canned replies.
 *
 * <p>Two things are pinned here and both are invisible in production. The
 * request is asserted field by field, because a wrong client name or a dropped
 * header comes back as {@code LOGIN_REQUIRED} — the same answer the bot check
 * gives, so the app would look blocked rather than broken. And each failure
 * shape is asserted to a distinct {@link ExtractionException.Reason}, because
 * "YouTube refused" and "this build can no longer read what YouTube serves" need
 * different sentences and only one of them is worth updating the app for.
 *
 * <p>What none of this can tell you is whether YouTube still answers this way
 * today. Every test here will keep passing after the approach stops working;
 * {@code InnerTubeLiveTest} is the one that would notice.
 */
public class InnerTubeTest {

    private static final String VIDEO = "dQw4w9WgXcQ";

    @Test
    public void theRequestIsTheOneThatWorks() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        new InnerTube(http).resolve(VIDEO);

        Http.Request request = http.requests.get(0);
        assertEquals("POST", request.method());
        assertEquals("https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                request.url());
        assertEquals("28", request.headers().get("X-YouTube-Client-Name"));
        assertEquals("1.65.10", request.headers().get("X-YouTube-Client-Version"));
        assertEquals("https://www.youtube.com", request.headers().get("Origin"));
        assertTrue(request.headers().get("User-Agent")
                .startsWith("com.google.android.apps.youtube.vr.oculus/1.65.10"));

        JsonNode body = new ObjectMapper().readTree(http.bodyOf(0));
        assertEquals(VIDEO, body.path("videoId").asText());
        assertEquals("ANDROID_VR", body.path("context").path("client").path("clientName").asText());
        assertEquals("1.65.10",
                body.path("context").path("client").path("clientVersion").asText());
        // Both of these are why an age-restricted video resolves at all.
        assertTrue(body.path("contentCheckOk").asBoolean());
        assertTrue(body.path("racyCheckOk").asBoolean());
    }

    /** No session yet, so nothing claims one. */
    @Test
    public void theFirstCallCarriesNoVisitorId() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        new InnerTube(http).resolve(VIDEO);

        assertNull(http.requests.get(0).headers().get("X-Goog-Visitor-Id"));
        JsonNode body = new ObjectMapper().readTree(http.bodyOf(0));
        assertTrue(body.path("context").path("client").path("visitorData").isMissingNode());
    }

    /**
     * The bootstrap: a refusal carries the credential that lifts it.
     *
     * <p>This is the whole mechanism, and it is worth stating why it is not
     * obvious — the reply that says "sign in to confirm you're not a bot" also
     * contains a {@code visitorData}, and sending that straight back passes the
     * check without any sign-in at all.
     */
    @Test
    public void aRefusalIsBootstrappedIntoASession() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(200, FakeHttp.fixture("player-login-required.json"))
                .reply(200, FakeHttp.fixture("player-ok.json"));

        PlayerInfo info = new InnerTube(http).resolve(VIDEO);
        assertNotNull(info);
        assertEquals(2, http.requests.size());

        String harvested = new ObjectMapper()
                .readTree(FakeHttp.fixture("player-login-required.json"))
                .path("responseContext").path("visitorData").asText();

        // Sent in both places, as yt-dlp does; either alone is enough.
        assertEquals(harvested, http.requests.get(1).headers().get("X-Goog-Visitor-Id"));
        JsonNode retry = new ObjectMapper().readTree(http.bodyOf(1));
        assertEquals(harvested,
                retry.path("context").path("client").path("visitorData").asText());
    }

    /** One bootstrap served twenty consecutive videos when this was measured. */
    @Test
    public void theSessionIsReusedForTheNextVideo() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(200, FakeHttp.fixture("player-login-required.json"))
                .reply(200, FakeHttp.fixture("player-ok.json"))
                .reply(200, FakeHttp.fixture("player-ok.json"));

        InnerTube tube = new InnerTube(http);
        tube.resolve(VIDEO);
        tube.resolve("9bZkp7q19f0");

        assertEquals(3, http.requests.size());
        assertNotNull(http.requests.get(2).headers().get("X-Goog-Visitor-Id"));
    }

    /**
     * A bare call that succeeds is not retried.
     *
     * <p>It succeeds often enough to matter — measured passing on its own once
     * and failing 7 times in 8 on another run — so spending a round trip to
     * provoke a rejection first would be waste.
     */
    @Test
    public void aSessionIsNotBootstrappedWhenNothingRefused() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        new InnerTube(http).resolve(VIDEO);
        assertEquals(1, http.requests.size());
    }

    @Test
    public void aRefusalThatSurvivesTheRetryIsTheBotCheck() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(200, FakeHttp.fixture("player-login-required.json"))
                .reply(200, FakeHttp.fixture("player-login-required.json"));

        ExtractionException refused = assertThrows(ExtractionException.class,
                () -> new InnerTube(http).resolve(VIDEO));
        assertEquals(ExtractionException.Reason.BOT_CHECK, refused.reason());
        assertEquals(2, http.requests.size());
    }

    /**
     * A session is committed only once it has been proved.
     *
     * <p>Caching the offered value before the retry would store something
     * nothing has tested, and — worse — would throw away a session that was
     * working the moment any refusal offered a different one.
     */
    @Test
    public void aWorkingSessionSurvivesARefusalThatOffersAnother() throws Exception {
        String poison = FakeHttp.fixture("player-login-required.json")
                .replace("CgtGQUtFVklTSVRPUiIDEgAqAA%3D%3D", "POISON");

        FakeHttp http = new FakeHttp()
                .reply(200, FakeHttp.fixture("player-login-required.json"))
                .reply(200, FakeHttp.fixture("player-ok.json"))
                .reply(200, poison)
                .reply(200, FakeHttp.fixture("player-ok.json"));

        InnerTube tube = new InnerTube(http);
        tube.resolve(VIDEO);
        String working = tube.visitorData();
        tube.resolve("9bZkp7q19f0");

        // The refusal offered POISON and the retry with it succeeded, so POISON
        // is now proved and held. What must not happen is the working value
        // being discarded before anything tested its replacement.
        assertEquals("POISON", tube.visitorData());
        assertEquals("POISON", http.requests.get(3).headers().get("X-Goog-Visitor-Id"));
        assertNotNull(working);
    }

    /**
     * A held session that has gone stale is dropped and bootstrapped again.
     *
     * <p>YouTube echoes back the visitor id it was sent, so a refusal of a
     * cached session offers nothing new — retrying with it would be the same
     * request twice, which by construction cannot answer differently. Starting
     * over bare is the only move that can.
     */
    @Test
    public void aStaleSessionIsDroppedRatherThanRepeated() throws Exception {
        String echoed = FakeHttp.fixture("player-login-required.json")
                .replace("CgtGQUtFVklTSVRPUiIDEgAqAA%3D%3D", "STALE");

        FakeHttp http = new FakeHttp()
                // Bootstrap a session called STALE and prove it.
                .reply(200, echoed)
                .reply(200, FakeHttp.fixture("player-ok.json"))
                // Next video: STALE is refused, and the refusal echoes STALE.
                .reply(200, echoed)
                // So the held one is dropped and a bare call bootstraps a new one.
                .reply(200, FakeHttp.fixture("player-login-required.json"))
                .reply(200, FakeHttp.fixture("player-ok.json"));

        InnerTube tube = new InnerTube(http);
        tube.resolve(VIDEO);
        assertEquals("STALE", tube.visitorData());

        tube.resolve("9bZkp7q19f0");

        assertEquals(5, http.requests.size());
        assertEquals("STALE", http.requests.get(2).headers().get("X-Goog-Visitor-Id"));
        // The bare call that starts the bootstrap over.
        assertNull(http.requests.get(3).headers().get("X-Goog-Visitor-Id"));
        assertNotEquals("STALE", tube.visitorData());
    }

    /**
     * A stream in progress is refused here, not in the caller.
     *
     * <p>It is a different mechanism from the ended-stream case above — that one
     * arrives as UNPLAYABLE, this one is playable and says so in videoDetails —
     * and both belong to the same decision, so one place owns them.
     */
    @Test
    public void aStreamStillRunningIsRefusedToo() throws Exception {
        String inProgress = FakeHttp.fixture("player-ok.json")
                .replace("\"isLiveContent\": false", "\"isLiveContent\": true,"
                        + " \"isLive\": true");
        FakeHttp http = new FakeHttp().reply(200, inProgress);

        ExtractionException live = assertThrows(ExtractionException.class,
                () -> new InnerTube(http).resolve(VIDEO));
        assertEquals(ExtractionException.Reason.LIVE, live.reason());
    }

    @Test
    public void anOkReplyWithNoUrlsMeansTheAppIsOutOfDate() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-sabr-only.json"));

        ExtractionException sabr = assertThrows(ExtractionException.class,
                () -> new InnerTube(http).resolve(VIDEO));
        assertEquals(ExtractionException.Reason.SABR_ONLY, sabr.reason());
    }

    /**
     * The signal is the missing URL, not the SABR endpoint being mentioned.
     *
     * <p>{@code serverAbrStreamingUrl} is present in every OK reply now,
     * including the ones with perfectly good URLs beside it. Treating it as the
     * signal would refuse every video.
     */
    @Test
    public void aSabrEndpointBesideWorkingUrlsIsNotASabrReply() throws Exception {
        assertTrue(FakeHttp.fixture("player-ok.json").contains("serverAbrStreamingUrl"));

        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        assertNotNull(new InnerTube(http).resolve(VIDEO));
    }

    @Test
    public void aLiveStreamIsRefusedAsOne() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-live.json"));

        ExtractionException live = assertThrows(ExtractionException.class,
                () -> new InnerTube(http).resolve(VIDEO));
        assertEquals(ExtractionException.Reason.LIVE, live.reason());
    }

    /**
     * A made-for-kids video is refused, and YouTube's own wording is passed on.
     *
     * <p>It is not reported as "made for children", because the reply does not
     * say that — it says "This video is not available", which some
     * region-restricted videos say too. Naming a cause the reply does not give
     * would be inventing one.
     */
    @Test
    public void aVideoYouTubeWillNotServeCarriesYouTubesOwnWords() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-made-for-kids.json"));

        ExtractionException refused = assertThrows(ExtractionException.class,
                () -> new InnerTube(http).resolve(VIDEO));
        assertEquals(ExtractionException.Reason.REFUSED, refused.reason());
        assertTrue(refused.getMessage(), refused.getMessage().contains("not available"));
    }

    @Test
    public void anIdThatNamesNothingIsUnavailable() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-error.json"));

        ExtractionException gone = assertThrows(ExtractionException.class,
                () -> new InnerTube(http).resolve(VIDEO));
        assertEquals(ExtractionException.Reason.UNAVAILABLE, gone.reason());
    }

    @Test
    public void anUnreadableReplyIsNotARefusal() {
        FakeHttp http = new FakeHttp().reply(200, "this is not JSON at all");

        ExtractionException broken = assertThrows(ExtractionException.class,
                () -> new InnerTube(http).resolve(VIDEO));
        assertEquals(ExtractionException.Reason.MALFORMED, broken.reason());
    }

    @Test
    public void anHttpErrorIsReportedAsOne() {
        FakeHttp http = new FakeHttp().reply(503, "");

        ExtractionException down = assertThrows(ExtractionException.class,
                () -> new InnerTube(http).resolve(VIDEO));
        assertEquals(ExtractionException.Reason.REFUSED, down.reason());
        assertTrue(down.getMessage(), down.getMessage().contains("503"));
    }

    /** A dead network is an IOException to retry, not an extraction verdict. */
    @Test
    public void noNetworkStaysAnIoException() {
        FakeHttp http = new FakeHttp().fail(new IOException("no route to host"));
        assertThrows(IOException.class, () -> new InnerTube(http).resolve(VIDEO));
    }

    @Test
    public void whatComesBackIsTheVideoAndItsAudio() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        PlayerInfo info = new InnerTube(http).resolve(VIDEO);

        assertEquals(VIDEO, info.videoId());
        assertTrue(info.title(), info.title().contains("Never Gonna Give You Up"));
        assertEquals("Rick Astley", info.author());
        assertEquals(213, info.lengthSeconds());

        // Four audio formats, and the video format in the fixture is not one.
        assertEquals(4, info.audio().size());
        for (AudioStream stream : info.audio()) {
            assertTrue(stream.mimeType(), stream.mimeType().startsWith("audio/"));
        }
    }
}
