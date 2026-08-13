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

package dev.olivelli.musicwizard.android.mw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * The log exists to be pasted into a chat, so what it must never carry is the
 * thing worth testing hardest.
 */
public class ImportLogTest {

    /**
     * A real media URL, of the shape the fixtures were captured from.
     *
     * <p>The address in it is deliberately not anyone's: what matters is that
     * the pattern that would catch a real one catches this.
     */
    private static final String MEDIA_URL =
            "https://rr1---sn-uxaxpu5ap5-ca9l.googlevideo.com/videoplayback"
                    + "?expire=1786637333&ei=tZd9atrrIrKCi9oP&ip=203.0.113.47&id=o-AOiCIyMOm"
                    + "&itag=140&source=youtube&spc=4Y_hyk8&vprv=1&sig=AJfQdSswRQIgY"
                    + "&lsig=ACJ0pHgwRQIhAP&bui=AZFlqhNi9eIYcD3&mime=audio%2Fmp4";

    /** The one that would matter most, and the one a deny-list is likeliest to miss. */
    @Test
    public void thePhonesAddressNeverSurvives() {
        String scrubbed = ImportLog.scrub("range 0-1048575 failed: " + MEDIA_URL);

        assertFalse(scrubbed, scrubbed.contains("203.0.113.47"));
        assertFalse("a bare address anywhere in a line, not only in a parameter",
                ImportLog.scrub("connect failed to 198.51.100.9").contains("198.51.100.9"));
        assertFalse("IPv6 too",
                ImportLog.scrub("connect failed to 2001:0db8:85a3:0000:8a2e:0370:7334")
                        .contains("2001:0db8:85a3:0000:8a2e:0370:7334"));
    }

    @Test
    public void theSessionAndTheSignaturesNeverSurvive() {
        String scrubbed = ImportLog.scrub(MEDIA_URL);

        for (String secret : new String[] {
                "AJfQdSswRQIgY", "ACJ0pHgwRQIhAP", "4Y_hyk8", "AZFlqhNi9eIYcD3",
                "tZd9atrrIrKCi9oP", "o-AOiCIyMOm"}) {
            assertFalse(secret + " survived: " + scrubbed, scrubbed.contains(secret));
        }
    }

    /**
     * The serving host is per-session, so it goes too — written bare as well as
     * inside a URL.
     *
     * <p>The bare form is the one that matters and the one an earlier version
     * missed: {@code StreamDownload} logs the host on its own, deliberately, so
     * a pattern that required a scheme passed this test against a URL while the
     * real log carried the edge name in full. Both shapes are asserted now
     * because only one of them actually occurs.
     */
    @Test
    public void theServingHostIsGeneralisedWithOrWithoutAScheme() {
        String inUrl = ImportLog.scrub(MEDIA_URL);
        assertFalse(inUrl, inUrl.contains("sn-uxaxpu5ap5-ca9l"));
        assertTrue(inUrl, inUrl.contains("googlevideo.invalid"));

        String bare = ImportLog.scrub(
                "range 0-1048575 -> HTTP 206 from rr1---sn-uxaxpu5ap5-ca9l.googlevideo.com");
        assertFalse(bare, bare.contains("sn-uxaxpu5ap5-ca9l"));
        assertFalse(bare, bare.contains("rr1"));
        assertTrue(bare, bare.contains("googlevideo.invalid"));
        // Still says what it was and what happened.
        assertTrue(bare, bare.contains("HTTP 206"));
        assertTrue(bare, bare.contains("0-1048575"));
    }

    /** What is left has to still be worth reading, or the panel is pointless. */
    @Test
    public void whatDecidesTheDiagnosisIsKept() {
        String scrubbed = ImportLog.scrub(
                "range 1048576-2097151 -> HTTP 403 from rr1---sn-x.googlevideo.com (hop 1)");

        assertTrue(scrubbed, scrubbed.contains("1048576-2097151"));
        assertTrue(scrubbed, scrubbed.contains("HTTP 403"));
        assertTrue(scrubbed, scrubbed.contains("hop 1"));
    }

    /** Nothing reaches the buffer without going through the scrubber. */
    @Test
    public void scrubbingIsNotSomethingACallerCanForget() {
        ImportLog log = new ImportLog();
        log.add("fetching " + MEDIA_URL);

        assertFalse(log.text(), log.text().contains("203.0.113.47"));
        assertFalse(log.text(), log.text().contains("AJfQdSswRQIgY"));
    }

    /** A long fetch is hundreds of ranges; the panel must stay readable. */
    @Test
    public void theLogIsBoundedAndSaysWhatItDropped() {
        ImportLog log = new ImportLog();
        for (int i = 0; i < 1_000; i++) {
            log.add("range " + i);
        }
        String text = log.text();

        assertTrue(text, text.contains("earlier lines dropped"));
        assertTrue("the tail is what matters when something failed at the end",
                text.contains("range 999"));
        assertFalse("the head is what gets dropped", text.contains("range 0\n"));
        assertTrue("bounded", text.split("\n").length < 500);
    }

    @Test
    public void anEmptyLogIsEmptyAndClearsBackToEmpty() {
        ImportLog log = new ImportLog();
        assertTrue(log.isEmpty());
        assertEquals("", log.text());

        log.add("something");
        assertFalse(log.isEmpty());

        log.clear();
        assertTrue(log.isEmpty());
        assertEquals("", log.text());
    }

    @Test
    public void aNullLineDoesNotBreakIt() {
        ImportLog log = new ImportLog();
        log.add(null);
        assertEquals("\n", log.text());
    }

    /**
     * The worker writes while the screen reads, so this must not throw or lose
     * lines — the shape that made an earlier test in this package flaky.
     */
    @Test
    public void theWorkerAndTheScreenCanUseItAtOnce() throws Exception {
        ImportLog log = new ImportLog();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Runnable writer = () -> {
            await(start);
            for (int i = 0; i < 2_000; i++) {
                log.add("line " + i);
            }
            done.countDown();
        };
        Runnable reader = () -> {
            await(start);
            for (int i = 0; i < 2_000; i++) {
                log.text();
            }
            done.countDown();
        };

        new Thread(writer, "writer").start();
        new Thread(reader, "reader").start();
        start.countDown();

        assertTrue("a reader and a writer deadlocked or died",
                done.await(30, TimeUnit.SECONDS));
        assertTrue(log.text().contains("line 1999"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
