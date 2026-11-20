/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.font;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Growable big-endian byte buffer, matching the primitive types used throughout the sfnt spec. */
final class ByteWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    ByteWriter u8(int v) {
        out.write(v & 0xFF);
        return this;
    }

    ByteWriter u16(int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
        return this;
    }

    ByteWriter i16(int v) {
        return u16(v);
    }

    ByteWriter u32(long v) {
        out.write((int) ((v >>> 24) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) (v & 0xFF));
        return this;
    }

    /** sfnt LONGDATETIME: an 8-byte big-endian signed count of seconds. */
    ByteWriter dateTime(long seconds) {
        u32((seconds >>> 32) & 0xFFFFFFFFL);
        u32(seconds & 0xFFFFFFFFL);
        return this;
    }

    ByteWriter tag(String fourCc) {
        byte[] b = fourCc.getBytes(StandardCharsets.US_ASCII);
        if (b.length != 4) {
            throw new IllegalArgumentException("tag must be 4 chars: " + fourCc);
        }
        out.writeBytes(b);
        return this;
    }

    ByteWriter bytes(byte[] b) {
        out.writeBytes(b);
        return this;
    }

    ByteWriter utf16be(String s) {
        for (int i = 0; i < s.length(); i++) {
            u16(s.charAt(i));
        }
        return this;
    }

    ByteWriter padTo4() {
        while (out.size() % 4 != 0) {
            out.write(0);
        }
        return this;
    }

    int size() {
        return out.size();
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

}
