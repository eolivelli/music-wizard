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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import dev.olivelli.musicwizard.android.mw.MwAnalysis;
import dev.olivelli.musicwizard.android.mw.RecordingStore;
import dev.olivelli.musicwizard.android.report.GitHubReporter;
import dev.olivelli.musicwizard.android.report.Http;
import dev.olivelli.musicwizard.android.report.TakeReport;
import dev.olivelli.musicwizard.android.report.UrlConnectionHttp;
import dev.olivelli.musicwizard.core.model.Score;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sending one take to the repository: the audio, and what the player says it is.
 *
 * <p>This is the app's only network use, and it happens only when this button is
 * pressed — recording and analysis stay offline, which is the point of the
 * instrument. See {@code android/README.md} for the override of epic #236's
 * "no network" non-goal.
 *
 * <p>The screen keeps one promise beyond sending: the typed comment is written
 * to the app's preferences before the first byte goes out and only cleared once
 * GitHub has answered. A recording can be sent again; the sentence someone typed
 * about what they were playing cannot be recovered by trying again.
 *
 * <p>It also declares {@code configChanges} for orientation in the manifest, so
 * that turning the phone during an upload does not tear the screen down under an
 * in-flight request.
 */
public final class ReportActivity extends MwActivity {

    /** Absolute path of the WAV to send. */
    public static final String EXTRA_WAV = "wav";

    /**
     * One send at a time, process-wide.
     *
     * <p>Static so that backing out of this screen mid-upload does not abandon
     * the request or leave a thread behind per visit; the result is reported to
     * whichever screen is still there, or as a toast if none is.
     */
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mw-report");
        thread.setDaemon(true);
        return thread;
    });

    private final Handler main = new Handler(Looper.getMainLooper());

    private File wav;
    private String takeName;
    private double durationSeconds;
    private EditText comment;
    private Button sendButton;
    private TextView status;
    private boolean sending;
    private volatile boolean alive = true;

    /** Set once the comment is on GitHub, so leaving the screen does not re-save it. */
    private boolean sent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);

        String path = getIntent().getStringExtra(EXTRA_WAV);
        if (path == null) {
            finish();
            return;
        }
        wav = new File(path);
        RecordingStore.Recording recording = new RecordingStore.Recording(wav);
        takeName = recording.displayName();
        durationSeconds = recording.durationSeconds();
        setTitle(R.string.report_title);

        comment = findViewById(R.id.comment);
        sendButton = findViewById(R.id.sendButton);
        status = findViewById(R.id.reportStatus);
        TextView header = findViewById(R.id.reportHeader);

        header.setText(takeName + "  ·  " + RecordingStore.formatDuration(durationSeconds));
        comment.setText(ReportSettings.draft(this, takeName));
        sendButton.setOnClickListener(view -> send());
        findViewById(R.id.tokenButton).setOnClickListener(
                view -> startActivity(new Intent(this, TokenActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!sending && !sent) {
            status.setText(ReportSettings.hasToken(this)
                    ? getString(R.string.report_ready)
                    : getString(R.string.report_no_token));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Not once it is filed: the draft would then reappear the next time this
        // take is opened, looking like something still to send.
        if (!sent && wav != null) {
            ReportSettings.setDraft(this, takeName, comment.getText().toString());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        alive = false;
    }

    private void send() {
        if (sending) {
            return;
        }
        String typed = comment.getText().toString();
        String token = ReportSettings.token(this);
        if (token.trim().isEmpty()) {
            // Checked before encoding rather than left to the reporter, which
            // would only find out after compressing the whole take.
            status.setText(R.string.report_no_token);
            return;
        }
        // On disk before the network is touched, so that a process killed
        // mid-upload does not take the sentence with it.
        ReportSettings.setDraft(this, takeName, typed);

        sending = true;
        sendButton.setEnabled(false);
        status.setText(R.string.report_encoding);

        TakeReport report = new TakeReport(takeName, durationSeconds, typed, chartText(),
                appVersion(), platform());
        GitHubReporter reporter = new GitHubReporter(
                new UrlConnectionHttp(), getString(R.string.report_repo_owner),
                getString(R.string.report_repo_name), token);
        String releaseTag = getString(R.string.report_release_tag);
        int inboxIssue = getResources().getInteger(R.integer.report_inbox_issue);
        File encoded = new File(getCacheDir(), "report-upload.flac");
        Context application = getApplicationContext();

        SENDER.execute(() -> {
            String failure = null;
            GitHubReporter.Sent result = null;
            File payload = wav;
            String contentType = "audio/wav";
            String extension = ".wav";
            try {
                try {
                    FlacEncoder.encode(wav, encoded);
                    payload = encoded;
                    contentType = "audio/flac";
                    extension = ".flac";
                } catch (IOException | RuntimeException e) {
                    // A device with no FLAC encoder — possible below Android 10,
                    // which this app still runs on. The WAV is lossless too and
                    // is what the corpus wants; it is only bigger, and the asset
                    // name says which one went up.
                    report(application, application.getString(R.string.report_no_flac));
                }
                String assetName = GitHubReporter.assetName(takeName, Instant.now(), extension);
                result = reporter.send(releaseTag, inboxIssue, assetName,
                        Http.Body.file(payload, contentType), report);
            } catch (IOException | RuntimeException e) {
                failure = describe(e);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                encoded.delete();
            }
            String message = failure;
            GitHubReporter.Sent sentTo = result;
            main.post(() -> finished(application, sentTo, message));
        });
    }

    private void finished(Context application, GitHubReporter.Sent result, String failure) {
        if (result != null) {
            sent = true;
            ReportSettings.setDraft(application, takeName, "");
        }
        if (!alive) {
            report(application, result != null
                    ? application.getString(R.string.report_sent_toast)
                    : failure);
            return;
        }
        sending = false;
        sendButton.setEnabled(true);
        if (result != null) {
            comment.setText("");
            status.setText(getString(R.string.report_sent) + "\n" + result.commentUrl());
        } else {
            // The typed comment is still in the field and still in the draft:
            // pressing send again is the whole retry.
            status.setText(getString(R.string.report_failed) + "\n" + failure);
        }
    }

    /** A toast from the application context, for a result no screen is left to show. */
    private static void report(Context application, String message) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(application, message, Toast.LENGTH_LONG).show());
    }

    /**
     * The chart the phone has for this take, or null if it has none.
     *
     * <p>Same order the result screen reads them in: the last finished run first,
     * because below Android 35 that is the only copy there is, and the file
     * beside the audio second.
     */
    private String chartText() {
        AnalysisJobs.Result last = AnalysisJobs.get().lastResult(wav);
        if (last != null) {
            return last.score == null ? null : MwAnalysis.chartText(last.score);
        }
        Score cached = MwAnalysis.readCache(MwAnalysis.scoreFileFor(wav));
        return cached == null ? null : MwAnalysis.chartText(cached);
    }

    private String appVersion() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name == null ? "unknown" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private static String platform() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MODEL;
    }

    /** A sentence for the screen; an exception with no message says nothing on its own. */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }
}
