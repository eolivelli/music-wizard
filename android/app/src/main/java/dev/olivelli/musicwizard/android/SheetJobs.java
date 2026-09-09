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
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Engraves a score's PDF off the main thread, one request after another. */
final class SheetJobs {

    interface PdfListener {
        /**
         * @param omitted why the playable part is not in it; null when it is,
         *                or was not asked for
         */
        void onPdf(File pdf, String omitted);

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
            instance = new SheetJobs(main::post);
        }
        return instance;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mw-sheet");
        thread.setDaemon(true);
        return thread;
    });
    private final Dispatcher dispatcher;

    SheetJobs(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * Writes the playable part when asked for and heard, else the chart, as a
     * PDF at {@code target}.
     *
     * @param context the application's, for the music font; null on a JVM
     */
    void pdf(Context context, Score score, boolean playable, File target, PdfListener listener) {
        worker.execute(() -> {
            String failure = null;
            String omitted = null;
            try {
                if (context != null) {
                    SheetRenderer.initialize(context);
                }
                omitted = SheetPdf.write(score, playable, target);
            } catch (Throwable t) {
                failure = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            String why = failure;
            String left = omitted;
            dispatcher.post(() -> {
                if (why == null) {
                    listener.onPdf(target, left);
                } else {
                    listener.onPdfFailed(why);
                }
            });
        });
    }
}
