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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * What the share sheet hands over, and what survives being read.
 *
 * <p>This is the only place a malformed share can be exercised at all — every
 * other class in the package assumes a valid id — so the awkward inputs belong
 * here rather than anywhere downstream.
 */
public class VideoLinkTest {

    private static final String ID = "dQw4w9WgXcQ";

    @Test
    public void everyShapeAShareCanProduceYieldsTheSameId() {
        assertEquals(ID, VideoLink.videoId("https://youtu.be/" + ID));
        assertEquals(ID, VideoLink.videoId("https://www.youtube.com/watch?v=" + ID));
        assertEquals(ID, VideoLink.videoId("https://m.youtube.com/watch?v=" + ID));
        assertEquals(ID, VideoLink.videoId("https://music.youtube.com/watch?v=" + ID));
        assertEquals(ID, VideoLink.videoId("https://www.youtube.com/shorts/" + ID));
        assertEquals(ID, VideoLink.videoId("https://www.youtube.com/live/" + ID));
        assertEquals(ID, VideoLink.videoId("https://www.youtube.com/embed/" + ID));
        assertEquals(ID, VideoLink.videoId("https://www.youtube.com/v/" + ID));
        assertEquals(ID, VideoLink.videoId("https://www.youtube-nocookie.com/embed/" + ID));
        assertEquals(ID, VideoLink.videoId("http://youtube.com/watch?v=" + ID));
        assertEquals(ID, VideoLink.videoId("youtube.com/watch?v=" + ID));
    }

    /**
     * The parameters that would otherwise have to be stripped.
     *
     * <p>None of them is stripped: the id is extracted and the rest of the URL is
     * discarded whole, so a playlist parameter cannot survive into the fetch —
     * unlike a strip-list, this needs no keeping up to date.
     */
    @Test
    public void playlistAndTrackingParametersCannotSurvive() {
        assertEquals(ID, VideoLink.videoId("https://youtu.be/" + ID + "?si=AbCdEfGhIjK"));
        assertEquals(ID, VideoLink.videoId(
                "https://www.youtube.com/watch?v=" + ID + "&list=RD" + ID + "&start_radio=1"));
        assertEquals(ID, VideoLink.videoId(
                "https://www.youtube.com/watch?v=" + ID + "&list=PLabc&index=3"));
        assertEquals(ID, VideoLink.videoId(
                "https://www.youtube.com/watch?app=desktop&v=" + ID + "&feature=shared"));
        assertEquals(ID, VideoLink.videoId("https://youtu.be/" + ID + "?t=42"));
    }

    /**
     * A seek offset is dropped with the rest, and that is worth its own test:
     * carrying one into the fetch would silently transcribe part of a song.
     */
    @Test
    public void aSeekOffsetDoesNotFollowTheVideoIn() {
        String id = VideoLink.videoId("https://youtu.be/" + ID + "?t=90&si=xyz");
        assertEquals("https://www.youtube.com/watch?v=" + ID, VideoLink.watchUrl(id));
    }

    /** YouTube has put the title above the link, beside it, and nowhere. */
    @Test
    public void theLinkIsFoundInsideWhateverElseWasShared() {
        assertEquals(ID, VideoLink.videoId(
                "Rick Astley - Never Gonna Give You Up\nhttps://youtu.be/" + ID));
        assertEquals(ID, VideoLink.videoId(
                "Check this out: https://youtu.be/" + ID + " — the chorus is the bit"));
        assertEquals(ID, VideoLink.videoId("(https://youtu.be/" + ID + ")"));
        assertEquals(ID, VideoLink.videoId("  https://youtu.be/" + ID + "  "));
    }

    @Test
    public void theFirstVideoWinsWhenTheShareHoldsSeveral() {
        assertEquals(ID, VideoLink.videoId(
                "https://youtu.be/" + ID + " and https://youtu.be/9bZkp7q19f0"));
    }

