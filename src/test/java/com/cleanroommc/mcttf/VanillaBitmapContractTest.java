/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf;

import com.cleanroommc.mcttf.api.McTTF;
import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.font.FontStyle;
import com.cleanroommc.mcttf.glyph.Glyph;
import com.cleanroommc.mcttf.glyph.GlyphBuilder;
import com.cleanroommc.mcttf.glyph.GlyphLoader;
import com.cleanroommc.mcttf.glyph.SimpleGlyphOutline;
import com.cleanroommc.mcttf.profile.FontDescriptor;
import com.cleanroommc.mcttf.testsupport.TtfTables;
import com.cleanroommc.mcttf.testsupport.VanillaFixtures;
import com.cleanroommc.mcttf.testsupport.WindingRasterizer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the shipped load → metrics → outline → write path against real vanilla 26.2
 * font JSON + PNGs. Expected advances are derived from the sliced source cell with
 * Minecraft's BitmapProvider rule (last opaque column + 1, then + 1 design pixel).
 */
class VanillaBitmapContractTest {

    static final int BOX_DRAWING_LIGHT_VERTICAL = 0x2502;
    static final int GOTHIC_LETTER_AHSA = 0x10330;
    static final int BUCKET_EMOJI = 0x1FAA3;

    /** Minecraft BitmapProvider advance: (int)(0.5 + (right+1)*scale) + 1, empty → 1. */
    static int minecraftBitmapAdvance(Glyph glyph) {
        if (glyph.explicitAdvance() >= 0) {
            return (int) glyph.explicitAdvance();
        }
        int right = lastOpaqueColumn(glyph.pixels());
        if (right < 0 || glyph.cellH() <= 0) {
            return 1;
        }
        double scale = (double) glyph.providerHeight() / glyph.cellH();
        return (int) (0.5 + (right + 1) * scale) + 1;
    }

    static int lastOpaqueColumn(boolean[][] pixels) {
        if (pixels.length == 0) {
            return -1;
        }
        int right = -1;
        for (int x = 0; x < pixels[0].length; x++) {
            for (boolean[] pixel : pixels) {
                if (pixel[x]) {
                    right = x;
                    break;
                }
            }
        }
        return right;
    }

    static int firstOpaqueColumn(boolean[][] pixels) {
        if (pixels.length == 0) {
            return -1;
        }
        for (int x = 0; x < pixels[0].length; x++) {
            for (boolean[] pixel : pixels) {
                if (pixel[x]) {
                    return x;
                }
            }
        }
        return -1;
    }

    static void assertPixelsMatch(Glyph glyph) {
        SimpleGlyphOutline outline = GlyphBuilder.build(glyph, FontStyle.REGULAR);
        double visualPxPerTexturePx = (double) glyph.providerHeight() / glyph.cellH();
        double unitsPerTexturePx = GlyphBuilder.UNITS_PER_VISUAL_PIXEL * visualPxPerTexturePx;
        double ascentInTexturePx = glyph.providerAscent() / visualPxPerTexturePx;
        for (int y = 0; y < glyph.cellH(); y++) {
            for (int x = 0; x < glyph.cellW(); x++) {
                double fx = (x + 0.5) * unitsPerTexturePx;
                double fy = (ascentInTexturePx - (y + 0.5)) * unitsPerTexturePx;
                boolean expected = glyph.pixels()[y][x];
                boolean actual = WindingRasterizer.filled(outline.contours(), fx, fy);
                assertThat(actual)
                        .as("pixel (" + x + "," + y + ") U+" + Integer.toHexString(glyph.codepoint()))
                        .isEqualTo(expected);
            }
        }
    }

    private static Map<Integer, Glyph> loadDefault() throws Exception {
        try (AssetSource assets = VanillaFixtures.open()) {
            return new GlyphLoader(assets).load(FontDescriptor.load(assets, "default"));
        }
    }

