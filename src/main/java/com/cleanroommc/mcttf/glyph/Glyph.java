/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.glyph;

/**
 * A single glyph's raster, cropped to its source cell, plus the Minecraft-space metrics
 * needed to place and scale it in the generated font.
 *
 * @param pixels          row-major opacity grid, {@code pixels[row][col]}, sized {@code cellW x cellH}
 * @param cellW           cell width in texture pixels
 * @param cellH           cell height in texture pixels
 * @param providerHeight  design pixel height declared by the owning font provider
 * @param providerAscent  design pixel ascent (baseline offset from top) declared by the owning provider
 * @param leftCol         first opaque column, inclusive (-1 if glyph is blank)
 * @param rightCol        last opaque column, inclusive (-1 if glyph is blank)
 * @param explicitAdvance design-pixel advance width from a {@code space} provider, or -1 if unset
 */
public record Glyph(int codepoint,
        boolean[][] pixels,
        int cellW,
        int cellH,
        int providerHeight,
        int providerAscent,
        int leftCol,
        int rightCol,
        double explicitAdvance) {

    public Glyph(int codepoint, boolean[][] pixels, int cellW, int cellH, int providerHeight, int providerAscent,
            int leftCol, int rightCol) {
        this(codepoint, pixels, cellW, cellH, providerHeight, providerAscent, leftCol, rightCol, -1);
    }

    public static Glyph space(int codepoint, double advanceDesignPixels) {
        return new Glyph(codepoint, new boolean[0][0], 0, 0, 8, 0, -1, -1, advanceDesignPixels);
    }

    public boolean isBlank() {
        return leftCol < 0;
    }

    /**
     * Advance width in the same design-pixel ("visual pixel") space {@link #providerHeight} is measured in.
     * <p>
     * Matches Minecraft {@code BitmapProvider}: width is the last opaque column + 1 from the cell
     * left (empty left columns count), then {@code round_half_up(width * scale) + 1}. The trailing
     * {@code +1} is one design pixel, not one texture pixel. Empty bitmap cells advance by 1.
     * A {@code space} provider's explicit advance wins when set.
     */
    public double advanceDesignPixels() {
        if (explicitAdvance >= 0) {
            return explicitAdvance;
        }
        if (isBlank() || cellH <= 0) {
            return 1;
        }
        double scale = (double) providerHeight / cellH;
        return (int) (0.5 + (rightCol + 1) * scale) + 1;
    }

}
