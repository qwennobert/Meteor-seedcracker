package com.qwennobert.seedminer.seed;

import dev.xpple.seedmapper.config.Configs;
import dev.xpple.seedmapper.util.SeedIdentifier;

/**
 * Reads the seed configured in SeedMapper via {@code /sm:config Seed set <seed>}.
 */
public final class SeedMapperSeed {
    private SeedMapperSeed() {
    }

    public static SeedIdentifier get() {
        try {
            return Configs.Seed;
        }
        catch (Throwable t) {
            // SeedMapper internals are not API, don't crash the module if they change
            return null;
        }
    }
}
