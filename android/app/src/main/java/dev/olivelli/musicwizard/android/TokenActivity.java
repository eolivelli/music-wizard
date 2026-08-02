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

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Where the GitHub token is pasted, once.
 *
 * <p>A fine-grained personal access token rather than OAuth: this is a
 * single-user instrument talking to one repository, and an OAuth dance would
 * mean a redirect URI, a client secret in an APK, and a server. Where the token
 * is kept, and what that is and is not proof against, is in
 * {@link ReportSettings}.
 *
 * <p>The stored token is never shown again — the field starts empty and saving
 * a blank one forgets it. Not secrecy from the person holding the phone, who
 * pasted it: it is so that "there is a token" and "here is the token" are
 * different questions, and shoulder-surfing a screen someone opened to check the
 * former does not answer the latter.
 */
public final class TokenActivity extends MwActivity {

    private EditText field;
    private TextView state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_token);
        setTitle(R.string.token_title);

        field = findViewById(R.id.token);
        state = findViewById(R.id.tokenState);

        Button save = findViewById(R.id.saveToken);
        save.setOnClickListener(view -> {
            ReportSettings.setToken(this, field.getText().toString());
            field.setText("");
            showState();
        });
        Button forget = findViewById(R.id.forgetToken);
        forget.setOnClickListener(view -> {
            ReportSettings.setToken(this, "");
            field.setText("");
            showState();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        showState();
    }

    private void showState() {
        state.setText(ReportSettings.hasToken(this)
                ? R.string.token_present : R.string.token_absent);
    }
}
