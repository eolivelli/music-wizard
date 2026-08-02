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

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import dev.olivelli.musicwizard.android.mw.RecordingStore;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;

/** The record screen: one button, a level meter, and a way to the library. */
public final class RecordActivity extends MwActivity implements Recorder.Listener {

    private static final int REQUEST_MICROPHONE = 1;

    /** How often the elapsed-time readout is redrawn while recording. */
    private static final long TICK_MILLIS = 200;

    private final Handler ticker = new Handler(Looper.getMainLooper());

    private RecordingStore store;
    private Recorder recorder;

    private TextView status;
    private TextView elapsed;
    private ProgressBar level;
    private Button recordButton;

    private long startedAtUptime;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (recorder != null && recorder.isRecording()) {
                double seconds = (SystemClock.uptimeMillis() - startedAtUptime) / 1000.0;
                elapsed.setText(RecordingStore.formatDuration(seconds));
                ticker.postDelayed(this, TICK_MILLIS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record);

        store = new RecordingStore(new File(getFilesDir(), "recordings"));
        recorder = new Recorder(this);

        status = findViewById(R.id.status);
        elapsed = findViewById(R.id.elapsed);
        level = findViewById(R.id.level);
        recordButton = findViewById(R.id.recordButton);

        recordButton.setOnClickListener(view -> toggle());
        findViewById(R.id.libraryButton).setOnClickListener(view ->
                startActivity(new Intent(this, LibraryActivity.class)));
    }

    /**
     * Stops a take when the screen goes away.
     *
     * <p>Not a choice about tidiness: since Android 9 an app that is not in the
     * foreground gets silence from the microphone rather than audio, so a take
     * that kept "running" in the background would be a file of nothing. What is
     * captured so far is kept.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (recorder.isRecording()) {
            recorder.stop();
        }
    }

    private void toggle() {
        if (recorder.isRecording()) {
            recorder.stop();
            recordButton.setEnabled(false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO},
                    REQUEST_MICROPHONE);
            return;
        }
        beginRecording();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MICROPHONE) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            beginRecording();
        } else {
            status.setText(R.string.permission_needed);
        }
    }

    private void beginRecording() {
        File wav;
        try {
            wav = store.newRecordingFile(Instant.now(), ZoneId.systemDefault());
        } catch (IOException e) {
            status.setText(getString(R.string.record_idle));
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        startedAtUptime = SystemClock.uptimeMillis();
        recorder.start(wav);
        if (!recorder.isRecording()) {
            // start() already reported why through onFailed.
            return;
        }
        recordButton.setText(R.string.record_stop);
        // The screen turning off pauses the activity, which stops the take.
        recordButton.setKeepScreenOn(true);
        status.setText(R.string.record_running);
        elapsed.setText(R.string.zero_time);
        ticker.postDelayed(tick, TICK_MILLIS);
    }

    @Override
    public void onLevel(float peak) {
        level.setProgress(Math.round(peak * 100));
    }

    @Override
    public void onFinished(File wav, double seconds) {
        idle();
        if (seconds <= 0) {
            //noinspection ResultOfMethodCallIgnored
            wav.delete();
            status.setText("nothing was captured; the take was discarded");
            return;
        }
        status.setText(getString(R.string.record_idle));
        Toast.makeText(this,
                "Saved " + wav.getName() + " (" + RecordingStore.formatDuration(seconds) + ")",
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onFailed(String message) {
        idle();
        status.setText(message);
    }

    private void idle() {
        ticker.removeCallbacks(tick);
        recordButton.setEnabled(true);
        recordButton.setText(R.string.record_start);
        recordButton.setKeepScreenOn(false);
        level.setProgress(0);
    }
}
