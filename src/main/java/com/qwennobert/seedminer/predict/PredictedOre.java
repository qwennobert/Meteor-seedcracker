package com.qwennobert.seedminer.predict;

import net.minecraft.util.math.BlockPos;

public record PredictedOre(BlockPos pos, OreKind kind) {
}
