package com.cleanroommc.mcttf.profile;

import com.cleanroommc.mcttf.extract.AssetSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Synthesizes {@link FontDescriptor}s for client jars that predate {@code font/*.json}
 * (Minecraft 1.12.2 and earlier).
 * <p>
 * Those versions keep the glyph map in {@code FontRenderer}: a 16×16 grid over
 * {@code textures/font/ascii.png} (and the same grid over {@code ascii_sga.png} for the
 * enchantment font), plus {@code font/glyph_sizes.bin} + {@code unicode_page_XX.png} for
 * everything else. {@code illageralt} does not exist yet.
 */
public final class PreJsonFonts {

    /**
     * Exact 256-character grid from 1.12.2 {@code FontRenderer.renderChar}.
     * Cell (row = i/16, col = i%16) is {@code ASCII_GRID.charAt(i)}.
     */
    public static final String ASCII_GRID =
            "\u00c0\u00c1\u00c2\u00c8\u00ca\u00cb\u00cd\u00d3\u00d4\u00d5\u00da\u00df\u00e3\u00f5\u011f\u0130"
                    + "\u0131\u0152\u0153\u015e\u015f\u0174\u0175\u017e\u0207\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
                    + " !\"#$%&'()*+,-./"
                    + "0123456789:;<=>?"
                    + "@ABCDEFGHIJKLMNO"
                    + "PQRSTUVWXYZ[\\]^_"
                    + "`abcdefghijklmno"
                    + "pqrstuvwxyz{|}~\u0000"
                    + "\u00c7\u00fc\u00e9\u00e2\u00e4\u00e0\u00e5\u00e7\u00ea\u00eb\u00e8\u00ef\u00ee\u00ec\u00c4\u00c5"
                    + "\u00c9\u00e6\u00c6\u00f4\u00f6\u00f2\u00fb\u00f9\u00ff\u00d6\u00dc\u00f8\u00a3\u00d8\u00d7\u0192"
                    + "\u00e1\u00ed\u00f3\u00fa\u00f1\u00d1\u00aa\u00ba\u00bf\u00ae\u00ac\u00bd\u00bc\u00a1\u00ab\u00bb"
                    + "\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556\u2555\u2563\u2551\u2557\u255d\u255c\u255b\u2510"
                    + "\u2514\u2534\u252c\u251c\u2500\u253c\u255e\u255f\u255a\u2554\u2569\u2566\u2560\u2550\u256c\u2567"
                    + "\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256b\u256a\u2518\u250c\u2588\u2584\u258c\u2590\u2580"
                    + "\u03b1\u03b2\u0393\u03c0\u03a3\u03c3\u03bc\u03c4\u03a6\u0398\u03a9\u03b4\u221e\u2205\u2208\u2229"
                    + "\u2261\u00b1\u2265\u2264\u2320\u2321\u00f7\u2248\u00b0\u2219\u00b7\u221a\u207f\u00b2\u25a0\u0000";

    static final String ASCII_TEXTURE = "assets/minecraft/textures/font/ascii.png";
    static final String SGA_TEXTURE = "assets/minecraft/textures/font/ascii_sga.png";
    static final String GLYPH_SIZES = "assets/minecraft/font/glyph_sizes.bin";

    private static final List<String> ASCII_ROWS = rows(ASCII_GRID, false);
    private static final List<String> SGA_ROWS = rows(ASCII_GRID, true);

    static {
        if (ASCII_GRID.length() != 256) {
            throw new IllegalStateException("ASCII_GRID must be 256 BMP chars, was " + ASCII_GRID.length());
        }
    }

    public static boolean canSynthesize(AssetSource assets, String fontName) {
        return switch (fontName) {
            case "default" -> assets.exists(ASCII_TEXTURE);
            case "alt" -> assets.exists(SGA_TEXTURE);
            default -> false;
        };
    }

    public static FontDescriptor synthesize(AssetSource assets, String fontName) {
        return switch (fontName) {
            case "default" -> defaultFont(assets);
            case "alt" -> altFont();
            default -> throw new IllegalArgumentException("No pre-JSON profile for font '" + fontName + "'");
        };
    }

    static String missingHint(String fontName) {
        return switch (fontName) {
            case "default" -> " (and no " + ASCII_TEXTURE + " to synthesize a 1.12.2 profile from)";
            case "alt" -> " (and no " + SGA_TEXTURE + " to synthesize a 1.12.2 profile from)";
            case "illageralt" -> " (illageralt was added in 1.18; 1.12.2 has neither the JSON nor asciillager.png)";
            default -> "";
        };
    }

    private static FontDescriptor defaultFont(AssetSource assets) {
        List<FontProvider> providers = new ArrayList<>();
        providers.add(spaceProvider());
        providers.add(FontProvider.bitmap("minecraft:font/ascii.png", 8, 7, ASCII_ROWS));
        if (assets.exists(GLYPH_SIZES)) {
            providers.add(FontProvider.legacyUnicode(
                    "minecraft:font/unicode_page_%s.png",
                    "minecraft:font/glyph_sizes.bin"));
        }
        return new FontDescriptor("default", List.copyOf(providers));
    }

    private static FontDescriptor altFont() {
        return new FontDescriptor("alt", List.of(
                spaceProvider(),
                FontProvider.bitmap("minecraft:font/ascii_sga.png", 8, 7, SGA_ROWS)));
    }

    /**
     * 1.12.2 {@code FontRenderer} hardcodes space and NBSP to 4px before consulting the sheet.
     */
    private static FontProvider spaceProvider() {
        return new FontProvider("space", null, 8, 0, null, null, null, Map.of(
                (int) ' ', 4,
                0x00A0, 4));
    }

    /**
     * @param lettersOnly if true, only A–Z / a–z keep their cells (1.13 {@code alt.json} layout;
     *                    the SGA sheet's other cells are empty)
     */
    private static List<String> rows(String grid, boolean lettersOnly) {
        List<String> rows = new ArrayList<>(16);
        for (int row = 0; row < 16; row++) {
            StringBuilder line = new StringBuilder(16);
            for (int col = 0; col < 16; col++) {
                char ch = grid.charAt(row * 16 + col);
                if (lettersOnly && !isAsciiLetter(ch)) {
                    ch = '\0';
                }
                line.append(ch);
            }
            rows.add(line.toString());
        }
        return List.copyOf(rows);
    }

    private static boolean isAsciiLetter(char ch) {
        return ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z';
    }

    private PreJsonFonts() {
    }
}
