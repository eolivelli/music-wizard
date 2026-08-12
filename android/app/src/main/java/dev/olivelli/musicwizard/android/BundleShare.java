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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Share bundle": the take and its results as one zip, handed to another app.
 *
 * <p>This is how a take and what the phone made of it travel together — to a
 * cloud drive, a cable, a chat with oneself — without the app talking to any
 * server itself. Whoever picks the zip up gets the recording, the chart, the
 * cached {@code score.json} where one could be written, and a few lines saying
 * what analysed it; {@code docs/phone-to-corpus.md} is what they do next.
 *
 * <p>The score is read on the main thread, where {@link AnalysisJobs} keeps all
 * of its state; only the zip writing — the audio copy — happens on the worker.
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
        RecordingStore.Recording recording = new RecordingStore.Recording(wav);
        String take = recording.displayName();

        // Same order the result screen reads them in: the last finished run
        // first, the file beside the audio second.
        Score score;
        AnalysisJobs.Result last = AnalysisJobs.get().lastResult(wav);
        if (last != null) {
            score = last.score;
        } else {
            score = MwAnalysis.readCache(MwAnalysis.scoreFileFor(wav));
        }
        String chart = score == null ? null : MwAnalysis.chartText(score);
        String info = infoText(activity, take, recording.durationSeconds(), score);
        File scoreJson = MwAnalysis.scoreFileFor(wav);

        File directory = new File(activity.getCacheDir(), "bundles");
        File zip = new File(directory, take + ".zip");
        BUILDER.execute(() -> {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("could not create " + directory);
                }
                TakeBundle.write(zip, take, wav, scoreJson, chart, info);
            } catch (IOException | RuntimeException e) {
                MAIN.post(() -> Toast.makeText(activity,
                        activity.getString(R.string.bundle_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show());
                return;
            }
            MAIN.post(() -> send(activity, take, zip));
        });
    }

    private static void send(Activity activity, String take, File zip) {
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".files", zip);
        } catch (IllegalArgumentException e) {
            // The provider's declared paths and this class's directory disagree,
            // which is a build-time mistake rather than a user's.
            Toast.makeText(activity, activity.getString(R.string.bundle_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, take + ".zip");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(
                Intent.createChooser(send, activity.getString(R.string.share_bundle)));
    }

    /** A few lines for whoever finds the zip later: what this is, and what read it. */
    private static String infoText(Activity activity, String take, double durationSeconds,
                                   Score score) {
        StringBuilder out = new StringBuilder();
        out.append(take).append("  ·  ")
                .append(RecordingStore.formatDuration(durationSeconds)).append('\n');
        out.append(score == null
                ? "not analyzed on the phone"
                : MwAnalysis.summary(score)).append('\n');
        out.append("Music Wizard ").append(appVersion(activity))
                .append(" on Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.MODEL).append('\n');
        return out.toString();
    }

    private static String appVersion(Activity activity) {
        try {
            String name = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
            return name == null ? "unknown" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
}
