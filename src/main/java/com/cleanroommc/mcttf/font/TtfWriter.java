/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.font;

import com.cleanroommc.mcttf.glyph.Glyph;
import com.cleanroommc.mcttf.glyph.GlyphBuilder;
import com.cleanroommc.mcttf.glyph.SimpleGlyphOutline;

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Assembles a minimal but spec-valid TrueType-outline sfnt font from traced glyph outlines. */
public final class TtfWriter {

    private static final long MAC_EPOCH_OFFSET_SECONDS = 2082844800L; // 1904-01-01 -> 1970-01-01

    private final List<SimpleGlyphOutline> glyphs = new ArrayList<>();
    private final Map<Integer, Integer> codepointToGlyphIndex = new LinkedHashMap<>();
    private final String familyName;
    private final FontStyle style;

    public TtfWriter(String familyName, FontStyle style) {
        this.familyName = familyName;
        this.style = style;
        this.glyphs.add(notdefOutline()); // glyph 0 = .notdef
    }

    /** A visible hollow box, so a missing character shows up obviously instead of rendering blank. */
    private static SimpleGlyphOutline notdefOutline() {
        int w = 5;
        int h = 8;
        boolean[][] pixels = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y][x] = (x == 0 || y == 0 || x == w - 1 || y == h - 1);
            }
        }
        Glyph notdef = new Glyph(0, pixels, w, h, 8, 7, 0, w - 1);
        return GlyphBuilder.build(notdef);
    }

    private static void patchU32(byte[] buf, int offset, long value) {
        buf[offset] = (byte) ((value >>> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, result, pos, p.length);
            pos += p.length;
        }
        return result;
    }

    private static byte[] pad4(byte[] data) {
        int rem = data.length % 4;
        if (rem == 0) {
            return data;
        }
        byte[] out = new byte[data.length + (4 - rem)];
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    private static long checksum(byte[] paddedData) {
        long sum = 0;
        for (int i = 0; i < paddedData.length; i += 4) {
            long word = ((long) (paddedData[i] & 0xFF) << 24) | ((long) (paddedData[i + 1] & 0xFF) << 16)
                    | ((long) (paddedData[i + 2] & 0xFF) << 8) | (paddedData[i + 3] & 0xFF);
            sum = (sum + word) & 0xFFFFFFFFL;
        }
        return sum;
    }

    private static int highestPowerOf2LE(int n) {
        int p = 1;
        while (p * 2 <= n) {
            p *= 2;
        }
        return p;
    }

    private static int log2(int powerOf2) {
        int r = 0;
        while ((1 << r) < powerOf2) {
            r++;
        }
        return r;
    }

    public void addGlyphs(Map<Integer, SimpleGlyphOutline> byCodepoint) {
        for (Map.Entry<Integer, SimpleGlyphOutline> e : new TreeMap<>(byCodepoint).entrySet()) {
            int glyphIndex = glyphs.size();
            glyphs.add(e.getValue());
            codepointToGlyphIndex.put(e.getKey(), glyphIndex);
        }
    }

    public void write(File outFile) throws IOException {
        Files.write(outFile.toPath(), toBytes());
    }

    public byte[] toBytes() {
        return build();
    }

    private byte[] build() {
        byte[] glyfBytes;
        int[] glyphOffsets = new int[glyphs.size() + 1];
        ByteWriter glyf = new ByteWriter();
        for (int i = 0; i < glyphs.size(); i++) {
            glyphOffsets[i] = glyf.size();
            glyf.bytes(encodeGlyph(glyphs.get(i)));
        }
        glyphOffsets[glyphs.size()] = glyf.size();
        glyfBytes = glyf.toByteArray();

        boolean longLoca = glyphOffsets[glyphs.size()] > 0xFFFF * 2;
        byte[] locaBytes = buildLoca(glyphOffsets, longLoca);
        byte[] cmapBytes = buildCmap();
        byte[] hmtxBytes = buildHmtx();
        byte[] hheaBytes = buildHhea();
        byte[] maxpBytes = buildMaxp();
        int[] fontBBox = fontBoundingBox();
        byte[] headBytes = buildHead(longLoca, fontBBox);
        byte[] nameBytes = buildName();
        byte[] postBytes = buildPost();
        byte[] os2Bytes = buildOs2(fontBBox);

        Map<String, byte[]> tables = new TreeMap<>();
        tables.put("OS/2", os2Bytes);
        tables.put("cmap", cmapBytes);
        tables.put("glyf", glyfBytes);
        tables.put("head", headBytes);
        tables.put("hhea", hheaBytes);
        tables.put("hmtx", hmtxBytes);
        tables.put("loca", locaBytes);
        tables.put("maxp", maxpBytes);
        tables.put("name", nameBytes);
        tables.put("post", postBytes);

        return assembleSfnt(tables);
    }

    private byte[] assembleSfnt(Map<String, byte[]> tables) {
        int numTables = tables.size();
        int searchRange = highestPowerOf2LE(numTables) * 16;
        int entrySelector = log2(highestPowerOf2LE(numTables));
        int rangeShift = numTables * 16 - searchRange;

        ByteWriter header = new ByteWriter();
        header.u32(0x00010000L);
        header.u16(numTables);
        header.u16(searchRange);
        header.u16(entrySelector);
        header.u16(rangeShift);

        int directorySize = 16 * numTables;
        int offset = header.size() + directorySize;

        List<TableEntry> entries = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : tables.entrySet()) {
            byte[] padded = pad4(e.getValue());
            long checksum = checksum(padded);
            entries.add(new TableEntry(e.getKey(), padded, offset, checksum));
            offset += padded.length;
        }

        ByteWriter directory = new ByteWriter();
        for (TableEntry e : entries) {
            directory.tag(e.tag());
            directory.u32(e.checksum());
            directory.u32(e.offset());
            directory.u32(tables.get(e.tag()).length);
        }

        ByteWriter body = new ByteWriter();
        for (TableEntry e : entries) {
            body.bytes(e.data());
        }

        byte[] full = concat(header.toByteArray(), directory.toByteArray(), body.toByteArray());

        long fileChecksum = checksum(pad4(full));
        long adjustment = (0xB1B0AFBAL - fileChecksum) & 0xFFFFFFFFL;
        int headOffset = findTableOffset(entries, "head");
        patchU32(full, headOffset + 8, adjustment); // checkSumAdjustment is the 3rd field in head (after version, fontRevision)

        return full;
    }

    private int findTableOffset(List<TableEntry> entries, String tag) {
        for (TableEntry e : entries) {
            if (e.tag().equals(tag)) {
                return e.offset();
            }
        }
        throw new IllegalStateException("missing table " + tag);
    }

    private byte[] encodeGlyph(SimpleGlyphOutline g) {
        if (g.contours().isEmpty()) {
            return new byte[0];
        }
        ByteWriter w = new ByteWriter();
        w.i16(g.contours().size());
        w.i16(g.minX());
        w.i16(g.minY());
        w.i16(g.maxX());
        w.i16(g.maxY());

        int cumulative = -1;
        for (List<Point> contour : g.contours()) {
            cumulative += contour.size();
            w.u16(cumulative);
        }
        w.u16(0); // instructionLength

        for (List<Point> contour : g.contours()) {
            for (int i = 0; i < contour.size(); i++) {
                w.u8(0x01); // ON_CURVE_POINT, full-width deltas for both axes
            }
        }
        int prevX = 0;
        int prevY = 0;
        for (List<Point> contour : g.contours()) {
            for (Point p : contour) {
                w.i16(p.x - prevX);
                prevX = p.x;
            }
        }
        prevX = 0;
        for (List<Point> contour : g.contours()) {
            for (Point p : contour) {
                w.i16(p.y - prevY);
                prevY = p.y;
            }
        }
        byte[] data = w.toByteArray();
        return data.length % 2 == 0 ? data : concat(data, new byte[] { 0 });
    }

    private byte[] buildLoca(int[] glyphOffsets, boolean longFormat) {
        ByteWriter w = new ByteWriter();
        for (int off : glyphOffsets) {
            if (longFormat) {
                w.u32(off);
            } else {
                w.u16(off / 2);
            }
        }
        return w.toByteArray();
    }

    /**
     * Consecutive (code, glyphId) runs. Each int[] is {startCode, endCode, startGlyphId}.
     */
    private List<int[]> cmapGroups(boolean bmpOnly) {
        List<int[]> groups = new ArrayList<>();
        int runStart = -1;
        int runEnd = -1;
        int runGid = -1;
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(codepointToGlyphIndex).entrySet()) {
            int code = e.getKey();
            int gid = e.getValue();
            if (bmpOnly && code > 0xFFFF) {
                continue;
            }
            if (runStart >= 0 && code == runEnd + 1 && gid == runGid + (runEnd - runStart + 1)) {
                runEnd = code;
            } else {
                if (runStart >= 0) {
                    groups.add(new int[] { runStart, runEnd, runGid });
                }
                runStart = runEnd = code;
                runGid = gid;
            }
        }
        if (runStart >= 0) {
            groups.add(new int[] { runStart, runEnd, runGid });
        }
        return groups;
    }

    private byte[] buildCmapFormat4(List<int[]> groups) {
        List<int[]> segs = new ArrayList<>(); // {startCode, endCode, idDelta}
        for (int[] g : groups) {
            segs.add(new int[] { g[0], g[1], (g[2] - g[0]) & 0xFFFF });
        }
        if (segs.isEmpty() || segs.get(segs.size() - 1)[1] != 0xFFFF) {
            segs.add(new int[] { 0xFFFF, 0xFFFF, 1 });
        }
        int segCount = segs.size();

        // The u16 length caps format 4 at 8,189 segments, and dropping some would hide BMP glyphs from its readers
        if (16 + segCount * 8 > 0xFFFF) {
            throw new IllegalStateException("The font maps BMP codepoints in " + segCount
                            + " separate runs, more than the 8,189 a format 4 cmap can hold");
        }
        int segCountX2 = segCount * 2;
        int searchRange = 2 * highestPowerOf2LE(segCount);
        int entrySelector = log2(highestPowerOf2LE(segCount));
        int rangeShift = segCountX2 - searchRange;
        int length = 16 + segCount * 8;

        ByteWriter sub = new ByteWriter();
        sub.u16(4);
        sub.u16(length);
        sub.u16(0);
        sub.u16(segCountX2);
        sub.u16(searchRange);
        sub.u16(entrySelector);
        sub.u16(rangeShift);
        for (int[] s : segs) {
            sub.u16(s[1]);
        }
        sub.u16(0);
        for (int[] s : segs) {
            sub.u16(s[0]);
        }
        for (int[] s : segs) {
            sub.i16(s[2]);
        }
        for (int[] s : segs) {
            sub.u16(0);
        }
        return sub.toByteArray();
    }

    private byte[] buildCmapFormat12(List<int[]> groups) {
        int groupCount = groups.size();
        int length = 16 + groupCount * 12;
        ByteWriter sub = new ByteWriter();
        sub.u16(12);
        sub.u16(0);
        sub.u32(length);
        sub.u32(0);
        sub.u32(groupCount);
        for (int[] g : groups) {
            sub.u32(g[0]);
            sub.u32(g[1]);
            sub.u32(g[2]);
        }
        return sub.toByteArray();
    }

    private byte[] buildCmap() {
        byte[] format4 = buildCmapFormat4(cmapGroups(true));
        byte[] format12 = buildCmapFormat12(cmapGroups(false));
        int headerSize = 4 + 8 * 2;
        ByteWriter cmap = new ByteWriter();
        cmap.u16(0);
        cmap.u16(2);
        cmap.u16(3);
        cmap.u16(1);
        cmap.u32(headerSize);
        cmap.u16(3);
        cmap.u16(10);
        cmap.u32(headerSize + format4.length);
        return concat(cmap.toByteArray(), format4, format12);
    }

    private byte[] buildHmtx() {
        ByteWriter w = new ByteWriter();
        for (SimpleGlyphOutline g : glyphs) {
            w.u16(g.advanceWidth());
            w.i16(g.leftSideBearing());
        }
        return w.toByteArray();
    }

    private byte[] buildHhea() {
        int advanceWidthMax = glyphs.stream().mapToInt(SimpleGlyphOutline::advanceWidth).max().orElse(0);
        int minLsb = glyphs.stream()
                .filter(g -> !g.contours().isEmpty())
                .mapToInt(SimpleGlyphOutline::leftSideBearing)
                .min()
                .orElse(0);
        int minRsb = glyphs.stream()
                .filter(g -> !g.contours().isEmpty())
                .mapToInt(g -> g.advanceWidth() - (g.maxX() - g.minX()) - g.leftSideBearing())
                .min()
                .orElse(0);
        int maxExtentX = glyphs.stream()
                .filter(g -> !g.contours().isEmpty())
                .mapToInt(g -> g.leftSideBearing() + (g.maxX() - g.minX()))
                .max()
                .orElse(0);

        int[] win = winMetrics(fontBoundingBox());
        ByteWriter w = new ByteWriter();
        w.u32(0x00010000L);
        w.i16(win[0]);
        w.i16(-win[1]);
        w.i16(0); // lineGap
        w.u16(advanceWidthMax);
        w.i16(minLsb);
        w.i16(minRsb);
        w.i16(maxExtentX);
        w.i16(1); // caretSlopeRise
        w.i16(0); // caretSlopeRun
        w.i16(0); // caretOffset
        w.i16(0);
        w.i16(0);
        w.i16(0);
        w.i16(0);
        w.i16(0); // metricDataFormat
        w.u16(glyphs.size()); // numberOfHMetrics
        return w.toByteArray();
    }

    private byte[] buildMaxp() {
        int maxPoints = glyphs.stream().mapToInt(SimpleGlyphOutline::pointCount).max().orElse(0);
        int maxContours = glyphs.stream().mapToInt(g -> g.contours().size()).max().orElse(0);
        ByteWriter w = new ByteWriter();
        w.u32(0x00010000L);
        w.u16(glyphs.size());
        w.u16(maxPoints);
        w.u16(maxContours);
        w.u16(0); // maxCompositePoints
        w.u16(0); // maxCompositeContours
        w.u16(2); // maxZones
        w.u16(0); // maxTwilightPoints
        w.u16(0); // maxStorage
        w.u16(0); // maxFunctionDefs
        w.u16(0); // maxInstructionDefs
        w.u16(0); // maxStackElements
        w.u16(0); // maxSizeOfInstructions
        w.u16(0); // maxComponentElements
        w.u16(0); // maxComponentDepth
        return w.toByteArray();
    }

    private int[] fontBoundingBox() {
        int minX = 0;
        int minY = 0;
        int maxX = 0;
        int maxY = 0;
        boolean any = false;
        for (SimpleGlyphOutline g : glyphs) {
            if (g.contours().isEmpty()) {
                continue;
            }
            if (!any) {
                minX = g.minX();
                minY = g.minY();
                maxX = g.maxX();
                maxY = g.maxY();
                any = true;
            } else {
                minX = Math.min(minX, g.minX());
                minY = Math.min(minY, g.minY());
                maxX = Math.max(maxX, g.maxX());
                maxY = Math.max(maxY, g.maxY());
            }
        }
        return new int[] { minX, minY, maxX, maxY };
    }

    private byte[] buildHead(boolean longLoca, int[] bbox) {
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) + MAC_EPOCH_OFFSET_SECONDS;
        ByteWriter w = new ByteWriter();
        w.u32(0x00010000L); // version
        w.u32(0x00010000L); // fontRevision
        w.u32(0); // checkSumAdjustment, patched later
        w.u32(0x5F0F3CF5L); // magicNumber
        w.u16(0); // flags
        w.u16(GlyphBuilder.UNITS_PER_EM);
        w.dateTime(now); // created
        w.dateTime(now); // modified
        w.i16(bbox[0]);
        w.i16(bbox[1]);
        w.i16(bbox[2]);
        w.i16(bbox[3]);
        w.u16(style.macStyleBits());
        w.u16(8); // lowestRecPPEM
        w.i16(2); // fontDirectionHint
        w.i16(longLoca ? 1 : 0); // indexToLocFormat
        w.i16(0); // glyphDataFormat
        return w.toByteArray();
    }

    private byte[] buildName() {
        String subfamilyName = style.subfamilyName();

        // PostScript names allow printable ASCII minus []{}()<>/% and at most 63 characters.
        // Other characters become their code point in hex, so Unicode families stay distinct.
        StringBuilder postScriptName = new StringBuilder();
        (familyName + "-" + subfamilyName).codePoints().forEach(cp -> {
            if (cp > ' ' && cp < 127 && "[](){}<>/%".indexOf(cp) < 0) {
                postScriptName.appendCodePoint(cp);
            } else if (cp != ' ') {
                postScriptName.append(String.format("U%04X", cp));
            }
        });
        postScriptName.setLength(Math.min(postScriptName.length(), 63));

        record Rec(int id, String value) { }
        List<Rec> recs = List.of(new Rec(1, familyName), new Rec(2, subfamilyName),
                new Rec(3, familyName + " " + subfamilyName + " (McTTF)"), new Rec(4, familyName + " " + subfamilyName),
                new Rec(5, "Version 1.0"), new Rec(6, postScriptName.toString()));

        ByteWriter strings = new ByteWriter();
        List<int[]> offsetsLengths = new ArrayList<>();
        for (Rec r : recs) {
            int start = strings.size();
            strings.utf16be(r.value());
            offsetsLengths.add(new int[] { start, strings.size() - start });
        }

        ByteWriter w = new ByteWriter();
        w.u16(0); // format
        w.u16(recs.size());
        w.u16(6 + recs.size() * 12); // storageOffset
        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            int[] ol = offsetsLengths.get(i);
            w.u16(3); // platformID windows
            w.u16(1); // encodingID unicode BMP
            w.u16(0x409); // languageID en-US
            w.u16(r.id());
            w.u16(ol[1]);
            w.u16(ol[0]);
        }
        return concat(w.toByteArray(), strings.toByteArray());
    }

    private byte[] buildPost() {
        ByteWriter w = new ByteWriter();
        w.u32(0x00030000L); // version 3.0, no glyph names
        double italicDegrees = style.italic() ? -Math.toDegrees(Math.atan(GlyphBuilder.ITALIC_SHEAR)) : 0;
        w.u32((int) Math.round(italicDegrees * 65536)); // italicAngle, Fixed 16.16
        w.i16((int) Math.round(GlyphBuilder.UNITS_PER_VISUAL_PIXEL * GlyphBuilder.UNDERLINE_POSITION_PIXELS));
        w.i16((int) Math.round(GlyphBuilder.UNITS_PER_VISUAL_PIXEL * GlyphBuilder.UNDERLINE_THICKNESS_PIXELS));
        w.u32(0); // isFixedPitch
        w.u32(0);
        w.u32(0);
        w.u32(0);
        w.u32(0);
        return w.toByteArray();
    }

    /** {@code [winAscent, winDescent]} covering the design line and the font bbox. */
    private int[] winMetrics(int[] bbox) {
        int designAscent = (int) Math.round(GlyphBuilder.UNITS_PER_VISUAL_PIXEL * GlyphBuilder.ASCENT_PIXELS);
        int designDescent = (int) Math.round(GlyphBuilder.UNITS_PER_VISUAL_PIXEL * GlyphBuilder.DESCENT_PIXELS);
        int winAscent = Math.max(designAscent, bbox[3]);
        int winDescent = Math.max(designDescent, Math.max(0, -bbox[1]));
        return new int[] { winAscent, winDescent };
    }

    private byte[] buildOs2(int[] bbox) {
        int firstChar = 0xFFFF;
        int lastBmp = 0;
        boolean anySmp = false;
        for (int code : codepointToGlyphIndex.keySet()) {
            if (code > 0xFFFF) {
                anySmp = true;
            } else {
                firstChar = Math.min(firstChar, code);
                lastBmp = Math.max(lastBmp, code);
            }
        }
        if (firstChar == 0xFFFF) {
            firstChar = 0;
        }
        int lastChar = anySmp ? 0xFFFF : lastBmp;
        int avgWidth = (int) glyphs.stream().mapToInt(SimpleGlyphOutline::advanceWidth).average().orElse(0);
        int ascent = (int) Math.round(GlyphBuilder.UNITS_PER_VISUAL_PIXEL * GlyphBuilder.ASCENT_PIXELS);
        int descent = (int) Math.round(GlyphBuilder.UNITS_PER_VISUAL_PIXEL * GlyphBuilder.DESCENT_PIXELS);
        int[] win = winMetrics(bbox);

        ByteWriter w = new ByteWriter();
        w.u16(1); // version (fields below match the fixed OS/2 version-1 layout, 86 bytes)
        w.i16(avgWidth); // xAvgCharWidth
        w.u16(style.bold() ? 700 : 400); // usWeightClass
        w.u16(5); // usWidthClass
        w.u16(0); // fsType
        w.i16(GlyphBuilder.UNITS_PER_EM / 5); // ySubscriptXSize
        w.i16(GlyphBuilder.UNITS_PER_EM / 5); // ySubscriptYSize
        w.i16(0); // ySubscriptXOffset
        w.i16(GlyphBuilder.UNITS_PER_EM / 5); // ySubscriptYOffset
        w.i16(GlyphBuilder.UNITS_PER_EM / 5); // ySuperscriptXSize
        w.i16(GlyphBuilder.UNITS_PER_EM / 5); // ySuperscriptYSize
        w.i16(0); // ySuperscriptXOffset
        w.i16(GlyphBuilder.UNITS_PER_EM / 5); // ySuperscriptYOffset
        w.i16((int) Math.round(GlyphBuilder.UNITS_PER_VISUAL_PIXEL * GlyphBuilder.STRIKEOUT_SIZE_PIXELS)); // yStrikeoutSize
        w.i16((int) Math.round(GlyphBuilder.UNITS_PER_VISUAL_PIXEL * GlyphBuilder.STRIKEOUT_POSITION_PIXELS)); // yStrikeoutPosition
        w.i16(0); // sFamilyClass
        for (int i = 0; i < 10; i++) {
            w.u8(0); // panose
        }
        w.u32(1); // ulUnicodeRange1 (basic latin)
        w.u32(0);
        w.u32(0);
        w.u32(0);
        w.tag("MCTF"); // achVendID
        w.u16(style.fsSelectionBits()); // fsSelection
        w.u16(firstChar);
        w.u16(lastChar);
        w.i16(ascent); // sTypoAscender
        w.i16(-descent); // sTypoDescender
        w.i16(0); // sTypoLineGap
        w.u16(win[0]); // usWinAscent
        w.u16(win[1]); // usWinDescent
        w.u32(1); // ulCodePageRange1 (Latin 1)
        w.u32(0);
        return w.toByteArray();
    }

    private record TableEntry(String tag, byte[] data, int offset, long checksum) { }

}
