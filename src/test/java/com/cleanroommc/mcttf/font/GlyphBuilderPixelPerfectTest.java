package com.cleanroommc.mcttf.font;

import com.cleanroommc.mcttf.glyph.Glyph;
import com.cleanroommc.mcttf.glyph.GlyphBuilder;
import com.cleanroommc.mcttf.glyph.SimpleGlyphOutline;
import com.cleanroommc.mcttf.testsupport.WindingRasterizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves {@link GlyphBuilder#build(Glyph)} - the actual production path, tracer plus the
 * scale/baseline transform into font-unit space - stays pixel-perfect for a REGULAR glyph:
 * mapping each source pixel's center through the exact same formula the builder uses, then
 * rasterizing the emitted (rounded-to-integer-font-units) contours back with a nonzero
 * winding fill, must reproduce every source pixel exactly. This also exercises non-square
 * cellW/cellH and non-8 provider heights, unlike the raw tracer tests.
 */
class GlyphBuilderPixelPerfectTest {

    static Stream<Arguments> patterns() {
        return Stream.of(
                Arguments.of("letter A, 8px cell", """
                        .###....
                        #...#...
                        #####...
                        #...#...
                        #...#...
                        #...#...
                        #...#...
                        ........
                        """),
                Arguments.of("ring, 16px supersampled cell", """
                        ................
                        .##############.
                        .#............#.
                        .#............#.
                        .#............#.
                        .#............#.
                        .#............#.
                        .#............#.
                        .#............#.
                        .#............#.
                        .#............#.
                        .#............#.
                        .#............#.
                        .#............#.
                        .##############.
                        ................
                        """),
                Arguments.of("figure-8 double pinch", """
                        .###.
                        #...#
                        .#.#.
                        #...#
                        .###.
                        """)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("patterns")
    void productionTransformStaysPixelPerfect(String name, String art) {
        boolean[][] pixels = parse(art);
        int cellH = pixels.length;
        int cellW = pixels[0].length;
        int providerHeight = 8;
        int providerAscent = 7;

        int left = -1, right = -1;
        for (int x = 0; x < cellW; x++) {
            for (int y = 0; y < cellH; y++) {
                if (pixels[y][x]) {
                    if (left < 0) left = x;
                    right = x;
                }
            }
        }
        Glyph glyph = new Glyph(0, pixels, cellW, cellH, providerHeight, providerAscent, left, right);
        SimpleGlyphOutline outline = GlyphBuilder.build(glyph);

        double visualPxPerTexturePx = (double) providerHeight / cellH;
        double unitsPerTexturePx = GlyphBuilder.UNITS_PER_VISUAL_PIXEL * visualPxPerTexturePx;
        double ascentInTexturePx = providerAscent / visualPxPerTexturePx;

        for (int y = 0; y < cellH; y++) {
            for (int x = 0; x < cellW; x++) {
                double fx = (x + 0.5) * unitsPerTexturePx;
                double fy = (ascentInTexturePx - (y + 0.5)) * unitsPerTexturePx;
                boolean expected = pixels[y][x];
                boolean actual = WindingRasterizer.filled(outline.contours(), fx, fy);
                assertEquals(expected, actual, "pixel (" + x + "," + y + ") in: " + name);
            }
        }
    }

    private static boolean[][] parse(String art) {
        String[] lines = art.strip().lines().toArray(String[]::new);
        int h = lines.length;
        int w = lines[0].length();
        boolean[][] pixels = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y][x] = lines[y].charAt(x) == '#';
            }
        }
        return pixels;
    }
}
