package com.qwennobert.seedminer.modules;

import com.github.cubiomes.Cubiomes;
import com.qwennobert.seedminer.SeedMinerAddon;
import com.qwennobert.seedminer.predict.OreKind;
import com.qwennobert.seedminer.predict.OreScanner;
import com.qwennobert.seedminer.predict.PredictedOre;
import com.qwennobert.seedminer.predict.ScanParams;
import com.qwennobert.seedminer.seed.ResolvedSeed;
import com.qwennobert.seedminer.seed.SeedSource;
import com.qwennobert.seedminer.util.BaritoneControl;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
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
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SeedMiner extends Module {
    private final SettingGroup sgSeed = settings.createGroup("Seed");
    private final SettingGroup sgTargets = settings.createGroup("Targets");
    private final SettingGroup sgMining = settings.createGroup("Mining");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // Seed

    private final Setting<SeedSource> seedSource = sgSeed.add(new EnumSetting.Builder<SeedSource>()
        .name("seed-source")
        .description("Where the world seed comes from. Auto: manual seed, then SeedcrackerX, then SeedMapper (/sm:config Seed set).")
        .defaultValue(SeedSource.Auto)
        .onChanged(v -> invalidate())
        .build()
    );

    private final Setting<String> manualSeed = sgSeed.add(new StringSetting.Builder()
        .name("manual-seed")
        .description("World seed (number or text like in the world creation screen). Leave empty to use SeedcrackerX/SeedMapper.")
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

    private final Setting<VerifyMode> verifyMode = sgTargets.add(new EnumSetting.Builder<VerifyMode>()
        .name("verify")
        .description("Check predictions against loaded chunks. SkipAir: drop positions that are air (caves, already mined). Exact: only keep positions that really contain the ore (does not work with anti-xray).")
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
        .description("Ignore ores further away than this (blocks).")
        .defaultValue(96)
        .range(8, 512)
        .sliderRange(16, 256)
        .build()
    );

    private final Setting<Double> verticalCost = sgMining.add(new DoubleSetting.Builder()
        .name("vertical-cost")
        .description("Multiplier for height differences when choosing the next ore. Higher values prefer ores on your level.")
        .defaultValue(2)
        .range(0, 10)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Integer> targetTimeout = sgMining.add(new IntSetting.Builder()
        .name("target-timeout")
        .description("Skip an ore if it wasn't mined after this many seconds.")
        .defaultValue(90)
        .range(5, 600)
        .sliderRange(10, 300)
        .build()
    );

    private final Setting<Integer> retries = sgMining.add(new IntSetting.Builder()
        .name("path-retries")
        .description("How often to retry an ore when Baritone gives up on the path.")
        .defaultValue(2)
        .range(0, 10)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Boolean> configureBaritone = sgMining.add(new BoolSetting.Builder()
        .name("configure-baritone")
        .description("Sets Baritone's allowBreak to true while active (required) and the options below. Restored when disabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowPlace = sgMining.add(new BoolSetting.Builder()
        .name("allow-place")
        .description("Allow Baritone to place blocks (bridging, pillaring, blocking lava).")
        .defaultValue(true)
        .visible(configureBaritone::get)
        .build()
    );

    private final Setting<Boolean> allowParkour = sgMining.add(new BoolSetting.Builder()
        .name("allow-parkour")
        .description("Allow Baritone to jump over gaps.")
        .defaultValue(false)
        .visible(configureBaritone::get)
        .build()
    );

    private final Setting<Boolean> disableWhenDone = sgMining.add(new BoolSetting.Builder()
        .name("disable-when-done")
        .description("Disable the module when no ores are left. Otherwise it waits for new chunks.")
        .defaultValue(false)
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
        .description("Disable when your inventory has no free slot left.")
        .defaultValue(true)
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
    private final List<PredictedOre> renderList = new ArrayList<>();

    private BaritoneControl baritone;
    private ResolvedSeed seed;
    private RegistryKey<World> dimension;
    private ChunkPos lastScanCenter;
    private boolean paramsDirty = true;

    private PredictedOre target;
    private int targetTicks, targetRetries, verifyTimer;
    /** Whether the target block was seen as a solid block, so that it turning into air means we mined it. */
    private boolean targetSeenSolid;
    private int mined, skipped, invalid;
    private boolean warnedNoSeed;
    private String status = "Idle";

    public SeedMiner() {
        super(SeedMinerAddon.CATEGORY, "seed-miner", "Predicts ores from the world seed (SeedcrackerX / SeedMapper) and mines them with Baritone.");
    }

    @Override
    public void onActivate() {
        if (!BaritoneUtils.IS_AVAILABLE && autoMine.get()) {
            warning("Baritone (Meteor build) is not installed, ores will only be highlighted.");
        }

        if (BaritoneUtils.IS_AVAILABLE) {
            if (baritone == null) baritone = new BaritoneControl();
            if (configureBaritone.get()) baritone.configure(allowPlace.get(), allowParkour.get());
        }

        warnedNoSeed = false;
        lastScanCenter = null;
        clearTarget();
        status = "Waiting for seed";
    }

    @Override
    public void onDeactivate() {
        if (baritone != null) {
            baritone.stop();
            baritone.restore();
        }
        target = null;
        status = "Idle";
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        resetAll();
        toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

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

    /** Resolves the seed and the scan parameters. Returns false if there is nothing to do yet. */
    private boolean updateParams() {
        ResolvedSeed resolved = ResolvedSeed.resolve(seedSource.get(), manualSeed.get(), largeBiomes.get());
        if (resolved == null) {
            status = "Waiting for seed";
            if (!warnedNoSeed) {
                warning("No seed available. Set manual-seed, crack it with SeedcrackerX or use /sm:config Seed set <seed>.");
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

            ScanParams params = new ScanParams(seed.seed(), Cubiomes.MC_1_21_11(), cubiomesDimension, seed.generatorFlags(),
                kinds, Math.min(minY.get(), maxY.get()), Math.max(minY.get(), maxY.get()), oreVeins.get());

            if (scanner.setParams(params)) {
                ores.clear();
                finished.clear();
                renderList.clear();
                clearTarget();
                lastScanCenter = null;
            }
        }

        return true;
    }

    /** Drops predictions that don't match the real world in loaded chunks. */
    private void verifyLoaded() {
        VerifyMode mode = verifyMode.get();

        for (Iterator<PredictedOre> it = ores.values().iterator(); it.hasNext(); ) {
            PredictedOre ore = it.next();
            if (ore == target || !isLoaded(ore.pos())) continue;

            BlockState state = mc.world.getBlockState(ore.pos());
            boolean valid = switch (mode) {
                case Off -> true;
                case SkipAir -> !state.isAir() && state.getFluidState().isEmpty();
                case Exact -> ore.kind().matches(state.getBlock());
            };

            if (!valid) {
                it.remove();
                finished.add(ore.pos());
                invalid++;
            }
        }
    }

    private boolean safetyCheck() {
        if (minHealth.get() > 0 && PlayerUtils.getTotalHealth() <= minHealth.get()) {
            error("Health is low, stopping.");
            toggle();
            return false;
        }

        if (stopWhenFull.get() && mc.player.getInventory().getEmptySlot() == -1) {
            info("Inventory is full, stopping.");
            toggle();
            return false;
        }

        if (requirePickaxe.get() && !hasPickaxe()) {
            error("No pickaxe in your hotbar, stopping.");
            toggle();
            return false;
        }

        if (pauseNearPlayers.get() && isPlayerNearby()) {
            if (target != null) {
                baritone.stop();
                target = null;
            }
            status = "Paused, player nearby";
            return false;
        }

        return true;
    }

    private void mine() {
        if (target != null) {
            BlockPos pos = target.pos();
            boolean loaded = isLoaded(pos);
            BlockState state = loaded ? mc.world.getBlockState(pos) : null;

            boolean gone = loaded && (state.isAir() || !state.getFluidState().isEmpty());
            boolean wrong = loaded && verifyMode.get() == VerifyMode.Exact && !target.kind().matches(state.getBlock());
            if (loaded && !gone && !wrong) targetSeenSolid = true;

            if (gone || wrong) {
                // Air after we saw the block means we mined it, otherwise the prediction was wrong (cave, already mined)
                if (gone && targetSeenSolid) mined++;
                else invalid++;
                finishTarget();
            }
            else if (baritone.wasHijacked()) {
                warning("Baritone was given another goal, pausing. Re-enable the module to continue.");
                baritone.stop();
                toggle();
                return;
            }
            else if (++targetTicks > targetTimeout.get() * 20) {
                warning("Could not mine ore at %s in time, skipping.", pos.toShortString());
                skipped++;
                finishTarget();
            }
            else if (!baritone.isWorking()) {
                // Baritone reached the goal without the block breaking, or could not find a path
                if (targetRetries++ < retries.get()) baritone.mine(pos);
                else {
                    warning("Baritone could not reach ore at %s, skipping.", pos.toShortString());
                    skipped++;
                    finishTarget();
                }
            }
        }

        if (target == null) {
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
            targetSeenSolid = false;
            baritone.mine(target.pos());
        }

        status = "Mining " + target.kind().name().toLowerCase().replace('_', ' ') + " at " + target.pos().toShortString();
    }

    private PredictedOre nextTarget() {
        PredictedOre best = null;
        double bestCost = Double.MAX_VALUE;
        double maxDistSq = maxDistance.get() * maxDistance.get();

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        for (PredictedOre ore : ores.values()) {
            BlockPos pos = ore.pos();
            double dx = pos.getX() + 0.5 - px, dy = pos.getY() - py, dz = pos.getZ() + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;

            double cost = Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * verticalCost.get();
            if (cost < bestCost) {
                bestCost = cost;
                best = ore;
            }
        }

        return best;
    }

    private void finishTarget() {
        if (target != null) {
            ores.remove(target.pos());
            finished.add(target.pos());
        }
        target = null;
    }

    private void clearTarget() {
        if (baritone != null) baritone.stop();
        target = null;
    }

    private boolean isLoaded(BlockPos pos) {
        return mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
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
        seed = null;
        dimension = null;
        paramsDirty = true;
    }

    private void invalidate() {
        paramsDirty = true;
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
