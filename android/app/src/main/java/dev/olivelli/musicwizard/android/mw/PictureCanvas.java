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

import alphaTab.Settings;
import alphaTab.model.Color;
import alphaTab.model.Font;
import alphaTab.model.FontStyle;
import alphaTab.model.FontWeight;
import alphaTab.model.MusicFontSymbol;
import alphaTab.platform.ICanvas;
import alphaTab.platform.MeasuredText;
import alphaTab.platform.TextAlign;
import alphaTab.platform.TextBaseline;
import alphaTab.platform.android.AndroidCanvasKt;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * alphaTab's Android canvas drawing into a {@link Picture} rather than a
 * bitmap, so a chunk replays as vectors on a PDF page. A port of the library's
 * AndroidCanvas.kt in its order, so the two can be read side by side; the
 * music font is the one that class loads.
 */
final class PictureCanvas implements ICanvas {

    /** From AndroidCanvas.kt, where they are inlined constants. */
    private static final int MUSIC_FONT_SIZE = 34;
    private static final float HANGING_AS_PERCENT_OF_ASCENT = 80;

    private Picture picture;
    private Canvas canvas;
    private Path path;
    private String typefaceKey = "";
    private Typeface typeface;

    private Settings settings;
    private Color color = new Color(0, 0, 0, 255);
    private double lineWidth = 1;
    private Font font = new Font("Arial", 10, FontStyle.Plain, FontWeight.Regular);
    private TextAlign textAlign = TextAlign.Left;
    private TextBaseline textBaseline = TextBaseline.Top;

    @Override
    public Settings getSettings() {
        return settings;
    }

    @Override
    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    @Override
    public Color getColor() {
        return color;
    }

    @Override
    public void setColor(Color color) {
        this.color = color;
    }

    @Override
    public double getLineWidth() {
        return lineWidth;
    }

    @Override
    public void setLineWidth(double lineWidth) {
        this.lineWidth = lineWidth;
    }

    @Override
    public Font getFont() {
        return font;
    }

    @Override
    public void setFont(Font font) {
        this.font = font;
    }

    @Override
    public TextAlign getTextAlign() {
        return textAlign;
    }

    @Override
    public void setTextAlign(TextAlign textAlign) {
        this.textAlign = textAlign;
    }

    @Override
    public TextBaseline getTextBaseline() {
        return textBaseline;
    }

    @Override
    public void setTextBaseline(TextBaseline textBaseline) {
        this.textBaseline = textBaseline;
    }

    private Typeface typeface() {
        String key = font.toCssString(settings.getDisplay().getScale());
        if (!key.equals(typefaceKey)) {
            typefaceKey = key;
            int style = font.isBold() && font.isItalic() ? Typeface.BOLD_ITALIC
                    : font.isBold() ? Typeface.BOLD
                    : font.isItalic() ? Typeface.ITALIC
                    : Typeface.NORMAL;
            typeface = Typeface.create(font.getFamily(), style);
        }
        return typeface;
    }

    @Override
    public void beginRender(double width, double height) {
        picture = new Picture();
        canvas = picture.beginRecording((int) Math.ceil(width), (int) Math.ceil(height));
        textBaseline = TextBaseline.Top;
        path = new Path();
        path.setFillType(Path.FillType.WINDING);
    }

    @Override
    public Object endRender() {
        picture.endRecording();
        Picture drawn = picture;
        picture = null;
        canvas = null;
        return drawn;
    }

    @Override
    public void destroy() {
    }

    @Override
    public Object onRenderFinished() {
        return null;
    }

    @Override
    public void fillRect(double x, double y, double w, double h) {
        Paint paint = paint();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(rect(x, y, w, h), paint);
    }

    @Override
    public void strokeRect(double x, double y, double w, double h) {
        Paint paint = paint();
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(rect(x, y, w, h), paint);
    }

    /** Snapped to whole units on the left and top, as the bitmap canvas does. */
    private static RectF rect(double x, double y, double w, double h) {
        int left = (int) x;
        int top = (int) y;
        return new RectF(left, top, (float) (left + w), (float) (top + h));
    }

    private Paint paint() {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setDither(false);
        paint.setARGB((int) color.getA(), (int) color.getR(), (int) color.getG(),
                (int) color.getB());
        return paint;
    }