    /**
     * Ids are exactly eleven characters.
     *
     * <p>Without the boundary check a twelve-character token would match on its
     * first eleven and fetch a different video, which is the kind of wrong that
     * looks like it worked.
     */
    @Test
    public void anIdIsElevenCharactersAndNotAPrefixOfSomethingLonger() {
        assertNull(VideoLink.videoId("https://youtu.be/dQw4w9WgXc"));
        assertNull(VideoLink.videoId("https://youtu.be/dQw4w9WgXcQQ"));
        assertNull(VideoLink.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQQ"));
    }

    @Test
    public void whatIsNotAVideoIsRefusedForItsOwnReason() {
        assertNull(VideoLink.videoId("https://www.youtube.com/playlist?list=PLabc"));
        assertEquals(VideoLink.Problem.PLAYLIST,
                VideoLink.problem("https://www.youtube.com/playlist?list=PLabc"));

        assertNull(VideoLink.videoId("https://www.youtube.com/@rickastley"));
        assertEquals(VideoLink.Problem.CHANNEL,
                VideoLink.problem("https://www.youtube.com/@rickastley"));
        assertEquals(VideoLink.Problem.CHANNEL,
                VideoLink.problem("https://www.youtube.com/channel/UCabc"));
        assertEquals(VideoLink.Problem.CHANNEL,
                VideoLink.problem("https://www.youtube.com/user/rickastley"));

        assertNull(VideoLink.videoId("https://vimeo.com/12345"));
        assertEquals(VideoLink.Problem.NO_LINK, VideoLink.problem("https://vimeo.com/12345"));

        assertEquals(VideoLink.Problem.NO_LINK, VideoLink.problem("just some words"));
        assertEquals(VideoLink.Problem.NO_LINK, VideoLink.problem(""));
        assertEquals(VideoLink.Problem.NO_LINK, VideoLink.problem(null));
    }

    @Test
    public void aYouTubeLinkThatNamesNoVideoSaysSo() {
        assertEquals(VideoLink.Problem.NO_VIDEO_ID,
                VideoLink.problem("https://www.youtube.com/"));
        assertEquals(VideoLink.Problem.NO_VIDEO_ID,
                VideoLink.problem("https://www.youtube.com/feed/subscriptions"));
    }

    @Test
    public void nullAndEmptyAreNotVideos() {
        assertNull(VideoLink.videoId(null));
        assertNull(VideoLink.videoId(""));
    }

    /**
     * A host that merely contains "youtube.com" is not YouTube.
     *
     * <p>Without a boundary the engine is free to start matching inside somebody
     * else's domain, and a share of a link to another site silently becomes a
     * fetch.
     */
    @Test
    public void aHostThatOnlyContainsTheNameIsNotYouTube() {
        assertNull(VideoLink.videoId("https://notyoutube.com/watch?v=" + ID));
        assertNull(VideoLink.videoId("https://phish-youtube.com/watch?v=" + ID));
        assertNull(VideoLink.videoId("https://myyoutu.be/" + ID));
        assertNull(VideoLink.videoId("xyoutube.com/watch?v=" + ID));
        assertNull(VideoLink.videoId("https://youtube.com.evil.test/watch?v=" + ID));

        // A real subdomain still is.
        assertEquals(ID, VideoLink.videoId("https://www.music.youtube.com/watch?v=" + ID));
    }

    /**
     * A pathological share must not hang the screen that parses it.
     *
     * <p>The input that matters is one long unbroken run of host-legal
     * characters, because that is what lets the scan restart at every offset.
     * Prose full of spaces does not exercise it at all: each word bounds the
     * work, whatever the parser does.
     */
    @Test(timeout = 10_000)
    public void anUnbrokenRunOfCharactersDoesNotStallTheParser() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400_000; i++) {
            text.append('a');
        }
        assertNull(VideoLink.videoId(text.toString()));

        StringBuilder dotted = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            dotted.append("a.");
        }
        assertNull(VideoLink.videoId(dotted.toString()));

        text.append("https://youtu.be/").append(ID);
        assertEquals(ID, VideoLink.videoId(text.toString()));
    }

    @Test
    public void theCanonicalUrlIsWhatGetsShownAndRecorded() {
        assertEquals("https://www.youtube.com/watch?v=" + ID, VideoLink.watchUrl(ID));
        assertThrows(IllegalArgumentException.class, () -> VideoLink.watchUrl("short"));
        assertThrows(IllegalArgumentException.class, () -> VideoLink.watchUrl(null));
    }
}
