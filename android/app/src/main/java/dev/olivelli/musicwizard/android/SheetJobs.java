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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.olivelli.musicwizard.android.mw.SheetRenderer;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.notation.MusicXmlExport;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Engraves a score's chart off the main thread, one request at a time; a
 * request made while another is queued supersedes it, so only the latest
 * reaches its listener.
 */
final class SheetJobs {

    interface Listener {
        void onSheet(List<SheetRenderer.Partial> systems);

        void onSheetFailed(String why);
    }

    interface PdfListener {
        void onPdf(File pdf);

        void onPdfFailed(String why);
    }

    /** Where callbacks land. */
    interface Dispatcher {
        void post(Runnable action);
    }

    private static SheetJobs instance;

    static synchronized SheetJobs get() {
        if (instance == null) {
            Handler main = new Handler(Looper.getMainLooper());
            instance = new SheetJobs(SheetRenderer.ENGINE_ANDROID, main::post);
        }
        return instance;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mw-sheet");
        thread.setDaemon(true);
        return thread;
    });
    private final String engine;
    private final Dispatcher dispatcher;
    private final AtomicInteger latest = new AtomicInteger();

    SheetJobs(String engine, Dispatcher dispatcher) {
        this.engine = engine;
        this.dispatcher = dispatcher;
    }

    /** Drops whatever is queued or running: its result reaches no listener. */
    void cancel() {
        latest.incrementAndGet();
    }

    /**
     * @param context the application's, for the music font; null on a JVM
     * @param widthPx the width the systems are laid out to
     * @param scale   how large the engraving is drawn
     */
    void render(Context context, Score score, int widthPx, double scale, Listener listener) {
        int request = latest.incrementAndGet();
        worker.execute(() -> {
            if (request != latest.get()) {
                return;
            }
            String failure;
            List<SheetRenderer.Partial> systems = List.of();
            try {
                if (context != null) {
                    SheetRenderer.initialize(context);
                }
                byte[] musicXml = MusicXmlExport.chordChart(score)
                        .getBytes(StandardCharsets.UTF_8);
                SheetRenderer.Result result =
                        SheetRenderer.render(musicXml, engine, widthPx, scale);
                failure = result.failure();
                if (failure == null) {
                    systems = result.partials().stream()
                            .filter(SheetRenderer.Partial::isSystem).toList();
                    if (systems.isEmpty()) {
                        failure = "the engraver drew no system";
                    }
                }
            } catch (IllegalArgumentException e) {
                failure = e.getMessage() == null ? e.toString() : e.getMessage();
            } catch (Throwable t) {
                failure = t.toString();
            }
            String why = failure;
            List<SheetRenderer.Partial> drawn = systems;
            dispatcher.post(() -> {
                if (request != latest.get()) {
                    return;
                }
                if (why == null) {
                    listener.onSheet(drawn);
                } else {
                    listener.onSheetFailed(why);
                }
            });
        });
    }

    /** Writes the chart as a PDF at {@code target}; not superseded by later sheet requests. */
    void pdf(Context context, Score score, File target, PdfListener listener) {
        worker.execute(() -> {
            String failure = null;
            try {
                SheetRenderer.initialize(context);
                SheetPdf.write(score, target);
            } catch (Throwable t) {
                failure = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            String why = failure;
            dispatcher.post(() -> {
                if (why == null) {
                    listener.onPdf(target);
                } else {
                    listener.onPdfFailed(why);
                }
            });
        });
    }
}
