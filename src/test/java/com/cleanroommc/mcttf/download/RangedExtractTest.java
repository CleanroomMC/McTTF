/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.download;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RangedExtractTest {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");

    private static void put(ZipOutputStream zip, String name, byte[] data, boolean stored) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    @Test
    void extractsOnlyWantedEntriesThroughRanges(@TempDir Path dir) throws Exception {
        byte[] png = new byte[50_000];
        new Random(1).nextBytes(png);
        byte[] json = "{\"providers\":[]}".getBytes(StandardCharsets.UTF_8);
        byte[] filler = new byte[300_000];
        new Random(2).nextBytes(filler);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "assets/minecraft/font/default.json", json, false);
            put(zip, "net/minecraft/Big.class", filler, false);
            put(zip, "assets/minecraft/font/", new byte[0], false);
            put(zip, "assets/minecraft/textures/font/ascii.png", png, true);
            put(zip, "assets/minecraft/textures/block/dirt.png", png, false);
        }
        byte[] jar = bytes.toByteArray();

        AtomicInteger servedBytes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client.jar", exchange -> {
            Matcher m = RANGE.matcher(exchange.getRequestHeaders().getFirst("Range"));
            assertThat(m.matches()).isTrue();
            int from = Integer.parseInt(m.group(1));
            int to = Integer.parseInt(m.group(2)) + 1;
            byte[] body = Arrays.copyOfRange(jar, from, to);
            servedBytes.addAndGet(body.length);
            exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (to - 1) + "/" + jar.length);
            exchange.sendResponseHeaders(206, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/client.jar";
            new MinecraftClientDownloader().extractRemote(url, jar.length, dir,
                    name -> name.startsWith("assets/minecraft/font/")
                            || name.startsWith("assets/minecraft/textures/font/"));
        } finally {
            server.stop(0);
        }

        assertThat(Files.readAllBytes(dir.resolve("assets/minecraft/font/default.json"))).isEqualTo(json);
        assertThat(Files.readAllBytes(dir.resolve("assets/minecraft/textures/font/ascii.png"))).isEqualTo(png);
        assertThat(Files.exists(dir.resolve("assets/minecraft/textures/block/dirt.png"))).isFalse();
        assertThat(Files.exists(dir.resolve("net"))).isFalse();
        assertThat(servedBytes.get() < filler.length).as("served " + servedBytes.get() + " of " + jar.length).isTrue();
    }

}
