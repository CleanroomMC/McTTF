/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.outline;

import com.cleanroommc.mcttf.testsupport.WindingRasterizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.awt.Point;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ContourTracer}'s output is pixel-perfect: rasterizing the traced contours
 * back (nonzero winding rule, sampled at each pixel's center - the same rule TrueType scan
 * conversion uses) must reproduce the exact source bitmap, for every pixel, with no
 * exceptions. This is what "pixel-perfect" actually means for a vector trace of a bitmap,
 * and it's cheap to check exhaustively rather than just eyeballing a render.
 */
class ContourTracerPixelPerfectTest {

    private static final Map<String, String> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put("solid block",
                """
                ....
                .##.
                .##.
                ....
                """);
        PATTERNS.put("ring with a hole",
                """
                ........
                .######.
                .#....#.
                .#....#.
                .#....#.
                .#....#.
                .######.
                ........
                """);
        PATTERNS.put("letter A (diagonal pinch counter)",
                """
                .###....
                #...#...
                #####...
                #...#...
                #...#...
                #...#...
                #...#...
                ........
                """);
        PATTERNS.put("figure-8 double pinch",
                """
                .###.
                #...#
                .#.#.
                #...#
                .###.
                """);
        PATTERNS.put("two separate holes (letter B-ish)",
                """
                ####..
                #..#..
                #..#..
                ####..
                #..#..
                #..#..
                ####..
                """);
        PATTERNS.put("nested island inside a hole",
                """
                .......
                .#####.
                .#...#.
                .#.#.#.
                .#...#.
                .#####.
                .......
                """);
        PATTERNS.put("checkerboard (many diagonal-only touches)",
                """
                #.#.#.
                .#.#.#
                #.#.#.
                .#.#.#
                """);
        PATTERNS.put("single pixel",
                """
                ...
                .#.
                ...
                """);
        PATTERNS.put("L-tromino touching itself diagonally",
                """
                #.....
                ##....
                .##...
                ..##..
                """);
        PATTERNS.put("empty",
                """
                ....
                ....
                """);
    }

    static Stream<Arguments> patterns() {
        return PATTERNS.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("patterns")
    void tracedContoursRasterizeBackToTheExactSourceBitmap(String name, String art) {
        boolean[][] pixels = parse(art);
        List<List<Point>> contours = ContourTracer.trace(pixels);

        int h = pixels.length;
        int w = h == 0 ? 0 : pixels[0].length;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean expected = pixels[y][x];
                boolean actual = WindingRasterizer.filled(contours, x + 0.5, y + 0.5);
                assertThat(actual).as("pixel (" + x + "," + y + ") in: " + name).isEqualTo(expected);
            }
        }
    }

    @Test
    void everyContourIsClosedAndRectilinear() {
        for (Map.Entry<String, String> e : PATTERNS.entrySet()) {
            boolean[][] pixels = parse(e.getValue());
            for (List<Point> contour : ContourTracer.trace(pixels)) {
                for (int i = 0; i < contour.size(); i++) {
                    Point a = contour.get(i);
                    Point b = contour.get((i + 1) % contour.size());
                    boolean axisAligned = a.x == b.x || a.y == b.y;
                    assertThat(axisAligned).as("non-axis-aligned edge in: " + e.getKey()).isEqualTo(true);
                }
            }
        }
    }

}
