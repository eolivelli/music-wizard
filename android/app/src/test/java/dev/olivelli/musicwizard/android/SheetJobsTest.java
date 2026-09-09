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

package dev.olivelli.musicwizard.android;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** The PDF job on the JVM, where the drawing cannot happen; what must arrive is the reason. */
public class SheetJobsTest {

    private static Score chart() {
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8)
                .withChords(new ChordProgression(List.of(
                        Chord.ofSeconds(PitchSpelling.parse("A4"), ChordQuality.MINOR_SEVENTH,
                                0, 4, Confidence.CERTAIN),
                        Chord.ofSeconds(PitchSpelling.parse("D4"), ChordQuality.MAJOR,
                                4, 8, Confidence.CERTAIN)), Confidence.CERTAIN));
    }

    private static final class Outcome implements SheetJobs.PdfListener {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<File> file = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();

        @Override
        public void onPdf(File pdf, String omitted) {
            file.set(pdf);
            done.countDown();
        }

        @Override
        public void onPdfFailed(String why) {
            failure.set(why);
            done.countDown();
        }

        void await() throws InterruptedException {
            assertTrue("no outcome arrived", done.await(30, TimeUnit.SECONDS));
        }
    }

    /** Nothing registers the picture engine on the JVM, so the drawing fails there, by name. */
    @Test
    public void aPdfThatCannotBeDrawnReportsWhyAndNeverAFile() throws InterruptedException {
        SheetJobs jobs = new SheetJobs(Runnable::run);
        Outcome outcome = new Outcome();

        jobs.pdf(null, chart(), true, new File("unused.pdf"), outcome);
        outcome.await();

        assertNull(outcome.file.get());
        assertNotNull(outcome.failure.get());
        assertTrue(outcome.failure.get(), outcome.failure.get().contains("not initialized"));
    }

    @Test
    public void aScoreWithoutChordsHasNoPdfAndSaysSo() throws InterruptedException {
        SheetJobs jobs = new SheetJobs(Runnable::run);
        Outcome outcome = new Outcome();

        jobs.pdf(null, Score.empty(TempoMap.constant(120), 8), false, new File("unused.pdf"),
                outcome);
        outcome.await();

        assertNull(outcome.file.get());
        assertNotNull(outcome.failure.get());
        assertTrue(outcome.failure.get(), outcome.failure.get().contains("chord"));
    }
}
