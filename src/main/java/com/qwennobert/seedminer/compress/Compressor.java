package com.qwennobert.seedminer.compress;

import com.qwennobert.seedminer.util.BaritoneControl;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.context.ContextParameterMap;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Crafts items into blocks to make room, the way a player would: take a crafting table from the inventory, place it,
 * open it, use the recipe book, take the result with shift-click, close it, mine the table and pick it up again.
 * Every step goes to the server as the normal packets, with short random pauses in between. With the crafting window
 * hidden ("silent") only the drawing is skipped, the window is really open.
 */
public final class Compressor {
    public record Options(List<Item> items, boolean showGui, int delayMin, int delayMax, boolean dropJunk, List<Item> junk) {
    }

    private enum State {
        AIM_THROW, THROW, SETTLE, CLOSE_INVENTORY, TO_HOTBAR, SELECT, AIM_PLACE, PLACE, WAIT_PLACED, AIM_OPEN, OPEN, WAIT_OPEN,
        CRAFT, WAIT_GRID, TAKE, WAIT_TAKEN, CLOSE, AIM_BREAK, BREAK, WAIT_DROP, PICKUP
    }

    private static final long COOLDOWN_MS = 60_000;
    private static final int MAX_ROUNDS = 12;
    /** Free slots wanted before the table is placed: one for the table, one for the crafted blocks. */
    private static final int ROOM = 2;
    private static final int MAX_THROWS = 10;
    /** Kept when throwing out junk, so Baritone still has blocks to build with. */
    private static final List<Item> BUILDING_BLOCKS = List.of(Items.COBBLESTONE, Items.NETHERRACK, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.STONE, Items.END_STONE);

    private final MinecraftClient mc;
    private final BaritoneControl baritone;
    private final Consumer<String> info, warning;
    private final Random random = new Random();
    private final Set<Item> warnedNoRecipe = new HashSet<>();

    private State state;
    private Options options;
    private int stateTicks;
    private long nextActionAt;
    private long cooldownUntil;
    private boolean warnedNoSpace;

    private BlockHitResult placeHit;
    private BlockPos tablePos;
    private boolean tablePlaced;
    private ItemEntity tableDrop;
    private int pickupRetries;
    private Vec3d throwTarget;
    private int throwsLeft, pickupThrows;
    private boolean swapped;
    /** Crafting tables in the inventory before placing one, to notice when it is back. */
    private int tableCount;

    private Compressible current;
    private RecipeDisplayEntry recipe;
    private int resultCountBefore, rounds;
    private final StringBuilder crafted = new StringBuilder();
    /** Set when something went wrong: the table is still mined and picked up, then this is reported. */
    private String failure;

    public Compressor(MinecraftClient mc, BaritoneControl baritone, Consumer<String> info, Consumer<String> warning) {
        this.mc = mc;
        this.baritone = baritone;
        this.info = info;
        this.warning = warning;
    }

    public boolean isActive() {
        return state != null;
    }

    /** With the window hidden, the crafting screen is not shown; the screen handler is open anyway. */
    public boolean hidesScreen(Screen screen) {
        return isActive() && !options.showGui() && screen instanceof CraftingScreen;
    }

    /**
     * Everything needed is there: a crafting table, enough items with a known recipe, crafting frees at least one
     * slot, there is a spot for the table and room in the inventory (or junk to throw out).
     */
    public boolean canStart(Options options) {
        if (System.currentTimeMillis() < cooldownUntil) return false;
        if (!mc.player.isOnGround() || mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isSneaking()) return false;
        if (findTable() < 0 || nextCompressible(options) == null || slotsSaved(options) < 1) return false;

        // Junk lying next to you is picked up the moment a slot gets free: then there is no room for the table
        if (!options.dropJunk() && junkInReach(options)) return false;
        if (options.dropJunk() && freeSlots() < ROOM && junkSlot(options) < 0 && freeSlots() < 1) return false;

        if (findPlaceSpot() == null) {
            if (!warnedNoSpace) warning.accept("Auto compress: no free spot for the crafting table here, trying again later.");
            warnedNoSpace = true;
            cooldownUntil = System.currentTimeMillis() + 5_000;
            return false;
        }
        warnedNoSpace = false;
        return true;
    }