    @Override
    public void beginPath() {
        path.reset();
    }

    @Override
    public void closePath() {
        path.close();
    }

    @Override
    public void moveTo(double x, double y) {
        path.moveTo((float) x, (float) y);
    }

    @Override
    public void lineTo(double x, double y) {
        path.lineTo((float) x, (float) y);
    }

    @Override
    public void quadraticCurveTo(double cpx, double cpy, double x, double y) {
        path.quadTo((float) cpx, (float) cpy, (float) x, (float) y);
    }

    @Override
    public void bezierCurveTo(double cp1X, double cp1Y, double cp2X, double cp2Y,
                              double x, double y) {
        path.cubicTo((float) cp1X, (float) cp1Y, (float) cp2X, (float) cp2Y,
                (float) x, (float) y);
    }

    @Override
    public void fillCircle(double x, double y, double radius) {
        beginPath();
        path.addCircle((float) x, (float) y, (float) radius, Path.Direction.CW);
        closePath();
        fill();
    }

    @Override
    public void strokeCircle(double x, double y, double radius) {
        beginPath();
        path.addCircle((float) x, (float) y, (float) radius, Path.Direction.CW);
        closePath();
        stroke();
    }

    @Override
    public void fill() {
        Paint paint = paint();
        paint.setStrokeWidth(0);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        path.reset();
    }

    @Override
    public void stroke() {
        Paint paint = paint();
        paint.setStrokeWidth((float) lineWidth);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);
        path.reset();
    }

    @Override
    public void beginGroup(String identifier) {
    }

    @Override
    public void endGroup() {
    }

    @Override
    public void fillText(String text, double x, double y) {
        Paint paint = textPaint(typeface(), font.getSize() * settings.getDisplay().getScale());
        paint.setTextAlign(switch (textAlign) {
            case Left -> Paint.Align.LEFT;
            case Center -> Paint.Align.CENTER;
            case Right -> Paint.Align.RIGHT;
        });
        canvas.drawText(text, (float) x, (float) y + baseline(paint), paint);
    }

    private Paint textPaint(Typeface face, double size) {
        Paint paint = paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(face);
        paint.setTextSize((float) size);
        paint.setSubpixelText(true);
        paint.setHinting(Paint.HINTING_ON);
        return paint;
    }

    /** The offsets Chromium's canvas uses for each baseline, as the bitmap canvas does. */
    private float baseline(Paint paint) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        return switch (textBaseline) {
            case Top -> -metrics.ascent * HANGING_AS_PERCENT_OF_ASCENT / 100;
            case Middle -> (-metrics.ascent - metrics.descent) / 2;
            case Alphabetic -> 0;
            case Bottom -> -metrics.descent;
        };
    }

    @Override
    public MeasuredText measureText(String text) {
        if (text.isEmpty()) {
            return new MeasuredText(0, 0);
        }
        Paint paint = textPaint(typeface(), font.getSize());
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        return new MeasuredText(bounds.width(), bounds.height());
    }

    @Override
    public void fillMusicFontSymbol(double x, double y, double scale, MusicFontSymbol symbol,
                                    Boolean centerAtPosition) {
        fillMusicFontSymbols(x, y, scale, new alphaTab.collections.List<>(symbol),
                centerAtPosition);
    }

    @Override
    public void fillMusicFontSymbols(double x, double y, double scale,
                                     alphaTab.collections.List<MusicFontSymbol> symbols,
                                     Boolean centerAtPosition) {
        StringBuilder glyphs = new StringBuilder();
        for (MusicFontSymbol symbol : symbols) {
            if (symbol != MusicFontSymbol.None) {
                glyphs.append((char) symbol.getValue());
            }
        }
        Paint paint = textPaint(AndroidCanvasKt.getMusicFont(), MUSIC_FONT_SIZE * scale);
        if (Boolean.TRUE.equals(centerAtPosition)) {
            paint.setTextAlign(Paint.Align.CENTER);
        }
        canvas.drawText(glyphs.toString(), (float) x, (float) y, paint);
    }

    @Override
    public void beginRotate(double centerX, double centerY, double angle) {
        canvas.save();
        canvas.translate((float) centerX, (float) centerY);
        canvas.rotate((float) angle);
    }

    @Override
    public void endRotate() {
        canvas.restore();
    }
}
