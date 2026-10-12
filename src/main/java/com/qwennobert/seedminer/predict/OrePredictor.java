package com.qwennobert.seedminer.predict;

import com.github.cubiomes.Cubiomes;
import com.github.cubiomes.Generator;
import com.github.cubiomes.OreConfig;
import com.github.cubiomes.OreVeinParameters;
import com.github.cubiomes.Pos3;
import com.github.cubiomes.Pos3List;
import com.github.cubiomes.SurfaceNoise;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Predicts ore positions from the world seed using the cubiomes bindings shipped with SeedMapper.
 * The ore generation logic mirrors SeedMapper's {@code /sm:highlight block} and {@code /sm:highlight orevein} commands.
 * <p>
 * Instances use a confined {@link Arena} and must only be used (and closed) by the thread that created them.
 */
public final class OrePredictor implements AutoCloseable {
    /** Y-range in which ore veins can generate. */
    private static final int ORE_VEIN_MIN_Y = -60, ORE_VEIN_MAX_Y = 50;

    private final Arena arena = Arena.ofConfined();
    private final int version;
    private final int dimension;
    private final MemorySegment generator;
    private final MemorySegment surfaceNoise;
    private final MemorySegment oreVeinParameters;
    private final int[] oreTypes;

    public OrePredictor(long seed, int version, int dimension, int generatorFlags) {
        this.version = version;
        this.dimension = dimension;

        generator = Generator.allocate(arena);
        Cubiomes.setupGenerator(generator, version, generatorFlags);
        Cubiomes.applySeed(generator, dimension, seed);

        surfaceNoise = SurfaceNoise.allocate(arena);
        Cubiomes.initSurfaceNoise(surfaceNoise, dimension, seed);

        if (dimension == Cubiomes.DIM_OVERWORLD()) {
            MemorySegment parameters = OreVeinParameters.allocate(arena);
            oreVeinParameters = Cubiomes.initOreVeinNoise(parameters, seed, version) != 0 ? parameters : null;
        }
        else oreVeinParameters = null;

        oreTypes = OreFeatures.all();
    }

    /**
     * Predicts the ores of one chunk.
     *
     * @param wanted   cubiomes block ids that should be reported
     * @param minY     lowest y (inclusive) to report
     * @param maxY     highest y (inclusive) to report
     * @param oreVeins whether large ore veins (copper/iron, 1.18+) should be included
     * @param out      receives every predicted position
     */
    public void predictChunk(int chunkX, int chunkZ, IntPredicate wanted, int minY, int maxY, boolean oreVeins, Output out) {
        // Insertion order matters: ores that generate later may only replace specific earlier blocks.
        Map<Long, int[]> generated = new HashMap<>();

        if (oreVeins && oreVeinParameters != null) predictOreVeins(chunkX, chunkZ, minY, maxY, generated);

        try (Arena chunkArena = Arena.ofConfined()) {
            List<Integer> biomes = new ArrayList<>(3);
            if (version <= Cubiomes.MC_1_17()) {
                biomes.add(Cubiomes.getBiomeForOreGen(generator, chunkX, chunkZ, 0));
            }
            else {
                // Same sample heights as SeedMapper, Minecraft checks all biomes in a 3x3 chunk square
                for (int y : new int[] {-30, 64, 120}) biomes.add(Cubiomes.getBiomeForOreGen(generator, chunkX, chunkZ, y));
            }

            List<MemorySegment> configs = new ArrayList<>();
            for (int oreType : oreTypes) {
                boolean viable = false;
                for (int biome : biomes) {
                    if (Cubiomes.isViableOreBiome(version, oreType, biome) != 0) {
                        viable = true;
                        break;
                    }
                }
                if (!viable) continue;

                MemorySegment config = OreConfig.allocate(chunkArena);
                if (Cubiomes.getOreConfig(oreType, version, biomes.getFirst(), config) != 0) configs.add(config);
            }
            configs.sort(Comparator.comparingInt(OreConfig::index));

            for (MemorySegment config : configs) {
                int oreBlock = OreConfig.oreBlock(config);
                int numReplaceBlocks = OreConfig.numReplaceBlocks(config);
                MemorySegment replaceBlocks = OreConfig.replaceBlocks(config);

                MemorySegment list = Cubiomes.generateOres(chunkArena, generator, surfaceNoise, config, chunkX, chunkZ);
                try {
                    int size = Pos3List.size(list);
                    MemorySegment positions = Pos3List.pos3s(list);

                    for (int i = 0; i < size; i++) {
                        MemorySegment pos = Pos3.asSlice(positions, i);
                        int x = Pos3.x(pos), y = Pos3.y(pos), z = Pos3.z(pos);
                        long key = pack(x, y, z);

                        int[] previous = generated.get(key);
                        if (previous != null && !canReplace(replaceBlocks, numReplaceBlocks, previous[3])) continue;

                        generated.put(key, new int[] {x, y, z, oreBlock});
                    }
                }
                finally {
                    Cubiomes.freePos3List(list);
                }
            }
        }

        for (int[] entry : generated.values()) {
            if (entry[1] < minY || entry[1] > maxY || !wanted.test(entry[3])) continue;
            out.accept(entry[0], entry[1], entry[2], entry[3]);
        }
    }

    private void predictOreVeins(int chunkX, int chunkZ, int minY, int maxY, Map<Long, int[]> generated) {
        int fromY = Math.max(minY, ORE_VEIN_MIN_Y);
        int toY = Math.min(maxY, ORE_VEIN_MAX_Y);
        if (fromY > toY) return;

        int granite = Cubiomes.GRANITE(), tuff = Cubiomes.TUFF();
        int minX = chunkX << 4, minZ = chunkZ << 4;

        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int y = fromY; y <= toY; y++) {
                    int block = Cubiomes.getOreVeinBlockAt(x, y, z, oreVeinParameters);
                    // Filler blocks are not interesting
                    if (block == -1 || block == granite || block == tuff) continue;
                    generated.put(pack(x, y, z), new int[] {x, y, z, block});
                }
            }
        }
    }

    private static boolean canReplace(MemorySegment replaceBlocks, int count, int block) {
        for (int i = 0; i < count; i++) {
            if (replaceBlocks.getAtIndex(Cubiomes.C_INT, i) == block) return true;
        }
        return false;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public int dimension() {
        return dimension;
    }

    @Override
    public void close() {
        arena.close();
    }

    @FunctionalInterface
    public interface Output {
        void accept(int x, int y, int z, int cubiomesBlock);
    }
}
