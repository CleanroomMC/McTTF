/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.download;

import com.cleanroommc.mcttf.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Downloads (and caches) a vanilla Minecraft client jar straight from Mojang's public manifests. */
public final class MinecraftClientDownloader {

    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** Prefixes of every jar entry that font descriptors and their textures live under. */
    private static final List<String> FONT_ASSET_PREFIXES = List.of("assets/minecraft/font/",
            "assets/minecraft/textures/font/");

    /** Entries closer together than this are fetched in one request, since a round trip costs more than the gap. */
    private static final long MERGE_GAP_BYTES = 64 * 1024;

    private record ClientJar(String version, String url, String sha1, long size) { }

    /** {@code offset} is the entry's local header and {@code end} the next one's, so {@code [offset, end)} holds the whole entry. */
    private record ZipEntryRef(String name,
            int method,
            long crc,
            long compressedSize,
            long size,
            long offset,
            long end) { }

    /** The version whose font entries are pinned below. Its jar URL is content-addressed, so the ranges never move. */
    public static final String PINNED_VERSION = "26.2";

    private static final ClientJar PINNED_JAR = new ClientJar(PINNED_VERSION,
            "https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar",
            "2dc72797acbc1b63fc16a11c4ac393605f453754", 39193383L);

    private static final List<ZipEntryRef> PINNED_FONT_ENTRIES = List.of(new ZipEntryRef("assets/minecraft/font/alt.json",
                    8, 0xB284D188L, 280, 2146, 10836633L, 10836989L),
            new ZipEntryRef("assets/minecraft/font/default.json", 8, 0xF54B7800L, 129, 398, 10836989L, 10837198L),
            new ZipEntryRef("assets/minecraft/font/illageralt.json", 8, 0x413657E7L, 298, 872, 10837198L, 10837579L),
            new ZipEntryRef("assets/minecraft/font/uniform.json", 8, 0x5430701DL, 97, 226, 10837579L, 10837756L),
            new ZipEntryRef("assets/minecraft/font/include/default.json", 8, 0x933BD631L, 6120, 19118, 10837834L,
                    10844042L),
            new ZipEntryRef("assets/minecraft/font/include/space.json", 8, 0x3EB5EFB9L, 83, 170, 10844042L, 10844211L),
            new ZipEntryRef("assets/minecraft/font/include/unifont.json", 8, 0x8E4D6D1CL, 26, 29, 10844211L, 10844325L),
            new ZipEntryRef("assets/minecraft/textures/font/accented.png", 8, 0x364C48ADL, 7699, 7694, 12174872L,
                    12182660L),
            new ZipEntryRef("assets/minecraft/textures/font/ascii.png", 8, 0x463BA35BL, 815, 810, 12182660L, 12183561L),
            new ZipEntryRef("assets/minecraft/textures/font/ascii_sga.png", 8, 0xA172737EL, 235, 238, 12183561L,
                    12183886L),
            new ZipEntryRef("assets/minecraft/textures/font/asciillager.png", 8, 0xBFA17B5AL, 357, 362, 12183886L,
                    12184335L),
            new ZipEntryRef("assets/minecraft/textures/font/nonlatin_european.png", 8, 0x5D6E1EAEL, 4872, 4867,
                    12184335L, 12189305L));

    /** FileLock only excludes other processes, and throws if two threads of one JVM lock the same file. */
    private static final Map<Path, Object> IN_PROCESS_LOCKS = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    /** Directory client jars are cached under: {@code <project-root>/<versionId>/client.jar}. */
    public static Path defaultCacheRoot() {
        return Path.of(".mcttf", "versions");
    }

