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

package dev.olivelli.musicwizard.teacher;

import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a {@code .spec.txt} file.
 *
 * <p>The format is line-oriented: {@code key: value} headers, then a
 * {@code bars:} line, then the chord grid in the {@code samples/list.txt}
 * shorthand — one token per bar, {@code X-Y} a bar holding both chords,
 * whitespace and line breaks free, {@code #} to end of line a comment.
 *
 * <p>Headers: {@code title}, {@code style}, {@code tempo}, {@code key} and
 * {@code seed} are required; {@code genre}, {@code meter} (default 4/4) and
 * {@code melody} (an instrument name, or {@code none}; default is the style's
 * choice) are optional. Unknown headers are an error, not a warning — a typoed
 * header silently ignored would generate a package that quietly ignores its
 * spec.
 */
public final class SpecParser {

    private SpecParser() {
    }

    public static SampleSpec parse(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    public static SampleSpec parse(String text) {
        Map<String, String> headers = new HashMap<>();
        List<SampleSpec.Bar> bars = new ArrayList<>();
        boolean inGrid = false;

        for (String rawLine : text.lines().toList()) {
            String line = stripComment(rawLine).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!inGrid) {
                if (line.equals("bars:")) {
                    inGrid = true;
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    throw new IllegalArgumentException(
                            "expected 'key: value' or 'bars:', got: '" + line + "'");
                }
                headers.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).strip());
            } else {
                for (String token : line.split("\\s+")) {
                    bars.add(parseBar(token));
                }
            }
        }
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("no 'bars:' grid");
        }
        return build(headers, bars);
    }

    private static SampleSpec build(Map<String, String> headers, List<SampleSpec.Bar> bars) {
        Map<String, String> remaining = new HashMap<>(headers);
        String title = require(remaining, "title");
        String genre = remaining.remove("genre");
        SampleSpec.Style style = SampleSpec.Style.byId(require(remaining, "style"));
        double tempo = Double.parseDouble(require(remaining, "tempo"));
        TimeSignature meter = parseMeter(remaining.remove("meter"));
        String[] key = parseKey(require(remaining, "key"));
        long seed = Long.parseLong(require(remaining, "seed"));
        Integer melody = parseMelody(remaining.remove("melody"), style);
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException("unknown headers: " + remaining.keySet());
        }
        return new SampleSpec(title, genre == null ? "" : genre, style, tempo, meter,
                key[0], key[1].equals("minor") ? Mode.MINOR : Mode.MAJOR, seed, melody, bars);
    }

    private static String require(Map<String, String> headers, String name) {
        String value = headers.remove(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("missing header: " + name);
        }
        return value;
    }

    private static TimeSignature parseMeter(String value) {
        if (value == null) {
            return new TimeSignature(4, 4);
        }
        String[] parts = value.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("meter must be 'n/d', got: '" + value + "'");
        }
        return new TimeSignature(Integer.parseInt(parts[0].strip()),
                Integer.parseInt(parts[1].strip()));
    }

    private static String[] parseKey(String value) {
        String[] parts = value.split("\\s+");
        if (parts.length != 2
                || !(parts[1].equals("major") || parts[1].equals("minor"))) {
            throw new IllegalArgumentException(
                    "key must be '<tonic> major|minor', got: '" + value + "'");
        }
        return parts;
    }

    private static Integer parseMelody(String value, SampleSpec.Style style) {
        if (value == null) {
            return style.defaultMelodyProgram();
        }
        if (value.equals("none")) {
            return null;
        }
        Integer program = SampleSpec.MELODY_INSTRUMENTS.get(value);
        if (program == null) {
            throw new IllegalArgumentException("unknown melody instrument: '" + value
                    + "' (known: " + SampleSpec.MELODY_INSTRUMENTS.keySet() + ", or none)");
        }
        return program;
    }

    private static SampleSpec.Bar parseBar(String token) {
        int dash = token.indexOf('-');
        if (dash < 0) {
            return new SampleSpec.Bar(ChordSymbol.parse(token), null);
        }
        return new SampleSpec.Bar(ChordSymbol.parse(token.substring(0, dash)),
                ChordSymbol.parse(token.substring(dash + 1)));
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }
}
