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
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import dev.olivelli.musicwizard.android.mw.MwAnalysis;
import dev.olivelli.musicwizard.android.mw.RecordingStore;
import dev.olivelli.musicwizard.android.mw.SheetDocuments;
import dev.olivelli.musicwizard.core.model.Score;
import java.io.File;
import java.io.IOException;

/**
 * The result screen: tempo, meter, the chart as text, and the PDF.
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
        implements AnalysisJobs.Listener, SheetJobs.PdfListener {

    /** What a PDF was asked for. */
    private enum PdfUse { SHARE, OPEN }

    /** Absolute path of the WAV to show. */
    public static final String EXTRA_WAV = "wav";

    private File wav;
    private TextView status;
    private TextView chart;
    private EditText notes;
    private Button analyzeButton;
    private Button shareButton;
    private Button pdfButton;
    private Button openButton;
    private CheckBox playableCheck;
    /** The melody's lowest note, chosen from {@code R.array.melody_floor_notes}. */
    private Spinner floorSpinner;

    /** The score on screen. */
    private Score shown;

    /** The status line. */
    private String summary = "";
    private String cacheNote;
    /** The score a PDF was asked for; its answer is dropped once another is shown. */
    private Score pdfRequested;
    private PdfUse pdfUse;
    private boolean resumed;

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
        pdfButton = findViewById(R.id.pdfButton);
        openButton = findViewById(R.id.openButton);
        playableCheck = findViewById(R.id.playableCheck);
        // The preference is the one copy: view state restored over it would
        // write back what this screen last saw, over a choice made since.
        playableCheck.setSaveEnabled(false);
        playableCheck.setChecked(Preferences.playablePart(this));
        playableCheck.setOnCheckedChangeListener((view, checked) -> {
            Preferences.setPlayablePart(this, checked);
            if (shown != null) {
                summary = summaryOf(shown);
                showStatus();
            }
        });
        floorSpinner = findViewById(R.id.floorSpinner);
        floorSpinner.setSaveEnabled(false);
        showFloor();
        floorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Preferences.setMelodyFloor(ResultActivity.this, floorNotes()[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        loadedNotes = RecordingStore.readNotes(new RecordingStore.Recording(wav));
        notes.setText(loadedNotes);
        analyzeButton.setOnClickListener(view -> analyze());
        shareButton.setOnClickListener(view -> shareText());
        pdfButton.setOnClickListener(view -> requestPdf(PdfUse.SHARE));
        openButton.setOnClickListener(view -> requestPdf(PdfUse.OPEN));
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
        resumed = true;
        if (wav == null) {
            return;
        }
        playableCheck.setChecked(Preferences.playablePart(this));
        showFloor();
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
        resumed = false;
        AnalysisJobs.get().stopObserving(this);
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
        AnalysisJobs.get().start(wav, melodyChoice(), this);
    }

    private MwAnalysis.MelodyChoice melodyChoice() {
        return playableCheck.isChecked()
                ? MwAnalysis.MelodyChoice.tracked(Preferences.melodyFloor(this))
                : MwAnalysis.MelodyChoice.off();
    }

    private String[] floorNotes() {
        return getResources().getStringArray(R.array.melody_floor_notes);
    }

    private void showFloor() {
        String[] notes = floorNotes();
        String chosen = Preferences.melodyFloor(this);
        for (int i = 0; i < notes.length; i++) {
            if (java.util.Objects.equals(notes[i], chosen)) {
                floorSpinner.setSelection(i);
                return;
            }
        }
        floorSpinner.setSelection(0);
    }

    private void showRunning() {
        analyzeButton.setEnabled(false);
        shareButton.setEnabled(false);
        pdfButton.setEnabled(false);
        openButton.setEnabled(false);
        // Nothing on screen is current until the run answers; a PDF asked for
        // the previous score is dropped on arrival.
        shown = null;
        String line = AnalysisJobs.get().progressOf(wav);
        status.setText(line.isEmpty() ? getString(R.string.analyzing) : line);
    }

    private void showUnanalyzed() {
        analyzeButton.setEnabled(true);
        analyzeButton.setText(R.string.analyze);
        shareButton.setEnabled(false);
        pdfButton.setEnabled(false);
        openButton.setEnabled(false);
        status.setText(R.string.not_analyzed);
        chart.setText("");
        shareable = "";
        shown = null;
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
        pdfButton.setEnabled(true);
        openButton.setEnabled(true);
        this.cacheNote = cacheNote;
        summary = summaryOf(score);
        showStatus();
        shareable = MwAnalysis.chartText(score);
        chart.setText(shareable);
        shown = score;
    }

    /** Tempo and meter, the cache's note, and whether the PDF can carry the playable part. */
    private String summaryOf(Score score) {
        StringBuilder out = new StringBuilder(MwAnalysis.summary(score));
        if (cacheNote != null) {
            out.append('\n').append(cacheNote);
        }
        if (playableCheck.isChecked()) {
            switch (SheetDocuments.melody(score)) {
                case UNTRACKED -> out.append('\n').append(getString(R.string.playable_untracked));
                case UNHEARD -> out.append('\n').append(getString(R.string.playable_unheard));
                case HEARD -> { }
            }
        }
        return out.toString();
    }

    private void showStatus() {
        status.setText(summary);
    }

    /**
     * Engraves the score on screen into the PDF beside the take, then shares
     * or opens it. Rendered afresh each time: the file may belong to an older
     * analysis.
     */
    private void requestPdf(PdfUse use) {
        Score score = shown;
        if (score == null) {
            return;
        }
        pdfButton.setEnabled(false);
        openButton.setEnabled(false);
        pdfRequested = score;
        pdfUse = use;
        Toast.makeText(this, R.string.pdf_building, Toast.LENGTH_SHORT).show();
        SheetJobs.get().pdf(getApplicationContext(), score, playableCheck.isChecked(),
                new RecordingStore.Recording(wav).pdfFile(), this);
    }

    /** Whether a PDF answer is for the score on screen, with the screen in front. */
    private boolean pdfStillWanted() {
        if (isFinishing() || isDestroyed() || pdfRequested == null || pdfRequested != shown) {
            return false;
        }
        pdfRequested = null;
        pdfButton.setEnabled(true);
        openButton.setEnabled(true);
        return resumed;
    }

    @Override
    public void onPdf(File pdf, String omitted) {
        if (!pdfStillWanted()) {
            return;
        }
        if (omitted != null) {
            Toast.makeText(this, getString(R.string.pdf_without_playable, omitted),
                    Toast.LENGTH_LONG).show();
        }
        android.net.Uri uri;
        try {
            uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".files", pdf);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "this PDF cannot be handed out: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (pdfUse == PdfUse.OPEN) {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, "application/pdf");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(view);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, R.string.pdf_no_viewer, Toast.LENGTH_LONG).show();
            }
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/pdf");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, pdf.getName());
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, getString(R.string.share_pdf)));
    }

    @Override
    public void onPdfFailed(String why) {
        if (!pdfStillWanted()) {
            return;
        }
        Toast.makeText(this, getString(R.string.pdf_failed, why), Toast.LENGTH_LONG).show();
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
        pdfButton.setEnabled(false);
        openButton.setEnabled(false);
        status.setText("analysis failed: " + message);
        chart.setText("");
        shareable = "";
        shown = null;
    }
}
