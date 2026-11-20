/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: MIT
 */

package com.cleanroommc.mcttf.testsupport;

import com.cleanroommc.mcttf.extract.AssetSource;
import com.cleanroommc.mcttf.extract.DirAssetSource;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

/** Root of the vanilla 26.2 font JSON + PNGs copied into test resources. */
public final class VanillaFixtures {

    private VanillaFixtures() { }

    public static Path root() {
        try {
            return Path.of(Objects.requireNonNull(VanillaFixtures.class.getResource("/vanilla"),
                            "missing /vanilla test resources")
                            .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static AssetSource open() {
        return new DirAssetSource(root());
    }

}
