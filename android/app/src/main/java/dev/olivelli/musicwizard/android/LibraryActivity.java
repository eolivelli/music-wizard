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

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import dev.olivelli.musicwizard.android.mw.RecordingStore;
import dev.olivelli.musicwizard.android.mw.RecordingStore.Recording;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The library: every take on the phone, and the five things to do with one.
 *
 * <p>Two of them get a take off the phone and into the corpus, which is what the
 * app is for. "Share" hands the WAV to another app through {@link FileProvider},
 * for a cable or a cloud drive; "Send to repo" ({@link ReportActivity}) files it
 * on GitHub there and then, together with what the player says was played.
 */
public final class LibraryActivity extends MwActivity {

    private RecordingStore store;
    private ListView list;
    private TextView empty;
    private final List<Recording> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        store = new RecordingStore(new File(getFilesDir(), "recordings"));
        list = findViewById(R.id.recordings);
        empty = findViewById(R.id.empty);

        list.setOnItemClickListener((parent, view, position, id) -> open(shown.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(shown.get(position));
            return true;
        });
    }

    /**
     * Reloads on every return.
     *
     * <p>Coming back from the result screen may mean a take was just analysed,
     * and coming back from the record screen may mean there is a new one.
     */
    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        shown.clear();
        shown.addAll(store.list());
        list.setAdapter(new RecordingAdapter(shown));
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void open(Recording recording) {
        Intent intent = new Intent(this, ResultActivity.class);
        intent.putExtra(ResultActivity.EXTRA_WAV, recording.wav().getAbsolutePath());
        startActivity(intent);
    }

    private void showActions(Recording recording) {
        String[] actions = {
                getString(R.string.share_wav),
                getString(R.string.report_send),
                getString(R.string.rename),
                getString(R.string.delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(recording.displayName())
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            shareWav(recording);
                            break;
                        case 1:
                            report(recording.wav());
                            break;
                        case 2:
                            askForName(recording);
                            break;
                        default:
                            confirmDelete(recording);
                            break;
                    }
                })
                .show();
    }

    private void report(File wav) {
        Intent intent = new Intent(this, ReportActivity.class);
        intent.putExtra(ReportActivity.EXTRA_WAV, wav.getAbsolutePath());
        startActivity(intent);
    }

    private void shareWav(Recording recording) {
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".files", recording.wav());
        } catch (IllegalArgumentException e) {
            // The provider's declared paths and the store's directory disagree,
            // which is a build-time mistake rather than a user's.
            Toast.makeText(this, "this recording cannot be shared: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("audio/wav");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, recording.wav().getName());
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, getString(R.string.share_wav)));
    }

    private void askForName(Recording recording) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(recording.displayName());
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    try {
                        Recording renamed = store.rename(recording, input.getText().toString());
                        // The store moves the cached analysis with the audio;
                        // the copy held in memory has to move with it too, and
                        // that is often the only copy there is.
                        AnalysisJobs.get().moved(recording.wav(), renamed.wav());
                        // And an unsent comment about this take, which is keyed
                        // by the take's name and would otherwise be left behind
                        // for whichever take is called that next.
                        ReportSettings.moved(this, recording.displayName(),
                                renamed.displayName());
                    } catch (IOException e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    reload();
                })
                .show();
    }

    private void confirmDelete(Recording recording) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(recording.displayName())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    store.delete(recording);
                    // The analysis is keyed by path, and this path is now free
                    // for another take to be renamed onto. The unsent comment
                    // is keyed by the name, and so is the same hazard.
                    AnalysisJobs.get().forget(recording.wav());
                    ReportSettings.forget(this, recording.displayName());
                    reload();
                })
                .show();
    }

    /** Two lines per take: its name, and what is known about it without decoding. */
    private final class RecordingAdapter extends ArrayAdapter<Recording> {

        RecordingAdapter(List<Recording> items) {
            super(LibraryActivity.this, android.R.layout.simple_list_item_2,
                    android.R.id.text1, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = super.getView(position, convertView, parent);
            Recording recording = getItem(position);
            TextView title = row.findViewById(android.R.id.text1);
            TextView subtitle = row.findViewById(android.R.id.text2);
            title.setText(recording.displayName());
            subtitle.setText(subtitleOf(recording));
            return row;
        }

        private String subtitleOf(Recording recording) {
            String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(recording.modifiedMillis()));
            String length = RecordingStore.formatDuration(recording.durationSeconds());
            String state = recording.isAnalyzed() ? "analyzed" : "not analyzed";
            return length + "  ·  " + state + "  ·  " + when;
        }
    }
}
