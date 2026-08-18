package com.cleanroommc.mcttf.profile;

import com.cleanroommc.mcttf.api.McTTF;
import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.extract.DirAssetSource;
import com.cleanroommc.mcttf.font.FontStyle;
import com.cleanroommc.mcttf.glyph.Glyph;
import com.cleanroommc.mcttf.glyph.GlyphLoader;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PreJsonFontsTest {

    static final Path VANILLA_1_12_2 = Path.of(System.getProperty("user.home"),
            ".gradle/caches/unimined/net/minecraft/minecraft/1.12.2/minecraft-1.12.2-client.jar");

    @Test
    void asciiGridIsTheFontRendererSheet() {
        assertEquals(256, PreJsonFonts.ASCII_GRID.length());
        assertEquals(' ', PreJsonFonts.ASCII_GRID.charAt(32));
        assertEquals('A', PreJsonFonts.ASCII_GRID.charAt(4 * 16 + 1));
        assertEquals('a', PreJsonFonts.ASCII_GRID.charAt(6 * 16 + 1));
    }

    @Test
    void synthesizesDefaultFromAsciiAndGlyphSizes() throws Exception {
        AssetSource assets = new MemoryAssetSource(
                PreJsonFonts.ASCII_TEXTURE,
                PreJsonFonts.GLYPH_SIZES);
        assertTrue(FontDescriptor.available(assets, "default"));
        FontDescriptor d = FontDescriptor.load(assets, "default");
        assertEquals("default", d.name());
        assertEquals(List.of("space", "bitmap", "legacy_unicode"),
                d.providers().stream().map(FontProvider::type).toList());
        FontProvider bitmap = d.providers().get(1);
        assertEquals("minecraft:font/ascii.png", bitmap.file());
        assertEquals(8, bitmap.height());
        assertEquals(7, bitmap.ascent());
        assertEquals(16, bitmap.chars().size());
        assertEquals(PreJsonFonts.ASCII_GRID.substring(0, 16), bitmap.chars().get(0));
        FontProvider uni = d.providers().get(2);
        assertEquals("minecraft:font/unicode_page_%s.png", uni.template());
        assertEquals("minecraft:font/glyph_sizes.bin", uni.sizes());
        assertEquals(4, d.providers().get(0).advances().get((int) ' '));
        assertEquals(4, d.providers().get(0).advances().get(0x00A0));
    }

    @Test
    void defaultWithoutGlyphSizesOmitsLegacyUnicode() throws Exception {
        AssetSource assets = new MemoryAssetSource(PreJsonFonts.ASCII_TEXTURE);
        FontDescriptor d = FontDescriptor.load(assets, "default");
        assertEquals(List.of("space", "bitmap"),
                d.providers().stream().map(FontProvider::type).toList());
    }

    @Test
    void synthesizesAltFromSgaLettersOnly() throws Exception {
        AssetSource assets = new MemoryAssetSource(PreJsonFonts.SGA_TEXTURE);
        assertTrue(FontDescriptor.available(assets, "alt"));
        FontDescriptor d = FontDescriptor.load(assets, "alt");
        FontProvider bitmap = d.providers().get(1);
        assertEquals("minecraft:font/ascii_sga.png", bitmap.file());
        String row4 = bitmap.chars().get(4);
        assertEquals('\0', row4.charAt(0));
        assertEquals('A', row4.charAt(1));
        assertEquals('O', row4.charAt(15));
        String row2 = bitmap.chars().get(2);
        assertTrue(row2.chars().allMatch(c -> c == 0), "SGA does not map punctuation cells");
    }

    @Test
    void illageraltIsNotSynthesized() {
        AssetSource assets = new MemoryAssetSource(
                PreJsonFonts.ASCII_TEXTURE,
                PreJsonFonts.SGA_TEXTURE,
                PreJsonFonts.GLYPH_SIZES);
        assertFalse(FontDescriptor.available(assets, "illageralt"));
        IOException thrown = assertThrows(IOException.class,
                () -> FontDescriptor.load(assets, "illageralt"));
        assertTrue(thrown.getMessage().contains("illageralt"));
        assertTrue(thrown.getMessage().contains("1.18"));
    }

    @Test
    void jsonDescriptorWinsOverSynthesizedProfile() throws Exception {
        try (AssetSource assets = VanillaFixtures.open()) {
            FontDescriptor d = FontDescriptor.load(assets, "default");
            assertTrue(d.providers().stream().anyMatch(p ->
                    "minecraft:font/nonlatin_european.png".equals(p.file())));
            assertTrue(d.providers().stream().noneMatch(p -> "legacy_unicode".equals(p.type())));
        }
    }

    @Test
    void loadsGlyphsFromAsciiSheetWithoutJson(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("assets/minecraft/textures/font/ascii.png");
        Files.createDirectories(dest.getParent());
        Files.copy(VanillaFixtures.root().resolve("assets/minecraft/textures/font/ascii.png"), dest);

        try (AssetSource assets = new DirAssetSource(tmp)) {
            assertTrue(FontDescriptor.available(assets, "default"));
            Map<Integer, Glyph> glyphs = new GlyphLoader(assets).load(FontDescriptor.load(assets, "default"));
            Glyph a = glyphs.get((int) 'A');
            assertTrue(a != null && !a.isBlank());
            assertEquals(8, a.cellW());
            assertEquals(8, a.cellH());
            assertEquals(4, glyphs.get((int) ' ').advanceDesignPixels());
        }
    }

    @Test
    void twelveTwelveTwoJarUsesAdapterAndSkipsIllageralt() throws Exception {
        assumeTrue(Files.isRegularFile(VANILLA_1_12_2), "no local 1.12.2 client jar");
        try (AssetSource assets = McTTF.openAssetSource(VANILLA_1_12_2)) {
            assertTrue(FontDescriptor.available(assets, "default"));
            assertTrue(FontDescriptor.available(assets, "alt"));
            assertFalse(FontDescriptor.available(assets, "illageralt"));

            FontDescriptor d = FontDescriptor.load(assets, "default");
            assertEquals(List.of("space", "bitmap", "legacy_unicode"),
                    d.providers().stream().map(FontProvider::type).toList());

            byte[] bytes = McTTF.convertToBytes(assets, "alt", "Minecraft Enchantment", FontStyle.REGULAR);
            TtfTables ttf = new TtfTables(bytes);
            assertTrue(ttf.glyphId('A') > 0);
            assertTrue(ttf.glyphId('Z') > 0);
        }
    }
}
