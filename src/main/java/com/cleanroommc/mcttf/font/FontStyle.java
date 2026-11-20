/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.font;

/**
 * The four styles a TTF family conventionally ships -
 * (matching {@code head.macStyle}/{@code OS/2.fsSelection} semantics).
 * Synthesized the same way Minecraft itself does at render time:
 * Bold by dilating the glyph raster 1px. Italic by shearing the outline.
 */
public enum FontStyle {

    REGULAR("Regular", false, false),
    BOLD("Bold", true, false),
    ITALIC("Italic", false, true),
    BOLD_ITALIC("Bold Italic", true, true);

    private final String subfamilyName;
    private final boolean bold;
    private final boolean italic;

    FontStyle(String subfamilyName, boolean bold, boolean italic) {
        this.subfamilyName = subfamilyName;
        this.bold = bold;
        this.italic = italic;
    }

    public String subfamilyName() {
        return subfamilyName;
    }

    public boolean bold() {
        return bold;
    }

    public boolean italic() {
        return italic;
    }

    public String fileSuffix() {
        return this == REGULAR ? "" : "-" + subfamilyName.replace(" ", "");
    }

    public int macStyleBits() {
        return (bold ? 0x0001 : 0) | (italic ? 0x0002 : 0);
    }

    public int fsSelectionBits() {
        if (this == REGULAR) {
            return 0x0040;
        }
        return (italic ? 0x0001 : 0) | (bold ? 0x0020 : 0);
    }

}
