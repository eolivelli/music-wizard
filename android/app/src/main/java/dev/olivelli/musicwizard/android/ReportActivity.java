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
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import dev.olivelli.musicwizard.android.mw.MwAnalysis;
import dev.olivelli.musicwizard.android.mw.RecordingStore;
import dev.olivelli.musicwizard.android.report.GitHubReporter;
import dev.olivelli.musicwizard.android.report.ReportJob;
import dev.olivelli.musicwizard.android.report.SendState;
import dev.olivelli.musicwizard.android.report.TakeReport;
import dev.olivelli.musicwizard.android.report.UrlConnectionHttp;
import dev.olivelli.musicwizard.core.model.Score;
import java.io.File;
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
 * <p>A send outlives the screen that started it, so nothing about one is kept
 * in an instance: it lives in {@link SendState}, and this screen asks it every
 * time it draws.
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
     * the request or leave a thread behind per visit.
     */
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mw-report");
        thread.setDaemon(true);
        return thread;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Everything known about sending, at {@link #SENDER}'s scope rather than a
     * screen's. See {@link SendState}, which is where it is reasoned about and
     * where it is tested.
     */
    private static SendState state;

    /**
     * The resumed screen, or null when none is.
     *
     * <p>Where a finished send draws its result. Not the screen that started it:
     * that one may be gone, and the one in front of the user may be a second
     * visit to the same take. Main thread only, and cleared in
     * {@link #onPause()}, which always runs before a screen is destroyed.
     */
    private static ReportActivity visible;

    private File wav;
    private String takeName;
    private double durationSeconds;
    private EditText comment;
    private Button sendButton;
    private TextView status;

    /**
     * True while the app, rather than the user, is changing the comment field.
     *
     * <p>The watcher below has to tell the two apart, and doing it by ordering
     * the writes was load-bearing and subtle twice over. This says it outright.
     */
    private boolean settingText;

    /** The one instance, over this app's preferences. Main thread only. */
    static SendState sends(Context context) {
        if (state == null) {
            Context application = context.getApplicationContext();
            state = new SendState(new SendState.Store() {
                @Override
                public String draft(String take) {
                    return ReportSettings.draft(application, take);
                }

                @Override
                public void setDraft(String take, String text) {
                    ReportSettings.setDraft(application, take, text);
                }

                @Override
                public boolean isFiled(String take) {
                    return ReportSettings.isFiled(application, take);
                }

                @Override
                public void setFiled(String take, boolean filed) {
                    ReportSettings.setFiled(application, take, filed);
                }
            });
        }
        return state;
    }

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
        setCommentText(sends(this).draft(takeName));
        comment.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // What was last sent is not what is in the box any more.
                if (!settingText && sends(ReportActivity.this).edited(takeName)) {
                    draw();
                }
            }
        });
        sendButton.setOnClickListener(view -> send());
        findViewById(R.id.tokenButton).setOnClickListener(
                view -> startActivity(new Intent(this, TokenActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wav == null) {
            return;
        }
        visible = this;
        draw();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (visible == this) {
            visible = null;
        }
        if (wav != null) {
            sends(this).keepDraft(takeName, comment.getText().toString());
        }
    }

    /**
     * The screen for whatever is true now.
     *
     * <p>A fresh instance cannot assume it is idle: a send for this take may be
     * running, started by a screen that has since gone away, and the take may
     * already be filed. Everything the screen shows between actions is derived
     * here rather than remembered, so there is one place that can be wrong.
     */
    private void draw() {
        SendState sends = sends(this);
        boolean sending = sends.isSending(takeName);
        // The field, not only the button: anything typed during a send would be
        // wiped by the clear that follows success, having never been sent — the
        // one thing this screen promises cannot happen.
        comment.setEnabled(!sending);
        sendButton.setEnabled(sends.canSend(takeName));

        String detail = sends.detailFor(takeName);
        if (sending) {
            status.setText(R.string.report_encoding);
        } else if (detail != null) {
            // The comment's URL is the only proof the take arrived, so it has
            // to survive a screen timeout rather than only the moment it lands.
            status.setText(detail);
        } else if (sends.isFiled(takeName)) {
            status.setText(R.string.report_sent);
        } else {
            status.setText(ReportSettings.hasToken(this)
                    ? getString(R.string.report_ready)
                    : getString(R.string.report_no_token));
        }
    }

    private void send() {
        if (!sends(this).canSend(takeName)) {
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

        String take = takeName;
        TakeReport report = new TakeReport(take, durationSeconds, typed, chartText(),
                appVersion(), platform());
        GitHubReporter reporter = new GitHubReporter(
                new UrlConnectionHttp(), getString(R.string.report_repo_owner),
                getString(R.string.report_repo_name), token);
        String releaseTag = getString(R.string.report_release_tag);
        int inboxIssue = getResources().getInteger(R.integer.report_inbox_issue);
        File encoded = new File(getCacheDir(), "report-upload.flac");
        Context application = getApplicationContext();
        File audio = wav;

        // Marked last, immediately before the work is handed over: anything
        // throwing between the mark and the executor would leave the take in
        // flight for the life of the process, Send disabled on every visit.
        SendState.Send send = sends(this).beginSend(take);
        draw();

        SENDER.execute(() -> {
            ReportJob.Outcome outcome;
            try {
                outcome = ReportJob.run(FlacEncoder::encode, reporter, releaseTag, inboxIssue,
                        audio, encoded, take, report, Instant.now());
            } catch (Throwable t) {
                // run() answers with an Outcome for every failure it can put
                // into words, and its javadoc names the one it cannot. If it
                // ever gets there, the screen still has to be answered: a send
                // that goes unanswered leaves the take marked in flight and
                // Send disabled for the life of the process.
                outcome = null;
            }
            ReportJob.Outcome result = outcome;
            MAIN.post(() -> finished(application, send, result));
        });
    }

    /**
     * Files the result and shows it, wherever the user now is.
     *
     * <p>Static because the screen that started the send is not necessarily the
     * one to tell — it may be gone, or replaced by a second visit to the same
     * take — and because the marking has to happen either way.
     */
    private static void finished(Context application, SendState.Send send,
                                 ReportJob.Outcome outcome) {
        boolean worked = outcome != null && outcome.sent() != null;
        // Recorded before anything is drawn, and whether or not a screen is
        // left to draw on: the record is what every screen reads, now and on
        // its next visit. What comes back is the take's name *now* — the take
        // may have been renamed while this ran, and the name the send was
        // started under may by then belong to a different take altogether.
        String take = sends(application).finishSend(send, worked,
                detailOf(application, outcome, worked));

        ReportActivity screen = visible;
        if (take == null || screen == null || !take.equals(screen.takeName)) {
            // With the reason, not just "not sent": backing out during a slow
            // upload is what someone on a bad connection does, and they are the
            // ones who most need to know which of the two it was.
            Toast.makeText(application, worked
                    ? application.getString(R.string.report_sent_toast)
                    : application.getString(R.string.report_failed) + "\n"
                            + reasonOf(application, outcome), Toast.LENGTH_LONG).show();
            return;
        }
        if (worked) {
            screen.setCommentText("");
        }
        screen.draw();
    }

    /** Sets the field without the watcher reading it as someone typing. */
    private void setCommentText(String text) {
        settingText = true;
        try {
            comment.setText(text);
        } finally {
            settingText = false;
        }
    }

    /** The line the screen shows for a finished send, and keeps showing. */
    private static String detailOf(Context context, ReportJob.Outcome outcome, boolean worked) {
        StringBuilder line = new StringBuilder();
        if (worked) {
            line.append(context.getString(R.string.report_sent)).append('\n')
                    .append(outcome.sent().commentUrl());
        } else {
            // The typed comment is still in the field and still in the draft:
            // pressing Send again is the whole retry.
            line.append(context.getString(R.string.report_failed)).append('\n')
                    .append(reasonOf(context, outcome));
        }
        if (outcome != null && outcome.encoderFailure() != null) {
            line.append('\n')
                    .append(context.getString(R.string.report_wav_instead,
                            outcome.encoderFailure()));
        }
        return line.toString();
    }

    /**
     * Why a send did not land.
     *
     * <p>Neither an outcome nor a reason means {@code ReportJob.run} itself
     * threw, which is what a second {@link OutOfMemoryError} looks like. Saying
     * nothing there would be the one thing this screen must not do.
     */
    private static String reasonOf(Context context, ReportJob.Outcome outcome) {
        return outcome == null || outcome.failure() == null
                ? context.getString(R.string.report_no_reason) : outcome.failure();
    }

    /**
     * The chart the phone has for this take, or null if it has none.
     *
     * <p>Same order the result screen reads them in: the last finished run
     * first ({@link MwAnalysis#writeCache}), the file beside the audio second.
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
}
