package com.qwennobert.seedminer.seed;

public enum SeedSource {
    /** Manual seed if set, otherwise the SeedcrackerX result, otherwise SeedMapper's configured seed. */
    Auto,
    Manual,
    SeedcrackerX,
    SeedMapper
}
