package com.cleanroommc.mcttf.extract;

import java.io.IOException;
import java.io.InputStream;

/** Source of Minecraft asset files, addressed by resource path e.g. {@code assets/minecraft/font/ascii.png}. */
public interface AssetSource extends AutoCloseable {

    boolean exists(String path);

    InputStream open(String path) throws IOException;

    @Override
    void close() throws IOException;

    default byte[] readAllBytes(String path) throws IOException {
        try (InputStream in = open(path)) {
            return in.readAllBytes();
        }
    }

    default String readString(String path) throws IOException {
        return new String(readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }

}
