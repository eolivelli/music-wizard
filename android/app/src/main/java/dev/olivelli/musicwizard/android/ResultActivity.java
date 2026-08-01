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
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import dev.olivelli.musicwizard.android.mw.MwAnalysis;
import dev.olivelli.musicwizard.core.model.Score;
import java.io.File;

/**
 * The result screen: tempo, meter, and the chart as text.
 *
 * <p>An analysis is cached in {@code <name>.score.json} beside its audio, so
 * opening a take that has been analysed once is instant, and "re-analyze"
 * overwrites it. That loop is the point of the whole app: the same recordings
 * are run again after every improvement to the pipeline, and the chart that
 * comes out is what changed. Where the cache cannot be written the analysis is
 * still shown and the screen says so; {@code MwAnalysis.writeCache} records why
 * that is the usual case on Android today.
 */
public final class ResultActivity extends Activity implements AnalysisJobs.Listener {

    /** Absolute path of the WAV to show. */
    public static final String EXTRA_WAV = "wav";

    private File wav;
    private TextView status;
    private TextView chart;
    private Button analyzeButton;
    private Button shareButton;

    /** The text on screen, kept so that "share" sends exactly what is shown. */
    private String shareable = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        String path = getIntent().getStringExtra(EXTRA_WAV);
        if (path == null) {
            finish();
            return;
        }
        wav = new File(path);
        setTitle(wav.getName());

        status = findViewById(R.id.status);
        chart = findViewById(R.id.chart);
        analyzeButton = findViewById(R.id.analyzeButton);
        shareButton = findViewById(R.id.shareButton);

        analyzeButton.setOnClickListener(view -> analyze());
        shareButton.setOnClickListener(view -> shareText());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wav == null) {
            return;
        }
        // An analysis started before this screen went away is still running;
        // reattach to it rather than starting a second one.
        if (AnalysisJobs.get().observe(wav, this)) {
            showRunning();
            return;
        }
        // In memory before on disk. An analysis that finished while this screen
        // was away -- a rotation, or a trip to the home screen -- is held by
        // AnalysisJobs, and on Android below 35 that is the only place it is
        // held, because the file beside the audio could not be written. Asking
        // the disk first would answer with the previous analysis, or with
        // nothing at all seconds after a successful one.
        AnalysisJobs.Result finished = AnalysisJobs.get().lastResult(wav);
        if (finished != null) {
            if (finished.failure != null) {
                // A run that failed is the current answer for this take, and
                // the disk's older chart is not: drawing that instead would
                // show the previous analysis as though it were this one's.
                showFailed(finished.failure);
            } else {
                show(finished.score, finished.cacheNote);
            }
            return;
        }
        Score cached = MwAnalysis.readCache(MwAnalysis.scoreFileFor(wav));
        if (cached != null) {
            // Read back from disk, so by definition it is cached.
            show(cached, null);
        } else {
            showUnanalyzed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        AnalysisJobs.get().stopObserving(this);
    }

    private void analyze() {
        showRunning();
        AnalysisJobs.get().start(wav, this);
    }

    private void showRunning() {
        analyzeButton.setEnabled(false);
        shareButton.setEnabled(false);
        String line = AnalysisJobs.get().progressOf(wav);
        status.setText(line.isEmpty() ? getString(R.string.analyzing) : line);
    }

    private void showUnanalyzed() {
        analyzeButton.setEnabled(true);
        analyzeButton.setText(R.string.analyze);
        shareButton.setEnabled(false);
        status.setText(R.string.not_analyzed);
        chart.setText("");
        shareable = "";
    }

    /**
     * Draws a finished analysis.
     *
     * <p>What is shared is the chart text unaltered — the same thing
     * {@code mw render} writes to {@code chords.txt} — so the two can be
     * compared line for line. Tempo and meter go in the status line above it;
     * see {@link MwAnalysis#summary}.
     */
    private void show(Score score, String cacheNote) {
        analyzeButton.setEnabled(true);
        analyzeButton.setText(R.string.reanalyze);
        shareButton.setEnabled(true);
        status.setText(cacheNote == null
                ? MwAnalysis.summary(score)
                : MwAnalysis.summary(score) + "\n" + cacheNote);
        shareable = MwAnalysis.chartText(score);
        chart.setText(shareable);
    }

    private void shareText() {
        if (shareable.isEmpty()) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, wav.getName());
        send.putExtra(Intent.EXTRA_TEXT, shareable);
        startActivity(Intent.createChooser(send, getString(R.string.share_chart)));
    }

    @Override
    public void onProgress(String line) {
        if (!line.isEmpty()) {
            status.setText(line);
        }
    }

    @Override
    public void onFinished(Score score, String cacheNote) {
        show(score, cacheNote);
    }

    @Override
    public void onFailed(String message) {
        showFailed(message);
    }

    private void showFailed(String message) {
        analyzeButton.setEnabled(true);
        analyzeButton.setText(R.string.analyze);
        shareButton.setEnabled(false);
        status.setText("analysis failed: " + message);
        chart.setText("");
        shareable = "";
    }
}
