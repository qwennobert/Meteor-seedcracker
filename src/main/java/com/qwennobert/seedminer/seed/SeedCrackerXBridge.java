package com.qwennobert.seedminer.seed;

import kaptainwutax.seedcrackerX.api.SeedCrackerAPI;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;

/**
 * SeedcrackerX entrypoint ({@code "seedcrackerx"} in fabric.mod.json). SeedcrackerX calls it as soon as it has cracked
 * the world seed. Only loaded when SeedcrackerX is installed.
 */
public class SeedCrackerXBridge implements SeedCrackerAPI {
    private static volatile Long crackedSeed;

    @Override
    public void pushWorldSeed(long seed) {
        crackedSeed = seed;
        MinecraftClient.getInstance().execute(() -> ChatUtils.infoPrefix("Seed Miner", "Received seed (highlight)%d(default) from SeedcrackerX.", seed));
    }

    public static Long crackedSeed() {
        return crackedSeed;
    }

    public static void clear() {
        crackedSeed = null;
    }
}
