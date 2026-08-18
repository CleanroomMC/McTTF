package com.cleanroommc.mcttf.testsupport;

import com.cleanroommc.mcttf.extract.AssetSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** In-memory {@link AssetSource} for descriptor-level tests (exists / optional tiny payloads). */
public final class MemoryAssetSource implements AssetSource {

    private final Map<String, byte[]> files = new HashMap<>();

    public MemoryAssetSource(String... existingPaths) {
        for (String path : existingPaths) {
            files.put(path, new byte[0]);
        }
    }

    @Override
    public boolean exists(String path) {
        return files.containsKey(path);
    }

    @Override
    public InputStream open(String path) throws IOException {
        byte[] bytes = files.get(path);
        if (bytes == null) {
            throw new IOException("Not found: " + path);
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void close() {
    }
}
