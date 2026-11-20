/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.profile;

import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.json.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A font's provider list: either parsed from {@code assets/minecraft/font/<name>.json}
 * (with {@code reference} providers inlined) or synthesized for pre-1.13 jars.
 */
public record FontDescriptor(String name, List<FontProvider> providers) {

    /**
     * {@code true} if {@link #load} will succeed: either {@code font/<name>.json} exists,
     * or a pre-1.13 bitmap profile can be synthesized from the textures that are present.
     */
    public static boolean available(AssetSource assets, String fontName) {
        String local = localName(fontName);
        return assets.exists(descriptorPath(qualify(fontName))) || PreJsonFonts.canSynthesize(assets, local);
    }

    public static FontDescriptor load(AssetSource assets, String fontName) throws IOException {
        String fontId = qualify(fontName);
        String local = localName(fontName);
        String assetPath = descriptorPath(fontId);
        if (assets.exists(assetPath)) {
            List<FontProvider> providers = new ArrayList<>();
            collect(assets, fontId, providers, new HashSet<>());
            return new FontDescriptor(local, providers);
        }
        if (PreJsonFonts.canSynthesize(assets, local)) {
            return PreJsonFonts.synthesize(assets, local);
        }
        throw new IOException("Font '" + fontName + "' not found: missing " + assetPath
                        + PreJsonFonts.missingHint(local));
    }

    static String qualify(String fontName) {
        return fontName.indexOf(':') < 0 ? "minecraft:" + fontName : fontName;
    }

    static String localName(String fontName) {
        int colon = fontName.indexOf(':');
        return colon < 0 ? fontName : fontName.substring(colon + 1);
    }

    static String descriptorPath(String fontId) {
        int colon = fontId.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : fontId.substring(0, colon);
        String path = colon < 0 ? fontId : fontId.substring(colon + 1);
        if (path.startsWith("font/")) {
            path = path.substring("font/".length());
        }
        return "assets/" + namespace + "/font/" + path + ".json";
    }

    private static void collect(AssetSource assets, String fontId, List<FontProvider> out, Set<String> visited)
            throws IOException {
        if (!visited.add(fontId)) {
            return; // Guard against reference cycles
        }
        String assetPath = descriptorPath(fontId);

        Map<String, Object> root = Json.parseObject(assets.readString(assetPath));
        @SuppressWarnings("unchecked") List<Object> rawProviders = (List<Object>) root.get("providers");
        for (Object o : rawProviders) {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) o;
            String type = (String) p.get("type");
            switch (type) {
                case "bitmap" -> out.add(FontProvider.bitmap(p));
                case "legacy_unicode" -> out.add(FontProvider.legacyUnicode(p));
                case "space" -> out.add(FontProvider.space(p));
                case "reference" -> collect(assets, (String) p.get("id"), out, visited);
                default ->
                { /* ttf, unihex, options, etc. not needed for these fonts */
                }
            }
        }
    }

}