    @Test
    void nonlatinEuropeanCellsAreEightByEightAndKeepSupplementaryScalars() throws Exception {
        Map<Integer, Glyph> glyphs = loadDefault();
        Glyph gothic = glyphs.get(GOTHIC_LETTER_AHSA);
        Glyph bucket = glyphs.get(BUCKET_EMOJI);
        assertThat(gothic != null).as("U+10330 must be loaded as its own scalar").isTrue();
        assertThat(bucket != null).as("U+1FAA3 must be loaded as its own scalar").isTrue();
        assertThat(gothic.cellW()).isEqualTo(8);
        assertThat(gothic.cellH()).isEqualTo(8);
        assertThat(bucket.cellW()).isEqualTo(8);
        assertThat(bucket.cellH()).isEqualTo(8);
        assertThat(!glyphs.containsKey(GOTHIC_LETTER_AHSA & 0xFFFF)
                        || glyphs.get(GOTHIC_LETTER_AHSA & 0xFFFF) != gothic).isTrue();
    }

    @Test
    void advanceMatchesMinecraftRightEdgeRuleOnAscii() throws Exception {
        Map<Integer, Glyph> glyphs = loadDefault();

        Glyph box = glyphs.get(BOX_DRAWING_LIGHT_VERTICAL);
        int derivedBox = minecraftBitmapAdvance(box);
        assertThat((int) box.advanceDesignPixels()).isEqualTo(derivedBox);
        assertThat(lastOpaqueColumn(box.pixels())).as("U+2502 last opaque col in 8px cell").isEqualTo(4);
        assertThat(derivedBox).isEqualTo(6);

        Glyph a = glyphs.get((int) 'A');
        int derivedA = minecraftBitmapAdvance(a);
        assertThat((int) a.advanceDesignPixels()).isEqualTo(derivedA);
        assertThat(firstOpaqueColumn(a.pixels())).as("A has no left padding").isEqualTo(0);
        assertThat(derivedA > 1).isTrue();

        Glyph space = glyphs.get((int) ' ');
        assertThat(space.advanceDesignPixels()).isEqualTo(4);
    }

    @Test
    void regularOutlinesRoundTripVanillaCells() throws Exception {
        Map<Integer, Glyph> glyphs = loadDefault();
        assertPixelsMatch(glyphs.get((int) 'A'));
        assertPixelsMatch(glyphs.get((int) 'O'));
        assertPixelsMatch(glyphs.get(BOX_DRAWING_LIGHT_VERTICAL));
        assertPixelsMatch(glyphs.get(GOTHIC_LETTER_AHSA));
    }

    @Test
    void writtenDefaultTtfMapsBmpAndSupplementaryCodepoints() throws Exception {
        byte[] bytes;
        try (AssetSource assets = VanillaFixtures.open()) {
            bytes = McTTF.convertToBytes(assets, "default", "Minecraft Default", FontStyle.REGULAR);
        }
        TtfTables ttf = new TtfTables(bytes);
        int gidA = ttf.glyphId('A');
        int gidGothic = ttf.glyphId(GOTHIC_LETTER_AHSA);
        int gidBucket = ttf.glyphId(BUCKET_EMOJI);
        assertThat(gidA > 0).as("A must map").isTrue();
        assertThat(gidGothic > 0).as("U+10330 must map via format 12").isTrue();
        assertThat(gidBucket > 0).as("U+1FAA3 must map via format 12").isTrue();
        assertThat(gidGothic).isNotEqualTo(gidA);
        assertThat(gidBucket).isNotEqualTo(gidGothic);
        assertThat(ttf.glyphId(GOTHIC_LETTER_AHSA & 0xFFFF)).isNotEqualTo(gidA);
        assertThat(ttf.format4Length() > 0 && ttf.format4Length() < 65535).isTrue();
        assertThat(ttf.usLastCharIndex()).isEqualTo(0xFFFF);
        assertThat(ttf.unitsPerEm()).isEqualTo(GlyphBuilder.UNITS_PER_EM);
        assertThat(ttf.usWinAscent() >= 1000).as("win ascent must cover accented 12px (ascent 10) yMax").isTrue();
        assertThat(TtfTables.fileChecksum(bytes)).isEqualTo(0xB1B0AFBAL);
    }

}
