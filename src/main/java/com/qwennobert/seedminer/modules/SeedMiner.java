package com.qwennobert.seedminer.modules;

import com.github.cubiomes.Cubiomes;
import com.qwennobert.seedminer.SeedMinerAddon;
import com.qwennobert.seedminer.compress.Compressible;
import com.qwennobert.seedminer.compress.Compressor;
import com.qwennobert.seedminer.predict.OreKind;
import com.qwennobert.seedminer.predict.OreScanner;
import com.qwennobert.seedminer.predict.PredictedOre;
import com.qwennobert.seedminer.predict.ScanParams;
import com.qwennobert.seedminer.seed.ResolvedSeed;
import com.qwennobert.seedminer.seed.SeedSource;
import com.qwennobert.seedminer.util.BaritoneControl;
import com.qwennobert.seedminer.util.ToolSelector;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class SeedMiner extends Module {
    private final SettingGroup sgSeed = settings.createGroup("Seed");
    private final SettingGroup sgTargets = settings.createGroup("Targets");
    private final SettingGroup sgMining = settings.createGroup("Mining");
    private final SettingGroup sgAutoTool = settings.createGroup("Auto Tool");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgAntiDetection = settings.createGroup("Anti-Detection", false);
    private final SettingGroup sgAutoCompress = settings.createGroup("Auto Compress", false);
    private final SettingGroup sgRender = settings.createGroup("Render");

    // Seed

    private final Setting<SeedSource> seedSource = sgSeed.add(new EnumSetting.Builder<SeedSource>()
        .name("seed-source")
        .description("Where the world seed comes from. Auto: manual seed if set, otherwise SeedMapper (/sm:config Seed set, saved seeds, online database).")
        .defaultValue(SeedSource.Auto)
        .onChanged(v -> invalidate())
        .build()
    );

    private final Setting<String> manualSeed = sgSeed.add(new StringSetting.Builder()
        .name("manual-seed")
        .description("World seed (number or text like in the world creation screen). Leave empty to use the seed from SeedMapper.")
        .defaultValue("")
        .onChanged(v -> invalidate())
        .build()
    );

    private final Setting<Boolean> largeBiomes = sgSeed.add(new BoolSetting.Builder()
        .name("large-biomes")
        .description("Enable if the world was created with the Large Biomes preset.")
        .defaultValue(false)
        .onChanged(v -> invalidate())
        .build()
    );

    // Targets

    private final Setting<List<Block>> blocks = sgTargets.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Ores to predict and mine. Stone and deepslate variants are treated the same.")
        .defaultValue(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS)
        .filter(OreKind::isSupported)
        .onChanged(v -> invalidate())
        .build()
    );

    private final Setting<Boolean> oreVeins = sgTargets.add(new BoolSetting.Builder()
        .name("ore-veins")
        .description("Also predict large copper/iron ore veins (incl. raw ore blocks). Slower to scan.")
        .defaultValue(false)
        .onChanged(v -> invalidate())
        .build()
    );

    private final Setting<Integer> chunkRadius = sgTargets.add(new IntSetting.Builder()
        .name("chunk-radius")
        .description("How many chunks around you are scanned.")
        .defaultValue(4)
        .range(0, 16)
        .sliderRange(0, 12)
        .onChanged(v -> lastScanCenter = null)
        .build()
    );

    private final Setting<Integer> minY = sgTargets.add(new IntSetting.Builder()
        .name("min-y")
        .description("Ignore ores below this height.")
        .defaultValue(-64)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .onChanged(v -> invalidate())
        .build()
    );

    private final Setting<Integer> maxY = sgTargets.add(new IntSetting.Builder()
        .name("max-y")
        .description("Ignore ores above this height.")
        .defaultValue(320)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .onChanged(v -> invalidate())
        .build()
    );

    private final Setting<Boolean> followPlayer = sgTargets.add(new BoolSetting.Builder()
        .name("follow-player")
        .description("Keep scanning new chunks around you while you move.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> serverAntiXray = sgTargets.add(new BoolSetting.Builder()
        .name("server-anti-xray")
        .description("The server may use anti-xray (e.g. Paper). Only blocks that touch air are trusted then, because hidden blocks can be fake ores or fake stone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mineSeenOres = sgTargets.add(new BoolSetting.Builder()
        .name("mine-seen-ores")
        .description("Also mine ores of the selected types that are visible near you, even if the prediction missed them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<VerifyMode> verifyMode = sgTargets.add(new EnumSetting.Builder<VerifyMode>()
        .name("verify")
        .description("Check predictions against loaded chunks. SkipAir: drop positions that are air (caves, already mined). Exact: also drop positions that are not the ore (with server-anti-xray only for blocks that touch air).")
        .defaultValue(VerifyMode.SkipAir)
        .build()
    );

    // Mining

    private final Setting<Boolean> autoMine = sgMining.add(new BoolSetting.Builder()
        .name("auto-mine")
        .description("Let Baritone walk to and mine the predicted ores. Disable to only highlight them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxDistance = sgMining.add(new IntSetting.Builder()
        .name("max-distance")
        .description("Ignore ores that are further away than this horizontally (blocks).")
        .defaultValue(96)
        .range(8, 512)
        .sliderRange(16, 256)
        .build()
    );

    private final Setting<Double> verticalCost = sgMining.add(new DoubleSetting.Builder()
        .name("vertical-cost")
        .description("How much height differences count when choosing the next ore. 1 = plain 3D distance, higher values prefer ores on your level.")
        .defaultValue(1.5)
        .range(0, 10)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Double> veinPriority = sgMining.add(new DoubleSetting.Builder()
        .name("vein-priority")
        .description("How much groups of ores are preferred over single ores. 0 = always the nearest ore, 0.5 = a group of 4 may be twice as far away, 1 = the group with the least walking per ore.")
        .defaultValue(0.5)
        .range(0, 3)
        .sliderRange(0, 2)
        .build()
    );

    private final Setting<Integer> targetTimeout = sgMining.add(new IntSetting.Builder()
        .name("target-timeout")
        .description("Skip an ore if you get no closer to it for this many seconds.")
        .defaultValue(60)
        .range(5, 600)
        .sliderRange(10, 300)
        .build()
    );

    private final Setting<Integer> retries = sgMining.add(new IntSetting.Builder()
        .name("path-retries")
        .description("How often to retry an ore when Baritone gives up on the path without getting closer.")
        .defaultValue(2)
        .range(0, 10)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Boolean> collectDrops = sgMining.add(new BoolSetting.Builder()
        .name("collect-drops")
        .description("Walk to the drops of mined ores before moving on, so none are left behind.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> collectRadius = sgMining.add(new IntSetting.Builder()
        .name("collect-radius")
        .description("How far away (blocks) drops are collected.")
        .defaultValue(6)
        .range(1, 16)
        .sliderRange(2, 12)
        .visible(collectDrops::get)
        .build()
    );

    private final Setting<Integer> collectTimeout = sgMining.add(new IntSetting.Builder()
        .name("collect-timeout")
        .description("Give up on a drop after this many seconds.")
        .defaultValue(8)
        .range(1, 60)
        .sliderRange(2, 20)
        .visible(collectDrops::get)
        .build()
    );

    private final Setting<Boolean> configureBaritone = sgMining.add(new BoolSetting.Builder()
        .name("configure-baritone")
        .description("Sets Baritone's allowBreak to true while active (required) and the options below. Restored when disabled.")
        .defaultValue(true)
        .onChanged(v -> applyBaritoneSettings())
        .build()
    );

    private final Setting<Boolean> allowPlace = sgMining.add(new BoolSetting.Builder()
        .name("allow-place")
        .description("Allow Baritone to place blocks (bridging, pillaring, blocking lava).")
        .defaultValue(true)
        .visible(configureBaritone::get)
        .onChanged(v -> applyBaritoneSettings())
        .build()
    );

    private final Setting<Boolean> allowParkour = sgMining.add(new BoolSetting.Builder()
        .name("allow-parkour")
        .description("Allow Baritone to jump over gaps.")
        .defaultValue(false)
        .visible(configureBaritone::get)
        .onChanged(v -> applyBaritoneSettings())
        .build()
    );

    private final Setting<Boolean> disableWhenDone = sgMining.add(new BoolSetting.Builder()
        .name("disable-when-done")
        .description("Disable the module when no ores are left. Otherwise it waits for new chunks.")
        .defaultValue(false)
        .build()
    );

    // Auto Tool

    private final Setting<Boolean> autoTool = sgAutoTool.add(new BoolSetting.Builder()
        .name("auto-tool")
        .description("Select the tool for every block Baritone breaks instead of letting Baritone do it. Tools must be in the hotbar.")
        .defaultValue(true)
        .onChanged(v -> applyBaritoneSettings())
        .build()
    );

    private final Setting<Boolean> fortuneOnlyOnOres = sgAutoTool.add(new BoolSetting.Builder()
        .name("fortune-only-on-ores")
        .description("Use fortune pickaxes only on ores and a pickaxe without fortune for everything else, so the fortune pickaxe lasts longer.")
        .defaultValue(true)
        .visible(autoTool::get)
        .build()
    );

    private final Setting<Boolean> fortuneFallback = sgAutoTool.add(new BoolSetting.Builder()
        .name("fortune-fallback")
        .description("If there is no other pickaxe, use the fortune pickaxe for other blocks too. Otherwise the module stops.")
        .defaultValue(false)
        .visible(() -> autoTool.get() && fortuneOnlyOnOres.get())
        .build()
    );

    private final Setting<Integer> minToolDurability = sgAutoTool.add(new IntSetting.Builder()
        .name("min-durability")
        .description("Don't use tools with less durability left than this (percent), so they don't break.")
        .defaultValue(5)
        .range(0, 50)
        .sliderRange(0, 25)
        .visible(autoTool::get)
        .build()
    );

    // Safety

    private final Setting<Integer> minHealth = sgSafety.add(new IntSetting.Builder()
        .name("min-health")
        .description("Stop and disable when your health (incl. absorption) drops to this value. 0 = off.")
        .defaultValue(8)
        .range(0, 36)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> stopWhenFull = sgSafety.add(new BoolSetting.Builder()
        .name("stop-when-full")
        .description("Disable when the drops of the selected ores don't fit into your inventory anymore. Until then, only ores whose drops still fit are mined.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> leaveWhenFull = sgSafety.add(new BoolSetting.Builder()
        .name("leave-when-full")
        .description("Also leave the server when the drops don't fit anymore.")
        .defaultValue(false)
        .visible(stopWhenFull::get)
        .build()
    );

    private final Setting<Integer> timeLimit = sgSafety.add(new IntSetting.Builder()
        .name("time-limit")
        .description("Disable after this many minutes. 0 = no limit.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 240)
        .build()
    );

    private final Setting<Boolean> leaveAtTimeLimit = sgSafety.add(new BoolSetting.Builder()
        .name("leave-at-time-limit")
        .description("Also leave the server when the time limit is reached.")
        .defaultValue(false)
        .visible(() -> timeLimit.get() > 0)
        .build()
    );

    private final Setting<Boolean> requirePickaxe = sgSafety.add(new BoolSetting.Builder()
        .name("require-pickaxe")
        .description("Disable when there is no pickaxe in your hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseNearPlayers = sgSafety.add(new BoolSetting.Builder()
        .name("pause-near-players")
        .description("Pause mining while another player is close.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> playerRange = sgSafety.add(new IntSetting.Builder()
        .name("player-range")
        .description("Distance for pause-near-players.")
        .defaultValue(32)
        .range(4, 256)
        .sliderRange(8, 128)
        .visible(pauseNearPlayers::get)
        .build()
    );

    // Anti-Detection

    private final Setting<Boolean> antiDetection = sgAntiDetection.add(new BoolSetting.Builder()
        .name("anti-detection")
        .description("Makes the mining pattern less obvious: limits ores per minute, adds random pauses and stone detours.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> maxOresPerMinute = sgAntiDetection.add(new IntSetting.Builder()
        .name("max-ores-per-minute")
        .description("Mine at most this many ores within any 60 seconds. 0 = no limit.")
        .defaultValue(8)
        .range(0, 120)
        .sliderRange(0, 30)
        .visible(antiDetection::get)
        .build()
    );

    private final Setting<Boolean> randomPause = sgAntiDetection.add(new BoolSetting.Builder()
        .name("random-pause")
        .description("Wait a random time after each ore before going to the next one.")
        .defaultValue(true)
        .visible(antiDetection::get)
        .build()
    );

    private final Setting<Double> pauseMin = sgAntiDetection.add(new DoubleSetting.Builder()
        .name("pause-min")
        .description("Shortest pause in seconds.")
        .defaultValue(1.5)
        .range(0, 120)
        .sliderRange(0, 15)
        .visible(() -> antiDetection.get() && randomPause.get())
        .build()
    );

    private final Setting<Double> pauseMax = sgAntiDetection.add(new DoubleSetting.Builder()
        .name("pause-max")
        .description("Longest pause in seconds.")
        .defaultValue(6)
        .range(0, 120)
        .sliderRange(0, 30)
        .visible(() -> antiDetection.get() && randomPause.get())
        .build()
    );

    private final Setting<Boolean> stoneDetours = sgAntiDetection.add(new BoolSetting.Builder()
        .name("stone-detours")
        .description("Sometimes mine a random stone block nearby after an ore instead of heading straight to the next one.")
        .defaultValue(false)
        .visible(antiDetection::get)
        .build()
    );

    private final Setting<Integer> detourChance = sgAntiDetection.add(new IntSetting.Builder()
        .name("detour-chance")
        .description("Chance in percent to make a stone detour after an ore.")
        .defaultValue(25)
        .range(1, 100)
        .sliderRange(1, 100)
        .visible(() -> antiDetection.get() && stoneDetours.get())
        .build()
    );

    private final Setting<Integer> detourDistance = sgAntiDetection.add(new IntSetting.Builder()
        .name("detour-distance")
        .description("How far away (blocks) the stone for a detour may be.")
        .defaultValue(4)
        .range(2, 8)
        .sliderRange(2, 8)
        .visible(() -> antiDetection.get() && stoneDetours.get())
        .build()
    );

    // Auto Compress

    private final Setting<Boolean> autoCompress = sgAutoCompress.add(new BoolSetting.Builder()
        .name("auto-compress")
        .description("When the inventory is (almost) full, place a crafting table from your inventory, craft blocks with the recipe book (e.g. diamond blocks) and pick the table up again.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Item>> compressItems = sgAutoCompress.add(new ItemListSetting.Builder()
        .name("compress-items")
        .description("Which items are crafted into blocks.")
        .defaultValue(Items.DIAMOND)
        .filter(Compressible::supports)
        .visible(autoCompress::get)
        .build()
    );

    private final Setting<Integer> compressAtFreeSlots = sgAutoCompress.add(new IntSetting.Builder()
        .name("compress-at-free-slots")
        .description("Start when this many free inventory slots or fewer are left, or when the ore drops don't fit anymore.")
        .defaultValue(1)
        .range(0, 9)
        .sliderRange(0, 9)
        .visible(autoCompress::get)
        .build()
    );

    private final Setting<Boolean> showCraftingGui = sgAutoCompress.add(new BoolSetting.Builder()
        .name("show-crafting-gui")
        .description("Show the crafting window while crafting. Off (silent): the window is not drawn, but it is really opened, clicked and closed like a player does.")
        .defaultValue(false)
        .visible(autoCompress::get)
        .build()
    );

    private final Setting<Boolean> dropJunk = sgAutoCompress.add(new BoolSetting.Builder()
        .name("drop-junk")
        .description("Before crafting, throw out junk (stone, deepslate, gravel, ...) until there is room, keeping one stack of building blocks for Baritone. Otherwise junk lying around is picked up into the freed slots and the crafting table doesn't fit back in. Off: only craft when no junk lies next to you.")
        .defaultValue(true)
        .visible(autoCompress::get)
        .build()
    );

    private final Setting<List<Item>> junkItems = sgAutoCompress.add(new ItemListSetting.Builder()
        .name("junk-items")
        .description("Items that may be thrown out to make room.")
        .defaultValue(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DEEPSLATE, Items.TUFF, Items.DIORITE, Items.GRANITE,
            Items.ANDESITE, Items.CALCITE, Items.DRIPSTONE_BLOCK, Items.POINTED_DRIPSTONE, Items.GRAVEL, Items.FLINT, Items.DIRT,
            Items.SAND, Items.NETHERRACK, Items.BASALT, Items.SMOOTH_BASALT, Items.BLACKSTONE, Items.SOUL_SAND, Items.SOUL_SOIL,
            Items.END_STONE
        )
        .visible(() -> autoCompress.get() && dropJunk.get())
        .build()
    );

    private final Setting<Integer> compressDelayMin = sgAutoCompress.add(new IntSetting.Builder()
        .name("action-delay-min")
        .description("Shortest pause between two actions (placing, opening, clicking, ...) in milliseconds.")
        .defaultValue(100)
        .range(0, 2000)
        .sliderRange(0, 1000)
        .visible(autoCompress::get)
        .build()
    );

    private final Setting<Integer> compressDelayMax = sgAutoCompress.add(new IntSetting.Builder()
        .name("action-delay-max")
        .description("Longest pause between two actions in milliseconds.")
        .defaultValue(300)
        .range(0, 2000)
        .sliderRange(0, 1000)
        .visible(autoCompress::get)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlight predicted ores.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renderLimit = sgRender.add(new IntSetting.Builder()
        .name("render-limit")
        .description("Maximum number of highlighted ores (nearest first).")
        .defaultValue(300)
        .range(1, 5000)
        .sliderRange(50, 1000)
        .visible(render::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color of predicted ores.")
        .defaultValue(new SettingColor(0, 200, 255, 25))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color of predicted ores.")
        .defaultValue(new SettingColor(0, 200, 255, 200))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("target-color")
        .description("Color of the ore that is currently being mined.")
        .defaultValue(new SettingColor(255, 60, 60, 220))
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer")
        .description("Draw a line to the current target.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final OreScanner scanner = new OreScanner();
    private final Map<BlockPos, PredictedOre> ores = new HashMap<>();
    /** Positions that were mined, skipped or found invalid. Kept until the seed/world changes. */
    private final Set<BlockPos> finished = new HashSet<>();
    /** Predicted positions where the real ore block was seen, so that turning into air means it was mined. */
    private final Set<BlockPos> seenOre = new HashSet<>();
    /** The ore kinds and height range of the current scan, used for ores that are seen but were not predicted. */
    private Set<OreKind> selectedKinds = EnumSet.noneOf(OreKind.class);
    private int scanMinY, scanMaxY;
    private final List<PredictedOre> renderList = new ArrayList<>();

    private BaritoneControl baritone;
    private ResolvedSeed seed, resolvedSeed;
    private int seedTimer;
    private RegistryKey<World> dimension;
    private ChunkPos lastScanCenter;
    private boolean paramsDirty = true;

    private PredictedOre target;
    private int targetTicks, targetRetries, retryWaitTicks, verifyTimer;
    /** Closest the eyes got to the current target, getting closer resets the timeout and the retries. */
    private double targetBestDistance;
    /** Baritone was stopped to break a block directly, so it stopping is not a failed path. */
    private boolean baritoneStopped;
    private int mined, skipped, invalid;

    // Anti-detection state
    private final Random random = new Random();
    /** Real time (ms) of recently mined ores, for the per-minute limit. */
    private final Deque<Long> minedTimes = new ArrayDeque<>();
    private long pauseUntil;
    private BlockPos detourPos;
    private int detourTicks;

    // Drop collection state
    private ItemEntity collectItem;
    private int collectTicks, collectRetries;
    /** Drops that could not be reached, by entity id. */
    private final Set<Integer> ignoredDrops = new HashSet<>();
    /** Ticks left to wait for the drops of the ore that was just mined, the server sends them a moment later. */
    private int dropWaitTicks;
    private static final int DROP_WAIT_TICKS = 20;
    /** The target ore is done, but the follow-up (detour, pause) waits until its drops are collected. */
    private boolean targetDonePending;

    /** Set from the block break event, the module is stopped on the next tick (not in the middle of Baritone's tick). */
    private String autoToolStop;
    private boolean warnedNoSeed;
    private String status = "Idle";
    private Compressor compressor;
    /** When the module was enabled, for the time limit. */
    private long startedAt;

    public SeedMiner() {
        super(SeedMinerAddon.CATEGORY, "seed-miner", "Predicts ores from the world seed (via SeedMapper) and mines them with Baritone.");
    }

    @Override
    public void onActivate() {
        startedAt = System.currentTimeMillis();
        if (!BaritoneUtils.IS_AVAILABLE && autoMine.get()) {
            warning("Baritone (Meteor build) is not installed, ores will only be highlighted.");
        }

        if (BaritoneUtils.IS_AVAILABLE && baritone == null) baritone = new BaritoneControl();
        if (baritone != null && compressor == null) compressor = new Compressor(mc, baritone, message -> info("%s", message), message -> warning("%s", message));
        applyBaritoneSettings();

        warnedNoSeed = false;
        autoToolStop = null;
        lastScanCenter = null;
        pauseUntil = 0;
        ignoredDrops.clear();
        clearTarget();
        status = "Waiting for seed";
    }

    @Override
    public void onDeactivate() {
        if (compressor != null) compressor.abort();
        if (baritone != null) {
            baritone.stop();
            baritone.restore();
            baritone.setExternalAutoTool(false);
        }
        target = null;
        detourPos = null;
        collectItem = null;
        status = "Idle";
    }

    /** Silent auto compress: the crafting window is open, but not drawn. */
    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (compressor != null && compressor.hidesScreen(event.screen)) event.cancel();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (compressor != null) compressor.reset();
        resetAll();
        toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (autoToolStop != null) {
            error(autoToolStop);
            autoToolStop = null;
            toggle();
            return;
        }

        if (!updateParams()) return;

        // Scan chunks around the player
        ChunkPos center = mc.player.getChunkPos();
        if (lastScanCenter == null || (followPlayer.get() && !center.equals(lastScanCenter))) {
            scanner.scanAround(center, chunkRadius.get());
            lastScanCenter = center;
        }

        // Collect finished chunks
        for (OreScanner.ChunkResult result; (result = scanner.poll()) != null; ) {
            for (PredictedOre ore : result.ores()) {
                if (!finished.contains(ore.pos())) ores.put(ore.pos(), ore);
            }
        }

        if (++verifyTimer >= 10) {
            verifyTimer = 0;
            if (mineSeenOres.get()) addSeenOres();
            verifyLoaded();
            updateRenderList();
        }

        if (!autoMine.get() || baritone == null) {
            if (target != null) clearTarget();
            status = ores.isEmpty() ? scanStatus("No ores") : "Highlighting " + ores.size() + " ores";
            return;
        }

        if (!safetyCheck()) return;

        mine();
    }

    /** Picks the tool right before a block starts breaking, for Baritone and also for blocks you break yourself. */
    @EventHandler
    private void onStartBreaking(StartBreakingBlockEvent event) {
        if (!autoTool.get() || autoToolStop != null) return;

        BlockState state = mc.world.getBlockState(event.blockPos);
        if (state.isAir() || state.getHardness(mc.world, event.blockPos) == 0) return;

        OreKind kind = OreKind.of(state.getBlock());
        boolean ore = kind != null && kind.fortuneAffects();

        int slot = ToolSelector.select(mc.player.getInventory(), state, ore, fortuneOnlyOnOres.get(), fortuneFallback.get(), minToolDurability.get());
        if (slot == ToolSelector.ONLY_FORTUNE) {
            event.cancel();
            autoToolStop = "Only a fortune pickaxe can mine " + state.getBlock().getName().getString()
                + ". Put another pickaxe in your hotbar or enable fortune-fallback. Stopping.";
            return;
        }

        if (slot >= 0 && slot != mc.player.getInventory().getSelectedSlot()) InvUtils.swap(slot, false);
    }

    /**
     * Notices ores the moment the client learns about them. With anti-xray an ore only becomes visible once it is
     * exposed, and a fast pickaxe breaks it right after, so the periodic check alone could miss it.
     */
    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        PredictedOre ore = ores.get(event.pos);
        if (ore == null) return;

        // An ore that is broken was real (you can only reach exposed blocks, and anti-xray shows those truthfully).
        // An ore that appears is only trusted if it is exposed, anti-xray fills hidden blocks with fake ores.
        boolean broken = isGone(event.newState) && ore.kind().matches(event.oldState.getBlock());
        boolean appeared = ore.kind().matches(event.newState.getBlock()) && isTrusted(ore.pos());
        if (broken || appeared) seenOre.add(ore.pos());
    }

    /** Resolves the seed and the scan parameters. Returns false if there is nothing to do yet. */
    private boolean updateParams() {
        // Asking SeedMapper is not free, once per second is plenty
        if (seed == null || paramsDirty || ++seedTimer >= 20) {
            seedTimer = 0;
            resolvedSeed = ResolvedSeed.resolve(mc, seedSource.get(), manualSeed.get(), largeBiomes.get());
        }

        ResolvedSeed resolved = resolvedSeed;
        if (resolved == null) {
            status = "Waiting for seed";
            if (!warnedNoSeed) {
                warning("No seed available. Set manual-seed or use /sm:config Seed set <seed> (SeedMapper).");
                warnedNoSeed = true;
            }
            return false;
        }

        RegistryKey<World> currentDimension = mc.world.getRegistryKey();
        if (!resolved.equals(seed) || currentDimension != dimension) {
            if (seed == null || resolved.seed() != seed.seed()) info("Using seed (highlight)%d(default) from %s.", resolved.seed(), resolved.origin());
            seed = resolved;
            dimension = currentDimension;
            paramsDirty = true;
        }

        if (paramsDirty) {
            paramsDirty = false;

            int cubiomesDimension;
            if (dimension == World.NETHER) cubiomesDimension = Cubiomes.DIM_NETHER();
            else if (dimension == World.END) cubiomesDimension = Cubiomes.DIM_END();
            else cubiomesDimension = Cubiomes.DIM_OVERWORLD();

            Set<OreKind> kinds = EnumSet.noneOf(OreKind.class);
            for (Block block : blocks.get()) {
                OreKind kind = OreKind.of(block);
                if (kind != null) kinds.add(kind);
            }

            ScanParams params = new ScanParams(seed.seed(), seed.version(), cubiomesDimension, seed.generatorFlags(),
                kinds, Math.min(minY.get(), maxY.get()), Math.max(minY.get(), maxY.get()), oreVeins.get());
            selectedKinds = kinds;
            scanMinY = params.minY();
            scanMaxY = params.maxY();

            if (scanner.setParams(params)) {
                ores.clear();
                finished.clear();
                seenOre.clear();
                renderList.clear();
                clearTarget();
                lastScanCenter = null;
            }
        }

        return true;
    }

    /**
     * Compares predictions with the real world in loaded chunks. Ores that were seen and are gone now were mined
     * (e.g. by Baritone on the way to another ore), predictions that don't match are dropped depending on the verify mode.
     */
    private void verifyLoaded() {
        VerifyMode mode = verifyMode.get();

        for (Iterator<PredictedOre> it = ores.values().iterator(); it.hasNext(); ) {
            PredictedOre ore = it.next();
            if (ore == target || !isLoaded(ore.pos())) continue;

            BlockState state = mc.world.getBlockState(ore.pos());
            boolean trusted = isTrusted(ore.pos());
            if (ore.kind().matches(state.getBlock())) {
                if (trusted) seenOre.add(ore.pos());
                continue;
            }

            boolean gone = isGone(state);
            boolean drop;
            if (gone && seenOre.contains(ore.pos())) {
                countMined();
                drop = true;
            }
            else {
                // The prediction doesn't know bedrock, positions near the bottom of the world can be unbreakable
                drop = isUnbreakable(state, ore.pos()) || switch (mode) {
                    case Off -> false;
                    case SkipAir -> gone;
                    case Exact -> gone || trusted;
                };
                if (drop) invalid++;
            }

            if (drop) {
                it.remove();
                seenOre.remove(ore.pos());
                finished.add(ore.pos());
            }
        }
    }

    /** Radius (blocks) around the player in which real ore blocks are added as targets. */
    private static final int SEEN_ORE_RADIUS = 5;

    /**
     * Adds ore blocks of the selected kinds near the player that the prediction missed. Without anti-xray the client
     * knows every block, with anti-xray only exposed ones (hidden ones may be fake), which are exactly the ones left
     * next to the tunnel.
     */
    private void addSeenOres() {
        if (selectedKinds.isEmpty()) return;

        BlockPos center = mc.player.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(center.add(-SEEN_ORE_RADIUS, -SEEN_ORE_RADIUS, -SEEN_ORE_RADIUS), center.add(SEEN_ORE_RADIUS, SEEN_ORE_RADIUS, SEEN_ORE_RADIUS))) {
            if (pos.getY() < scanMinY || pos.getY() > scanMaxY || ores.containsKey(pos) || finished.contains(pos)) continue;

            OreKind kind = OreKind.of(mc.world.getBlockState(pos).getBlock());
            if (kind == null || !selectedKinds.contains(kind) || !isTrusted(pos)) continue;

            BlockPos immutable = pos.toImmutable();
            ores.put(immutable, new PredictedOre(immutable, kind));
            seenOre.add(immutable);
        }
    }

    /**
     * Whether the block the client sees at this position can be believed. Anti-xray only hides blocks that don't
     * touch air (or other see-through blocks) and shows exposed blocks as they really are.
     */
    private boolean isTrusted(BlockPos pos) {
        if (!serverAntiXray.get()) return true;

        BlockPos.Mutable neighbor = new BlockPos.Mutable();
        for (Direction direction : Direction.values()) {
            if (!mc.world.getBlockState(neighbor.set(pos, direction)).isOpaqueFullCube()) return true;
        }
        return false;
    }

    private static boolean isGone(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    private boolean isUnbreakable(BlockState state, BlockPos pos) {
        return state.getHardness(mc.world, pos) < 0;
    }

    /** Disables the module and, if wanted, leaves the server the way Meteor's Auto Log does. */
    private void stop(String reason, boolean leave) {
        info("%s, %s.", reason, leave ? "leaving the server" : "stopping");
        toggle();
        if (!leave) return;

        // Otherwise Auto Reconnect would join again right away
        AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect.isActive()) autoReconnect.toggle();
        mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(Text.literal("[Seed Miner] " + reason + ".")));
    }

    private boolean safetyCheck() {
        if (minHealth.get() > 0 && PlayerUtils.getTotalHealth() <= minHealth.get()) {
            error("Health is low, stopping.");
            toggle();
            return false;
        }

        // Crafting blocks to make room is finished first, it only takes a few seconds
        if (compressor.isActive()) {
            status = compressor.tick();
            return false;
        }

        boolean full = !hasRoomForAnyDrop();
        if (autoCompress.get() && (full || freeSlots() <= compressAtFreeSlots.get())) {
            Compressor.Options options = compressOptions();
            if (compressor.canStart(options)) {
                baritone.stop();
                baritoneStopped = true;
                collectItem = null;
                compressor.start(options);
                status = "Compressing";
                return false;
            }
        }

        if (stopWhenFull.get() && full) {
            stop("No room left for the drops of the selected ores", leaveWhenFull.get());
            return false;
        }

        if (timeLimit.get() > 0 && System.currentTimeMillis() - startedAt >= timeLimit.get() * 60_000L) {
            stop("Time limit of " + timeLimit.get() + (timeLimit.get() == 1 ? " minute" : " minutes") + " reached", leaveAtTimeLimit.get());
            return false;
        }

        if (requirePickaxe.get() && !hasPickaxe()) {
            error("No pickaxe in your hotbar, stopping.");
            toggle();
            return false;
        }

        if (pauseNearPlayers.get() && isPlayerNearby()) {
            if (target != null || detourPos != null || collectItem != null) {
                baritone.stop();
                target = null;
                detourPos = null;
                collectItem = null;
            }
            status = "Paused, player nearby";
            return false;
        }

        return true;
    }

    private void mine() {
        BlockHitResult directHit = null;

        if (target != null) {
            BlockPos pos = target.pos();
            boolean loaded = isLoaded(pos);
            BlockState state = loaded ? mc.world.getBlockState(pos) : null;

            boolean trusted = loaded && isTrusted(pos);
            if (trusted && target.kind().matches(state.getBlock())) seenOre.add(pos);

            // Getting closer is progress: the timeout and the path retries only count while you get nowhere
            double distance = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
            if (distance < targetBestDistance - 1) {
                targetBestDistance = distance;
                targetTicks = 0;
                targetRetries = 0;
                retryWaitTicks = 0;
            }

            boolean gone = loaded && isGone(state);
            boolean wrong = loaded && !gone && ((trusted && verifyMode.get() == VerifyMode.Exact && !target.kind().matches(state.getBlock())) || isUnbreakable(state, pos));

            if (gone || wrong) {
                // Only an ore that was really there counts as mined. Otherwise the prediction was wrong
                // (stone, cave, already mined), even if Baritone had to break the block to get there.
                if (gone && seenOre.contains(pos)) {
                    countMined();
                    dropWaitTicks = DROP_WAIT_TICKS;
                }
                else invalid++;
                finishTarget();
            }
            else if (loaded && mc.player.isOnGround() && !touchesFluid(pos) && ((directHit = findReachableFace(pos)) != null || (directHit = findBlockInTheWay(pos)) != null)) {
                // In reach: break it from here, or first the block that hides it. Baritone's goal is to stand inside
                // the ore, which can be impossible (e.g. ore above your head and no blocks to pillar up with).
                // Only while standing, in the middle of a jump (pillaring up) the ore is out of reach again a moment later.
                // Not when water or lava touches the ore, it would flow out towards you. Baritone has its own rules for that.
                if (baritone.isWorking()) {
                    baritone.stop();
                    baritoneStopped = true;
                }
                breakDirectly(directHit);
                if (++targetTicks > targetTimeout.get() * 20) skipNoProgress(pos);
            }
            else if (baritone.wasHijacked()) {
                warning("Baritone was given another goal, pausing. Re-enable the module to continue.");
                baritone.stop();
                toggle();
                return;
            }
            else if (++targetTicks > targetTimeout.get() * 20) skipNoProgress(pos);
            else if (!baritone.isWorking()) {
                // Baritone reached the goal without the block breaking, or could not find a path. If it was stopped
                // here to break a block directly, it simply continues.
                if (baritoneStopped) {
                    baritoneStopped = false;
                    baritone.mine(pos);
                }
                // Right after a teleport Baritone finds no way until the chunks around you have arrived, that's no failure
                else if (!isAreaLoaded(mc.player.getBlockPos())) retryWaitTicks = 0;
                // Ask again after a moment (1 s, 2 s, ...)
                else if (++retryWaitTicks >= 20 * (targetRetries + 1)) {
                    retryWaitTicks = 0;
                    if (targetRetries++ < retries.get()) baritone.mine(pos);
                    else {
                        warning("Baritone could not reach ore at %s, skipping.", pos.toShortString());
                        skipped++;
                        finishTarget();
                    }
                }
            }

            if (target == null) {
                targetDonePending = true;
                directHit = null;
            }
        }

        // Pick up the drops before moving on, Baritone would otherwise walk away during the pickup delay
        if (target == null && detourPos == null && collectDrops.get()) {
            if (collectItem == null) findDrop();
            if (collectItem != null) {
                if (baritone.wasHijacked()) {
                    warning("Baritone was given another goal, pausing. Re-enable the module to continue.");
                    baritone.stop();
                    toggle();
                    return;
                }
                dropWaitTicks = 0;
                updateCollect();
                status = "Collecting drops";
                return;
            }
            // The block is gone on your side right away, its drops only show up once the server has broken it too.
            // When you stand inside the ore that doesn't matter, but not when it was broken from a few blocks away.
            if (dropWaitTicks > 0) {
                dropWaitTicks--;
                status = "Collecting drops";
                return;
            }
        }

        if (targetDonePending) {
            targetDonePending = false;
            onTargetDone();
        }

        if (detourPos != null) {
            if (baritone.wasHijacked()) {
                warning("Baritone was given another goal, pausing. Re-enable the module to continue.");
                baritone.stop();
                toggle();
                return;
            }
            if (!updateDetour()) return;
        }

        if (target == null) {
            String wait = antiDetectionWait();
            if (wait != null) {
                baritone.stop();
                status = wait;
                return;
            }

            target = nextTarget();

            if (target == null) {
                baritone.stop();
                if (scanner.pendingChunks() == 0 && disableWhenDone.get()) {
                    info("No ores left (mined %d, skipped %d).", mined, skipped);
                    toggle();
                }
                status = scanStatus("No ores in range");
                return;
            }

            targetTicks = 0;
            targetRetries = 0;
            retryWaitTicks = 0;
            targetBestDistance = Double.MAX_VALUE;
            baritoneStopped = false;
            baritone.mine(target.pos());
        }

        status = "Mining " + target.kind().name().toLowerCase().replace('_', ' ') + " at " + target.pos().toShortString()
            + (directHit == null ? "" : directHit.getBlockPos().equals(target.pos()) ? " (in reach)" : " (uncovering)");
    }

    // Drop collection

    /** Looks for the nearest drop of a selected ore and lets Baritone walk to it. */
    private void findDrop() {
        double best = collectRadius.get() * collectRadius.get();
        ItemEntity found = null;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity item) || item.isRemoved() || ignoredDrops.contains(item.getId())) continue;
            if (!isWantedDrop(item.getStack()) || !hasRoomFor(item.getStack().getItem())) continue;

            double distance = item.squaredDistanceTo(mc.player);
            if (distance <= best) {
                best = distance;
                found = item;
            }
        }

        if (found != null) {
            collectItem = found;
            collectTicks = 0;
            collectRetries = 0;
            baritone.walkTo(found.getBlockPos());
        }
    }

    private void updateCollect() {
        ItemEntity item = collectItem;

        // Picked up (or despawned, burned, ...)
        if (item.isRemoved()) {
            collectItem = null;
            return;
        }

        if (++collectTicks > collectTimeout.get() * 20) {
            giveUpDrop();
            return;
        }

        if (!baritone.isWorking()) {
            // In pickup range (a bit less than the server's, to be sure): just wait, fresh drops can only be picked up
            // after a short delay
            if (mc.player.getBoundingBox().expand(0.8, 0.4, 0.8).intersects(item.getBoundingBox())) return;

            // The item moved (fell down) or Baritone gave up, try again
            if (collectRetries++ < 2) baritone.walkTo(item.getBlockPos());
            else giveUpDrop();
        }
    }

    private void giveUpDrop() {
        ignoredDrops.add(collectItem.getId());
        collectItem = null;
        baritone.stop();
    }

    /** The item can still be picked up: there is a free slot, or a stack of it that isn't full yet. */
    private boolean hasRoomFor(Item item) {
        PlayerInventory inventory = mc.player.getInventory();
        if (inventory.getEmptySlot() != -1) return true;

        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item) && stack.getCount() < stack.getMaxCount()) return true;
        }
        ItemStack offhand = mc.player.getOffHandStack();
        return offhand.isOf(item) && offhand.getCount() < offhand.getMaxCount();
    }

    private Compressor.Options compressOptions() {
        return new Compressor.Options(compressItems.get(), showCraftingGui.get(), compressDelayMin.get(), compressDelayMax.get(), dropJunk.get(), junkItems.get());
    }

    private int freeSlots() {
        int free = 0;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    private boolean hasRoomForAnyDrop() {
        for (Block block : blocks.get()) {
            OreKind kind = OreKind.of(block);
            if (kind != null && hasRoomFor(kind.drop())) return true;
        }
        return false;
    }

    private boolean isWantedDrop(ItemStack stack) {
        for (Block block : blocks.get()) {
            OreKind kind = OreKind.of(block);
            if (kind != null && kind.isDrop(stack.getItem())) return true;
        }
        return false;
    }

    // Anti-detection

    private void countMined() {
        mined++;
        long now = System.currentTimeMillis();
        minedTimes.addLast(now);
        purgeMinedTimes(now);
    }

    private void purgeMinedTimes(long now) {
        while (!minedTimes.isEmpty() && now - minedTimes.peekFirst() >= 60_000) minedTimes.removeFirst();
    }

    /** Called when the current target ore is done (mined, wrong or skipped): maybe a stone detour, then maybe a pause. */
    private void onTargetDone() {
        if (!antiDetection.get()) return;

        if (stoneDetours.get() && random.nextInt(100) < detourChance.get() && startDetour()) return;
        schedulePause();
    }

    private void schedulePause() {
        if (!antiDetection.get() || !randomPause.get()) return;

        double min = Math.min(pauseMin.get(), pauseMax.get());
        double max = Math.max(pauseMin.get(), pauseMax.get());
        pauseUntil = System.currentTimeMillis() + (long) ((min + random.nextDouble() * (max - min)) * 1000);
    }

    /** Picks a random stone block near the player and lets Baritone mine it. */
    private boolean startDetour() {
        BlockPos base = mc.player.getBlockPos();
        int d = detourDistance.get();

        for (int i = 0; i < 30; i++) {
            BlockPos pos = base.add(random.nextInt(2 * d + 1) - d, random.nextInt(3) - 1, random.nextInt(2 * d + 1) - d);
            if (pos.equals(base) || pos.equals(base.up()) || !isLoaded(pos) || ores.containsKey(pos)) continue;

            BlockState state = mc.world.getBlockState(pos);
            if (!state.isIn(BlockTags.BASE_STONE_OVERWORLD) && !state.isIn(BlockTags.BASE_STONE_NETHER)) continue;

            detourPos = pos;
            detourTicks = 0;
            baritone.mine(pos);
            status = "Detour (stone) at " + pos.toShortString();
            return true;
        }

        return false;
    }

    /** Returns true when the detour is over. */
    private boolean updateDetour() {
        boolean gone = isGone(mc.world.getBlockState(detourPos));

        // Give Baritone a moment to start, then end the detour once it is idle, the block is gone or it takes too long
        if (gone || ++detourTicks > 20 * 20 || (detourTicks > 5 && !baritone.isWorking())) {
            detourPos = null;
            schedulePause();
            return true;
        }

        status = "Detour (stone) at " + detourPos.toShortString();
        return false;
    }

    /** Returns a status text while anti-detection wants to wait before the next ore, otherwise null. */
    private String antiDetectionWait() {
        if (!antiDetection.get()) return null;

        long now = System.currentTimeMillis();
        if (now < pauseUntil) return "Pause (" + seconds(pauseUntil - now) + "s)";

        int limit = maxOresPerMinute.get();
        if (limit > 0) {
            purgeMinedTimes(now);
            if (minedTimes.size() >= limit) {
                return "Waiting (rate limit, " + seconds(60_000 - (now - minedTimes.peekFirst())) + "s)";
            }
        }

        return null;
    }

    private static long seconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    /** Time left until the time limit as "Time left: m:ss", or null without a limit. */
    public String timeLeftInfo() {
        if (!isActive() || timeLimit.get() <= 0) return null;

        long left = Math.max(0, timeLimit.get() * 60_000L - (System.currentTimeMillis() - startedAt)) / 1000;
        return String.format("Time left: %d:%02d", left / 60, left % 60);
    }

    /** Short summary for the GUI, HUD and command, or null when anti-detection is off. */
    public String antiDetectionInfo() {
        if (!antiDetection.get()) return null;

        purgeMinedTimes(System.currentTimeMillis());
        int limit = maxOresPerMinute.get();
        return "Last minute: " + minedTimes.size() + (limit > 0 ? "/" + limit : "") + " ores";
    }

    // Breaking ores in reach

    /** Returns where a ray from the eyes first hits the block, if it is in reach and not hidden behind other blocks. */
    private BlockHitResult findReachableFace(BlockPos pos) {
        for (BlockHitResult hit : raycastsInReach(pos)) {
            if (hit.getBlockPos().equals(pos)) return hit;
        }
        return null;
    }

    /**
     * The block is in reach, but other blocks are in the way: returns the first of them if it is safe to break.
     * Mining it uncovers the ore, like a player would do instead of walking around.
     */
    private BlockHitResult findBlockInTheWay(BlockPos pos) {
        for (BlockHitResult hit : raycastsInReach(pos)) {
            if (!hit.getBlockPos().equals(pos) && isSafeToBreak(hit.getBlockPos())) return hit;
        }
        return null;
    }

    /** Rays from the eyes to the center and to the middle of each face of the block, as far as they are in reach. */
    private List<BlockHitResult> raycastsInReach(BlockPos pos) {
        List<BlockHitResult> hits = new ArrayList<>();
        Vec3d eyes = mc.player.getEyePos();
        double reach = mc.player.getBlockInteractionRange() - 0.25;
        Vec3d center = Vec3d.ofCenter(pos);
        if (eyes.squaredDistanceTo(center) > (reach + 0.9) * (reach + 0.9)) return hits;

        List<Vec3d> points = new ArrayList<>();
        points.add(center);
        for (Direction direction : Direction.values()) {
            points.add(center.add(direction.getOffsetX() * 0.49, direction.getOffsetY() * 0.49, direction.getOffsetZ() * 0.49));
        }

        for (Vec3d point : points) {
            if (eyes.squaredDistanceTo(point) > reach * reach) continue;

            BlockHitResult hit = mc.world.raycast(new RaycastContext(eyes, point, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK) hits.add(hit);
        }

        return hits;
    }

    private boolean isSafeToBreak(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() || isUnbreakable(state, pos) || !state.getFluidState().isEmpty() || mc.world.getBlockEntity(pos) != null) return false;

        // Not the floor you are standing on
        Box box = mc.player.getBoundingBox();
        if (new Box(box.minX, box.minY - 0.5, box.minZ, box.maxX, box.minY, box.maxZ).intersects(new Box(pos))) return false;

        // No water or lava flowing in, no sand or gravel coming down
        return !touchesFluid(pos) && !(mc.world.getBlockState(pos.up()).getBlock() instanceof FallingBlock);
    }

    private boolean touchesFluid(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!mc.world.getFluidState(pos.offset(direction)).isEmpty()) return true;
        }
        return false;
    }

    /** Looks at the block and keeps mining it, like holding the attack key. */
    private void breakDirectly(BlockHitResult hit) {
        mc.player.setYaw((float) Rotations.getYaw(hit.getPos()));
        mc.player.setPitch((float) Rotations.getPitch(hit.getPos()));

        if (mc.interactionManager.updateBlockBreakingProgress(hit.getBlockPos(), hit.getSide())) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private PredictedOre nextTarget() {
        PredictedOre best = null;
        double bestCost = Double.MAX_VALUE;
        double maxDistSq = maxDistance.get() * maxDistance.get();

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        Map<BlockPos, Integer> veinSizes = veinPriority.get() > 0 ? veinSizes(maxDistSq) : null;

        // With stop-when-full, ores whose drops don't fit anymore are left for later
        Set<OreKind> full = EnumSet.noneOf(OreKind.class);
        if (stopWhenFull.get()) {
            for (Block block : blocks.get()) {
                OreKind kind = OreKind.of(block);
                if (kind != null && !hasRoomFor(kind.drop())) full.add(kind);
            }
        }

        for (PredictedOre ore : ores.values()) {
            if (full.contains(ore.kind())) continue;
            BlockPos pos = ore.pos();
            double dx = pos.getX() + 0.5 - px, dy = pos.getY() - py, dz = pos.getZ() + 0.5 - pz;
            // Horizontal only, ores are often far below the player (e.g. diamonds when standing on a mountain)
            double horizontalSq = dx * dx + dz * dz;
            if (horizontalSq > maxDistSq) continue;

            // Distance with height weighted, like the way there: diagonally down counts as far as it really is
            double vertical = dy * verticalCost.get();
            double cost = Math.sqrt(horizontalSq + vertical * vertical);

            // Prefer groups of ores over single ones: with vein-priority 1 this is the walking distance per ore
            if (veinSizes != null) cost /= Math.pow(veinSizes.get(pos), veinPriority.get());

            // Finish the ores you can already see right next to you before walking off, otherwise a vein can be
            // left half mined and its rest ends up out of range later
            if (seenOre.contains(pos) && horizontalSq + dy * dy <= SEEN_ORE_RADIUS * SEEN_ORE_RADIUS) cost -= 1000;

            if (cost < bestCost) {
                bestCost = cost;
                best = ore;
            }
        }

        return best;
    }

    /** Radius (blocks) in which ores count as one group, so a block of stone between two ores still connects them. */
    private static final int VEIN_GAP = 2;

    /** Number of ores in the group each ore within max-distance belongs to. */
    private Map<BlockPos, Integer> veinSizes(double maxDistSq) {
        double px = mc.player.getX(), pz = mc.player.getZ();
        Set<BlockPos> candidates = new HashSet<>();
        for (BlockPos pos : ores.keySet()) {
            double dx = pos.getX() + 0.5 - px, dz = pos.getZ() + 0.5 - pz;
            if (dx * dx + dz * dz <= maxDistSq) candidates.add(pos);
        }

        Map<BlockPos, Integer> sizes = new HashMap<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> group = new ArrayList<>();
        BlockPos.Mutable neighbour = new BlockPos.Mutable();

        for (BlockPos start : candidates) {
            if (sizes.containsKey(start)) continue;

            group.clear();
            sizes.put(start, 0);
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                group.add(pos);

                for (int x = -VEIN_GAP; x <= VEIN_GAP; x++) {
                    for (int y = -VEIN_GAP; y <= VEIN_GAP; y++) {
                        for (int z = -VEIN_GAP; z <= VEIN_GAP; z++) {
                            neighbour.set(pos, x, y, z);
                            if (candidates.contains(neighbour) && !sizes.containsKey(neighbour)) {
                                BlockPos next = neighbour.toImmutable();
                                sizes.put(next, 0);
                                queue.add(next);
                            }
                        }
                    }
                }
            }

            for (BlockPos pos : group) sizes.put(pos, group.size());
        }

        return sizes;
    }

    private void skipNoProgress(BlockPos pos) {
        warning("Got no closer to ore at %s for %d seconds, skipping.", pos.toShortString(), targetTimeout.get());
        skipped++;
        finishTarget();
    }

    private void finishTarget() {
        if (target != null) {
            ores.remove(target.pos());
            seenOre.remove(target.pos());
            finished.add(target.pos());
        }
        target = null;
    }

    private void clearTarget() {
        if (baritone != null) baritone.stop();
        target = null;
        detourPos = null;
        collectItem = null;
        dropWaitTicks = 0;
        targetDonePending = false;
    }

    private boolean isLoaded(BlockPos pos) {
        return mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** The chunk of the position and the ones around it are loaded. */
    private boolean isAreaLoaded(BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (!mc.world.getChunkManager().isChunkLoaded(cx + x, cz + z)) return false;
            }
        }
        return true;
    }

    private boolean hasPickaxe() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isIn(ItemTags.PICKAXES)) return true;
        }
        return mc.player.getOffHandStack().isIn(ItemTags.PICKAXES);
    }

    private boolean isPlayerNearby() {
        double rangeSq = playerRange.get() * playerRange.get();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (player.squaredDistanceTo(mc.player) <= rangeSq) return true;
        }
        return false;
    }

    private String scanStatus(String idle) {
        int pending = scanner.pendingChunks();
        return pending > 0 ? "Scanning, " + pending + " chunks left" : idle;
    }

    private void updateRenderList() {
        renderList.clear();
        if (!render.get()) return;

        renderList.addAll(ores.values());
        if (renderList.size() > renderLimit.get()) {
            BlockPos playerPos = mc.player.getBlockPos();
            renderList.sort((a, b) -> Double.compare(a.pos().getSquaredDistance(playerPos), b.pos().getSquaredDistance(playerPos)));
            renderList.subList(renderLimit.get(), renderList.size()).clear();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        for (PredictedOre ore : renderList) {
            if (ore == target) continue;
            event.renderer.box(ore.pos(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }

        if (target != null) {
            BlockPos pos = target.pos();
            event.renderer.box(pos, targetColor.get(), targetColor.get(), shapeMode.get(), 0);
            if (tracer.get()) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, targetColor.get());
            }
        }
    }

    // GUI

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        list.add(theme.label("Seed: " + (seed == null ? "none" : seed.seed() + " (" + seed.origin() + ")")));
        list.add(theme.label("Status: " + status));
        list.add(theme.label("Predicted: " + ores.size() + "  Mined: " + mined + "  Skipped: " + skipped + "  Invalid: " + invalid));
        String antiDetection = antiDetectionInfo();
        if (antiDetection != null) list.add(theme.label("Anti-Detection: " + antiDetection));
        String timeLeft = timeLeftInfo();
        if (timeLeft != null) list.add(theme.label(timeLeft));

        WHorizontalList buttons = list.add(theme.horizontalList()).widget();

        WButton rescan = buttons.add(theme.button("Rescan")).widget();
        rescan.action = this::rescan;

        WButton skip = buttons.add(theme.button("Skip target")).widget();
        skip.action = this::skipTarget;

        WButton reset = buttons.add(theme.button("Reset stats")).widget();
        reset.action = () -> mined = skipped = invalid = 0;

        return list;
    }

    // API for the command and HUD

    /** Forgets all predictions (including mined/skipped positions) and scans again. */
    public void rescan() {
        ores.clear();
        finished.clear();
        seenOre.clear();
        renderList.clear();
        clearTarget();
        scanner.reset();
        lastScanCenter = null;
    }

    public void skipTarget() {
        if (target == null) return;
        skipped++;
        finishTarget();
        if (baritone != null) baritone.stop();
    }

    public void setManualSeed(String value) {
        manualSeed.set(value);
    }

    private void resetAll() {
        rescan();
        seed = resolvedSeed = null;
        dimension = null;
        paramsDirty = true;
    }

    private void invalidate() {
        paramsDirty = true;
    }

    /** Applies or restores the Baritone settings, also when they are changed while the module is running. */
    private void applyBaritoneSettings() {
        if (baritone == null || !isActive()) return;

        if (configureBaritone.get()) baritone.configure(allowPlace.get(), allowParkour.get());
        else baritone.restore();

        baritone.setExternalAutoTool(autoTool.get());
    }

    public String status() {
        return isActive() ? status : "Disabled";
    }

    public ResolvedSeed seed() {
        return seed;
    }

    public int predicted() {
        return ores.size();
    }

    public int mined() {
        return mined;
    }

    public int skipped() {
        return skipped;
    }

    public int invalid() {
        return invalid;
    }

    @Override
    public String getInfoString() {
        return isActive() ? Integer.toString(ores.size()) : null;
    }

    public enum VerifyMode {
        Off,
        SkipAir,
        Exact
    }
}
