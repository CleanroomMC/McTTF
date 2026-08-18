package com.cleanroommc.mcttf.download;

import com.cleanroommc.mcttf.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/** Downloads (and caches) a vanilla Minecraft client jar straight from Mojang's public manifests. */
public final class MinecraftClientDownloader {

    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

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
        Map<String, Object> manifest = fetchJson(VERSION_MANIFEST_URL);
        String resolvedVersion = versionId != null ? versionId : latestRelease(manifest);
        String versionUrl = findVersionUrl(manifest, resolvedVersion);

        Map<String, Object> versionMeta = fetchJson(versionUrl);
        @SuppressWarnings("unchecked")
        Map<String, Object> downloads = (Map<String, Object>) versionMeta.get("downloads");
        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) downloads.get("client");
        String jarUrl = (String) client.get("url");
        String sha1 = (String) client.get("sha1");

        Path target = cacheRoot.resolve(resolvedVersion).resolve("client.jar");
        if (Files.isRegularFile(target) && sha1.equals(sha1Hex(target))) {
            return target;
        }

        Files.createDirectories(target.getParent());
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

    private String latestRelease(Map<String, Object> manifest) {
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) manifest.get("latest");
        return (String) latest.get("release");
    }

    private String findVersionUrl(Map<String, Object> manifest, String versionId) throws IOException {
        @SuppressWarnings("unchecked")
        List<Object> versions = (List<Object>) manifest.get("versions");
        for (Object o : versions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> v = (Map<String, Object>) o;
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
