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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

/**
 * The one check that would notice the day this stops working.
 *
 * <p>Every other test in this package answers a canned reply, so all of them
 * will keep passing long after YouTube has changed and the app has stopped being
 * able to fetch anything. That is not a flaw in them — a test that needs the
 * internet is not a test the build can depend on — but it does mean the suite
 * cannot tell "works" from "worked in August 2026".
 *
 * <p>So this one makes a real call, and is skipped unless asked for:
 *
 * <pre>./gradlew test -Dmw.yt.live=true</pre>
 *
 * <p>CI does not set it and must not: the build stays offline and hermetic. Run
 * it by hand before releasing anything that depends on the fetch, and when a
 * user reports that importing stopped working — it separates "YouTube changed"
 * from "this phone's network" in one call.
 *
 * <p>It is the same idea as {@code checkDexedApiLevel}'s positive control in
 * {@code build.gradle}: a check that can only ever pass is not a check.
 */
public class InnerTubeLiveTest {

    /** Old, popular, and not going anywhere. */
    private static final String STABLE_VIDEO = "dQw4w9WgXcQ";

    @Test
    public void youTubeStillServesAudioToThisClient() throws Exception {
        assumeTrue("set -Dmw.yt.live=true to run the live check",
                Boolean.getBoolean("mw.yt.live"));

        PlayerInfo info = new InnerTube(new UrlConnectionHttp()).resolve(STABLE_VIDEO);

        assertNotNull("no player info came back", info);
        assertTrue("no audio formats at all", !info.audio().isEmpty());

        AudioStream chosen = AudioStream.choose(info.audio());
        assertNotNull("every audio format came back without a URL, which is what SABR"
                + " enforcement reaching this client looks like — see"
                + " ExtractionException.Reason.SABR_ONLY", chosen);
        assertTrue("the chosen format declared no length", chosen.contentLength() > 0);

        // Not asserted, because neither is guaranteed and a failure here would be
        // read as "the approach broke" when it has not: which itag was chosen, and
        // its sample rate. Printed instead, since a human runs this.
        System.out.println("live check: " + info.title() + " — " + chosen);
    }

    /**
     * The whole fetch, because resolving is the half that is least likely to break
     * quietly.
     *
     * <p>A resolve that works proves the client is still accepted. It does not
     * prove the media URLs can be read: the throttling, the redirect that drops a
     * {@code Range} header, and the six-hour expiry all live past that point, and
     * every one of them is invisible to a fixture. This is also the only check
     * that would catch the chunking being lost — the download would still
     * succeed, just at 0.03 MB/s, so the timing is printed rather than asserted
     * (a slow network is not a bug).
     */
    @Test
    public void theWholeFetchStillWorks() throws Exception {
        assumeTrue("set -Dmw.yt.live=true to run the live check",
                Boolean.getBoolean("mw.yt.live"));

        java.io.File directory = java.nio.file.Files.createTempDirectory("mw-live").toFile();
        try {
            long started = System.nanoTime();
            Fetch.Fetched fetched = new Fetch(new UrlConnectionHttp())
                    .run("https://youtu.be/" + STABLE_VIDEO, directory,
                            (done, total) -> { }, () -> false);
            double seconds = (System.nanoTime() - started) / 1e9;

            assertTrue("the fetched file is missing", fetched.file().isFile());
            assertTrue("the fetched file is empty", fetched.file().length() > 0);
            System.out.printf("live fetch: %s, %.2f MB in %.2fs (%.2f MB/s)%n",
                    fetched.file().getName(), fetched.file().length() / 1e6, seconds,
                    fetched.file().length() / seconds / 1e6);
        } finally {
            java.io.File[] left = directory.listFiles();
            if (left != null) {
                for (java.io.File file : left) {
                    file.delete();
                }
            }
            directory.delete();
        }
    }
}
