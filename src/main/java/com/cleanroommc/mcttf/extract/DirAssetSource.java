package com.cleanroommc.mcttf.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads assets from an already-extracted jar or resource pack directory (root containing {@code assets/}). */
public final class DirAssetSource implements AssetSource {

    private final Path root;

    public DirAssetSource(Path root) {
        this.root = root;
    }

    @Override
    public boolean exists(String path) {
        return Files.isRegularFile(resolve(path));
    }

    @Override
    public InputStream open(String path) throws IOException {
        return Files.newInputStream(resolve(path));
    }

    @Override
    public void close() { }

    private Path resolve(String path) {
        return root.resolve(path);
    }

}
