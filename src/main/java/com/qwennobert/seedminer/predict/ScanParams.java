package com.qwennobert.seedminer.predict;

import java.util.Set;

/**
 * Everything that influences the prediction result. When any of it changes, cached chunks are invalid.
 */
public record ScanParams(long seed, int version, int dimension, int generatorFlags, Set<OreKind> ores, int minY, int maxY, boolean oreVeins) {
}
