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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import dev.olivelli.musicwizard.android.mw.ImportLog;
import dev.olivelli.musicwizard.android.mw.RecordingStore;
import dev.olivelli.musicwizard.android.yt.VideoLink;
import java.io.File;

/**
 * The confirmation screen for a video shared into the app.
 *
 * <p>Reached only from another app's share sheet, and exported for that reason —
 * so anything on the phone can start it with any text. Nothing happens on
 * arrival: the screen states what it would fetch and waits for a tap. That is
 * the whole of the mitigation, and it is why the download is not started from
 * {@code onCreate} however convenient that would be.
 */
public final class ImportActivity extends MwActivity implements ImportJobs.Listener {

    private TextView titleView;
    private TextView urlView;
    private TextView statusView;
    private ProgressBar progressView;
    private Button downloadButton;
    private Button cancelButton;
    private Button copyLogButton;
    private TextView logLabel;
    private TextView logView;
    private View logScroll;

    /** The log revision already drawn, so an unchanged log is not redrawn. */
    private int drawnRevision = -1;

    private RecordingStore store;
    private File cacheDirectory;

    /** The link as it will be fetched, or null when the share held none. */
    private String videoUrl;
    private String shareText;
    private String sharedTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import);

        titleView = findViewById(R.id.title);
        urlView = findViewById(R.id.url);
        statusView = findViewById(R.id.status);
        progressView = findViewById(R.id.progress);
        downloadButton = findViewById(R.id.downloadButton);
        cancelButton = findViewById(R.id.cancelButton);
        copyLogButton = findViewById(R.id.copyLogButton);
        logLabel = findViewById(R.id.logLabel);
        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);

        store = new RecordingStore(new File(getFilesDir(), "recordings"));
        cacheDirectory = new File(getCacheDir(), "imports");

        downloadButton.setOnClickListener(v -> onDownloadTapped());
        cancelButton.setOnClickListener(v -> finish());
        copyLogButton.setOnClickListener(v -> copyLog());

        readIntent(getIntent());
    }

    /**
     * A second share while this screen is up.
     *
     * <p>{@code singleTop} sends it here rather than stacking another copy. A
     * running import is left alone — {@code BundleShare}'s rule, that a second
     * tap is ignored rather than queued — and the new link is taken up only when
     * nothing is in flight.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ImportJobs.get().isRunning()) {
            statusView.setText(R.string.import_busy);
            return;
        }
        readIntent(intent);
        showConfirmation();
    }

    private void readIntent(Intent intent) {
        // As a CharSequence, which is what the extra is documented to hold:
        // getStringExtra returns null for the SpannableString some senders use,
        // and the screen would then say the share held no link when it did.
        shareText = text(intent, Intent.EXTRA_TEXT);
        String subject = text(intent, Intent.EXTRA_SUBJECT);

        String id = VideoLink.videoId(shareText);
        videoUrl = id == null ? null : VideoLink.watchUrl(id);
        // A hint for the name shown before the fetch; the video's real title
        // arrives with the player response and supersedes it. YouTube has put it
        // in either extra depending on the version, so both are read.
        sharedTitle = firstNonBlank(subject, firstLineOf(shareText), id);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The instance may be new while the log is not, so nothing drawn by a
        // previous screen counts as drawn by this one.
        drawnRevision = -1;
        ImportJobs jobs = ImportJobs.get();

        if (jobs.observe(this)) {
            showRunning();
            return;
        }
        ImportJobs.Result last = jobs.lastResult();
        if (last != null) {
            jobs.clearResult();
            if (last.wav != null) {
                openResult(last.wav);
                return;
            }
            showConfirmation();
            statusView.setText(last.cancelled
                    ? getString(R.string.import_cancelled)
                    : getString(R.string.import_failed, last.failure));
            downloadButton.setText(R.string.import_retry);
            return;
        }
        // Also where a process killed mid-import lands: the singleton went with
        // it, so the screen offers the download again rather than showing a bar
        // that will never move.
        showConfirmation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ImportJobs.get().stopObserving(this);
    }

    /**
     * Draws the log if there is one, and only when it has changed.
     *
     * <p>The decode reports progress once per output buffer, which is thousands
     * of calls for one track. Setting the same text on a selectable view inside
     * a scroller that many times would relayout each time and throw away any
     * selection the user had made in it.
     */
    private void showLog() {
        ImportLog log = ImportJobs.get().log();
        if (log.revision() == drawnRevision) {
            return;
        }
        drawnRevision = log.revision();

        String text = log.text();
        int visibility = text.isEmpty() ? View.GONE : View.VISIBLE;
        logLabel.setVisibility(visibility);
        logScroll.setVisibility(visibility);
        copyLogButton.setVisibility(visibility);
        logView.setText(text);
    }

    private void copyLog() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Music Wizard import log",
                ImportJobs.get().log().text()));
        Toast.makeText(this, R.string.import_log_copied, Toast.LENGTH_SHORT).show();
    }

    private void showConfirmation() {
        showLog();
        progressView.setVisibility(View.GONE);
        getWindow().getDecorView().setKeepScreenOn(false);
        cancelButton.setText(R.string.cancel);
        downloadButton.setText(R.string.import_download);

        if (videoUrl == null) {
            titleView.setText(R.string.import_title);
            urlView.setText("");
            statusView.setText(describeShare());
            downloadButton.setEnabled(false);
            return;
        }
        titleView.setText(sharedTitle == null ? getString(R.string.import_untitled) : sharedTitle);
        urlView.setText(videoUrl);
        statusView.setText(R.string.import_confirm);
        downloadButton.setEnabled(true);
    }

    /** The share's own reason, so a playlist and a channel do not read alike. */
    private String describeShare() {
        switch (VideoLink.problem(shareText)) {
            case PLAYLIST:
                return "That is a playlist. Share one video.";
            case CHANNEL:
                return "That is a channel. Share one video.";
            case NO_VIDEO_ID:
                return "That YouTube link does not name a video.";
            case NO_LINK:
            default:
                return "That does not hold a YouTube link.";
        }
    }

    private void showRunning() {
        showLog();
        progressView.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(true);
        downloadButton.setText(R.string.import_cancel_download);
        cancelButton.setText(R.string.cancel);
        // The import outlives this screen, so the screen is kept awake only
        // while it is the thing the user is watching.
        getWindow().getDecorView().setKeepScreenOn(true);
    }

    private void onDownloadTapped() {
        ImportJobs jobs = ImportJobs.get();
        if (jobs.isRunning()) {
            jobs.cancel();
            statusView.setText(R.string.import_cancelled);
            return;
        }
        if (videoUrl == null) {
            return;
        }
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            statusView.setText(getString(R.string.import_failed,
                    "the download folder could not be made"));
            return;
        }
        if (!jobs.start(shareText, cacheDirectory, store, this)) {
            statusView.setText(R.string.import_busy);
            return;
        }
        showRunning();
    }

    @Override
    public void onProgress(String line, int percent) {
        showLog();
        statusView.setText(getString(R.string.import_running, line));
        if (percent < 0) {
            progressView.setIndeterminate(true);
        } else {
            progressView.setIndeterminate(false);
            progressView.setProgress(percent);
        }
    }

    @Override
    public void onFinished(File wav) {
        openResult(wav);
    }

    @Override
    public void onFailed(String message) {
        showConfirmation();
        statusView.setText(getString(R.string.import_failed, message));
        downloadButton.setText(R.string.import_retry);
    }

    @Override
    public void onCancelled() {
        showConfirmation();
        statusView.setText(R.string.import_cancelled);
    }

    /**
     * Hands the finished take to the result screen and steps out of the way.
     *
     * <p>The analysis is not started here. A five-minute track is a minute of
     * DSP and a large allocation, and spending that is the user's call — exactly
     * as it is for a take opened from the library.
     */
    private void openResult(File wav) {
        getWindow().getDecorView().setKeepScreenOn(false);
        startActivity(new Intent(this, ResultActivity.class)
                .putExtra(ResultActivity.EXTRA_WAV, wav.getAbsolutePath()));
        finish();
    }

    private static String text(Intent intent, String extra) {
        CharSequence value = intent == null ? null : intent.getCharSequenceExtra(extra);
        return value == null ? null : value.toString();
    }

    private static String firstLineOf(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && VideoLink.videoId(trimmed) == null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
