/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.extract;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads assets directly out of the vanilla client jar (or any zip with the same layout). */
public final class JarAssetSource implements AssetSource {

    private final ZipFile zip;

    public JarAssetSource(File jarFile) throws IOException {
        this.zip = new ZipFile(jarFile);
    }

    @Override
    public boolean exists(String path) {
        return this.zip.getEntry(path) != null;
    }

    @Override
    public InputStream open(String path) throws IOException {
        ZipEntry entry = this.zip.getEntry(path);
        if (entry == null) {
            throw new IOException("Not found in jar: " + path);
        }
        return this.zip.getInputStream(entry);
    }

    @Override
    public void close() throws IOException {
        this.zip.close();
    }

}
