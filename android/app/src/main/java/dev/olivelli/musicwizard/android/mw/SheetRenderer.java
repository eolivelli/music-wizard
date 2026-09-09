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

package dev.olivelli.musicwizard.android.mw;

import alphaTab.ILogger;
import alphaTab.LayoutMode;
import alphaTab.LogLevel;
import alphaTab.Logger;
import alphaTab.NotationElement;
import alphaTab.Settings;
import alphaTab.core.ecmaScript.Uint8Array;
import alphaTab.importer.ScoreLoader;
import alphaTab.model.Chord;
import alphaTab.model.Score;
import alphaTab.model.Staff;
import alphaTab.model.Track;
import alphaTab.rendering.RenderFinishedEventArgs;
import alphaTab.rendering.ScoreRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kotlin.Unit;

/**
 * Engraves MusicXML with alphaTab, one result per drawn chunk, on whatever
 * thread calls it; renders are serialized, since alphaTab's logger is
 * process-wide.
 *
 * <p>alphaTab reports a failure on an event and otherwise returns nothing, so a
 * caller that only read the results would take an empty page for a healthy one.
 * Every way of producing nothing is a {@link Result#failure()} here, and no
 * failure escapes as an exception.
 */
public final class SheetRenderer {

    private static final Object LOCK = new Object();

    /** Draws into {@code android.graphics.Bitmap}s; needs {@link #initialize} once. */
    public static final String ENGINE_ANDROID = "android";

    /** Renders SVG text; no Android at all, which is what a JVM test uses. */
    public static final String ENGINE_SVG = "svg";

    /** Records {@code android.graphics.Picture}s, for vector PDF pages; needs {@link #initialize} once. */
    public static final String ENGINE_PICTURE = "picture";

    private static boolean initialized;

    private SheetRenderer() {
    }

    /**
     * One drawn chunk: a system, or the title or footer, which hold no bar.
     *
     * @param firstBar the first bar's index, negative for a chunk with none
     */
    public record Partial(Object result, double x, double y, double width, double height,
                          int firstBar, int lastBar) {

        public boolean isSystem() {
            return firstBar >= 0;
        }
    }

    /**
     * What a render produced.
     *
     * @param failure why nothing usable came out, or {@code null} when
     *                {@code partials} is the whole page
     */
    public record Result(List<Partial> partials, double totalWidth, double totalHeight,
                         List<String> warnings, String failure) {

        public boolean succeeded() {
            return failure == null;
        }

        static Result failed(String why, List<String> warnings) {
            return new Result(List.of(), 0, 0, List.copyOf(warnings), why);
        }
    }

