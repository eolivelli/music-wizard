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
import android.provider.OpenableColumns;
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
import dev.olivelli.musicwizard.android.mw.TakeSource;
import dev.olivelli.musicwizard.android.mw.RecordingStore.Recording;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The library: every take on the phone, and the four things to do with one.
 *
 * <p>Two of them get a take off the phone and into the corpus, which is what the
 * app is for, both through {@link FileProvider} and another app — a cable or a
 * cloud drive. "Share WAV" hands over the audio alone; "Share bundle"
 * ({@link BundleShare}) zips it together with everything the phone made of it.
 */
public final class LibraryActivity extends MwActivity {

    private static final int REQUEST_OPEN_FILE = 1;

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

        findViewById(R.id.openFileButton).setOnClickListener(view -> pickFile());
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

    /** The system picker, for audio already on the phone; the pick arrives in onActivityResult. */
    private void pickFile() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"audio/*", "video/*"});
        try {
            //noinspection deprecation
            startActivityForResult(pick, REQUEST_OPEN_FILE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "this phone has no file picker", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_OPEN_FILE || result != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String name = displayNameOf(uri);
        File cacheDirectory = new File(getCacheDir(), "imports");
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            Toast.makeText(this, "the import folder could not be made", Toast.LENGTH_LONG).show();
            return;
        }
        android.content.ContentResolver resolver = getApplicationContext().getContentResolver();
        boolean started = ImportJobs.get().startFile(name, () -> {
            java.io.InputStream in = resolver.openInputStream(uri);
            if (in == null) {
                throw new IOException("the file could not be opened");
            }
            return in;
        }, cacheDirectory, store, null);
        if (!started) {
            Toast.makeText(this, R.string.import_busy, Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, ImportActivity.class)
                .putExtra(ImportActivity.EXTRA_PICKED, true)
                .putExtra(Intent.EXTRA_SUBJECT, name));
    }

    /** The name the picker shows, or the last of the path where it shows none. */
    private String displayNameOf(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (RuntimeException e) {
            // A provider that refuses the query still has a path below.
        }
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "picked" : last.trim();
    }

    private void open(Recording recording) {
        Intent intent = new Intent(this, ResultActivity.class);
        intent.putExtra(ResultActivity.EXTRA_WAV, recording.wav().getAbsolutePath());
        startActivity(intent);
    }

    private void showActions(Recording recording) {
        String[] actions = {
                getString(R.string.share_wav),
                getString(R.string.share_bundle),
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
                            BundleShare.share(this, recording.wav());
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
                    // for another take to be renamed onto.
                    AnalysisJobs.get().forget(recording.wav());
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
            // What it says, not merely that it exists: BundleShare reads the
            // same file as a kind, and a presence test here would label every
            // take "imported" the day a microphone take starts writing one.
            String origin = TakeSource.parse(RecordingStore.readSource(recording)).isCommercial()
                    ? "  ·  " + getString(R.string.imported_marker) : "";
            return length + "  ·  " + state + origin + "  ·  " + when;
        }
    }
}
