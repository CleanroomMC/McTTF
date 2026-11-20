/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.api;

import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.extract.DirAssetSource;
import com.cleanroommc.mcttf.extract.JarAssetSource;
import com.cleanroommc.mcttf.font.FontStyle;
import com.cleanroommc.mcttf.font.TtfWriter;
import com.cleanroommc.mcttf.glyph.Glyph;
import com.cleanroommc.mcttf.glyph.GlyphBuilder;
import com.cleanroommc.mcttf.glyph.GlyphLoader;
import com.cleanroommc.mcttf.glyph.SimpleGlyphOutline;
import com.cleanroommc.mcttf.profile.FontDescriptor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embeddable entry point for converting vanilla Minecraft's bitmap fonts to TTF.
 */
public final class McTTF {

    /** The three fonts this tool targets: id -> display family name. */
    public static final Map<String, String> STANDARD_FONTS = Map.of("default", "Minecraft Default", "alt",
            "Minecraft Enchantment", "illageralt", "Minecraft Illager");

    private McTTF() { }

    /** Opens a client jar or an already-extracted assets directory as an {@link AssetSource}. */
    public static AssetSource openAssetSource(Path jarOrDir) throws IOException {
        File file = jarOrDir.toFile();
        return file.isFile() ? new JarAssetSource(file) : new DirAssetSource(jarOrDir);
    }

    /**
     * Converts a single font (e.g. {@code "default"}, {@code "alt"}, {@code "illageralt"})
     * from its JSON descriptor + backing PNGs into TTF bytes, in-memory.
     * <p>
     * 1.12.2 (and earlier) jars have no {@code font/*.json}; {@code default} and {@code alt}
     * are synthesized from {@code FontRenderer}'s hardcoded ascii grid plus
     * {@code glyph_sizes.bin} / unicode pages when those textures are present.
     *
     * @param assets     an open asset source (jar or extracted dir) - not closed by this call!
     * @param fontName   the font id, matching {@code assets/minecraft/font/<fontName>.json}
     *                   or a pre-1.13 synthesized profile
     * @param familyName the TTF family name to embed
     * @param style      which of Regular/Bold/Italic/Bold Italic to synthesize
     */
    public static byte[] convertToBytes(AssetSource assets, String fontName, String familyName, FontStyle style)
            throws IOException {
        return convertToBytes(assets, fontName, familyName, style, Map.of());
    }

    /**
     * Same as {@link #convertToBytes(AssetSource, String, String, FontStyle)}, with {@code fallback}
     * glyphs (e.g. from {@link com.cleanroommc.mcttf.glyph.TtfGlyphLoader}) filling every codepoint
     * the font's own providers don't define.
     */
    public static byte[] convertToBytes(AssetSource assets, String fontName, String familyName, FontStyle style,
            Map<Integer, Glyph> fallback) throws IOException {
        FontDescriptor descriptor = FontDescriptor.load(assets, fontName);
        GlyphLoader loader = new GlyphLoader(assets);
        Map<Integer, Glyph> glyphs = loader.load(descriptor);
        fallback.forEach(glyphs::putIfAbsent);

        // The glyph count is a u16 and .notdef takes one slot
        if (glyphs.size() > 0xFFFF - 1) {
            throw new IOException("Font '" + fontName + "' has " + glyphs.size()
                            + " glyphs with fallbacks merged, more than the 65,534 a TrueType font can hold");
        }

        Map<Integer, SimpleGlyphOutline> outlines = new LinkedHashMap<>();
        for (Map.Entry<Integer, Glyph> e : glyphs.entrySet()) {
            outlines.put(e.getKey(), GlyphBuilder.build(e.getValue(), style));
        }

        TtfWriter writer = new TtfWriter(familyName, style);
        writer.addGlyphs(outlines);
        return writer.toBytes();
    }

}
