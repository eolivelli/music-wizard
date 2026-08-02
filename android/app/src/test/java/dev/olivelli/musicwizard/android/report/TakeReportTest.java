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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** What one take reads like on the inbox issue. */
public class TakeReportTest {

    private static final String URL =
            "https://github.com/eolivelli/music-wizard/releases/download/field-takes/take.flac";

    /** Everything the corpus needs to find this take again is in the comment. */
    @Test
    public void theCommentCarriesTheLinkTheWordsAndTheChart() {
        TakeReport report = new TakeReport("kitchen blues", 107, "G C D, twice round",
                "| G | C | D | G |", "0.1", "Android 15 (API 35), Pixel 7");
        String body = report.body("take.flac", URL);

        assertTrue(body, body.contains("**kitchen blues**"));
        // From the header, not from the audio, and in the same m:ss the library
        // shows: 107 seconds is 1:47.
        assertTrue(body, body.contains("1:47"));
        assertTrue(body, body.contains("app 0.1"));
        assertTrue(body, body.contains("Android 15 (API 35), Pixel 7"));
        assertTrue(body, body.contains("[take.flac](" + URL + ")"));
        assertTrue(body, body.contains("```text\n| G | C | D | G |\n```"));
    }

    /**
     * The player's words are the one blockquoted thing, on every line of them.
     *
     * <p>A reader scrolling the inbox has to be able to tell the ground truth
     * from the phone's guess at it without reading either carefully. A second
     * line left unquoted would break out of the quote and read as the app's.
     */
    @Test
    public void everyLineOfThePlayersCommentIsQuoted() {
        TakeReport report = new TakeReport("take", 10, "verse: G C\n\nchorus: Am F",
                null, "0.1", "Android 15 (API 35), Pixel 7");
        String body = report.body("take.flac", URL);

        assertTrue(body, body.contains("> verse: G C\n> \n> chorus: Am F\n"));
    }

    /** A take can be sent without a word about it; the recording is still worth having. */
    @Test
    public void aBlankCommentSaysSoRatherThanVanishing() {
        TakeReport report = new TakeReport("take", 10, "   ", null, "0.1", "Android 15");
        String body = report.body("take.flac", URL);

        assertTrue(body, body.contains("_The player left no comment._"));
        assertFalse("a blank comment must not produce an empty quote",
                body.contains("> "));
    }

    /** Not every take has been analysed, and the comment says which case it is. */
    @Test
    public void anUnanalysedTakeSaysSoInsteadOfShowingAnEmptyChart() {
        TakeReport report = new TakeReport("take", 10, "just the riff", null, "0.1",
                "Android 15");
        String body = report.body("take.flac", URL);

        assertTrue(body, body.contains("has not analysed this take"));
        assertFalse("no fence without a chart to put in it", body.contains("```"));
    }

    /**
     * The fence closes on its own line whether or not the chart ends in one.
     *
     * <p>{@code ChordChart.toText} is under no obligation either way, and a
     * closing fence welded to the last bar of the chart is not a fence: the rest
     * of the comment renders as code.
     */
    @Test
    public void theChartFenceClosesOnItsOwnLine() {
        String withNewline = new TakeReport("t", 1, "", "| G |\n", "0.1", "A")
                .body("t.flac", URL);
        String without = new TakeReport("t", 1, "", "| G |", "0.1", "A")
                .body("t.flac", URL);

        assertTrue(withNewline, withNewline.contains("| G |\n```"));
        assertTrue(without, without.contains("| G |\n```"));
        assertFalse("the chart's own newline must not be doubled",
                withNewline.contains("| G |\n\n```"));
        assertEquals(withNewline, without);
    }
}
