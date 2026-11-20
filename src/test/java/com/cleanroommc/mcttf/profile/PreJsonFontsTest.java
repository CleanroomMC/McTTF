/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.profile;

import com.cleanroommc.mcttf.api.McTTF;
import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.extract.DirAssetSource;
import com.cleanroommc.mcttf.font.FontStyle;
import com.cleanroommc.mcttf.glyph.Glyph;
import com.cleanroommc.mcttf.glyph.GlyphBuilder;
import com.cleanroommc.mcttf.glyph.GlyphLoader;
import com.cleanroommc.mcttf.glyph.SimpleGlyphOutline;
import com.cleanroommc.mcttf.testsupport.MemoryAssetSource;
import com.cleanroommc.mcttf.testsupport.TtfTables;
import com.cleanroommc.mcttf.testsupport.VanillaFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PreJsonFontsTest {

    static final Path VANILLA_1_12_2 = Path.of(System.getProperty("user.home"),
            ".gradle/caches/unimined/net/minecraft/minecraft/1.12.2/minecraft-1.12.2-client.jar");

    @Test
    void asciiGridIsTheFontRendererSheet() {
        assertThat(PreJsonFonts.ASCII_GRID.length()).isEqualTo(256);
        assertThat(PreJsonFonts.ASCII_GRID.charAt(32)).isEqualTo(' ');
        assertThat(PreJsonFonts.ASCII_GRID.charAt(4 * 16 + 1)).isEqualTo('A');
        assertThat(PreJsonFonts.ASCII_GRID.charAt(6 * 16 + 1)).isEqualTo('a');
    }

    @Test
    void synthesizesDefaultFromAsciiAndGlyphSizes() throws Exception {
        AssetSource assets = new MemoryAssetSource(PreJsonFonts.ASCII_TEXTURE, PreJsonFonts.GLYPH_SIZES);
        assertThat(FontDescriptor.available(assets, "default")).isTrue();
        FontDescriptor d = FontDescriptor.load(assets, "default");
        assertThat(d.name()).isEqualTo("default");
        assertThat(d.providers().stream().map(FontProvider::type).toList()).isEqualTo(List.of("space", "bitmap",
                "legacy_unicode"));
        FontProvider bitmap = d.providers().get(1);
        assertThat(bitmap.file()).isEqualTo("minecraft:font/ascii.png");
        assertThat(bitmap.height()).isEqualTo(8);
        assertThat(bitmap.ascent()).isEqualTo(7);
        assertThat(bitmap.chars().size()).isEqualTo(16);
        assertThat(bitmap.chars().get(0)).isEqualTo(PreJsonFonts.ASCII_GRID.substring(0, 16));
        FontProvider uni = d.providers().get(2);
        assertThat(uni.template()).isEqualTo("minecraft:font/unicode_page_%s.png");
        assertThat(uni.sizes()).isEqualTo("minecraft:font/glyph_sizes.bin");
        assertThat(d.providers().get(0).advances().get((int) ' ')).isEqualTo(4);
        assertThat(d.providers().get(0).advances().get(0x00A0)).isEqualTo(4);
    }

    @Test
    void defaultWithoutGlyphSizesOmitsLegacyUnicode() throws Exception {
        AssetSource assets = new MemoryAssetSource(PreJsonFonts.ASCII_TEXTURE);
        FontDescriptor d = FontDescriptor.load(assets, "default");
        assertThat(d.providers().stream().map(FontProvider::type).toList()).isEqualTo(List.of("space", "bitmap"));
    }

    @Test
    void synthesizesAltFromSgaLettersOnly() throws Exception {
        AssetSource assets = new MemoryAssetSource(PreJsonFonts.SGA_TEXTURE);
        assertThat(FontDescriptor.available(assets, "alt")).isTrue();
        FontDescriptor d = FontDescriptor.load(assets, "alt");
        FontProvider bitmap = d.providers().get(1);
        assertThat(bitmap.file()).isEqualTo("minecraft:font/ascii_sga.png");
        String row4 = bitmap.chars().get(4);
        assertThat(row4.charAt(0)).isEqualTo('\0');
        assertThat(row4.charAt(1)).isEqualTo('A');
        assertThat(row4.charAt(15)).isEqualTo('O');
        String row2 = bitmap.chars().get(2);
        assertThat(row2.chars().allMatch(c -> c == 0)).as("SGA does not map punctuation cells").isTrue();
    }

    @Test
    void illageraltIsNotSynthesized() {
        AssetSource assets = new MemoryAssetSource(PreJsonFonts.ASCII_TEXTURE, PreJsonFonts.SGA_TEXTURE,
                PreJsonFonts.GLYPH_SIZES);
        assertThat(FontDescriptor.available(assets, "illageralt")).isFalse();
        assertThatThrownBy(() -> FontDescriptor.load(assets, "illageralt"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("illageralt")
                .hasMessageContaining("1.18");
    }

    @Test
    void jsonDescriptorWinsOverSynthesizedProfile() throws Exception {
        try (AssetSource assets = VanillaFixtures.open()) {
            FontDescriptor d = FontDescriptor.load(assets, "default");
            assertThat(d.providers()
                            .stream()
                            .anyMatch(p -> "minecraft:font/nonlatin_european.png".equals(p.file()))).isTrue();
            assertThat(d.providers().stream().noneMatch(p -> "legacy_unicode".equals(p.type()))).isTrue();
        }
    }

    @Test
    void loadsGlyphsFromAsciiSheetWithoutJson(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("assets/minecraft/textures/font/ascii.png");
        Files.createDirectories(dest.getParent());
        Files.copy(VanillaFixtures.root().resolve("assets/minecraft/textures/font/ascii.png"), dest);

        try (AssetSource assets = new DirAssetSource(tmp)) {
            assertThat(FontDescriptor.available(assets, "default")).isTrue();
            Map<Integer, Glyph> glyphs = new GlyphLoader(assets).load(FontDescriptor.load(assets, "default"));
            Glyph a = glyphs.get((int) 'A');
            assertThat(a != null && !a.isBlank()).isTrue();
            assertThat(a.cellW()).isEqualTo(8);
            assertThat(a.cellH()).isEqualTo(8);
            assertThat(glyphs.get((int) ' ').advanceDesignPixels()).isEqualTo(4);
        }
    }

    @Test
    void twelveTwelveTwoJarUsesAdapterAndSkipsIllageralt() throws Exception {
        assumeTrue(Files.isRegularFile(VANILLA_1_12_2), "no local 1.12.2 client jar");
        try (AssetSource assets = McTTF.openAssetSource(VANILLA_1_12_2)) {
            assertThat(FontDescriptor.available(assets, "default")).isTrue();
            assertThat(FontDescriptor.available(assets, "alt")).isTrue();
            assertThat(FontDescriptor.available(assets, "illageralt")).isFalse();

            FontDescriptor d = FontDescriptor.load(assets, "default");
            assertThat(d.providers().stream().map(FontProvider::type).toList()).isEqualTo(List.of("space", "bitmap",
                    "legacy_unicode"));

            // U+05D0 sizes byte 0x16 draws texture columns 1..6 at the pen and advances (7 - 1) / 2 + 1 = 4.
            SimpleGlyphOutline alef = GlyphBuilder.build(new GlyphLoader(assets).load(d).get(0x05D0));
            assertThat(alef.advanceWidth()).isEqualTo((int) (4 * GlyphBuilder.UNITS_PER_VISUAL_PIXEL));
            assertThat(alef.minX()).isEqualTo(0);

            byte[] bytes = McTTF.convertToBytes(assets, "alt", "Minecraft Enchantment", FontStyle.REGULAR);
            TtfTables ttf = new TtfTables(bytes);
            assertThat(ttf.glyphId('A') > 0).isTrue();
            assertThat(ttf.glyphId('Z') > 0).isTrue();
        }
    }

}
