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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import dev.olivelli.musicwizard.android.mw.MwAnalysis;
import dev.olivelli.musicwizard.android.mw.RecordingStore;
import dev.olivelli.musicwizard.android.mw.TakeBundle;
import dev.olivelli.musicwizard.core.model.Score;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Share bundle": the take and its results as one zip, handed to another app.
 *
 * <p>This is how a take and what the phone made of it travel together — to a
 * cloud drive, a cable, a chat with oneself — without the app talking to any
 * server itself. Whoever picks the zip up gets the recording, the chart, the
 * cached {@code score.json} where one could be written, the player's note when
 * one was typed, and a few lines about the take; {@code docs/phone-to-corpus.md}
 * is what they do next.
 *
 * <p>The score is read on the main thread, where {@link AnalysisJobs} keeps all
 * of its state; only the zip writing — the audio copy — happens on the worker,
 * which holds the application context rather than the screen that asked. The
 * chooser is therefore launched in its own task, and appears even when that
 * screen has gone away, so long as the app is still on screen — a rotation, or
 * backing out to the library mid-build; leaving the app altogether may see the
 * start dropped as a background one. The build takes seconds on a long take,
 * and a share someone asked for must not vanish because they rotated.
 */
final class BundleShare {

    /**
     * One bundle at a time, process-wide, so backing out of a screen mid-build
     * neither abandons the work nor leaves a thread behind per visit.
     */
    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mw-bundle");
        thread.setDaemon(true);
        return thread;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** How the info file states the take's date. */
    private static final DateTimeFormatter RECORDED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /**
     * True from a tap to its chooser or its toast. Main thread only.
     *
     * <p>A second tap during a build is ignored rather than queued: it would
     * rewrite the same zip, possibly under a receiver still reading it through
     * the first chooser's grant.
     */
    private static boolean building;

    private BundleShare() {
    }

    /**
     * Builds the zip in the cache directory and opens the share sheet on it.
     *
     * <p>Main thread only. Not gated on there being an analysis: the recording
     * alone is the ground truth worth moving, and the chart is whatever the
     * phone happened to make of it.
     */
    static void share(Activity activity, File wav) {
        if (building) {
            return;
        }
        Context application = activity.getApplicationContext();
        RecordingStore.Recording recording = new RecordingStore.Recording(wav);
        String take = recording.displayName();

        // Same order the result screen reads them in: the last finished run
        // first, the file beside the audio second. The disk file rides along
        // only when it holds the same analysis the chart was made from — a
        // failed re-analysis leaves the previous run's cache behind, and the
        // result screen refuses to show that as though it were this run's; the
        // bundle must not ship it either.
        Score score;
        File scoreJson = null;
        AnalysisJobs.Result last = AnalysisJobs.get().lastResult(wav);
        if (last != null) {
            score = last.score;
            if (score != null && last.cacheNote == null) {
                scoreJson = MwAnalysis.scoreFileFor(wav);
            }
        } else {
            score = MwAnalysis.readCache(MwAnalysis.scoreFileFor(wav));
            if (score != null) {
                scoreJson = MwAnalysis.scoreFileFor(wav);
            }
        }
        String chart = score == null ? null : MwAnalysis.chartText(score);
        // The file is authoritative: the result screen shares only after a
        // successful flush of its field into it.
        String typed = RecordingStore.readNotes(recording);
        String notes = typed.trim().isEmpty() ? null : typed;
        String info = infoText(application, take, recording.durationSeconds(),
                wav.lastModified(), score);

        File directory = new File(application.getCacheDir(), "bundles");
        File zip = new File(directory, take + ".zip");
        File scoreFile = scoreJson;
        building = true;
        Toast.makeText(application, R.string.bundle_building, Toast.LENGTH_SHORT).show();
        BUILDER.execute(() -> {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("could not create " + directory);
                }
                prune(directory);
                TakeBundle.write(zip, take, wav, scoreFile, chart, notes, info);
            } catch (Throwable t) {
                // Throwable for the same reason as AnalysisJobs.run: an
                // Exception-only catch lets an Error vanish into the executor's
                // Future, where nothing reads it — no chooser, no toast, and
                // Share dead until the process dies.
                MAIN.post(() -> {
                    building = false;
                    Toast.makeText(application,
                            application.getString(R.string.bundle_failed, reasonOf(t)),
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            MAIN.post(() -> {
                building = false;
                send(application, take, zip);
            });
        });
    }

    /**
     * Earlier shares' bundles, deleted before this one is written.
     *
     * <p>This is the only pruning there is: each zip holds a copy of the whole
     * take, and leaving one per name would grow the cache by megabytes per
     * share until the OS reclaimed it. Deleting under a receiver that already
     * has the file open is safe — it lives on without its name; one that kept
     * the URI to read later loses it, as it would to any clearing of the cache.
     */
    private static void prune(File directory) {
        File[] old = directory.listFiles();
        if (old != null) {
            for (File file : old) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static void send(Context application, String take, File zip) {
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(
                    application, application.getPackageName() + ".files", zip);
        } catch (IllegalArgumentException e) {
            // The provider's declared paths and this class's directory disagree,
            // which is a build-time mistake rather than a user's.
            Toast.makeText(application, "this bundle cannot be shared: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, take + ".zip");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send,
                application.getString(R.string.share_bundle));
        // Launched from the application context, so it needs a task of its own.
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        application.startActivity(chooser);
    }

    /** A few lines for whoever finds the zip later: what this is, and what read it. */
    private static String infoText(Context application, String take, double durationSeconds,
                                   long recordedMillis, Score score) {
        StringBuilder out = new StringBuilder();
        out.append(take).append("  ·  ")
                .append(RecordingStore.formatDuration(durationSeconds)).append('\n');
        if (recordedMillis > 0) {
            out.append("recorded ").append(RECORDED.format(
                    Instant.ofEpochMilli(recordedMillis).atZone(ZoneId.systemDefault())))
                    .append('\n');
        }
        out.append(score == null
                ? "not analyzed on the phone"
                : MwAnalysis.summary(score)).append('\n');
        out.append("Music Wizard ").append(appVersion(application))
                .append(" on Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.MODEL).append('\n');
        return out.toString();
    }

    /** Why a build failed, never empty: a toast with no reason is the one thing worse than none. */
    private static String reasonOf(Throwable t) {
        return t.getMessage() == null || t.getMessage().isEmpty()
                ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String appVersion(Context application) {
        try {
            String name = application.getPackageManager()
                    .getPackageInfo(application.getPackageName(), 0).versionName;
            return name == null ? "unknown" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
}
