package com.qwennobert.seedminer.predict;

import com.github.cubiomes.Cubiomes;

/**
 * All cubiomes ore features. Every feature has to be simulated, even unwanted ones, because ores that generate
 * later can only replace certain blocks placed by earlier ores. Same list as SeedMapper's {@code OreTypes}.
 */
public final class OreFeatures {
    private OreFeatures() {
    }

    public static int[] all() {
        return new int[] {
            Cubiomes.AndesiteOre(),
            Cubiomes.BlackstoneOre(),
            Cubiomes.BuriedDiamondOre(),
            Cubiomes.BuriedLapisOre(),
            Cubiomes.ClayOre(),
            Cubiomes.CoalOre(),
            Cubiomes.CopperOre(),
            Cubiomes.DeepslateOre(),
            Cubiomes.DeltasGoldOre(),
            Cubiomes.DeltasQuartzOre(),
            Cubiomes.DiamondOre(),
            Cubiomes.DioriteOre(),
            Cubiomes.DirtOre(),
            Cubiomes.EmeraldOre(),
            Cubiomes.ExtraGoldOre(),
            Cubiomes.GoldOre(),
            Cubiomes.GraniteOre(),
            Cubiomes.GravelOre(),
            Cubiomes.IronOre(),
            Cubiomes.LapisOre(),
            Cubiomes.LargeCopperOre(),
            Cubiomes.LargeDebrisOre(),
            Cubiomes.LargeDiamondOre(),
            Cubiomes.LowerAndesiteOre(),
            Cubiomes.LowerCoalOre(),
            Cubiomes.LowerDioriteOre(),
            Cubiomes.LowerGoldOre(),
            Cubiomes.LowerGraniteOre(),
            Cubiomes.LowerRedstoneOre(),
            Cubiomes.MagmaOre(),
            Cubiomes.MediumDiamondOre(),
            Cubiomes.MiddleIronOre(),
            Cubiomes.NetherGoldOre(),
            Cubiomes.NetherGravelOre(),
            Cubiomes.NetherQuartzOre(),
            Cubiomes.RedstoneOre(),
            Cubiomes.SmallDebrisOre(),
            Cubiomes.SmallIronOre(),
            Cubiomes.SoulSandOre(),
            Cubiomes.TuffOre(),
            Cubiomes.UpperAndesiteOre(),
            Cubiomes.UpperCoalOre(),
            Cubiomes.UpperDioriteOre(),
            Cubiomes.UpperGraniteOre(),
            Cubiomes.UpperIronOre()
        };
    }
}
