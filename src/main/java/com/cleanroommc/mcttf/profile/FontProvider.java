/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.profile;

import java.util.List;
import java.util.Map;

/**
 * One entry of a Minecraft {@code font/*.json} descriptor.
 * Supports the {@code bitmap}, {@code legacy_unicode} and {@code space} provider types,
 * which is what vanilla uses (directly or via {@code reference}) for {@code default},
 * {@code alt} and {@code illageralt}.
 */
public record FontProvider(String type,
        String file,
        int height,
        int ascent,
        List<String> chars,
        String template,
        String sizes,
        Map<Integer, Integer> advances) {

    public static FontProvider bitmap(String file, int height, int ascent, List<String> chars) {
        return new FontProvider("bitmap", file, height, ascent, List.copyOf(chars), null, null, null);
    }

    public static FontProvider bitmap(Map<String, Object> json) {
        Object heightObj = json.get("height");
        long height = heightObj == null ? 8L : ((Number) heightObj).longValue();
        long ascent = ((Number) json.get("ascent")).longValue();
        @SuppressWarnings("unchecked") List<Object> rawChars = (List<Object>) json.get("chars");
        List<String> chars = rawChars.stream().map(Object::toString).toList();
        return bitmap((String) json.get("file"), (int) height, (int) ascent, chars);
    }

    public static FontProvider legacyUnicode(String template, String sizes) {
        return new FontProvider("legacy_unicode", null, 8, 7, null, template, sizes, null);
    }

    public static FontProvider legacyUnicode(Map<String, Object> json) {
        return legacyUnicode((String) json.get("template"), (String) json.get("sizes"));
    }

    public static FontProvider space(Map<String, Object> json) {
        @SuppressWarnings("unchecked") Map<String, Object> rawAdvances = (Map<String, Object>) json.get("advances");
        Map<Integer, Integer> advances = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawAdvances.entrySet()) {
            if (e.getKey().isEmpty()) {
                continue;
            }
            advances.put(e.getKey().codePointAt(0), ((Number) e.getValue()).intValue());
        }
        return new FontProvider("space", null, 8, 0, null, null, null, Map.copyOf(advances));
    }

}
