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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The HLS road, driven against canned playlists and segments. */
public class HlsAudioTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String MASTER_URL =
            "https://manifest.example.invalid/api/manifest/hls_variant/index.m3u8";

    private static final String HIGH_URL = "https://manifest.example.invalid/high/index.m3u8";

    /** Two audio groups; only the variant lines say which codec each one is. */
    private static final String MASTER = "#EXTM3U\n"
            + "#EXT-X-INDEPENDENT-SEGMENTS\n"
            + "#EXT-X-MEDIA:URI=\"https://manifest.example.invalid/low/index.m3u8\",TYPE=AUDIO,"
            + "GROUP-ID=\"233\",NAME=\"Default\",DEFAULT=YES,AUTOSELECT=YES\n"
            + "#EXT-X-MEDIA:URI=\"" + HIGH_URL + "\",TYPE=AUDIO,"
            + "GROUP-ID=\"234\",NAME=\"Default, with a comma\",DEFAULT=YES,AUTOSELECT=YES\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=309895,CODECS=\"avc1.4D4015,mp4a.40.5\","
            + "RESOLUTION=426x240,AUDIO=\"233\"\n"
            + "https://manifest.example.invalid/v240-low/index.m3u8\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=394378,CODECS=\"avc1.4D4015,mp4a.40.2\","
            + "RESOLUTION=426x240,AUDIO=\"234\"\n"
            + "https://manifest.example.invalid/v240/index.m3u8\n";

    /** Three segments, one of them a relative address. */
    private static final String PLAYLIST = "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-PLAYLIST-TYPE:VOD\n"
            + "#EXT-X-TARGETDURATION:7\n"
            + "#EXTINF:3.12,\n"
            + "https://media.example.invalid/seg/1\n"
            + "#EXTINF:3.12,\n"
            + "/seg/2\n"
            + "#EXTINF:1.0,\n"
            + "https://media.example.invalid/seg/3\n"
            + "#EXT-X-ENDLIST\n";

    private static final byte[] FRAMES_A = frames((byte) 1, 40);
    private static final byte[] FRAMES_B = frames((byte) 2, 30);
    private static final byte[] FRAMES_C = frames((byte) 3, 20);

    private static final java.util.function.BooleanSupplier NEVER_CANCELLED = () -> false;

    /** An ADTS sync word followed by filler. */
    private static byte[] frames(byte fill, int length) {
        byte[] out = new byte[length];
        java.util.Arrays.fill(out, fill);
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xF1;
        return out;
    }

    /** An ID3v2 tag of {@code payload} bytes, then the frames, as YouTube serves it. */
    private static byte[] segment(int payload, byte[] frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('I');
        out.write('D');
        out.write('3');
        out.write(3);
        out.write(0);
        out.write(0);
        out.write((payload >> 21) & 0x7F);
        out.write((payload >> 14) & 0x7F);
        out.write((payload >> 7) & 0x7F);
        out.write(payload & 0x7F);
        for (int i = 0; i < payload; i++) {
            out.write('P');
        }
        out.write(frames, 0, frames.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static FakeHttp playlists() {
        return new FakeHttp().content(200, Map.of(), utf8(MASTER)).content(200, Map.of(), utf8(PLAYLIST));
    }

    /** No waits between retries, which prove nothing here. */
    private static HlsAudio hls(FakeHttp http) {
        return new HlsAudio(new StreamDownload(http, 0, 0));
    }

    @Test
    public void theAacLcRenditionIsChosenAndItsSegmentsAreJoinedWithoutTheirTags()
            throws Exception {
        FakeHttp http = playlists()
                .content(200, Map.of(), segment(63, FRAMES_A))
                .content(200, Map.of(), segment(63, FRAMES_B))
                .content(200, Map.of(), segment(200, FRAMES_C));
        List<long[]> progress = new ArrayList<>();
        File target = new File(folder.getRoot(), "take.aac");

        hls(http).to(target, MASTER_URL, (done, total) -> progress.add(new long[] {done, total}),
                NEVER_CANCELLED);

        assertArrayEquals(concat(FRAMES_A, FRAMES_B, FRAMES_C), Files.readAllBytes(target.toPath()));
        assertEquals(HIGH_URL, http.requests.get(1).url());
        assertEquals("https://manifest.example.invalid/seg/2", http.requests.get(3).url());
        assertEquals(3, progress.size());
        assertEquals(3, progress.get(2)[0]);
        assertEquals(3, progress.get(2)[1]);
        assertEquals(0, http.unclosedContents());
    }

    /** A tag may declare a footer, which is ten more bytes before the frames. */
    @Test
    public void aTagWithAFooterIsCutWhole() throws Exception {
        byte[] tagged = segment(5, FRAMES_A);
        tagged[5] = 0x10;
        byte[] withFooter = concat(java.util.Arrays.copyOf(tagged, 15), new byte[10], FRAMES_A);

        assertArrayEquals(FRAMES_A, HlsAudio.adtsFrames(withFooter, 1));
    }

    @Test
    public void aSegmentThatIsNotAacIsRefusedAndNothingIsLeft() throws Exception {
        byte[] notAudio = segment(10, new byte[] {0, 0, 0, 0});
        FakeHttp http = playlists().content(200, Map.of(), notAudio);
        File target = new File(folder.getRoot(), "take.aac");

        IOException refused = assertThrows(IOException.class,
                () -> hls(http).to(target, MASTER_URL, (done, total) -> { }, NEVER_CANCELLED));

        assertTrue(refused.getMessage(), refused.getMessage().contains("not AAC"));
        assertFalse(target.exists());
    }

    @Test
    public void aRefusedSegmentIsItsOwnFailure() throws Exception {
        FakeHttp http = playlists()
                .content(200, Map.of(), segment(63, FRAMES_A))
                .content(403, Map.of(), new byte[0])
                .content(403, Map.of(), new byte[0])
                .content(403, Map.of(), new byte[0]);
        File target = new File(folder.getRoot(), "take.aac");

        assertThrows(HlsAudio.RefusedException.class,
                () -> hls(http).to(target, MASTER_URL, (done, total) -> { }, NEVER_CANCELLED));

        assertFalse(target.exists());
        assertEquals(0, http.unclosedContents());
    }

    @Test
    public void aManifestWithNoAudioRenditionIsRefused() {
        FakeHttp http = new FakeHttp().content(200, Map.of(), utf8("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n"
                + "https://manifest.example.invalid/v/index.m3u8\n"));
        File target = new File(folder.getRoot(), "take.aac");

        IOException refused = assertThrows(IOException.class,
                () -> hls(http).to(target, MASTER_URL, (done, total) -> { }, NEVER_CANCELLED));

        assertTrue(refused.getMessage(), refused.getMessage().contains("no audio"));
        assertFalse(target.exists());
    }

    @Test
    public void aMasterThatFailsToLoadIsReportedByStatus() {
        FakeHttp http = new FakeHttp()
                .content(503, Map.of(), new byte[0])
                .content(503, Map.of(), new byte[0])
                .content(503, Map.of(), new byte[0]);

        IOException failed = assertThrows(IOException.class,
                () -> hls(http).to(new File(folder.getRoot(), "take.aac"), MASTER_URL,
                        (done, total) -> { }, NEVER_CANCELLED));

        assertTrue(failed.getMessage(), failed.getMessage().contains("503"));
    }

    @Test
    public void cancellationBetweenSegmentsLeavesNothing() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeHttp http = playlists()
                .content(200, Map.of(), segment(63, FRAMES_A))
                .content(200, Map.of(), segment(63, FRAMES_B))
                .content(200, Map.of(), segment(63, FRAMES_C));
        File target = new File(folder.getRoot(), "take.aac");

        assertThrows(InterruptedIOException.class, () -> hls(http).to(target, MASTER_URL,
                (done, total) -> cancelled.set(true), cancelled::get));

        assertFalse(target.exists());
        // The master, the playlist, and the one segment fetched before the flag.
        assertEquals(3, http.requests.size());
    }

    /** The segments come from the hosts the ranges do, and fail the same ways. */
    @Test
    public void aSegmentThatFailsOnceIsFetchedAgain() throws Exception {
        FakeHttp http = playlists()
                .content(500, Map.of(), new byte[0])
                .content(200, Map.of(), segment(63, FRAMES_A))
                .content(200, Map.of(), segment(63, FRAMES_B))
                .content(200, Map.of(), segment(63, FRAMES_C));
        File target = new File(folder.getRoot(), "take.aac");

        hls(http).to(target, MASTER_URL, (done, total) -> { }, NEVER_CANCELLED);

        assertArrayEquals(concat(FRAMES_A, FRAMES_B, FRAMES_C), Files.readAllBytes(target.toPath()));
    }

    @Test
    public void aRedirectedSegmentIsFollowed() throws Exception {
        FakeHttp http = playlists()
                .content(302, Map.of("Location", "https://other.example.invalid/seg/1"), new byte[0])
                .content(200, Map.of(), segment(63, FRAMES_A))
                .content(200, Map.of(), segment(63, FRAMES_B))
                .content(200, Map.of(), segment(63, FRAMES_C));
        File target = new File(folder.getRoot(), "take.aac");

        hls(http).to(target, MASTER_URL, (done, total) -> { }, NEVER_CANCELLED);

        assertEquals("https://other.example.invalid/seg/1", http.requests.get(3).url());
        assertArrayEquals(concat(FRAMES_A, FRAMES_B, FRAMES_C), Files.readAllBytes(target.toPath()));
    }

    /** A refused manifest closes the road the same way a refused segment does. */
    @Test
    public void aRefusedManifestIsItsOwnFailureToo() {
        FakeHttp http = new FakeHttp()
                .content(403, Map.of(), new byte[0])
                .content(403, Map.of(), new byte[0])
                .content(403, Map.of(), new byte[0]);

        assertThrows(HlsAudio.RefusedException.class,
                () -> hls(http).to(new File(folder.getRoot(), "take.aac"), MASTER_URL,
                        (done, total) -> { }, NEVER_CANCELLED));
    }

    /** A group listing several tracks, as a dubbed video does, yields the one marked default. */
    @Test
    public void theDefaultTrackOfAGroupIsChosen() throws Exception {
        String dubbed = "#EXTM3U\n"
                + "#EXT-X-MEDIA:URI=\"https://manifest.example.invalid/it/index.m3u8\",TYPE=AUDIO,"
                + "GROUP-ID=\"234\",NAME=\"Italian\",LANGUAGE=\"it\",DEFAULT=YES\n"
                + "#EXT-X-MEDIA:URI=\"https://manifest.example.invalid/en/index.m3u8\",TYPE=AUDIO,"
                + "GROUP-ID=\"234\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=NO\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"avc1.4D4015,mp4a.40.2\",AUDIO=\"234\"\n"
                + "https://manifest.example.invalid/v/index.m3u8\n";
        FakeHttp http = new FakeHttp().content(200, Map.of(), utf8(dubbed))
                .content(200, Map.of(), utf8("#EXTM3U\n#EXTINF:1.0,\nhttps://media.example.invalid/s\n"))
                .content(200, Map.of(), segment(0, FRAMES_A));

        hls(http).to(new File(folder.getRoot(), "take.aac"), MASTER_URL, (done, total) -> { },
                NEVER_CANCELLED);

        assertEquals("https://manifest.example.invalid/it/index.m3u8", http.requests.get(1).url());
    }

    @Test
    public void attributesSplitOnCommasOutsideQuotesOnly() {
        Map<String, String> attributes = HlsAudio.attributes(
                "URI=\"https://a.invalid/x,y\",TYPE=AUDIO,NAME=\"One, two\",DEFAULT=YES");

        assertEquals("https://a.invalid/x,y", attributes.get("URI"));
        assertEquals("One, two", attributes.get("NAME"));
        assertEquals("YES", attributes.get("DEFAULT"));
    }
}
