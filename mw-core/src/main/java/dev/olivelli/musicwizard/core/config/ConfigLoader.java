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
    private static final String[] POSIX_EXECUTABLES = {"lilypond"};
    private static final String[] WINDOWS_EXECUTABLES = {"lilypond.exe", "lilypond.bat", "lilypond.cmd"};

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
     * The discovery half of {@link #findLilyPond}, with the environment passed
     * in so that either platform's rules can be exercised from a test running
     * on the other. {@code windows} selects the executable names, the search
     * prefixes and the {@code PATH} separator; nothing else about the host
     * changes with it.
     */
    static Optional<Path> discover(String pathVariable, boolean windows) {
        String[] names = windows ? WINDOWS_EXECUTABLES : POSIX_EXECUTABLES;

        if (pathVariable != null) {
            for (String entry : pathVariable.split(windows ? ";" : ":")) {
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

        for (String prefix : searchPrefixes(windows)) {
            Optional<Path> found = firstExecutableIn(Path.of(prefix), names);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
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
        if (entry.isBlank()) {
            return null;
        }
        String value = entry;
        if (windows && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            // cmd.exe strips these; leaving them in makes the whole entry
            // unusable rather than merely unquoted.
            value = value.substring(1, value.length() - 1);
        }
        Path directory;
        try {
            directory = Path.of(value);
        } catch (InvalidPathException e) {
            // One unparseable entry must not cost us the rest of PATH.
            return null;
        }
        return directory.isAbsolute() ? directory : null;
    }

    private static Optional<Path> firstExecutableIn(Path directory, String[] names) {
        for (String name : names) {
            Path candidate = directory.resolve(name);
            if (isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Where to look once {@code PATH} has come up empty. On Windows this is the
     * layout the LilyPond installer uses; the POSIX list exists because
     * Homebrew is not on a non-login shell's {@code PATH}.
     */
    private static List<String> searchPrefixes(boolean windows) {
        if (!windows) {
            return List.of(
                    "/home/linuxbrew/.linuxbrew/bin",
                    System.getProperty("user.home") + "/.linuxbrew/bin",
                    "/opt/homebrew/bin",
                    "/usr/local/bin",
                    "/usr/bin");
        }
        List<String> prefixes = new ArrayList<>();
        for (String variable : new String[] {"ProgramFiles", "ProgramFiles(x86)"}) {
            String root = System.getenv(variable);
            if (root != null && !root.isBlank()) {
                prefixes.add(root + "\\LilyPond\\usr\\bin");
            }
        }
        return prefixes;
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
