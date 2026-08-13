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

import java.util.List;
import org.junit.Test;

/** Which format gets fetched, and why that is a question about sample rates. */
public class AudioStreamTest {

    private static AudioStream format(int itag, String mime, int bitrate, int rate, String url) {
        return new AudioStream(itag, mime, url, bitrate, 3_000_000, rate, 2, 213_000);
    }

    private static AudioStream m4a128() {
        return format(140, "audio/mp4; codecs=\"mp4a.40.2\"", 130_677, 44_100, "https://a/140");
    }

    private static AudioStream opus160() {
        return format(251, "audio/webm; codecs=\"opus\"", 136_000, 48_000, "https://a/251");
    }

    private static AudioStream heAac50() {
        return format(139, "audio/mp4; codecs=\"mp4a.40.5\"", 50_152, 22_050, "https://a/139");
    }

    private static AudioStream opus50() {
        return format(249, "audio/webm; codecs=\"opus\"", 49_000, 48_000, "https://a/249");
    }

    /**
     * itag 140 wins, and not because AAC beats Opus — at these bitrates it does
     * not. It wins because it arrives at 44100 Hz, which decimates to the
     * analysis rate exactly, so a fetched track and a recorded one are analysed
     * by the same arithmetic rather than by similar arithmetic.
     */
    @Test
    public void the44100FormatIsPreferredEvenWhenAnotherIsLouder() {
        assertEquals(140, AudioStream.choose(List.of(opus160(), m4a128())).itag());
        assertEquals(44_100, AudioStream.choose(List.of(opus160(), m4a128())).sampleRate());
    }

    @Test
    public void opusIsTheFallbackWhenThereIsNo140() {
        assertEquals(251, AudioStream.choose(List.of(heAac50(), opus160(), opus50())).itag());
    }

    /**
     * The 50 kbps formats are last by bitrate rather than by name. They are
     * band-limited around 10 kHz, which takes energy out of exactly the partials
     * chord estimation reads.
     */
    @Test
    public void aThinFormatIsOnlyChosenWhenItIsAllThereIs() {
        assertEquals(139, AudioStream.choose(List.of(heAac50())).itag());
        assertEquals(249, AudioStream.choose(List.of(opus50())).itag());
        assertEquals(139, AudioStream.choose(List.of(heAac50(), opus50())).itag());
    }

    @Test
    public void aFormatWithNoUrlIsNotAChoice() {
        AudioStream sabr = format(140, "audio/mp4", 130_677, 44_100, null);
        assertEquals(251, AudioStream.choose(List.of(sabr, opus160())).itag());
        assertNull(AudioStream.choose(List.of(sabr)));
    }

    /** A length of zero is a format the download could not verify itself against. */
    @Test
    public void aFormatWithNoDeclaredLengthIsNotAChoice() {
        AudioStream lengthless = new AudioStream(140, "audio/mp4", "https://a/140",
                130_677, 0, 44_100, 2, 213_000);
        assertEquals(251, AudioStream.choose(List.of(lengthless, opus160())).itag());
    }

    @Test
    public void nothingFetchableIsNull() {
        assertNull(AudioStream.choose(List.of()));
        assertNull(AudioStream.choose(List.of(format(140, "audio/mp4", 1, 44_100, null))));
    }

    /** The fixture is the shape this is really up against. */
    @Test
    public void theChoiceOnARealReplyIsItag140() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, FakeHttp.fixture("player-ok.json"));
        PlayerInfo info = new InnerTube(http).resolve("dQw4w9WgXcQ");

        AudioStream chosen = AudioStream.choose(info.audio());
        assertEquals(140, chosen.itag());
        assertEquals(44_100, chosen.sampleRate());
    }
}
