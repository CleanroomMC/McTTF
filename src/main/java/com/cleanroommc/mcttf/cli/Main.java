/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.cli;

import com.cleanroommc.mcttf.api.McTTF;
import com.cleanroommc.mcttf.download.MinecraftClientDownloader;
import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.font.FontStyle;
import com.cleanroommc.mcttf.profile.FontDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts vanilla Minecraft's bitmap fonts into pixel-perfect TTFs.
 * <p>
 * With no {@code --input}, this downloads the client jar for the requested (or latest
 * release) Minecraft version straight from Mojang and caches it under
 * {@code ~/.mcttf/versions/<id>/client.jar} — no manual jar path needed.
 * <p>
 * Usage:
 * {@code mcttf [--input <jar-or-assets-dir>] [--version <id>] [--output <dir>] [--fonts a,b,c] [--styles a,b,c]}
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);

        Path assetLocation = options.input;
        if (assetLocation == null) {
            String version = options.version == null ? "latest release" : options.version;
            System.out.println("No --input given, downloading Minecraft client jar (" + version + ")...");
            MinecraftClientDownloader downloader = new MinecraftClientDownloader();
            assetLocation = downloader.downloadClientJar(options.version, MinecraftClientDownloader.defaultCacheRoot());
            System.out.println("Using " + assetLocation);
        }

        Files.createDirectories(options.output);

        try (AssetSource assets = McTTF.openAssetSource(assetLocation)) {
            int converted = 0;
            for (Map.Entry<String, String> font : options.fonts.entrySet()) {
                if (!FontDescriptor.available(assets, font.getKey())) {
                    if (options.fontsExplicit) {
                        throw new IOException("Font '" + font.getKey() + "' is not in this asset tree (no font/"
                                        + font.getKey() + ".json and no pre-1.13 texture to synthesize from)");
                    }
                    System.out.println("Skipping font '" + font.getKey() + "' (not in this asset tree)");
                    continue;
                }
                String fileBaseName = font.getValue().replace(" ", "");
                for (FontStyle style : options.styles) {
                    System.out.println("Converting font '" + font.getKey() + "' (" + style.subfamilyName() + ")...");
                    Path outFile = options.output.resolve(fileBaseName + style.fileSuffix() + ".ttf");
                    McTTF.convert(assets, font.getKey(), font.getValue(), style, outFile);
                    System.out.println("  -> " + outFile);
                    converted++;
                }
            }
            if (converted == 0) {
                throw new IOException("No fonts could be converted from " + assetLocation);
            }
        }
    }

    private static final class Options {

        Path input;
        String version;
        Path output = MinecraftClientDownloader.defaultCacheRoot().resolve("output");
        Map<String, String> fonts = McTTF.STANDARD_FONTS;
        boolean fontsExplicit = false;
        FontStyle[] styles = McTTF.ALL_STYLES;

        static Options parse(String[] args) {
            Options o = new Options();
            List<String> fontNames = null;
            List<String> styleNames = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-i", "--input" -> o.input = Path.of(require(args, ++i, arg));
                    case "-v", "--version" -> o.version = require(args, ++i, arg);
                    case "-o", "--output" -> o.output = Path.of(require(args, ++i, arg));
                    case "-f", "--fonts" -> fontNames = List.of(require(args, ++i, arg).split(","));
                    case "-s", "--styles" -> styleNames = List.of(require(args, ++i, arg).split(","));
                    case "-h", "--help" -> {
                        printUsage();
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            if (fontNames != null) {
                Map<String, String> selected = new LinkedHashMap<>();
                for (String name : fontNames) {
                    String family = McTTF.STANDARD_FONTS.get(name);
                    if (family == null) {
                        throw new IllegalArgumentException("Unknown font: " + name + " (known: "
                                        + String.join(", ", McTTF.STANDARD_FONTS.keySet()) + ")");
                    }
                    selected.put(name, family);
                }
                o.fonts = selected;
                o.fontsExplicit = true;
            }
            if (styleNames != null) {
                Set<FontStyle> selected = new LinkedHashSet<>();
                for (String name : styleNames) {
                    selected.add(parseStyle(name));
                }
                o.styles = selected.toArray(FontStyle[]::new);
            }
            return o;
        }

        private static FontStyle parseStyle(String name) {
            return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "regular" -> FontStyle.REGULAR;
                case "bold" -> FontStyle.BOLD;
                case "italic" -> FontStyle.ITALIC;
                case "bolditalic", "bold-italic", "bold_italic" -> FontStyle.BOLD_ITALIC;
                default ->
                        throw new IllegalArgumentException("Unknown style: " + name
                                        + " (known: regular, bold, italic, bolditalic)");
            };
        }

        private static String require(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[index];
        }

        private static void printUsage() {
            System.out.println("""
                               Usage: mcttf [options]
                                 -i, --input <jar-or-assets-dir>   Use this client jar or extracted assets dir instead of downloading
                                 -v, --version <id>                Minecraft version to download (default: latest release)
                                 -o, --output <dir>                Output directory (default: mcttf-output)
                                 -f, --fonts <a,b,c>                Comma-separated font ids: default,alt,illageralt (default: all)
                                 -s, --styles <a,b,c>               Comma-separated styles: regular,bold,italic,bolditalic (default: all)
                               """);
        }

    }

}
