package com.qwennobert.seedminer.seed;

import com.github.cubiomes.Cubiomes;
import com.qwennobert.seedminer.SeedMinerAddon;
import dev.xpple.seedmapper.util.SeedIdentifier;
import net.fabricmc.loader.api.FabricLoader;

/**
 * A world seed together with where it came from.
 */
public record ResolvedSeed(long seed, int generatorFlags, SeedSource origin) {
    private static final boolean SEEDCRACKERX = FabricLoader.getInstance().isModLoaded("seedcrackerx");

    public static ResolvedSeed resolve(SeedSource source, String manualSeed, boolean largeBiomes) {
        int flags = largeBiomes ? Cubiomes.LARGE_BIOMES() : 0;

        return switch (source) {
            case Manual -> manual(manualSeed, flags);
            case SeedcrackerX -> seedcrackerX(flags);
            case SeedMapper -> seedMapper(flags);
            case Auto -> {
                ResolvedSeed seed = manual(manualSeed, flags);
                if (seed == null) seed = seedcrackerX(flags);
                if (seed == null) seed = seedMapper(flags);
                yield seed;
            }
        };
    }

    private static ResolvedSeed manual(String text, int flags) {
        Long seed = parseSeed(text);
        return seed == null ? null : new ResolvedSeed(seed, flags, SeedSource.Manual);
    }

    private static ResolvedSeed seedcrackerX(int flags) {
        if (!SEEDCRACKERX) return null;
        Long seed = SeedCrackerXBridge.crackedSeed();
        return seed == null ? null : new ResolvedSeed(seed, flags, SeedSource.SeedcrackerX);
    }

    private static ResolvedSeed seedMapper(int flags) {
        SeedIdentifier identifier = SeedMapperSeed.get();
        if (identifier == null) return null;
        return new ResolvedSeed(identifier.seed(), flags | identifier.generatorFlags(), SeedSource.SeedMapper);
    }

    /**
     * Parses a seed the same way the vanilla world creation screen does: numbers are used as-is, any other text is hashed.
     */
    public static Long parseSeed(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty()) return null;

        try {
            return Long.parseLong(text);
        }
        catch (NumberFormatException e) {
            SeedMinerAddon.LOG.debug("Seed '{}' is not a number, using its hash", text);
            return (long) text.hashCode();
        }
    }
}
