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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.olivelli.musicwizard.android.mw.SheetRenderer;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class SheetJobsTest {

    private static Score chart() {
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8)
                .withChords(new ChordProgression(List.of(
                        Chord.ofSeconds(PitchSpelling.parse("A4"), ChordQuality.MINOR_SEVENTH,
                                0, 4, Confidence.CERTAIN),
                        Chord.ofSeconds(PitchSpelling.parse("D4"), ChordQuality.MAJOR,
                                4, 8, Confidence.CERTAIN)), Confidence.CERTAIN));
    }

    /** A listener that records one outcome and lets the test wait for it. */
    private static final class Outcome implements SheetJobs.Listener {

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<List<SheetRenderer.Partial>> systems = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();

        @Override
        public void onSheet(List<SheetRenderer.Partial> drawn) {
            systems.set(drawn);
            done.countDown();
        }

        @Override
        public void onSheetFailed(String why) {
            failure.set(why);
            done.countDown();
        }

        void await() throws InterruptedException {
            assertTrue("no outcome arrived", done.await(30, TimeUnit.SECONDS));
        }
    }

    @Test
    public void deliversTheSystemsOfAChart() throws InterruptedException {
        SheetJobs jobs = new SheetJobs(SheetRenderer.ENGINE_SVG, Runnable::run);
        Outcome outcome = new Outcome();

        jobs.render(null, chart(), 1200, 1, outcome);
        outcome.await();

        assertNull(outcome.failure.get());
        assertEquals(1, outcome.systems.get().size());
        assertTrue(String.valueOf(outcome.systems.get().get(0).result()).contains("Am7"));
    }

    /** The JVM has no music font to load, so the PDF path fails there; the failure must arrive. */
    @Test
    public void aPdfThatCannotBeMadeReportsWhyAndNeverAFile() throws InterruptedException {
        SheetJobs jobs = new SheetJobs(SheetRenderer.ENGINE_SVG, Runnable::run);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<java.io.File> file = new AtomicReference<>();
        jobs.pdf(null, chart(), true, new java.io.File("unused.pdf"), new SheetJobs.PdfListener() {
            @Override
            public void onPdf(java.io.File pdf, String omitted) {
                file.set(pdf);
                done.countDown();
            }

            @Override
            public void onPdfFailed(String why) {
                failure.set(why);
                done.countDown();
            }
        });
        assertTrue("no outcome arrived", done.await(30, TimeUnit.SECONDS));

        assertNull(file.get());
        assertNotNull(failure.get());
        assertTrue(failure.get(), !failure.get().isEmpty());
    }

    @Test
    public void saysWhyAScoreWithoutChordsHasNoSheet() throws InterruptedException {
        SheetJobs jobs = new SheetJobs(SheetRenderer.ENGINE_SVG, Runnable::run);
        Outcome outcome = new Outcome();

        jobs.render(null, Score.empty(TempoMap.constant(120), 8), 1200, 1, outcome);
        outcome.await();

        assertNotNull(outcome.failure.get());
        assertNull(outcome.systems.get());
    }

    @Test
    public void aResultOvertakenBeforeItIsDeliveredIsDropped() throws InterruptedException {
        List<Runnable> queue = Collections.synchronizedList(new ArrayList<>());
        SheetJobs jobs = new SheetJobs(SheetRenderer.ENGINE_SVG, queue::add);
        Outcome first = new Outcome();
        Outcome second = new Outcome();

        jobs.render(null, chart(), 1200, 1, first);
        awaitQueued(queue, 1);
        // The first has rendered and posted; a newer request lands before
        // its delivery runs.
        jobs.render(null, chart(), 800, 1, second);
        queue.remove(0).run();
        assertEquals("the overtaken result must not report", 1, first.done.getCount());

        awaitQueued(queue, 1);
        queue.remove(0).run();
        assertNull(second.failure.get());
        assertEquals(1, second.systems.get().size());
    }

    private static void awaitQueued(List<Runnable> queue, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (queue.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(count, queue.size());
    }

    @Test
    public void onlyTheLatestRequestReachesItsListener() throws InterruptedException {
        SheetJobs jobs = new SheetJobs(SheetRenderer.ENGINE_SVG, Runnable::run);
        Outcome first = new Outcome();
        Outcome second = new Outcome();

        jobs.render(null, chart(), 1200, 1, first);
        jobs.render(null, chart(), 800, 1, second);
        second.await();

        assertNull(second.failure.get());
        assertEquals("the superseded request must not report", 1, first.done.getCount());
    }
}
