/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.glyph;

import com.cleanroommc.mcttf.font.FontStyle;
import com.cleanroommc.mcttf.outline.ContourTracer;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a Minecraft {@link Glyph} raster into a {@link SimpleGlyphOutline} in font-unit space.
 * <p>
 * The em is modeled on Minecraft's own chat line box: a {@link #LINE_HEIGHT_PIXELS}-pixel-tall
 * line with a {@link #ASCENT_PIXELS}-pixel ascent, exactly the convention Minecraft documents for
 * reimporting a TTF as a {@code ttf} font provider at {@code "size": 12} and getting a pixel-perfect
 * match. ascii glyphs (8px cells at height 8) and unicode-page glyphs (16px cells at height 8, 2x
 * supersampled) end up the same physical size once scaled, since scaling is driven by each
 * provider's declared {@code height}, not its texture's raw cell size.
 * <p>
 * Bold and italic are synthesized the same way Minecraft's own renderer does it:
 * Bold by dilating the source raster 1px to the right before tracing.
 * Italic by shearing the traced outline in {@code GlyphBitmap} line-top space ({@code x' = x + 1 - 0.25 * y},
 * y from the hardcoded 7px reference line), matching {@code BakedSheetGlyph.shearTop/Bottom}.
 */
public final class GlyphBuilder {

    public static final int LINE_HEIGHT_PIXELS = 12;
    public static final int UNITS_PER_EM = 1200;
    public static final double UNITS_PER_VISUAL_PIXEL = (double) UNITS_PER_EM / LINE_HEIGHT_PIXELS;
    public static final double ITALIC_SHEAR = 0.25;
    /**
     * Minecraft {@code GlyphBitmap.getTop()} measures italic shear from a hardcoded 7px
     * reference line ({@code 7 - bearingTop}), not from the baseline.
     */
    public static final double MC_LINE_TOP_PIXELS = 7;
    public static final double ASCENT_PIXELS = 9;
    public static final double DESCENT_PIXELS = 2;
    public static final double STRIKEOUT_POSITION_PIXELS = 4;
    public static final double STRIKEOUT_SIZE_PIXELS = 1;
    public static final double UNDERLINE_POSITION_PIXELS = -1;
    public static final double UNDERLINE_THICKNESS_PIXELS = 1;

    private GlyphBuilder() { }

    public static SimpleGlyphOutline build(Glyph glyph) {
        return build(glyph, FontStyle.REGULAR);
    }

    public static SimpleGlyphOutline build(Glyph glyph, FontStyle style) {
        Glyph source = style.bold() ? dilate(glyph) : glyph;

        int advanceWidth = (int) Math.round(source.advanceDesignPixels() * UNITS_PER_VISUAL_PIXEL);
        if (style.bold() && source.isBlank()) {
            advanceWidth += (int) Math.round(UNITS_PER_VISUAL_PIXEL);
        }
        if (source.isBlank() || source.cellH() <= 0) {
            return SimpleGlyphOutline.empty(advanceWidth);
        }

        double visualPxPerTexturePx = (double) source.providerHeight() / source.cellH();
        double unitsPerTexturePx = UNITS_PER_VISUAL_PIXEL * visualPxPerTexturePx;
        double ascentInTexturePx = source.providerAscent() / visualPxPerTexturePx;

        List<List<Point>> pixelContours = ContourTracer.trace(source.pixels());

        List<List<Point>> fontContours = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (List<Point> pixelContour : pixelContours) {
            List<Point> fontContour = new ArrayList<>(pixelContour.size());
            for (Point p : pixelContour) {
                int fy = (int) Math.round((ascentInTexturePx - p.y) * unitsPerTexturePx);
                double fxVisual = p.x * visualPxPerTexturePx;
                if (style.italic()) {
                    double mcY = (MC_LINE_TOP_PIXELS - source.providerAscent()) + p.y * visualPxPerTexturePx;
                    fxVisual += 1.0 - ITALIC_SHEAR * mcY;
                }
                int fx = (int) Math.round(fxVisual * UNITS_PER_VISUAL_PIXEL);
                fontContour.add(new Point(fx, fy));
                minX = Math.min(minX, fx);
                maxX = Math.max(maxX, fx);
                minY = Math.min(minY, fy);
                maxY = Math.max(maxY, fy);
            }
            fontContours.add(fontContour);
        }

        int lsb = minX;
        return new SimpleGlyphOutline(fontContours, minX, minY, maxX, maxY, advanceWidth, lsb);
    }

    /**
     * OR's each row with itself shifted 1px right, matching Minecraft's own bold rendering
     * (which literally draws the glyph twice, offset by one pixel). The canvas is widened by
     * 1px first so a glyph that already fills its cell's right edge doesn't get clipped.
     */
    private static Glyph dilate(Glyph glyph) {
        if (glyph.isBlank() || glyph.cellH() <= 0) {
            return glyph;
        }
        int w = glyph.cellW();
        int h = glyph.cellH();
        int newW = w + 1;
        boolean[][] src = glyph.pixels();
        boolean[][] out = new boolean[h][newW];
        int left = -1;
        int right = -1;
        for (int x = 0; x < newW; x++) {
            boolean columnOpaque = false;
            for (int y = 0; y < h; y++) {
                boolean fromHere = x < w && src[y][x];
                boolean fromLeft = x > 0 && (x - 1) < w && src[y][x - 1];
                boolean v = fromHere || fromLeft;
                out[y][x] = v;
                columnOpaque |= v;
            }
            if (columnOpaque) {
                if (left < 0) {
                    left = x;
                }
                right = x;
            }
        }
        double advance = glyph.explicitAdvance() < 0 ? -1 : glyph.explicitAdvance() + 1;
        return new Glyph(glyph.codepoint(), out, newW, h, glyph.providerHeight(), glyph.providerAscent(), left, right,
                advance);
    }

}
