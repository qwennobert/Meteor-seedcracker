package com.qwennobert.seedminer.predict;

import com.github.cubiomes.Cubiomes;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * Blocks that can be predicted from the seed, mapped between Minecraft blocks and cubiomes block ids.
 * Cubiomes does not distinguish stone and deepslate ore variants, so both map to the same id.
 */
public enum OreKind {
    COAL(Cubiomes::COAL_ORE, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
    COPPER(Cubiomes::COPPER_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
    IRON(Cubiomes::IRON_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
    GOLD(Cubiomes::GOLD_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
    REDSTONE(Cubiomes::REDSTONE_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
    LAPIS(Cubiomes::LAPIS_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
    DIAMOND(Cubiomes::DIAMOND_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
    EMERALD(Cubiomes::EMERALD_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
    RAW_COPPER_BLOCK(Cubiomes::RAW_COPPER_BLOCK, Blocks.RAW_COPPER_BLOCK),
    RAW_IRON_BLOCK(Cubiomes::RAW_IRON_BLOCK, Blocks.RAW_IRON_BLOCK),
    NETHER_GOLD(Cubiomes::NETHER_GOLD_ORE, Blocks.NETHER_GOLD_ORE),
    NETHER_QUARTZ(Cubiomes::NETHER_QUARTZ_ORE, Blocks.NETHER_QUARTZ_ORE),
    ANCIENT_DEBRIS(Cubiomes::ANCIENT_DEBRIS, Blocks.ANCIENT_DEBRIS);

    private static final OreKind[] VALUES = values();

    private final IntSupplier cubiomesId;
    private final List<Block> blocks;
    private int cachedId = Integer.MIN_VALUE;

    OreKind(IntSupplier cubiomesId, Block... blocks) {
        this.cubiomesId = cubiomesId;
        this.blocks = List.of(blocks);
    }

    /** Resolved lazily, cubiomes must not be touched before SeedMapper has loaded the native library. */
    public int cubiomesId() {
        if (cachedId == Integer.MIN_VALUE) cachedId = cubiomesId.getAsInt();
        return cachedId;
    }

    public List<Block> blocks() {
        return blocks;
    }

    public boolean matches(Block block) {
        return blocks.contains(block);
    }

    public static OreKind of(Block block) {
        for (OreKind kind : VALUES) {
            if (kind.matches(block)) return kind;
        }
        return null;
    }

    public static OreKind ofCubiomes(int id) {
        for (OreKind kind : VALUES) {
            if (kind.cubiomesId() == id) return kind;
        }
        return null;
    }

    public static boolean isSupported(Block block) {
        return of(block) != null;
    }
}
