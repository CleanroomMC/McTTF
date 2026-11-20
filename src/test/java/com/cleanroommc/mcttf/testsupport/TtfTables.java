/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.testsupport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal sfnt reader for tests: locate tables and look up cmap / OS/2 / head fields. */
public final class TtfTables {

    public final byte[] data;
    public final Map<String, Table> tables = new LinkedHashMap<>();

    public TtfTables(byte[] data) {
        this.data = data;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buf.position(4);
        int numTables = buf.getShort() & 0xFFFF;
        buf.position(12);
        for (int i = 0; i < numTables; i++) {
            byte[] tag = new byte[4];
            buf.get(tag);
            buf.getInt();
            int offset = buf.getInt();
            int length = buf.getInt();
            tables.put(new String(tag), new Table(offset, length));
        }
    }

    public static long fileChecksum(byte[] data) {
        int rem = data.length % 4;
        byte[] padded = rem == 0 ? data : java.util.Arrays.copyOf(data, data.length + (4 - rem));
        long sum = 0;
        for (int i = 0; i < padded.length; i += 4) {
            long word = ((padded[i] & 0xFFL) << 24) | ((padded[i + 1] & 0xFFL) << 16) | ((padded[i + 2] & 0xFFL) << 8)
                    | (padded[i + 3] & 0xFFL);
            sum = (sum + word) & 0xFFFFFFFFL;
        }
        return sum;
    }

    private static int lookupFormat12(ByteBuffer cmap, int offset, int codepoint) {
        int groupCount = cmap.position(offset + 12).getInt();
        int pos = offset + 16;
        for (int i = 0; i < groupCount; i++) {
            int start = cmap.position(pos).getInt();
            int end = cmap.getInt();
            int startGid = cmap.getInt();
            if (codepoint >= start && codepoint <= end) {
                return startGid + (codepoint - start);
            }
            pos += 12;
        }
        return 0;
    }

    private static int lookupFormat4(ByteBuffer cmap, int offset, int codepoint) {
        int segCount = (cmap.position(offset + 6).getShort() & 0xFFFF) / 2;
        int endOff = offset + 14;
        int startOff = endOff + 2 * segCount + 2;
        int deltaOff = startOff + 2 * segCount;
        int rangeOff = deltaOff + 2 * segCount;
        for (int i = 0; i < segCount; i++) {
            int end = cmap.position(endOff + 2 * i).getShort() & 0xFFFF;
            int start = cmap.position(startOff + 2 * i).getShort() & 0xFFFF;
            if (codepoint < start || codepoint > end) {
                continue;
            }
            int range = cmap.position(rangeOff + 2 * i).getShort() & 0xFFFF;
            int delta = cmap.position(deltaOff + 2 * i).getShort();
            if (range == 0) {
                return (codepoint + delta) & 0xFFFF;
            }
            return 0;
        }
        return 0;
    }

    public ByteBuffer table(String tag) {
        Table t = tables.get(tag);
        if (t == null) {
            throw new IllegalArgumentException("missing table " + tag);
        }
        return ByteBuffer.wrap(data, t.offset, t.length)
                .order(ByteOrder.BIG_ENDIAN)
                .slice()
                .order(ByteOrder.BIG_ENDIAN);
    }

    public int unitsPerEm() {
        return table("head").position(18).getShort() & 0xFFFF;
    }

    public int usLastCharIndex() {
        return table("OS/2").position(66).getShort() & 0xFFFF;
    }

    public int usWinAscent() {
        return table("OS/2").position(74).getShort() & 0xFFFF;
    }

    public int glyphId(int codepoint) {
        ByteBuffer cmap = table("cmap");
        int numTables = cmap.position(2).getShort() & 0xFFFF;
        Integer format12 = null;
        Integer format4 = null;
        for (int i = 0; i < numTables; i++) {
            cmap.position(4 + i * 8);
            int plat = cmap.getShort() & 0xFFFF;
            int enc = cmap.getShort() & 0xFFFF;
            int offset = cmap.getInt();
            int format = cmap.position(offset).getShort() & 0xFFFF;
            if (format == 12 && plat == 3) {
                format12 = offset;
            } else if (format == 4 && plat == 3 && enc == 1) {
                format4 = offset;
            }
        }
        if (format12 != null) {
            int gid = lookupFormat12(cmap, format12, codepoint);
            if (gid != 0 || codepoint == 0) {
                return gid;
            }
        }
        if (format4 != null && codepoint <= 0xFFFF) {
            return lookupFormat4(cmap, format4, codepoint);
        }
        return 0;
    }

    public int format4Length() {
        ByteBuffer cmap = table("cmap");
        int numTables = cmap.position(2).getShort() & 0xFFFF;
        for (int i = 0; i < numTables; i++) {
            cmap.position(4 + i * 8 + 4);
            int offset = cmap.getInt();
            if ((cmap.position(offset).getShort() & 0xFFFF) == 4) {
                return cmap.position(offset + 2).getShort() & 0xFFFF;
            }
        }
        return -1;
    }

    public record Table(int offset, int length) { }

}
