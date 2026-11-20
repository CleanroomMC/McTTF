/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.glyph;

import com.cleanroommc.mcttf.font.FontStyle;
import com.cleanroommc.mcttf.font.TtfWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TtfGlyphLoaderTest {

    @Test
    void roundTripsTracedGlyphPixelForPixel(@TempDir Path dir) throws Exception {
        boolean[][] ring = new boolean[8][8];
        for (int y = 1; y < 7; y++) {
            for (int x = 1; x < 7; x++) {
                ring[y][x] = y == 1 || y == 6 || x == 1 || x == 6;
            }
        }
        Glyph source = new Glyph('O', ring, 8, 8, 8, 7, 1, 6);

        TtfWriter writer = new TtfWriter("McTTF Round Trip", FontStyle.REGULAR);
        writer.addGlyphs(Map.of((int) 'O', GlyphBuilder.build(source), (int) ' ',
                GlyphBuilder.build(Glyph.space(' ', 4))));
        Path ttf = dir.resolve("roundtrip.ttf");
        Files.write(ttf, writer.toBytes());

        Map<Integer, Glyph> loaded = TtfGlyphLoader.load(ttf);
        Glyph o = loaded.get((int) 'O');
        assertThat(o.advanceDesignPixels()).isEqualTo(source.advanceDesignPixels());
        assertThat(loaded.get((int) ' ').isBlank()).isTrue();
        assertThat(loaded.get((int) ' ').advanceDesignPixels()).isEqualTo(4);

        int rowShift = o.providerAscent() - source.providerAscent();
        for (int row = 0; row < o.cellH(); row++) {
            for (int col = 0; col < o.cellW(); col++) {
                int sourceRow = row - rowShift;
                boolean expected = sourceRow >= 0 && sourceRow < 8 && col < 8 && ring[sourceRow][col];
                assertThat(o.pixels()[row][col]).as("pixel at row " + row + ", col " + col).isEqualTo(expected);
            }
        }

        int unitsPerPixel = (int) GlyphBuilder.UNITS_PER_VISUAL_PIXEL;
        assertThat(GlyphBuilder.build(o, FontStyle.BOLD).advanceWidth()).isEqualTo(GlyphBuilder.build(o).advanceWidth()
                        + unitsPerPixel);
    }

}
