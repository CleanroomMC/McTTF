package com.cleanroommc.mcttf.glyph;

import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.profile.FontDescriptor;
import com.cleanroommc.mcttf.profile.FontProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a {@link FontDescriptor} plus its backing textures into per-codepoint {@link Glyph}s.
 * <p>
 * Providers are applied in order and the first one to define a codepoint wins, matching
 * Minecraft's own provider precedence (later providers only fill in gaps).
 */
public final class GlyphLoader {

    private final AssetSource assets;
    private final Map<String, BufferedImage> textureCache = new HashMap<>();

    public GlyphLoader(AssetSource assets) {
        this.assets = assets;
    }

    public Map<Integer, Glyph> load(FontDescriptor descriptor) throws IOException {
        Map<Integer, Glyph> glyphs = new LinkedHashMap<>();
        for (FontProvider provider : descriptor.providers()) {
            switch (provider.type()) {
                case "bitmap" -> loadBitmap(provider, glyphs);
                case "legacy_unicode" -> loadLegacyUnicode(provider, glyphs);
                case "space" -> loadSpace(provider, glyphs);
                default -> throw new IllegalStateException("Unsupported provider type: " + provider.type());
            }
        }
        return glyphs;
    }

    private BufferedImage texture(String resourcePath) throws IOException {
        BufferedImage cached = textureCache.get(resourcePath);
        if (cached != null) {
            return cached;
        }
        BufferedImage image;
        try (var in = assets.open(resourcePath)) {
            image = ImageIO.read(in);
        }
        if (image == null) {
            throw new IOException("Unreadable image: " + resourcePath);
        }
        textureCache.put(resourcePath, image);
        return image;
    }

    private static String namespace(String mcId) {
        int colon = mcId.indexOf(':');
        return colon < 0 ? "minecraft" : mcId.substring(0, colon);
    }

    private static String idPath(String mcId) {
        int colon = mcId.indexOf(':');
        return colon < 0 ? mcId : mcId.substring(colon + 1);
    }

    private static String toTexturePath(String mcId) {
        return "assets/" + namespace(mcId) + "/textures/" + idPath(mcId);
    }

    private static String toFontPath(String mcId) {
        return "assets/" + namespace(mcId) + "/font/" + idPath(mcId).replaceFirst("^font/", "");
    }

    private void loadSpace(FontProvider provider, Map<Integer, Glyph> out) {
        for (Map.Entry<Integer, Integer> e : provider.advances().entrySet()) {
            out.putIfAbsent(e.getKey(), Glyph.space(e.getKey(), e.getValue()));
        }
    }

    private void loadBitmap(FontProvider provider, Map<Integer, Glyph> out) throws IOException {
        BufferedImage image = texture(toTexturePath(provider.file()));
        int rows = provider.chars().size();
        int cols = 0;
        for (String line : provider.chars()) {
            cols = Math.max(cols, line.codePointCount(0, line.length()));
        }
        if (rows == 0 || cols == 0) {
            return;
        }
        int cellW = image.getWidth() / cols;
        int cellH = image.getHeight() / rows;

        for (int row = 0; row < rows; row++) {
            String line = provider.chars().get(row);
            int col = 0;
            int i = 0;
            while (i < line.length()) {
                int codepoint = line.codePointAt(i);
                i += Character.charCount(codepoint);
                if (codepoint != 0 && !out.containsKey(codepoint)) {
                    Glyph glyph = sliceCell(image, col * cellW, row * cellH, cellW, cellH,
                            codepoint, provider.height(), provider.ascent());
                    out.put(codepoint, glyph);
                }
                col++;
            }
        }
    }

    private void loadLegacyUnicode(FontProvider provider, Map<Integer, Glyph> out) throws IOException {
        byte[] sizes = assets.readAllBytes(toFontPath(provider.sizes()));
        for (int codepoint = 0; codepoint < sizes.length; codepoint++) {
            int b = sizes[codepoint] & 0xFF;
            if (b == 0 || out.containsKey(codepoint)) {
                continue;
            }
            int page = codepoint >> 8;
            String pagePath = toTexturePath(provider.template().replace("%s", String.format("%02x", page)));
            if (!assets.exists(pagePath)) {
                continue;
            }
            BufferedImage image = texture(pagePath);
            int local = codepoint & 0xFF;
            int cellRow = local >> 4;
            int cellCol = local & 0xF;
            int cellW = image.getWidth() / 16;
            int cellH = image.getHeight() / 16;

            int startCol = (b >> 4) & 0xF;
            int endCol = b & 0xF;

            boolean[][] pixels = readPixels(image, cellCol * cellW, cellRow * cellH, cellW, cellH);
            out.put(codepoint, new Glyph(codepoint, pixels, cellW, cellH,
                    provider.height(), provider.ascent(), startCol, endCol));
        }
    }

    private Glyph sliceCell(BufferedImage image, int x, int y, int w, int h,
                             int codepoint, int providerHeight, int providerAscent) {
        boolean[][] pixels = readPixels(image, x, y, w, h);
        int left = -1;
        int right = -1;
        for (int col = 0; col < w; col++) {
            boolean opaque = false;
            for (int row = 0; row < h; row++) {
                if (pixels[row][col]) {
                    opaque = true;
                    break;
                }
            }
            if (opaque) {
                if (left < 0) left = col;
                right = col;
            }
        }
        return new Glyph(codepoint, pixels, w, h, providerHeight, providerAscent, left, right);
    }

    private boolean[][] readPixels(BufferedImage image, int x, int y, int w, int h) {
        boolean[][] pixels = new boolean[h][w];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int px = x + col;
                int py = y + row;
                int argb = (px < image.getWidth() && py < image.getHeight()) ? image.getRGB(px, py) : 0;
                pixels[row][col] = (argb >>> 24) != 0;
            }
        }
        return pixels;
    }
}