    /**
     * Loads the music font the Android engines draw with and registers the
     * picture engine. Before the first render with {@link #ENGINE_ANDROID} or
     * {@link #ENGINE_PICTURE}; later calls are no-ops.
     */
    public static void initialize(android.content.Context context) {
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            alphaTab.platform.android.AndroidCanvas.Companion.initialize(context);
            alphaTab.Environment.Companion.getRenderEngines().set(ENGINE_PICTURE,
                    new alphaTab.RenderEngineFactory(false, PictureCanvas::new));
            initialized = true;
        }
    }

    /**
     * @param widthPx the page width the systems are laid out to
     * @param scale   how large the engraving is drawn, where one is alphaTab's
     *                own size; the display density goes here
     */
    public static Result render(byte[] musicXml, String engine, double widthPx, double scale) {
        Objects.requireNonNull(musicXml, "musicXml");
        Objects.requireNonNull(engine, "engine");
        List<String> warnings = new ArrayList<>();
        if (!(widthPx > 0) || !Double.isFinite(widthPx)) {
            return Result.failed("the page has no width to lay the music out to", warnings);
        }
        if (!(scale > 0) || !Double.isFinite(scale)) {
            return Result.failed("the engraving has no size to be drawn at", warnings);
        }
        // Any other name falls back to alphaTab's default engine, whose
        // natives are excluded from the app.
        if (!engine.equals(ENGINE_ANDROID) && !engine.equals(ENGINE_SVG)
                && !engine.equals(ENGINE_PICTURE)) {
            return Result.failed("no such engine: " + engine, warnings);
        }
        synchronized (LOCK) {
            // Unregistered, the name would fall back the same way.
            if (engine.equals(ENGINE_PICTURE) && !initialized) {
                return Result.failed("the picture engine is not initialized", warnings);
            }
            try {
                return engrave(musicXml, engine, widthPx, scale, warnings);
            } catch (Throwable t) {
                return Result.failed("alphaTab could not engrave it: " + t, warnings);
            }
        }
    }

    private static Result engrave(byte[] musicXml, String engine, double widthPx, double scale,
                                  List<String> warnings) {
        Settings settings = new Settings();
        settings.getCore().setEngine(engine);
        settings.getCore().setUseWorkers(false);
        // Each system is drawn as soon as it is laid out, so the results arrive
        // inside the render call rather than on request.
        settings.getCore().setEnableLazyLoading(false);
        settings.getCore().setLogLevel(LogLevel.Warning);
        settings.getDisplay().setScale(scale);
        settings.getDisplay().setLayoutMode(LayoutMode.Page);
        // Drawn beside the first system, a track's name is cut to the staff's
        // height.
        settings.getNotation().getElements().set(NotationElement.TrackNames, false);

        ILogger previous = Logger.Companion.getLog();
        Logger.Companion.setLog(new Collecting(warnings));
        try {
            Score score;
            try {
                score = ScoreLoader.Companion.loadScoreFromBytes(bytesOf(musicXml), settings);
            } catch (Throwable t) {
                return Result.failed("the MusicXML could not be read: " + t, warnings);
            }
            nameNoChords(score);
            List<Partial> partials = new ArrayList<>();
            Throwable[] error = new Throwable[1];
            RenderFinishedEventArgs[] finished = new RenderFinishedEventArgs[1];
            ScoreRenderer renderer = new ScoreRenderer(settings);
            renderer.setWidth(widthPx);
            renderer.getError().on(t -> {
                error[0] = t;
                return Unit.INSTANCE;
            });
            renderer.getPartialRenderFinished().on(e -> {
                partials.add(new Partial(e.getRenderResult(), e.getX(), e.getY(), e.getWidth(),
                        e.getHeight(), (int) e.getFirstMasterBarIndex(),
                        (int) e.getLastMasterBarIndex()));
                return Unit.INSTANCE;
            });
            renderer.getRenderFinished().on(e -> {
                finished[0] = e;
                return Unit.INSTANCE;
            });
            try {
                renderer.renderTracks(score.getTracks());
            } catch (Throwable t) {
                error[0] = t;
            }
            if (error[0] != null) {
                return Result.failed("alphaTab could not engrave it: " + error[0], warnings);
            }
            if (finished[0] == null) {
                return Result.failed("alphaTab produced nothing and said nothing", warnings);
            }
            if (partials.isEmpty()) {
                return Result.failed("alphaTab laid out no system", warnings);
            }
            return new Result(List.copyOf(partials), finished[0].getTotalWidth(),
                    finished[0].getTotalHeight(), List.copyOf(warnings), null);
        } finally {
            Logger.Companion.setLog(previous);
        }
    }

    /**
     * alphaTab prints a chord as its root's step and its kind's text, so the
     * MusicXML idiom for no chord, a root that displays nothing under a kind
     * of {@code none}, comes out as the step and the text run together.
     */
    private static void nameNoChords(Score score) {
        for (Track track : score.getTracks()) {
            for (Staff staff : track.getStaves()) {
                for (Chord chord : staff.getChords().values()) {
                    if (chord.getName().endsWith(NO_CHORD) && !chord.getName().equals(NO_CHORD)) {
                        chord.setName(NO_CHORD);
                    }
                }
            }
        }
    }

    private static final String NO_CHORD =
            dev.olivelli.musicwizard.core.model.ChordQuality.NONE.symbol();

    /** The byte-array constructor is not visible to Java, so the bytes are copied in one by one. */
    private static Uint8Array bytesOf(byte[] bytes) {
        Uint8Array array = new Uint8Array((double) bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            array.set(i, (double) (bytes[i] & 0xFF));
        }
        return array;
    }

    /** alphaTab's warnings and errors, kept so a result can carry them. */
    private static final class Collecting implements ILogger {

        private final List<String> lines;

        Collecting(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public void debug(String category, String message, Object... details) {
        }

        @Override
        public void info(String category, String message, Object... details) {
        }

        @Override
        public void warning(String category, String message, Object... details) {
            lines.add(category + ": " + message);
        }

        @Override
        public void error(String category, String message, Object... details) {
            lines.add(category + ": " + message);
        }
    }
}
