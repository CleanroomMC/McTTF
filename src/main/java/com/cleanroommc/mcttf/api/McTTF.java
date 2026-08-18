package com.cleanroommc.mcttf.api;

import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.extract.DirAssetSource;
import com.cleanroommc.mcttf.extract.JarAssetSource;
import com.cleanroommc.mcttf.glyph.Glyph;
import com.cleanroommc.mcttf.glyph.GlyphLoader;
import com.cleanroommc.mcttf.profile.FontDescriptor;
import com.cleanroommc.mcttf.font.FontStyle;
import com.cleanroommc.mcttf.glyph.GlyphBuilder;
import com.cleanroommc.mcttf.glyph.SimpleGlyphOutline;
import com.cleanroommc.mcttf.font.TtfWriter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embeddable entry point for converting vanilla Minecraft's bitmap fonts to TTF.
 */
public final class McTTF {

    /** The three fonts this tool targets: id -> display family name. */
    public static final Map<String, String> STANDARD_FONTS = Map.of(
            "default", "Minecraft Default",
            "alt", "Minecraft Enchantment",
            "illageralt", "Minecraft Illager"
    );

    /** Regular, Bold, Italic, Bold Italic - generated for every font by default. */
    public static final FontStyle[] ALL_STYLES = FontStyle.values();

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
    public static byte[] convertToBytes(AssetSource assets, String fontName, String familyName, FontStyle style) throws IOException {
        FontDescriptor descriptor = FontDescriptor.load(assets, fontName);
        GlyphLoader loader = new GlyphLoader(assets);
        Map<Integer, Glyph> glyphs = loader.load(descriptor);

        Map<Integer, SimpleGlyphOutline> outlines = new LinkedHashMap<>();
        for (Map.Entry<Integer, Glyph> e : glyphs.entrySet()) {
            outlines.put(e.getKey(), GlyphBuilder.build(e.getValue(), style));
        }

        TtfWriter writer = new TtfWriter(familyName, style);
        writer.addGlyphs(outlines);
        return writer.toBytes();
    }

    /** Same as {@link #convertToBytes} but for {@link FontStyle#REGULAR}. */
    public static byte[] convertToBytes(AssetSource assets, String fontName, String familyName) throws IOException {
        return convertToBytes(assets, fontName, familyName, FontStyle.REGULAR);
    }

    /** Same as {@link #convertToBytes} but writes straight to {@code outputTtf}. */
    public static void convert(AssetSource assets, String fontName, String familyName, FontStyle style, Path outputTtf) throws IOException {
        Files.createDirectories(outputTtf.toAbsolutePath().getParent());
        Files.write(outputTtf, convertToBytes(assets, fontName, familyName, style));
    }

    /** Same as {@link #convert} but for {@link FontStyle#REGULAR}. */
    public static void convert(AssetSource assets, String fontName, String familyName, Path outputTtf) throws IOException {
        convert(assets, fontName, familyName, FontStyle.REGULAR, outputTtf);
    }

    /**
     * Converts one font into all four styles ({@link #ALL_STYLES}), writing
     * {@code <outputDir>/<fileBaseName><style-suffix>.ttf} for each (e.g.
     * {@code MinecraftDefault.ttf}, {@code MinecraftDefault-Bold.ttf}, ...).
     */
    public static void convertAllStyles(AssetSource assets, String fontName, String familyName,
                                         String fileBaseName, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        for (FontStyle style : ALL_STYLES) {
            convert(assets, fontName, familyName, style, outputDir.resolve(fileBaseName + style.fileSuffix() + ".ttf"));
        }
    }

    /**
     * Converts all of {@link #STANDARD_FONTS} that this asset tree actually contains
     * (default/alt/illageralt), each in all four styles, into
     * {@code <outputDir>/<FontId><style-suffix>.ttf}.
     * Fonts with neither a JSON descriptor nor a pre-1.13 texture (e.g. {@code illageralt}
     * on 1.12.2) are skipped. Throws if none can be converted.
     */
    public static void convertStandardFonts(AssetSource assets, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        boolean any = false;
        for (Map.Entry<String, String> e : STANDARD_FONTS.entrySet()) {
            if (!FontDescriptor.available(assets, e.getKey())) {
                continue;
            }
            any = true;
            String familyName = e.getValue();
            String fileBaseName = familyName.replace(" ", "");
            convertAllStyles(assets, e.getKey(), familyName, fileBaseName, outputDir);
        }
        if (!any) {
            throw new IOException("No convertible fonts in this asset tree "
                    + "(need font/*.json, or pre-1.13 ascii.png / ascii_sga.png)");
        }
    }

    /** Convenience overload that opens and closes the asset source for you. */
    public static void convertStandardFonts(Path clientJarOrAssetsDir, Path outputDir) throws IOException {
        try (AssetSource assets = openAssetSource(clientJarOrAssetsDir)) {
            convertStandardFonts(assets, outputDir);
        }
    }

    private McTTF() { }

}