    /**
     * Downloads the client jar for {@code versionId} (or the latest release if null),
     * reusing a previously cached copy when its checksum still matches.
     */
    public Path downloadClientJar(String versionId, Path cacheRoot) throws IOException {
        ClientJar jar = resolveClientJar(versionId);
        String jarUrl = jar.url();
        String sha1 = jar.sha1();

        Path target = cacheRoot.resolve(jar.version()).resolve("client.jar");
        if (Files.isRegularFile(target) && sha1.equals(sha1Hex(target))) {
            return target;
        }

        Files.createDirectories(target.getParent());
        Path jarLock = target.getParent().toRealPath().resolve("client.jar.lock");
        synchronized (IN_PROCESS_LOCKS.computeIfAbsent(jarLock, k -> new Object())) {
            try (FileChannel lockChannel = FileChannel.open(jarLock, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); FileLock ignored = lockChannel.lock()) {
                if (Files.isRegularFile(target) && sha1.equals(sha1Hex(target))) {
                    return target;
                }
                Path tmp = target.resolveSibling("client.jar.tmp");
                HttpRequest request = HttpRequest.newBuilder(URI.create(jarUrl)).GET().build();
                HttpResponse<Path> response;
                try {
                    response = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while downloading " + jarUrl, e);
                }
                if (response.statusCode() != 200) {
                    Files.deleteIfExists(tmp);
                    throw new IOException("Failed to download " + jarUrl + ": HTTP " + response.statusCode());
                }
                if (!sha1.equals(sha1Hex(tmp))) {
                    Files.deleteIfExists(tmp);
                    throw new IOException("Checksum mismatch downloading " + jarUrl);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            }
        }
    }

    /**
     * Fetches only the font descriptors and font textures of {@code versionId}'s client jar (or the
     * latest release if null) using HTTP range requests, and returns a directory to open with
     * {@link com.cleanroommc.mcttf.extract.DirAssetSource}.
     * <p>
     * {@link #PINNED_VERSION} skips Mojang's manifests and the jar's central directory entirely and
     * fetches about 22 KB from hardcoded ranges. If those no longer verify, it falls back to the
     * generic path, which reads the central directory first (about 3.4 MB for 26.2).
     * <p>
     * The extracted entries are cached under {@code <cacheRoot>/<version>/fonts} and reused while
     * the jar's checksum is unchanged.
     */
    public Path downloadFontAssets(String versionId, Path cacheRoot) throws IOException {
        boolean pinned = PINNED_VERSION.equals(versionId);
        ClientJar jar = pinned ? PINNED_JAR : resolveClientJar(versionId);
        Path target = cacheRoot.resolve(jar.version()).resolve("fonts");
        Path marker = target.resolve(".client-sha1");
        if (Files.isRegularFile(marker) && jar.sha1().equals(Files.readString(marker))) {
            return target;
        }

        // Serializes threads and processes sharing this cache, so only one stages and publishes a version at a time
        Files.createDirectories(target.getParent());
        Path fontsLock = target.getParent().toRealPath().resolve("fonts.lock");
        synchronized (IN_PROCESS_LOCKS.computeIfAbsent(fontsLock, k -> new Object())) {
            try (FileChannel lockChannel = FileChannel.open(fontsLock, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); FileLock ignored = lockChannel.lock()) {
                if (Files.isRegularFile(marker) && jar.sha1().equals(Files.readString(marker))) {
                    return target;
                }
                Path tmp = target.resolveSibling("fonts.tmp");
                deleteRecursively(tmp);
                if (pinned) {
                    try {
                        fetchEntries(jar.url(), PINNED_FONT_ENTRIES, tmp);
                    } catch (RangeNotSupportedException e) {
                        throw e;
                    } catch (IOException e) {
                        jar = resolveClientJar(versionId);
                        deleteRecursively(tmp);
                        extractRemote(jar.url(), jar.size(), tmp, MinecraftClientDownloader::isFontAsset);
                    }
                } else {
                    extractRemote(jar.url(), jar.size(), tmp, MinecraftClientDownloader::isFontAsset);
                }
                Files.writeString(tmp.resolve(".client-sha1"), jar.sha1());
                deleteRecursively(target);
                Files.move(tmp, target);
                return target;
            }
        }
    }

    private static final class RangeNotSupportedException extends IOException {

        RangeNotSupportedException(String message) {
            super(message);
        }

    }

    private static boolean isFontAsset(String name) {
        return FONT_ASSET_PREFIXES.stream().anyMatch(name::startsWith);
    }

    /** Reads the remote zip's central directory, then range-fetches and inflates every entry {@code wanted} accepts. */
    void extractRemote(String url, long size, Path outDir, Predicate<String> wanted) throws IOException {
        // The end of central directory record sits in the last 22 bytes plus an up to 64 KiB comment
        long tailStart = Math.max(0, size - 22 - 0xFFFF);
        ByteBuffer tail = range(url, tailStart, size);
        int eocd = -1;
        for (int i = tail.limit() - 22; i >= 0; i--) {
            if (tail.getInt(i) == 0x06054B50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new IOException("No zip end of central directory record in " + url);
        }
        int entryCount = tail.getShort(eocd + 10) & 0xFFFF;
        long cdSize = tail.getInt(eocd + 12) & 0xFFFFFFFFL;
        long cdOffset = tail.getInt(eocd + 16) & 0xFFFFFFFFL;
        if (entryCount == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw new IOException("ZIP64 archives are not supported for ranged downloads: " + url);
        }

        ByteBuffer cd = range(url, cdOffset, cdOffset + cdSize);
        long[] offsets = new long[entryCount];
        int entryIndex = 0;
        List<ZipEntryRef> selected = new ArrayList<>();
        for (int p = 0; p < cd.limit();) {
            if (cd.getInt(p) != 0x02014B50) {
                throw new IOException("Corrupt zip central directory in " + url);
            }
            int nameLen = cd.getShort(p + 28) & 0xFFFF;
            int extraLen = cd.getShort(p + 30) & 0xFFFF;
            int commentLen = cd.getShort(p + 32) & 0xFFFF;
            long offset = cd.getInt(p + 42) & 0xFFFFFFFFL;
            byte[] nameBytes = new byte[nameLen];
            cd.get(p + 46, nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            offsets[entryIndex++] = offset;
            if (!name.endsWith("/") && wanted.test(name)) {
                selected.add(new ZipEntryRef(name, cd.getShort(p + 10) & 0xFFFF, cd.getInt(p + 16) & 0xFFFFFFFFL,
                        cd.getInt(p + 20) & 0xFFFFFFFFL, cd.getInt(p + 24) & 0xFFFFFFFFL, offset, -1));
            }
            p += 46 + nameLen + extraLen + commentLen;
        }

        // An entry ends where the next local header in the file begins, which bounds each fetch exactly
        Arrays.sort(offsets);
        List<ZipEntryRef> entries = new ArrayList<>(selected.size());
        for (ZipEntryRef e : selected) {
            int i = Arrays.binarySearch(offsets, e.offset());
            long end = i + 1 < offsets.length ? offsets[i + 1] : cdOffset;
            entries.add(new ZipEntryRef(e.name(), e.method(), e.crc(), e.compressedSize(), e.size(), e.offset(), end));
        }
        fetchEntries(url, entries, outDir);
    }

    /** Range-fetches, inflates and CRC-checks {@code entries}, merging nearby ones into a single request. */
    private void fetchEntries(String url, List<ZipEntryRef> entries, Path outDir) throws IOException {
        entries = entries.stream().sorted(Comparator.comparingLong(ZipEntryRef::offset)).toList();
        Files.createDirectories(outDir);
        for (int i = 0; i < entries.size();) {
            int j = i + 1;
            while (j < entries.size() && entries.get(j).offset() - entries.get(j - 1).end() <= MERGE_GAP_BYTES) {
                j++;
            }
            long groupStart = entries.get(i).offset();
            ByteBuffer group = range(url, groupStart, entries.get(j - 1).end());
            for (ZipEntryRef e : entries.subList(i, j)) {
                writeEntry(group, (int) (e.offset() - groupStart), e, outDir);
            }
            i = j;
        }
    }

    private static void writeEntry(ByteBuffer group, int local, ZipEntryRef e, Path outDir) throws IOException {
        if (group.getInt(local) != 0x04034B50) {
            throw new IOException("Corrupt zip local header for " + e.name());
        }
        int dataStart = local + 30 + (group.getShort(local + 26) & 0xFFFF) + (group.getShort(local + 28) & 0xFFFF);
        byte[] compressed = new byte[(int) e.compressedSize()];
        group.get(dataStart, compressed);

        byte[] data = switch (e.method()) {
            case 0 -> compressed;
            case 8 -> inflate(compressed, (int) e.size(), e.name());
            default -> throw new IOException("Unsupported zip compression method " + e.method() + " for " + e.name());
        };
        CRC32 crc = new CRC32();
        crc.update(data);
        if (data.length != e.size() || crc.getValue() != e.crc()) {
            throw new IOException("CRC mismatch extracting " + e.name());
        }

        Path root = outDir.normalize();
        Path out = root.resolve(e.name()).normalize();
        if (!out.startsWith(root)) {
            throw new IOException("Zip entry escapes the output directory: " + e.name());
        }
        Files.createDirectories(out.getParent());
        Files.write(out, data);
    }

    private static byte[] inflate(byte[] compressed, int size, String name) throws IOException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[size];
            int n = 0;
            while (n < size && !inflater.finished()) {
                int read = inflater.inflate(out, n, size - n);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += read;
            }
            if (n != size) {
                throw new IOException("Truncated deflate data for " + name);
            }
            return out;
        } catch (DataFormatException ex) {
            throw new IOException("Corrupt deflate data for " + name, ex);
        } finally {
            inflater.end();
        }
    }

    /** Fetches bytes {@code [from, to)}. */
    private ByteBuffer range(String url, long from, long to) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Range", "bytes=" + from + "-" + (to - 1))
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }

