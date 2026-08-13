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

import org.junit.Test;

/** The one grammar the app, the bundle and the desktop agree on. */
public class TakeSourceTest {

    private static final String URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    /**
     * The token the desktop greps for.
     *
     * <p>Pinned as a literal, the way {@code TakeBundleTest} pins {@code
     * .mwz.zip}: {@code .claude/agents/take-importer.md} and
     * {@code docs/phone-to-corpus.md} both key on this exact string to decide
     * that a take is commercial audio and may not reach the committed corpus.
     * Changing it here without changing them there loses that, silently, in the
     * direction that matters.
     */
    @Test
    public void theInfoLineCarriesTheTokenTheDesktopReads() {
        String line = TakeSource.youtube(URL, "Some Song", "2026-08-13 18:22").infoLine();
        assertTrue(line, line.startsWith("source: youtube"));
        assertTrue(line, line.contains(URL));

        assertEquals("source: microphone", TakeSource.microphone().infoLine());
    }

    @Test
    public void aYouTubeTakeIsCommercialAndAMicrophoneTakeIsNot() {
        assertTrue(TakeSource.youtube(URL, "t", "when").isCommercial());
        assertFalse(TakeSource.microphone().isCommercial());
    }

    @Test
    public void theTextRoundTrips() {
        TakeSource written = TakeSource.youtube(URL, "Rick Astley - Never Gonna Give You Up",
                "2026-08-13 18:22");
        TakeSource read = TakeSource.parse(written.toText());

        assertEquals(TakeSource.YOUTUBE, read.kind());
        assertEquals(URL, read.url());
        assertEquals("Rick Astley - Never Gonna Give You Up", read.title());
        assertTrue(read.isCommercial());
    }

    /**
     * A title is arbitrary text and the format is one field per line, so a
     * newline in it would read back as a field nobody wrote.
     */
    @Test
    public void aTitleCannotBreakTheFormat() {
        TakeSource written = TakeSource.youtube(URL,
                "Some Song\nsource: microphone", "2026-08-13 18:22");
        TakeSource read = TakeSource.parse(written.toText());

        assertEquals(TakeSource.YOUTUBE, read.kind());
        assertTrue(read.isCommercial());
        assertEquals("Some Song source: microphone", read.title());
    }

    /** A colon in a title is ordinary and must survive. */
    @Test
    public void aColonInATitleSurvives() {
        TakeSource read = TakeSource.parse(
                TakeSource.youtube(URL, "Live: at the BBC", "when").toText());
        assertEquals("Live: at the BBC", read.title());
    }

    /**
     * Unreadable text is a microphone take, never a YouTube one.
     *
     * <p>The direction matters: guessing "commercial" wrongly costs a take being
     * kept out of the corpus, and guessing the other way puts a commercial
     * recording into it.
     */
    @Test
    public void whatCannotBeReadIsNotGuessedToBeCommercial() {
        assertFalse(TakeSource.parse("").isCommercial());
        assertFalse(TakeSource.parse(null).isCommercial());
        assertFalse(TakeSource.parse("nonsense with no fields").isCommercial());
        assertFalse(TakeSource.parse("source:").isCommercial());
        assertEquals(TakeSource.MICROPHONE, TakeSource.parse("").kind());
    }

    @Test
    public void aMicrophoneTakeCarriesNoUrlOrTitle() {
        assertEquals("source: microphone\n", TakeSource.microphone().toText());
    }
}
