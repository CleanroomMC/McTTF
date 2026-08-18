package com.cleanroommc.mcttf.font;

import com.cleanroommc.mcttf.glyph.Glyph;
import com.cleanroommc.mcttf.glyph.GlyphBuilder;
import com.cleanroommc.mcttf.glyph.SimpleGlyphOutline;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TtfWriterSmokeTest {

    @Test
    void producesFontThatJavaCanLoad() throws Exception {
        // 8x8 'O'-like ring so the contour tracer must emit an outer + inner (hole) contour.
        boolean[][] ring = new boolean[8][8];
        for (int y = 1; y < 7; y++) {
            for (int x = 1; x < 7; x++) {
                boolean border = (y == 1 || y == 6 || x == 1 || x == 6);
                ring[y][x] = border;
            }
        }
        Glyph oGlyph = new Glyph('O', ring, 8, 8, 8, 7, 1, 6);

        boolean[][] solid = new boolean[8][8];
        for (int y = 2; y < 6; y++) {
            for (int x = 2; x < 6; x++) {
                solid[y][x] = true;
            }
        }
        Glyph blockGlyph = new Glyph('I', solid, 8, 8, 8, 7, 2, 5);

        Map<Integer, SimpleGlyphOutline> outlines = new LinkedHashMap<>();
        outlines.put((int) 'O', GlyphBuilder.build(oGlyph));
        outlines.put((int) 'I', GlyphBuilder.build(blockGlyph));

        TtfWriter writer = new TtfWriter("McTTF Smoke", FontStyle.REGULAR);
        writer.addGlyphs(outlines);

        File tmp = File.createTempFile("mcttf-smoke", ".ttf");
        tmp.deleteOnExit();
        writer.write(tmp);

        assertTrue(tmp.length() > 0);

        Font font = Font.createFont(Font.TRUETYPE_FONT, tmp);
        assertTrue(font.canDisplay('O'));
        assertTrue(font.canDisplay('I'));
    }
}