        // Closing the stream unread cancels the transfer, so a server ignoring Range never sends the whole jar
        byte[] body;
        try (InputStream in = response.body()) {
            if (response.statusCode() == 200) {
                throw new RangeNotSupportedException("Server ignored a range request for " + url);
            }
            if (response.statusCode() != 206) {
                throw new IOException("Range request failed for " + url + ": HTTP " + response.statusCode());
            }
            body = in.readAllBytes();
        }
        if (body.length != to - from) {
            throw new IOException("Short range response for " + url + ": expected " + (to - from) + " bytes, got "
                            + body.length);
        }
        return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private ClientJar resolveClientJar(String versionId) throws IOException {
        Map<String, Object> manifest = fetchJson(VERSION_MANIFEST_URL);
        String resolvedVersion = versionId != null ? versionId : latestRelease(manifest);
        Map<String, Object> versionMeta = fetchJson(findVersionUrl(manifest, resolvedVersion));
        @SuppressWarnings("unchecked")
        Map<String, Object> downloads = (Map<String, Object>) versionMeta.get("downloads");
        @SuppressWarnings("unchecked") Map<String, Object> client = (Map<String, Object>) downloads.get("client");
        return new ClientJar(resolvedVersion, (String) client.get("url"), (String) client.get("sha1"),
                ((Number) client.get("size")).longValue());
    }

    private String latestRelease(Map<String, Object> manifest) {
        @SuppressWarnings("unchecked") Map<String, Object> latest = (Map<String, Object>) manifest.get("latest");
        return (String) latest.get("release");
    }

    private String findVersionUrl(Map<String, Object> manifest, String versionId) throws IOException {
        @SuppressWarnings("unchecked") List<Object> versions = (List<Object>) manifest.get("versions");
        for (Object o : versions) {
            @SuppressWarnings("unchecked") Map<String, Object> v = (Map<String, Object>) o;
            if (versionId.equals(v.get("id"))) {
                return (String) v.get("url");
            }
        }
        throw new IOException("Unknown Minecraft version: " + versionId);
    }

    private Map<String, Object> fetchJson(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch " + url + ": HTTP " + response.statusCode());
        }
        return Json.parseObject(response.body());
    }

    private static String sha1Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

}
