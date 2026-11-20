/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.glyph;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a pixel TrueType font back into {@link Glyph} rasters, so it can fill codepoints
 * the Mojangles sheets lack.
 * <p>
 * The font must use simple glyphs with on-curve points only, every coordinate on a shared
 * pixel grid (e.g. Fusion Pixel 12px: 100 units per pixel). The grid size is the GCD of all
 * coordinates, advances and line metrics. One font pixel maps to one Minecraft design pixel.
 */
public final class TtfGlyphLoader {

    private TtfGlyphLoader() { }

    public static Map<Integer, Glyph> load(Path ttf) throws IOException {
        return load(Files.readAllBytes(ttf));
    }

    public static Map<Integer, Glyph> load(byte[] ttf) throws IOException {
        ByteBuffer font = ByteBuffer.wrap(ttf);
        Map<String, ByteBuffer> tables = tables(font);

        ByteBuffer head = table(tables, "head");
        ByteBuffer hhea = table(tables, "hhea");
        ByteBuffer hmtx = table(tables, "hmtx");
        ByteBuffer loca = table(tables, "loca");
        ByteBuffer glyf = table(tables, "glyf");
        boolean longLoca = head.getShort(50) != 0;
        int numGlyphs = table(tables, "maxp").getShort(4) & 0xFFFF;
        int ascender = hhea.getShort(4);
        int descender = hhea.getShort(6);
        int numHMetrics = hhea.getShort(34) & 0xFFFF;

        Map<Integer, Integer> cmap = cmap(table(tables, "cmap"));

        Map<Integer, int[][]> outlines = new HashMap<>();
        int unit = gcd(ascender, descender);
        for (int glyphId : cmap.values()) {
            if (glyphId >= numGlyphs || outlines.containsKey(glyphId)) {
                continue;
            }
            int start = longLoca ? loca.getInt(glyphId * 4) : (loca.getShort(glyphId * 2) & 0xFFFF) * 2;
            int end = longLoca ? loca.getInt(glyphId * 4 + 4) : (loca.getShort(glyphId * 2 + 2) & 0xFFFF) * 2;
            int[][] contours = end > start ? contours(glyf.slice(start, end - start), glyphId) : new int[0][];
            outlines.put(glyphId, contours);
            unit = gcd(unit, advance(hmtx, numHMetrics, glyphId));
            for (int[] c : contours) {
                for (int v : c) {
                    unit = gcd(unit, v);
                }
            }
        }
        unit = Math.max(unit, 1);

        int lineAscentPx = ascender / unit;
        int lineDescentPx = descender / unit;
        Map<Integer, Glyph> glyphs = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(cmap).entrySet()) {
            int codepoint = e.getKey();
            int[][] contours = outlines.get(e.getValue());
            if (contours == null || skipCodepoint(codepoint)) {
                continue;
            }
            int advancePx = advance(hmtx, numHMetrics, e.getValue()) / unit;
            if (contours.length == 0) {
                glyphs.put(codepoint, Glyph.space(codepoint, advancePx));
                continue;
            }

            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (int[] c : contours) {
                for (int i = 0; i < c.length; i += 2) {
                    minX = Math.min(minX, c[i]);
                    maxX = Math.max(maxX, c[i]);
                    minY = Math.min(minY, c[i + 1]);
                    maxY = Math.max(maxY, c[i + 1]);
                }
            }

            if (minX < 0) {
                throw new IOException(String.format("Glyph for U+%04X has a negative left bearing, "
                                + "which Minecraft glyph cells cannot represent",
                        codepoint));
            }

            int cellW = Math.max(advancePx, maxX / unit);

            // The cell grows past the line box for tall ink, keeping the baseline where it is
            int ascentPx = Math.max(lineAscentPx, maxY / unit);
            int cellH = ascentPx - Math.min(lineDescentPx, minY / unit);
            boolean[][] pixels = new boolean[cellH][cellW];
            int left = -1;
            int right = -1;
            for (int col = 0; col < cellW; col++) {
                double x = (col + 0.5) * unit;
                for (int row = 0; row < cellH; row++) {
                    if (inside(contours, x, (ascentPx - row - 0.5) * unit)) {
                        pixels[row][col] = true;
                        if (left < 0) {
                            left = col;
                        }
                        right = col;
                    }
                }
            }
            glyphs.put(codepoint,
                    left < 0
                            ? Glyph.space(codepoint, advancePx)
                            : new Glyph(codepoint, pixels, cellW, cellH, cellH, ascentPx, left, right, advancePx));
        }
        return glyphs;
    }

    /** Private-use glyphs carry font-specific meanings that would clash with resource packs. */
    private static boolean skipCodepoint(int codepoint) {
        int type = Character.getType(codepoint);
        return type == Character.CONTROL || type == Character.PRIVATE_USE || type == Character.SURROGATE;
    }

    private static Map<String, ByteBuffer> tables(ByteBuffer font) {
        Map<String, ByteBuffer> tables = new HashMap<>();
        int numTables = font.getShort(4) & 0xFFFF;
        for (int i = 0; i < numTables; i++) {
            int record = 12 + i * 16;
            byte[] tag = new byte[4];
            font.get(record, tag);
            tables.put(new String(tag), font.slice(font.getInt(record + 8), font.getInt(record + 12)));
        }
        return tables;
    }

    private static ByteBuffer table(Map<String, ByteBuffer> tables, String tag) throws IOException {
        ByteBuffer table = tables.get(tag);
        if (table == null) {
            throw new IOException("Missing '" + tag + "' table (only TrueType-outline fonts are supported)");
        }
        return table;
    }

    private static int advance(ByteBuffer hmtx, int numHMetrics, int glyphId) {
        return hmtx.getShort(Math.min(glyphId, numHMetrics - 1) * 4) & 0xFFFF;
    }

    /** Prefers a format 12 subtable (full Unicode), falling back to format 4 (BMP). */
    private static Map<Integer, Integer> cmap(ByteBuffer cmap) throws IOException {
        int format4 = -1;
        int numSubtables = cmap.getShort(2) & 0xFFFF;
        for (int i = 0; i < numSubtables; i++) {
            int platform = cmap.getShort(4 + i * 8) & 0xFFFF;
            int offset = cmap.getInt(8 + i * 8);
            if (platform != 0 && platform != 3) {
                continue;
            }
            int format = cmap.getShort(offset) & 0xFFFF;
            if (format == 12) {
                Map<Integer, Integer> map = new HashMap<>();
                int groups = cmap.getInt(offset + 12);
                for (int g = 0; g < groups; g++) {
                    int group = offset + 16 + g * 12;
                    int first = cmap.getInt(group);
                    int last = cmap.getInt(group + 4);
                    int glyph = cmap.getInt(group + 8);
                    for (int cp = first; cp <= last; cp++) {
                        map.put(cp, glyph + cp - first);
                    }
                }
                return map;
            }
            if (format == 4) {
                format4 = offset;
            }
        }
        if (format4 < 0) {
            throw new IOException("No Unicode cmap subtable in format 4 or 12");
        }
        Map<Integer, Integer> map = new HashMap<>();
        int segCount = (cmap.getShort(format4 + 6) & 0xFFFF) / 2;
        int ends = format4 + 14;
        int starts = ends + segCount * 2 + 2;
        int deltas = starts + segCount * 2;
        int rangeOffsets = deltas + segCount * 2;
        for (int s = 0; s < segCount; s++) {
            int end = cmap.getShort(ends + s * 2) & 0xFFFF;
            int start = cmap.getShort(starts + s * 2) & 0xFFFF;
            int delta = cmap.getShort(deltas + s * 2);
            int rangeOffset = cmap.getShort(rangeOffsets + s * 2) & 0xFFFF;
            for (int cp = start; cp <= end && cp != 0xFFFF; cp++) {
                int glyph;
                if (rangeOffset == 0) {
                    glyph = (cp + delta) & 0xFFFF;
                } else {
                    glyph = cmap.getShort(rangeOffsets + s * 2 + rangeOffset + (cp - start) * 2) & 0xFFFF;
                    if (glyph != 0) {
                        glyph = (glyph + delta) & 0xFFFF;
                    }
                }
                if (glyph != 0) {
                    map.put(cp, glyph);
                }
            }
        }
        return map;
    }

    /** Each contour as a flat {@code x0, y0, x1, y1, ...} array in font units. */
    private static int[][] contours(ByteBuffer glyph, int glyphId) throws IOException {
        int numContours = glyph.getShort(0);
        if (numContours < 0) {
            throw new IOException("Glyph " + glyphId
                            + " is a composite glyph, which pixel fallback fonts don't support");
        }
        int[] endPoints = new int[numContours];
        for (int i = 0; i < numContours; i++) {
            endPoints[i] = glyph.getShort(10 + i * 2) & 0xFFFF;
        }
        int numPoints = numContours == 0 ? 0 : endPoints[numContours - 1] + 1;
        int pos = 10 + numContours * 2;
        pos += 2 + (glyph.getShort(pos) & 0xFFFF);

        byte[] flags = new byte[numPoints];
        for (int i = 0; i < numPoints;) {
            byte flag = glyph.get(pos++);
            if ((flag & 1) == 0) {
                throw new IOException("Glyph " + glyphId + " has curves, so it is not a pixel font");
            }
            flags[i++] = flag;
            if ((flag & 8) != 0) {
                for (int repeat = glyph.get(pos++) & 0xFF; repeat > 0; repeat--) {
                    flags[i++] = flag;
                }
            }
        }

        int[] xs = new int[numPoints];
        int[] ys = new int[numPoints];
        int value = 0;
        for (int i = 0; i < numPoints; i++) {
            int f = flags[i];
            if ((f & 2) != 0) {
                int d = glyph.get(pos++) & 0xFF;
                value += (f & 16) != 0 ? d : -d;
            } else if ((f & 16) == 0) {
                value += glyph.getShort(pos);
                pos += 2;
            }
            xs[i] = value;
        }
        value = 0;
        for (int i = 0; i < numPoints; i++) {
            int f = flags[i];
            if ((f & 4) != 0) {
                int d = glyph.get(pos++) & 0xFF;
                value += (f & 32) != 0 ? d : -d;
            } else if ((f & 32) == 0) {
                value += glyph.getShort(pos);
                pos += 2;
            }
            ys[i] = value;
        }

        int[][] contours = new int[numContours][];
        int first = 0;
        for (int c = 0; c < numContours; c++) {
            int n = endPoints[c] - first + 1;
            int[] points = new int[n * 2];
            for (int i = 0; i < n; i++) {
                points[i * 2] = xs[first + i];
                points[i * 2 + 1] = ys[first + i];
            }
            contours[c] = points;
            first = endPoints[c] + 1;
        }
        return contours;
    }

    /** Nonzero winding test, the fill rule TrueType rasterizers use. */
    private static boolean inside(int[][] contours, double x, double y) {
        int winding = 0;
        for (int[] c : contours) {
            int n = c.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                int ax = c[i * 2];
                int ay = c[i * 2 + 1];
                int bx = c[j * 2];
                int by = c[j * 2 + 1];
                if ((ay <= y) != (by <= y) && ax + (y - ay) * (bx - ax) / (by - ay) > x) {
                    winding += by > ay ? 1 : -1;
                }
            }
        }
        return winding != 0;
    }

    private static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

}
