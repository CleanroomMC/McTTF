package com.cleanroommc.mcttf;

import com.cleanroommc.mcttf.api.McTTF;
import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.glyph.Glyph;
import com.cleanroommc.mcttf.glyph.GlyphLoader;
import com.cleanroommc.mcttf.profile.FontDescriptor;
import com.cleanroommc.mcttf.testsupport.TtfTables;
import com.cleanroommc.mcttf.testsupport.VanillaFixtures;
import com.cleanroommc.mcttf.testsupport.WindingRasterizer;
import com.cleanroommc.mcttf.font.FontStyle;
import com.cleanroommc.mcttf.glyph.GlyphBuilder;
import com.cleanroommc.mcttf.glyph.SimpleGlyphOutline;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the shipped load → metrics → outline → write path against real vanilla 26.2
 * font JSON + PNGs. Expected advances are derived from the sliced source cell with
 * Minecraft's BitmapProvider rule (last opaque column + 1, then + 1 design pixel).
 */
class VanillaBitmapContractTest {

    static final int BOX_DRAWING_LIGHT_VERTICAL = 0x2502;
    static final int GOTHIC_LETTER_AHSA = 0x10330;
    static final int BUCKET_EMOJI = 0x1FAA3;

    @Test
    void nonlatinEuropeanCellsAreEightByEightAndKeepSupplementaryScalars() throws Exception {
        Map<Integer, Glyph> glyphs = loadDefault();
        Glyph gothic = glyphs.get(GOTHIC_LETTER_AHSA);
        Glyph bucket = glyphs.get(BUCKET_EMOJI);
        assertTrue(gothic != null, "U+10330 must be loaded as its own scalar");
        assertTrue(bucket != null, "U+1FAA3 must be loaded as its own scalar");
        assertEquals(8, gothic.cellW());
        assertEquals(8, gothic.cellH());
        assertEquals(8, bucket.cellW());
        assertEquals(8, bucket.cellH());
        assertTrue(!glyphs.containsKey(GOTHIC_LETTER_AHSA & 0xFFFF)
                || glyphs.get(GOTHIC_LETTER_AHSA & 0xFFFF) != gothic);
    }

    @Test
    void advanceMatchesMinecraftRightEdgeRuleOnAscii() throws Exception {
        Map<Integer, Glyph> glyphs = loadDefault();

        Glyph box = glyphs.get(BOX_DRAWING_LIGHT_VERTICAL);
        int derivedBox = minecraftBitmapAdvance(box);
        assertEquals(derivedBox, (int) box.advanceDesignPixels());
        assertEquals(4, lastOpaqueColumn(box.pixels()), "U+2502 last opaque col in 8px cell");
        assertEquals(6, derivedBox);

        Glyph a = glyphs.get((int) 'A');
        int derivedA = minecraftBitmapAdvance(a);
        assertEquals(derivedA, (int) a.advanceDesignPixels());
        assertEquals(0, firstOpaqueColumn(a.pixels()), "A has no left padding");
        assertTrue(derivedA > 1);

        Glyph space = glyphs.get((int) ' ');
        assertEquals(4, space.advanceDesignPixels());
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
        assertTrue(gidA > 0, "A must map");
        assertTrue(gidGothic > 0, "U+10330 must map via format 12");
        assertTrue(gidBucket > 0, "U+1FAA3 must map via format 12");
        assertNotEquals(gidA, gidGothic);
        assertNotEquals(gidGothic, gidBucket);
        assertNotEquals(gidA, ttf.glyphId(GOTHIC_LETTER_AHSA & 0xFFFF));
        assertTrue(ttf.format4Length() > 0 && ttf.format4Length() < 65535);
        assertEquals(0xFFFF, ttf.usLastCharIndex());
        assertEquals(GlyphBuilder.UNITS_PER_EM, ttf.unitsPerEm());
        assertTrue(ttf.usWinAscent() >= 1000, "win ascent must cover accented 12px (ascent 10) yMax");
        assertEquals(0xB1B0AFBAL, TtfTables.fileChecksum(bytes));
    }

    private static Map<Integer, Glyph> loadDefault() throws Exception {
        try (AssetSource assets = VanillaFixtures.open()) {
            return new GlyphLoader(assets).load(FontDescriptor.load(assets, "default"));
        }
    }

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
                assertEquals(expected, actual, "pixel (" + x + "," + y + ") U+"
                        + Integer.toHexString(glyph.codepoint()));
            }
        }
    }
}
