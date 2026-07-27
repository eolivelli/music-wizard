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

package dev.olivelli.musicwizard.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads and writes {@link MusicWizardConfig}, and applies the layering rule.
 *
 * <p>Precedence, weakest first: built-in defaults, then the user's global
 * config, then the workspace config, then anything passed on the command line.
 * Each layer only needs to state what it changes.
 */
public final class ConfigLoader {

    private static final String GLOBAL_CONFIG_FILE = "config.yaml";
    private static final String APP_DIRECTORY = "music-wizard";

    /**
     * What the binary is called, per platform. Windows has no extensionless
     * executables, so a discovery that only ever looks for {@code lilypond}
     * cannot find an installation there at all.
     */
    private static final List<String> POSIX_EXECUTABLES = List.of("lilypond");
    private static final List<String> WINDOWS_EXECUTABLES =
            List.of("lilypond.exe", "lilypond.bat", "lilypond.cmd");

    private final ObjectMapper yamlMapper;

    public ConfigLoader() {
        YAMLFactory factory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        this.yamlMapper = new ObjectMapper(factory)
                // Required for the model's Optional fields. Without it any type
                // carrying an Optional fails to serialize through this mapper,
                // which is the same defect already fixed on the JSON path.
                .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }

    /**
     * The global config directory, honouring {@code XDG_CONFIG_HOME} where set
     * and falling back to {@code ~/.config}.
     */
    public static Path globalConfigDirectory() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve(APP_DIRECTORY);
    }

    /** The global config file path, which need not exist. */
    public static Path globalConfigFile() {
        return globalConfigDirectory().resolve(GLOBAL_CONFIG_FILE);
    }

    /**
     * Reads a config layer from a file, returning an empty layer when the file
     * is absent. A malformed file is an error rather than being silently
     * ignored, because quietly discarding a user's settings is worse than
     * failing loudly.
     */
    public MusicWizardConfig readLayer(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return MusicWizardConfig.empty();
        }
        try {
            String content = Files.readString(file);
            if (content.isBlank()) {
                return MusicWizardConfig.empty();
            }
            MusicWizardConfig parsed = yamlMapper.readValue(content, MusicWizardConfig.class);
            return parsed != null ? parsed : MusicWizardConfig.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read config file: " + file, e);
        }
    }

    /** Reads the user's global config layer, if any. */
    public MusicWizardConfig readGlobalLayer() {
        return readLayer(globalConfigFile());
    }

    /**
     * Produces the effective config for a workspace: defaults, then global,
     * then workspace, then command-line overrides.
     *
     * @param workspaceConfigFile the workspace config file, which may be absent
     * @param commandLineOverrides overrides from flags, or null when there are none
     */
    public MusicWizardConfig effectiveConfig(Path workspaceConfigFile,
                                             MusicWizardConfig commandLineOverrides) {
        return MusicWizardConfig.DEFAULTS
                .overriddenBy(readGlobalLayer())
                .overriddenBy(readLayer(workspaceConfigFile))
                .overriddenBy(commandLineOverrides);
    }

    /** Writes a config layer, creating parent directories as needed. */
    public void write(Path file, MusicWizardConfig config) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, yamlMapper.writeValueAsString(config));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write config file: " + file, e);
        }
    }

    /** The mapper used for config, exposed so callers can reuse its settings. */
    public ObjectMapper yamlMapper() {
        return yamlMapper;
    }

    /**
     * Locates the LilyPond binary.
     *
     * <p>Checked in order: an explicit configured path, then {@code PATH}, then
     * the usual package-manager prefixes. The last step matters because
     * Homebrew installs outside the default {@code PATH} of a non-login shell,
     * which is exactly how a user ends up with LilyPond installed and the tool
     * unable to find it.
     *
     * <p>An explicit path is used exactly as written, including its extension:
     * a configured path that is not executable is an error rather than a hint,
     * so a Windows user who omits {@code .exe} is told so instead of silently
     * getting some other binary.
     */
    public static Optional<Path> findLilyPond(MusicWizardConfig config) {
        Optional<String> configured = config != null
                ? config.lilypondPath()
                : Optional.empty();
        if (configured.isPresent() && !configured.get().isBlank()) {
            Path explicit = Path.of(configured.get());
            if (!isExecutable(explicit)) {
                // Silently falling back to a discovered binary would ignore an
                // explicit instruction, which is exactly what this class says
                // elsewhere it will not do.
                throw new IllegalStateException(
                        "notation.lilypondPath is set to " + explicit
                                + " but that is not an executable file");
            }
            return Optional.of(explicit);
        }
        return discover(System.getenv("PATH"), isWindows());
    }

    /**
     * The discovery half of {@link #findLilyPond}, with {@code PATH} passed in
     * so it can be driven from a test.
     *
     * <p>{@code windows} selects the executable names, the fallback prefixes
     * and whether entries may be quoted — the conventions that live in the
     * shell rather than in the filesystem. Everything path-shaped still comes
     * from the host: {@link Path#of} decides what parses and what is absolute,
     * and entries are separated by {@link java.io.File#pathSeparator}. So a
     * test on POSIX can prove that {@code lilypond.exe} is the name looked for,
     * but it must still hand in POSIX-shaped directories; it cannot prove
     * anything about how {@code C:\...} is parsed.
     */
    static Optional<Path> discover(String pathVariable, boolean windows) {
        return discover(pathVariable, windows, searchPrefixes(windows));
    }

    /**
     * As {@link #discover(String, boolean)}, with the fallback prefixes given
     * explicitly so a test can plant a binary under one. The real list names
     * directories no test may write to.
     */
    static Optional<Path> discover(String pathVariable, boolean windows, List<String> prefixes) {
        List<String> names = windows ? WINDOWS_EXECUTABLES : POSIX_EXECUTABLES;

        if (pathVariable != null) {
            for (String entry : splitPath(pathVariable, windows)) {
                Path directory = pathEntryDirectory(entry, windows);
                if (directory == null) {
                    continue;
                }
                Optional<Path> found = firstExecutableIn(directory, names);
                if (found.isPresent()) {
                    return found;
                }
            }
        }

        for (String prefix : prefixes) {
            Path directory = parseDirectory(prefix);
            if (directory == null) {
                continue;
            }
            Optional<Path> found = firstExecutableIn(directory, names);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Splits {@code PATH} into entries.
     *
     * <p>On Windows a separator inside quotes does not separate: a directory
     * whose name contains {@code ;} is only expressible as a quoted entry, and
     * splitting it blindly turns one usable entry into two unusable fragments.
     * On POSIX a quote is an ordinary filename character and is left alone.
     *
     * <p>A quote only quotes when it opens an entry and is matched by one that
     * closes it. Anything else is an ordinary character, so a stray quote costs
     * its own entry and nothing else. Tracking quotes as a running toggle
     * instead would merge everything between two strays into a single unusable
     * entry — which is the failure the quoting support exists to prevent, only
     * harder to see.
     *
     * <p>One shape is beyond saving: a whole {@code PATH} wrapped in quotes,
     * as {@code set PATH="%PATH%;C:\lily\bin"} produces, is by this rule — and
     * by {@code cmd.exe}'s — a single entry naming a directory that does not
     * exist. Nothing is found, which is what every other Windows tool sees too.
     *
     * <p>The split is lossless: joining the entries with the separator
     * reproduces the input, whatever the quoting.
     */
    static List<String> splitPath(String pathVariable, boolean windows) {
        List<String> entries = new ArrayList<>();
        int position = 0;
        boolean quotingPossible = windows;
        while (position <= pathVariable.length()) {
            int end = -1;
            if (quotingPossible && position < pathVariable.length()
                    && pathVariable.charAt(position) == '"') {
                end = closingQuote(pathVariable, position);
                if (end < 0) {
                    // A closing quote is a position, not a property of this
                    // entry, so if none lies beyond here none lies beyond any
                    // later entry either. Without the latch, a PATH of stray
                    // quotes rescans to the end for each one, which is
                    // quadratic — 50 seconds on a 300 kB PATH.
                    quotingPossible = false;
                }
            }
            if (end < 0) {
                // Unquoted, or opened by a quote that never closes: read it as
                // an ordinary entry, so a malformed entry costs only itself.
                int separator = pathVariable.indexOf(java.io.File.pathSeparatorChar, position);
                end = separator >= 0 ? separator : pathVariable.length();
            }
            entries.add(pathVariable.substring(position, end));
            position = end + 1;
        }
        return entries;
    }

    /**
     * The index just past the quote closing the entry that opens at
     * {@code start}, or -1 if no quote closes it. A quote closes only when the
     * next character ends the entry.
     */
    private static int closingQuote(String pathVariable, int start) {
        for (int quote = pathVariable.indexOf('"', start + 1); quote >= 0;
                quote = pathVariable.indexOf('"', quote + 1)) {
            if (quote + 1 == pathVariable.length()
                    || pathVariable.charAt(quote + 1) == java.io.File.pathSeparatorChar) {
                return quote + 1;
            }
        }
        return -1;
    }

    /**
     * A {@code PATH} entry as a directory to search, or null if it is not one
     * we are willing to use.
     *
     * <p>Relative entries — including the empty entry, which a POSIX shell
     * reads as the working directory — are skipped rather than resolved. A
     * shell would run them, but what we find here is handed to
     * {@code ProcessBuilder}, and executing whatever {@code ./lilypond} happens
     * to be in the directory the user ran {@code mw} from is a footgun we get
     * nothing for. Anyone who genuinely wants one sets
     * {@code notation.lilypondPath}.
     */
    private static Path pathEntryDirectory(String entry, boolean windows) {
        String value = entry;
        if (windows) {
            // cmd.exe strips these; leaving them in makes the whole entry
            // unusable rather than merely unquoted.
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            // A quote cannot be part of a Windows filename, so an entry still
            // carrying one is malformed however it is read.
            if (value.indexOf('"') >= 0) {
                return null;
            }
        }
        if (value.isBlank()) {
            return null;
        }
        Path directory = parseDirectory(value);
        return directory != null && directory.isAbsolute() ? directory : null;
    }

    /** A path, or null when it does not parse on this platform. */
    private static Path parseDirectory(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            // One unusable entry must not cost us the rest of the search.
            return null;
        }
    }

    private static Optional<Path> firstExecutableIn(Path directory, List<String> names) {
        for (String name : names) {
            Path candidate = directory.resolve(name);
            if (isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Where to look once {@code PATH} has come up empty.
     *
     * <p>The POSIX list exists because Homebrew is not on a non-login shell's
     * {@code PATH}. Windows gets no such list: LilyPond has shipped no Windows
     * installer since 2.24, only a zip the user extracts wherever they like, so
     * there is no location to guess. The package managers that do install it —
     * Chocolatey, Scoop — put their shims on {@code PATH}, which is the route
     * this class now handles. Anyone else sets {@code notation.lilypondPath}.
     *
     * <p>Package-private so a test can pin the POSIX list: it is the reason
     * {@code mw doctor} finds a Homebrew install at all, and nothing else in
     * the suite can tell you it has been deleted.
     */
    static List<String> searchPrefixes(boolean windows) {
        if (windows) {
            return List.of();
        }
        return List.of(
                "/home/linuxbrew/.linuxbrew/bin",
                System.getProperty("user.home") + "/.linuxbrew/bin",
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "/usr/bin");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows");
    }

    private static boolean isExecutable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }
}
