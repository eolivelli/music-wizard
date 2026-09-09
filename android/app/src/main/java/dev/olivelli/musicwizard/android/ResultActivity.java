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

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.olivelli.musicwizard.android.mw.MwAnalysis;
import dev.olivelli.musicwizard.android.mw.RecordingStore;
import dev.olivelli.musicwizard.android.mw.SheetRenderer;
import dev.olivelli.musicwizard.core.model.Score;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The result screen: tempo, meter, and the chart, engraved and as text.
 *
 * <p>An analysis is cached in {@code <name>.score.json} beside its audio, so
 * opening a take that has been analysed once is instant, and "re-analyze"
 * overwrites it. That loop is the point of the whole app: the same recordings
 * are run again after every improvement to the pipeline, and the chart that
 * comes out is what changed. Where the cache cannot be written the analysis is
 * still shown and the screen says so; {@code MwAnalysis.writeCache} records why
 * that is the usual case on Android today.
 */
public final class ResultActivity extends MwActivity
        implements AnalysisJobs.Listener, SheetJobs.Listener {

    /** How much one zoom step enlarges the engraving, and how far it may go. */
    private static final double ZOOM_STEP = 1.25;
    private static final double ZOOM_MIN = 0.5;
    private static final double ZOOM_MAX = 2;

    /** Absolute path of the WAV to show. */
    public static final String EXTRA_WAV = "wav";

    private File wav;
    private TextView status;
    private TextView chart;
    private EditText notes;
    private Button analyzeButton;
    private Button shareButton;
    private Button viewButton;
    private View sheetScroll;
    private View textScroll;
    private LinearLayout sheet;

    /** The score on screen, re-engraved on every zoom. */
    private Score shown;
    private double zoom = 1;
    private boolean sheetVisible;

    /** What the pane was last asked to draw, so a resume does not draw it again. */
    private Score engraved;
    private double engravedZoom;
    private boolean engraving;

    /** The status line without any engraving failure appended to it. */
    private String summary = "";

    /** Whether the user tapped over to the text, which a new engraving respects. */
    private boolean textChosen;

    /** The text on screen, kept so that "share" sends exactly what is shown. */
    private String shareable = "";

    /** What the notes file held when last read or written, so an untouched field is a no-op. */
    private String loadedNotes = "";

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
        notes = findViewById(R.id.notes);
        analyzeButton = findViewById(R.id.analyzeButton);
        shareButton = findViewById(R.id.shareButton);

        loadedNotes = RecordingStore.readNotes(new RecordingStore.Recording(wav));
        notes.setText(loadedNotes);
        viewButton = findViewById(R.id.viewButton);
        sheetScroll = findViewById(R.id.sheetScroll);
        textScroll = findViewById(R.id.textScroll);
        sheet = findViewById(R.id.sheet);
        analyzeButton.setOnClickListener(view -> analyze());
        shareButton.setOnClickListener(view -> shareText());
        viewButton.setOnClickListener(view -> {
            textChosen = sheetVisible;
            showSheet(!sheetVisible);
        });
        findViewById(R.id.zoomInButton).setOnClickListener(view -> rezoom(ZOOM_STEP));
        findViewById(R.id.zoomOutButton).setOnClickListener(view -> rezoom(1 / ZOOM_STEP));
        clearSheet();
        // Not gated on there being an analysis: the recording alone is the
        // ground truth worth moving, and the chart is whatever the phone
        // happened to make of it.
        findViewById(R.id.bundleButton).setOnClickListener(view -> {
            // A failed flush aborts: bundling the previous note as though it
            // were this one is the one wrong outcome.
            if (saveNotes()) {
                BundleShare.share(this, wav);
            }
        });
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
        // A render in flight is dropped and asked for again on resume; one
        // already on screen stays.
        SheetJobs.get().cancel();
        if (engraving) {
            engraved = null;
            engraving = false;
        }
        if (wav != null) {
            saveNotes();
        }
    }

    /**
     * Writes the note when it changed; false only when a write was attempted
     * and failed.
     *
     * <p>Leaving the screen is what commits the words — there is no save
     * button. Only on a change, so that an untouched field is a no-op: a note
     * file that could not be read arrives here as a blank field, and writing
     * that back would delete the very file the blank stood for.
     */
    private boolean saveNotes() {
        String typed = notes.getText().toString();
        if (typed.equals(loadedNotes)) {
            return true;
        }
        try {
            RecordingStore.writeNotes(new RecordingStore.Recording(wav), typed);
            loadedNotes = typed;
            return true;
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.notes_unsaved, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void analyze() {
        showRunning();
        AnalysisJobs.get().start(wav, this);
    }

    private void showRunning() {
        analyzeButton.setEnabled(false);
        shareButton.setEnabled(false);
        SheetJobs.get().cancel();
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
        clearSheet();
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
        summary = cacheNote == null
                ? MwAnalysis.summary(score)
                : MwAnalysis.summary(score) + "\n" + cacheNote;
        status.setText(summary);
        shareable = MwAnalysis.chartText(score);
        chart.setText(shareable);
        shown = score;
        engrave();
    }

    /** Asks for the engraving at the width the pane will have: its parent's, inside the padding. */
    private void engrave() {
        Score score = shown;
        // By value: a score read back from the cache is a new object each time.
        if (score == null || (score.equals(engraved) && zoom == engravedZoom)) {
            return;
        }
        engraved = score;
        engravedZoom = zoom;
        engraving = true;
        sheetScroll.post(() -> {
            View parent = (View) sheetScroll.getParent();
            int width = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            if (width <= 0) {
                width = getResources().getDisplayMetrics().widthPixels;
            }
            double density = getResources().getDisplayMetrics().density;
            SheetJobs.get().render(getApplicationContext(), score, width, density * zoom, this);
        });
    }

    private void rezoom(double factor) {
        zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * factor));
        engrave();
    }

    @Override
    public void onSheet(List<SheetRenderer.Partial> systems) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        engraving = false;
        status.setText(summary);
        releaseSystems();
        for (SheetRenderer.Partial system : systems) {
            ImageView image = new ImageView(this);
            image.setImageBitmap((Bitmap) system.result());
            image.setAdjustViewBounds(true);
            sheet.addView(image, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        viewButton.setEnabled(true);
        showSheet(!textChosen);
    }

    @Override
    public void onSheetFailed(String why) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // The score stays, so a zoom step out can try again.
        engraving = false;
        engraved = null;
        hideSheet();
        status.setText(summary + "\n" + getString(R.string.sheet_failed, why));
    }

    /** No score to engrave: text only, until a new analysis arrives. */
    private void clearSheet() {
        shown = null;
        engraved = null;
        SheetJobs.get().cancel();
        hideSheet();
    }

    private void hideSheet() {
        releaseSystems();
        viewButton.setEnabled(false);
        showSheet(false);
    }

    /** Frees the drawn systems' bitmaps before the views that hold them go. */
    private void releaseSystems() {
        for (int i = 0; i < sheet.getChildCount(); i++) {
            ImageView image = (ImageView) sheet.getChildAt(i);
            Drawable drawable = image.getDrawable();
            image.setImageDrawable(null);
            if (drawable instanceof BitmapDrawable bitmap && bitmap.getBitmap() != null) {
                bitmap.getBitmap().recycle();
            }
        }
        sheet.removeAllViews();
    }

    private void showSheet(boolean sheetOnTop) {
        sheetVisible = sheetOnTop;
        sheetScroll.setVisibility(sheetOnTop ? View.VISIBLE : View.GONE);
        textScroll.setVisibility(sheetOnTop ? View.GONE : View.VISIBLE);
        viewButton.setText(sheetOnTop ? R.string.show_text : R.string.show_sheet);
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
        clearSheet();
    }
}