    public void start(Options options) {
        this.options = options;
        crafted.setLength(0);
        failure = null;
        tablePlaced = false;
        tableDrop = null;
        pickupRetries = 0;
        pickupThrows = 0;
        swapped = false;
        throwsLeft = MAX_THROWS;
        rounds = 0;
        tableCount = mc.player.getInventory().count(Items.CRAFTING_TABLE);

        // Make room first, otherwise junk lying around takes the slots that crafting frees
        if (options.dropJunk() && freeSlots() < ROOM && (throwTarget = findThrowTarget()) != null) set(State.AIM_THROW);
        else afterRoom();
    }

    private void afterRoom() {
        set(SlotUtils.isHotbar(findTable()) ? State.SELECT : State.TO_HOTBAR);
    }

    /** Stops right away (module disabled): closes the crafting window if it is open. */
    public void abort() {
        if (state != null && mc.player != null && mc.player.currentScreenHandler instanceof CraftingScreenHandler) mc.player.closeHandledScreen();
        state = null;
    }

    public void reset() {
        state = null;
        cooldownUntil = 0;
        warnedNoRecipe.clear();
        warnedNoSpace = false;
    }

    /** Runs one tick and returns the status text. */
    public String tick() {
        stateTicks++;
        if (stateTicks > timeout(state)) {
            fail("timed out (" + state.name().toLowerCase().replace('_', ' ') + ")");
            return status();
        }

        // Turning the head happens every tick, everything else after a short random pause
        switch (state) {
            case AIM_THROW -> {
                if (aim(throwTarget)) set(State.THROW);
                return status();
            }
            case AIM_PLACE -> {
                if (aim(placeHit.getPos())) set(State.PLACE);
                return status();
            }
            case AIM_OPEN, AIM_BREAK -> {
                BlockHitResult hit = tableHit();
                if (hit == null) {
                    if (state == State.AIM_BREAK) set(State.WAIT_DROP);
                    else fail("the crafting table is not in sight");
                }
                else if (aim(hit.getPos())) set(state == State.AIM_OPEN ? State.OPEN : State.BREAK);
                return status();
            }
            case BREAK -> {
                breakTable();
                return status();
            }
            default -> {
            }
        }

        if (System.currentTimeMillis() < nextActionAt) return status();

        switch (state) {
            case THROW -> {
                if (freeSlots() >= ROOM) {
                    set(State.CLOSE_INVENTORY);
                    break;
                }
                int slot = junkSlot(options);
                if (slot < 0 || throwsLeft-- <= 0) {
                    if (freeSlots() >= 1) set(State.CLOSE_INVENTORY);
                    else fail("no room in the inventory and no junk to throw out");
                    break;
                }
                // Like Ctrl+Q over the stack in the inventory: it flies off where you look
                if (options.showGui() && !(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player));
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, SlotUtils.indexToId(slot), 1, SlotActionType.THROW, mc.player);
                set(State.SETTLE);
            }
            case SETTLE -> {
                // Junk lying next to you is picked up into the slot that just got free; wait for that, then look again
                if (stateTicks >= 15) set(State.THROW);
            }
            case TO_HOTBAR -> {
                // Like pressing a number key over the table in the inventory
                int from = findTable();
                if (from < 0 || swapped) {
                    fail(from < 0 ? "no crafting table in the inventory" : "could not move the crafting table into the hotbar");
                    break;
                }
                swapped = true;
                if (options.showGui()) mc.setScreen(new InventoryScreen(mc.player));
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, SlotUtils.indexToId(from), freeHotbarSlot(), SlotActionType.SWAP, mc.player);
                set(State.CLOSE_INVENTORY);
            }
            case CLOSE_INVENTORY -> {
                mc.player.closeHandledScreen();
                afterRoom();
            }
            case SELECT -> {
                int slot = findTable();
                if (!SlotUtils.isHotbar(slot)) {
                    fail("the crafting table is not in the hotbar");
                    break;
                }
                InvUtils.swap(slot, false);
                placeHit = findPlaceSpot();
                if (placeHit == null) {
                    fail("no free spot for the crafting table");
                    break;
                }
                tablePos = placeHit.getBlockPos().offset(placeHit.getSide());
                set(State.AIM_PLACE);
            }
            case PLACE -> {
                if (!mc.player.getMainHandStack().isOf(Items.CRAFTING_TABLE)) {
                    fail("the crafting table is not in the hand");
                    break;
                }
                if (mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit).isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
                set(State.WAIT_PLACED);
            }
            case WAIT_PLACED -> {
                if (mc.world.getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE)) {
                    tablePlaced = true;
                    set(State.AIM_OPEN);
                }
            }
            case OPEN -> {
                BlockHitResult hit = tableHit();
                if (hit == null) {
                    fail("the crafting table is not in sight");
                    break;
                }
                if (mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit).isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
                set(State.WAIT_OPEN);
            }
            case WAIT_OPEN -> {
                if (mc.player.currentScreenHandler instanceof CraftingScreenHandler) set(State.CRAFT);
            }
            case CRAFT -> craft();
            case WAIT_GRID -> {
                CraftingScreenHandler handler = handler();
                if (handler == null) fail("the crafting window was closed");
                else if (handler.getSlot(CraftingScreenHandler.RESULT_ID).getStack().isOf(current.result)) set(State.TAKE);
            }
            case TAKE -> {
                CraftingScreenHandler handler = handler();
                if (handler == null) {
                    fail("the crafting window was closed");
                    break;
                }
                resultCountBefore = mc.player.getInventory().count(current.result);
                // Shift-click on the result crafts as often as the grid allows
                mc.interactionManager.clickSlot(handler.syncId, CraftingScreenHandler.RESULT_ID, 0, SlotActionType.QUICK_MOVE, mc.player);
                set(State.WAIT_TAKEN);
            }
            case WAIT_TAKEN -> {
                CraftingScreenHandler handler = handler();
                if (handler == null) {
                    fail("the crafting window was closed");
                    break;
                }
                if (handler.getSlot(CraftingScreenHandler.RESULT_ID).getStack().isEmpty() && gridEmpty(handler)) {
                    int made = mc.player.getInventory().count(current.result) - resultCountBefore;
                    if (made > 0) {
                        if (!crafted.isEmpty()) crafted.append(", ");
                        crafted.append(made).append("x ").append(current.result.getName().getString());
                    }
                    rounds++;
                    set(State.CRAFT);
                }
            }
            case CLOSE -> {
                if (mc.player.currentScreenHandler instanceof CraftingScreenHandler) mc.player.closeHandledScreen();
                set(State.AIM_BREAK);
            }
            case WAIT_DROP -> {
                if (tableBack()) finish();
                else if ((tableDrop = findTableDrop()) != null) set(State.PICKUP);
            }
            case PICKUP -> pickUp();
            default -> {
            }
        }

        return status();
    }

    // Steps

    private void craft() {
        CraftingScreenHandler handler = handler();
        if (handler == null) {
            fail("the crafting window was closed");
            return;
        }

        current = rounds < MAX_ROUNDS ? nextCompressible(options) : null;
        if (current == null) {
            set(State.CLOSE);
            return;
        }

        // Like shift-clicking the recipe in the recipe book: the server fills the grid with as many as possible
        recipe = findRecipe(current);
        mc.interactionManager.clickRecipe(handler.syncId, recipe.id(), true);
        set(State.WAIT_GRID);
    }

    private void breakTable() {
        if (!mc.world.getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE)) {
            set(State.WAIT_DROP);
            return;
        }

        BlockHitResult hit = tableHit();
        if (hit == null) {
            fail("the crafting table is not in sight");
            return;
        }
        aim(hit.getPos());
        if (mc.interactionManager.updateBlockBreakingProgress(tablePos, hit.getSide())) mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void pickUp() {
        if (tableDrop.isRemoved() || tableBack()) {
            finish();
            return;
        }

        // Junk took the room meanwhile: throw out one stack, the table can be picked up first (shorter pickup delay)
        if (!hasRoomFor(Items.CRAFTING_TABLE) && options.dropJunk() && pickupThrows < 3) {
            int slot = junkSlot(options);
            if (slot >= 0) {
                pickupThrows++;
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, SlotUtils.indexToId(slot), 1, SlotActionType.THROW, mc.player);
                mc.player.closeHandledScreen();
                nextActionAt = System.currentTimeMillis() + 500;
                return;
            }
        }

        if (!baritone.isWorking()) {
            if (mc.player.getBoundingBox().expand(0.8, 0.4, 0.8).intersects(tableDrop.getBoundingBox())) return;
            if (pickupRetries++ < 3) baritone.walkTo(tableDrop.getBlockPos());
            else fail("could not pick up the crafting table");
        }
    }

    private void finish() {
        baritone.stop();
        state = null;
        if (failure != null) {
            warning.accept("Auto compress: " + failure + ".");
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
        }
        else if (!crafted.isEmpty()) info.accept("Crafted " + crafted + ".");
    }

    /**
     * Something went wrong. The crafting window is closed and a placed table is still mined and picked up, unless
     * that is what failed.
     */
    private void fail(String reason) {
        if (failure == null) failure = reason;
        if (mc.player.currentScreenHandler instanceof CraftingScreenHandler || mc.currentScreen instanceof InventoryScreen) mc.player.closeHandledScreen();

        boolean cleaningUp = state == State.AIM_BREAK || state == State.BREAK || state == State.WAIT_DROP || state == State.PICKUP;
        if (tablePlaced && !cleaningUp && mc.world.getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE)) set(State.AIM_BREAK);
        else finish();
    }

    private void set(State next) {
        state = next;
        stateTicks = 0;
        int min = Math.min(options.delayMin(), options.delayMax()), max = Math.max(options.delayMin(), options.delayMax());
        nextActionAt = System.currentTimeMillis() + min + (max > min ? random.nextInt(max - min + 1) : 0);
    }

    private static int timeout(State state) {
        return switch (state) {
            case BREAK, PICKUP -> 300;
            default -> 100;
        };
    }

    private String status() {
        if (state == null) return "Compressing: done";
        return switch (state) {
            case AIM_THROW, THROW, SETTLE -> "Compressing: making room";
            case CLOSE_INVENTORY, TO_HOTBAR, SELECT, AIM_PLACE, PLACE, WAIT_PLACED -> "Compressing: placing crafting table";
            case AIM_OPEN, OPEN, WAIT_OPEN -> "Compressing: opening crafting table";
            case CRAFT, WAIT_GRID, TAKE, WAIT_TAKEN, CLOSE -> "Compressing: crafting" + (current != null ? " " + current.result.getName().getString() : "");
            case AIM_BREAK, BREAK, WAIT_DROP, PICKUP -> "Compressing: picking up crafting table";
        };
    }

    // Helpers

    /** Turns the head a bit towards the point, like moving the mouse. True once it looks there. */
    private boolean aim(Vec3d target) {
        float yaw = (float) Rotations.getYaw(target), pitch = (float) Rotations.getPitch(target);
        float yawDiff = MathHelper.wrapDegrees(yaw - mc.player.getYaw()), pitchDiff = pitch - mc.player.getPitch();
        float step = 12 + random.nextFloat() * 14;

        mc.player.setYaw(mc.player.getYaw() + MathHelper.clamp(yawDiff, -step, step));
        mc.player.setPitch(MathHelper.clamp(mc.player.getPitch() + MathHelper.clamp(pitchDiff, -step, step), -90, 90));
        return Math.abs(yawDiff) <= step && Math.abs(pitchDiff) <= step;
    }

    /** Next item in the list with enough of it in the inventory and a recipe in the recipe book. */
    private Compressible nextCompressible(Options options) {
        for (Item item : options.items()) {
            Compressible compressible = Compressible.of(item);
            if (compressible == null || warnedNoRecipe.contains(item) || mc.player.getInventory().count(item) < compressible.count) continue;

            if (findRecipe(compressible) != null) return compressible;
            if (warnedNoRecipe.add(item)) {
                warning.accept("Auto compress: the recipe for " + compressible.result.getName().getString() + " is not in your recipe book yet.");
            }
        }
        return null;
    }

    private RecipeDisplayEntry findRecipe(Compressible compressible) {
        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);
        ItemStack ingredient = new ItemStack(compressible.item);

        for (RecipeResultCollection collection : mc.player.getRecipeBook().getOrderedResults()) {
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                if (!entry.display().result().getFirst(context).isOf(compressible.result)) continue;

                Optional<List<Ingredient>> requirements = entry.craftingRequirements();
                if (requirements.isEmpty() || requirements.get().size() != compressible.count) continue;
                if (requirements.get().stream().allMatch(i -> i.test(ingredient))) return entry;
            }
        }
        return null;
    }

    /** How many inventory slots crafting the selected items into blocks would free. */
    private int slotsSaved(Options options) {
        int saved = 0;
        for (Item item : options.items()) {
            Compressible c = Compressible.of(item);
            if (c == null || warnedNoRecipe.contains(item)) continue;

            int count = 0, slots = 0, resultRoom = 0;
            for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack.isOf(c.item)) {
                    count += stack.getCount();
                    slots++;
                }
                else if (stack.isOf(c.result)) resultRoom += stack.getMaxCount() - stack.getCount();
            }

            int results = count / c.count;
            if (results == 0) continue;
            int rest = count - results * c.count;
            int restSlots = (rest + c.item.getMaxCount() - 1) / c.item.getMaxCount();
            int resultSlots = (Math.max(0, results - resultRoom) + c.result.getMaxCount() - 1) / c.result.getMaxCount();
            saved += slots - restSlots - resultSlots;
        }
        return saved;
    }

    private int freeSlots() {
        int free = 0;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    /** The item can be picked up: a free slot, or a stack of it with room. */
    private boolean hasRoomFor(Item item) {
        if (freeSlots() > 0) return true;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(item) && stack.getCount() < stack.getMaxCount()) return true;
        }
        return false;
    }

    /** The biggest junk stack to throw out, keeping one stack of building blocks for Baritone. -1 if there is none. */
    private int junkSlot(Options options) {
        int keep = -1;
        for (Item building : BUILDING_BLOCKS) {
            int best = -1;
            for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack.isOf(building) && (best < 0 || stack.getCount() > mc.player.getInventory().getStack(best).getCount())) best = i;
            }
            if (best >= 0) {
                keep = best;
                break;
            }
        }

        int slot = -1;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (i == keep || stack.isEmpty() || !options.junk().contains(stack.getItem())) continue;
            if (slot < 0 || stack.getCount() > mc.player.getInventory().getStack(slot).getCount()) slot = i;
        }
        return slot;
    }

    /** Junk lying close enough to be picked up as soon as a slot is free. */
    private boolean junkInReach(Options options) {
        Box reach = mc.player.getBoundingBox().expand(2, 1.5, 2);
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemEntity item && options.junk().contains(item.getStack().getItem()) && reach.intersects(item.getBoundingBox())) return true;
        }
        return false;
    }

    /** A point to throw junk at: the direction with the most free room in front, so it isn't picked up again. */
    private Vec3d findThrowTarget() {
        Vec3d eyes = mc.player.getEyePos();
        Vec3d best = null;
        double bestDistance = 0;
        for (int i = 0; i < 16; i++) {
            double yaw = Math.toRadians(i * 22.5);
            Vec3d direction = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
            BlockHitResult hit = raycast(eyes, eyes.add(direction.multiply(6)));
            double distance = hit.getType() == HitResult.Type.MISS ? 6 : hit.getPos().distanceTo(eyes);
            if (distance > bestDistance) {
                bestDistance = distance;
                best = eyes.add(direction.multiply(3)).add(0, 0.3, 0);
            }
        }
        return best;
    }

    /** Inventory index of a crafting table (hotbar first), or -1. */
    private int findTable() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.CRAFTING_TABLE)) return i;
        }
        return -1;
    }

    /** A hotbar slot for the table: an empty one, otherwise one without a tool. */
    private int freeHotbarSlot() {
        int noTool = -1;
        for (int i = 8; i >= 0; i--) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) return i;
            if (noTool < 0 && !stack.isDamageable()) noTool = i;
        }
        if (noTool >= 0) return noTool;
        for (int i = 8; i >= 0; i--) {
            if (!mc.player.getInventory().getStack(i).isIn(ItemTags.PICKAXES)) return i;
        }
        return 8;
    }

    /** Top of a solid block next to you, free above, visible and in reach. Closest first. */
    private BlockHitResult findPlaceSpot() {
        Vec3d eyes = mc.player.getEyePos();
        double reach = mc.player.getBlockInteractionRange() - 0.5;
        Box player = mc.player.getBoundingBox();
        BlockPos feet = mc.player.getBlockPos();

        BlockHitResult best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(feet.add(-2, -1, -2), feet.add(2, 1, 2))) {
            BlockState state = mc.world.getBlockState(pos);
            if (!state.isReplaceable() || !mc.world.getFluidState(pos).isEmpty() || player.intersects(new Box(pos))) continue;
            if (touchesFluid(pos)) continue;

            BlockPos support = pos.down();
            if (!mc.world.getBlockState(support).isSideSolidFullSquare(mc.world, support, Direction.UP)) continue;

            // Aim just inside the block: a ray that ends exactly on the face doesn't hit it
            Vec3d top = Vec3d.ofCenter(support).add(0, 0.49, 0);
            if (eyes.distanceTo(top) > reach) continue;

            BlockHitResult hit = raycast(eyes, top);
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(support) || hit.getSide() != Direction.UP) continue;

            int dx = pos.getX() - feet.getX(), dy = pos.getY() - feet.getY(), dz = pos.getZ() - feet.getZ();
            double score = dx * dx + dz * dz + dy * dy * 2;
            if (score < bestScore) {
                bestScore = score;
                best = hit;
            }
        }
        return best;
    }

    /** Where a ray from the eyes hits the placed table: its center or the middle of a face. */
    private BlockHitResult tableHit() {
        if (tablePos == null || !mc.world.getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE)) return null;

        Vec3d eyes = mc.player.getEyePos();
        double reach = mc.player.getBlockInteractionRange() - 0.25;
        Vec3d center = Vec3d.ofCenter(tablePos);
        if (eyes.distanceTo(center) <= reach) {
            BlockHitResult hit = raycast(eyes, center);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(tablePos)) return hit;
        }
        for (Direction direction : Direction.values()) {
            Vec3d point = center.add(direction.getOffsetX() * 0.45, direction.getOffsetY() * 0.45, direction.getOffsetZ() * 0.45);
            if (eyes.distanceTo(point) > reach) continue;
            BlockHitResult hit = raycast(eyes, point);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(tablePos)) return hit;
        }
        return null;
    }

    private BlockHitResult raycast(Vec3d from, Vec3d to) {
        return mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
    }

    private boolean touchesFluid(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!mc.world.getFluidState(pos.offset(direction)).isEmpty()) return true;
        }
        return false;
    }

    private ItemEntity findTableDrop() {
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemEntity item && !item.isRemoved() && item.getStack().isOf(Items.CRAFTING_TABLE)
                && item.squaredDistanceTo(Vec3d.ofCenter(tablePos)) < 4 * 4) return item;
        }
        return null;
    }

    private boolean tableBack() {
        return mc.player.getInventory().count(Items.CRAFTING_TABLE) >= tableCount;
    }

    private CraftingScreenHandler handler() {
        return mc.player.currentScreenHandler instanceof CraftingScreenHandler handler ? handler : null;
    }

    private static boolean gridEmpty(CraftingScreenHandler handler) {
        for (Slot slot : handler.getInputSlots()) {
            if (slot.hasStack()) return false;
        }
        return true;
    }
}
