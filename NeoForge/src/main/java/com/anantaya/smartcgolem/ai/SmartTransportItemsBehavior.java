package com.anantaya.smartcgolem.ai;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.block.ChestBlock;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class SmartTransportItemsBehavior extends Behavior<PathfinderMob> {

    private enum TaskState {
        IDLE,
        WALK_TO_SOURCE,
        WAIT_FOR_SOURCE,
        INTERACT_SOURCE,
        WALK_TO_DESTINATION,
        WAIT_FOR_DESTINATION,
        INTERACT_DESTINATION,
        RETURN_TO_SOURCE
    }

    private static final Set<BlockPos> LOCKED_CHESTS = new HashSet<>();

    private final float speedModifier;
    private final Predicate<BlockState> sourceBlockType;
    private final Predicate<BlockState> destinationBlockType;
    private final int horizontalSearchDistance;
    private final int verticalSearchDistance;
    private final Consumer<PathfinderMob> onTravelling;

    private TaskState taskState = TaskState.IDLE;

    private BlockPos currentTarget = null;
    private BlockPos currentWalkTarget = null;
    private BlockPos lastPickupChest = null;
    private BlockPos returnToSourceChest = null;
    private BlockPos lockedChest = null;

    private Container openedContainer = null;
    private CopperGolem openedCopperGolem = null;

    private boolean actionDone = false;

    private int ticksAtTarget = 0;
    private long nextSearchTick = 0;

    private static final int ARRIVAL_DISTANCE_SQUARED = 4;
    private static final int SEARCH_COOLDOWN_TICKS = 20;

    public SmartTransportItemsBehavior(
            float speedModifier,
            Predicate<BlockState> sourceBlockType,
            Predicate<BlockState> destinationBlockType,
            int horizontalSearchDistance,
            int verticalSearchDistance,
            Map<?, ?> interactions,
            Consumer<PathfinderMob> onTravelling,
            Predicate<?> shouldQueue
    ) {
        super(ImmutableMap.of());

        this.speedModifier = speedModifier;
        this.sourceBlockType = sourceBlockType;
        this.destinationBlockType = destinationBlockType;
        this.horizontalSearchDistance = horizontalSearchDistance;
        this.verticalSearchDistance = verticalSearchDistance;
        this.onTravelling = onTravelling;
    }

    @Override
    protected void start(ServerLevel level, PathfinderMob mob, long gameTime) {

        boolean carrying = !mob.getMainHandItem().isEmpty();

        currentTarget = null;
        currentWalkTarget = null;
        openedContainer = null;
        openedCopperGolem = null;
        actionDone = false;
        ticksAtTarget = 0;
        taskState = TaskState.IDLE;
        unlockChest();

        // IMPORTANT:
        // Do NOT clear source memory if golem is already carrying an item.
        // Behavior can restart between pickup and deposit.
        if (!carrying) {
            lastPickupChest = null;
            returnToSourceChest = null;
        }

        System.out.println("[SMART-GOLEM START] carrying=" + carrying
                + " returnToSourceChest=" + returnToSourceChest
                + " lastPickupChest=" + lastPickupChest);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, PathfinderMob mob, long gameTime) {
        return true;
    }

    private boolean needsChestLock(TaskState state) {
        return state == TaskState.INTERACT_SOURCE
                || state == TaskState.INTERACT_DESTINATION;
    }

    private boolean isChestLocked(BlockPos pos) {
        synchronized (LOCKED_CHESTS) {
            return LOCKED_CHESTS.contains(pos);
        }
    }

    private boolean tryLockChest(BlockPos pos) {
        if (pos == null) {
            return false;
        }

        if (pos.equals(lockedChest)) {
            return true;
        }

        synchronized (LOCKED_CHESTS) {
            if (LOCKED_CHESTS.contains(pos)) {
                return false;
            }

            LOCKED_CHESTS.add(pos);
            lockedChest = pos;

            System.out.println("[SMART-GOLEM CHEST-LOCK] locked=" + pos);
            return true;
        }
    }

    private void unlockChest() {
        if (lockedChest == null) {
            return;
        }

        synchronized (LOCKED_CHESTS) {
            LOCKED_CHESTS.remove(lockedChest);
            System.out.println("[SMART-GOLEM CHEST-UNLOCK] unlocked=" + lockedChest);
        }

        lockedChest = null;
    }

    private void switchState(PathfinderMob mob, TaskState newState, BlockPos target) {

        if (needsChestLock(newState)) {
            if (!tryLockChest(target)) {
                System.out.println("[SMART-GOLEM CHEST-LOCK-FAILED] target busy=" + target);

                if (newState == TaskState.INTERACT_SOURCE) {
                    this.taskState = TaskState.WAIT_FOR_SOURCE;
                } else if (newState == TaskState.INTERACT_DESTINATION) {
                    this.taskState = TaskState.WAIT_FOR_DESTINATION;
                } else {
                    this.taskState = TaskState.IDLE;
                }

                this.currentTarget = target;
                this.currentWalkTarget = target == null ? null : findBestWalkTargetForChest(mob, target);

                this.ticksAtTarget = 0;
                this.actionDone = false;
                return;
            }
        } else {
            unlockChest();
        }

        this.taskState = newState;
        this.currentTarget = target;
        this.currentWalkTarget = target == null ? null : findBestWalkTargetForChest(mob, target);

        this.ticksAtTarget = 0;
        this.actionDone = false;

        System.out.println("[SMART-GOLEM STATE] -> " + newState
                + " chestTarget=" + target
                + " walkTarget=" + currentWalkTarget);
    }

    @Override
    protected void tick(ServerLevel level, PathfinderMob mob, long gameTime) {

        switch (taskState) {

            case IDLE ->
                searchForTarget(level, mob, gameTime);

            case WALK_TO_SOURCE -> {
                walkToTarget(mob);

                if (hasArrived(mob)) {
                    switchState(mob, TaskState.WAIT_FOR_SOURCE, currentTarget);
                }
            }

            case WAIT_FOR_SOURCE -> {
                walkToTarget(mob);

                if (currentTarget == null) {
                    switchState(mob, TaskState.IDLE, null);
                    return;
                }

                BlockEntity blockEntity = level.getBlockEntity(currentTarget);

                if (!(blockEntity instanceof ChestBlockEntity chest)) {
                    switchState(mob, TaskState.IDLE, null);
                    return;
                }

                if (!isChestLocked(currentTarget) && !isChestBusy(chest)) {
                    switchState(mob, TaskState.INTERACT_SOURCE, currentTarget);
                }
            }

            case INTERACT_SOURCE ->
                interactWithSource(level, mob);

            case WALK_TO_DESTINATION -> {
                walkToTarget(mob);

                if (hasArrived(mob)) {
                    switchState(mob, TaskState.WAIT_FOR_DESTINATION, currentTarget);
                }
            }

            case WAIT_FOR_DESTINATION -> {
                walkToTarget(mob);

                if (currentTarget == null) {
                    switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest);
                    return;
                }

                BlockEntity blockEntity = level.getBlockEntity(currentTarget);

                if (!(blockEntity instanceof ChestBlockEntity chest)) {
                    switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest);
                    return;
                }

                if (!isChestLocked(currentTarget) && !isChestBusy(chest)) {
                    switchState(mob, TaskState.INTERACT_DESTINATION, currentTarget);
                }
            }

            case INTERACT_DESTINATION ->
                interactWithDestination(level, mob);

            case RETURN_TO_SOURCE -> {
                walkToTarget(mob);

                if (hasArrived(mob)) {

                    if (mob instanceof CopperGolem copperGolem) {
                        copperGolem.clearOpenedChestPos();
                        copperGolem.setState(CopperGolemState.IDLE);
                    }

                    System.out.println("[SMART-GOLEM RETURNED-TO-SOURCE] source=" + currentTarget);

                    returnToSourceChest = null;
                    lastPickupChest = null;

                    taskState = TaskState.IDLE;
                    currentTarget = null;
                    currentWalkTarget = null;
                    ticksAtTarget = 0;
                    actionDone = false;
                }
            }
        }
    }

    private void walkToTarget(PathfinderMob mob) {

        if (currentWalkTarget == null) {
            switchState(mob, TaskState.IDLE, null);
            return;
        }

        BehaviorUtils.setWalkAndLookTargetMemories(
                mob,
                currentWalkTarget,
                speedModifier,
                0
        );
    }

    private boolean hasArrived(PathfinderMob mob) {
        return currentWalkTarget != null
                && mob.blockPosition().distSqr(currentWalkTarget)
                <= ARRIVAL_DISTANCE_SQUARED;
    }

    private double getPathCost(PathfinderMob mob, BlockPos chestPos) {

        double bestCost = Double.MAX_VALUE;

        for (BlockPos nearby : BlockPos.betweenClosed(
                chestPos.offset(-1, 0, -1),
                chestPos.offset(1, 1, 1))) {

            Path path = mob.getNavigation().createPath(nearby, 1);

            if (path == null || !path.canReach()) {
                continue;
            }

            double cost = path.getNodeCount();

            if (cost < bestCost) {
                bestCost = cost;
            }
        }

        if (bestCost == Double.MAX_VALUE) {
            System.out.println("[SMART-GOLEM PATH-SKIP] Cannot path near " + chestPos);
        }

        return bestCost;
    }

    private BlockPos findBestWalkTargetForChest(PathfinderMob mob, BlockPos chestPos) {

        BlockPos best = chestPos;
        double bestCost = Double.MAX_VALUE;

        for (BlockPos nearby : BlockPos.betweenClosed(
                chestPos.offset(-1, 0, -1),
                chestPos.offset(1, 1, 1))) {

            Path path = mob.getNavigation().createPath(nearby, 1);

            if (path == null || !path.canReach()) {
                continue;
            }

            double cost = path.getNodeCount();

            if (returnToSourceChest != null) {
                cost += Math.abs(nearby.getY() - returnToSourceChest.getY()) * 200;
            }

            if (cost < bestCost) {
                bestCost = cost;
                best = nearby.immutable();
            }
        }

        return best;
    }

    private void interactWithSource(ServerLevel level, PathfinderMob mob) {

        if (currentTarget == null) {
            closeOpenedContainer();
            switchState(mob, TaskState.IDLE, null);
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(currentTarget);

        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            System.out.println("[SMART-GOLEM ERROR] Source target is not ChestBlockEntity at " + currentTarget);
            closeOpenedContainer();
            switchState(mob, TaskState.IDLE, null);
            return;
        }

        ticksAtTarget++;

        mob.getLookControl().setLookAt(
                currentTarget.getX() + 0.5,
                currentTarget.getY() + 0.5,
                currentTarget.getZ() + 0.5
        );

        if (ticksAtTarget == 1) {

            if (mob instanceof CopperGolem copperGolem) {

                chest.startOpen(copperGolem);
                copperGolem.setOpenedChestPos(currentTarget);

                if (copperGolem.getMainHandItem().isEmpty()) {
                    copperGolem.setState(CopperGolemState.GETTING_ITEM);
                }

                openedContainer = chest;
                openedCopperGolem = copperGolem;

                System.out.println("[SMART-GOLEM VANILLA-OPEN] source=" + currentTarget);
            }

            return;
        }

        if (ticksAtTarget < 9) {
            return;
        }

        if (!actionDone) {

            mob.swing(InteractionHand.MAIN_HAND);

            boolean movedItem = false;

            if (mob.getMainHandItem().isEmpty()) {
                movedItem = pickupFromChest(level, mob, chest);
            }

            if (mob instanceof CopperGolem copperGolem) {

                if (movedItem) {
                    copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_GET);
                    copperGolem.setState(CopperGolemState.GETTING_ITEM);
                } else {
                    copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_NO_GET);
                    copperGolem.setState(CopperGolemState.GETTING_NO_ITEM);
                }
            }

            actionDone = true;
        }

        if (ticksAtTarget >= 60) {

            closeOpenedContainer();

            if (!mob.getMainHandItem().isEmpty()) {

                unlockChest();

                BlockPos bestDestination = findDestinationChest(level, mob);

                if (bestDestination != null) {
                    switchState(mob, TaskState.WALK_TO_DESTINATION, bestDestination);
                } else {
                    System.out.println("[SMART-GOLEM NO-DESTINATION] No valid destination found after pickup.");
                    switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest);
                }

                return;
            }

            switchState(mob, TaskState.IDLE, null);
        }
    }

    private void interactWithDestination(ServerLevel level, PathfinderMob mob) {

        if (currentTarget == null) {
            closeOpenedContainer();
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest);
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(currentTarget);

        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            System.out.println("[SMART-GOLEM ERROR] Destination target is not ChestBlockEntity at " + currentTarget);
            closeOpenedContainer();
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest);
            return;
        }

        ticksAtTarget++;

        mob.getLookControl().setLookAt(
                currentTarget.getX() + 0.5,
                currentTarget.getY() + 0.5,
                currentTarget.getZ() + 0.5
        );

        if (ticksAtTarget == 1) {

            if (mob instanceof CopperGolem copperGolem) {

                chest.startOpen(copperGolem);
                copperGolem.setOpenedChestPos(currentTarget);

                if (!copperGolem.getMainHandItem().isEmpty()) {
                    copperGolem.setState(CopperGolemState.DROPPING_ITEM);
                }

                openedContainer = chest;
                openedCopperGolem = copperGolem;

                System.out.println("[SMART-GOLEM VANILLA-OPEN] destination=" + currentTarget);
            }

            return;
        }

        if (ticksAtTarget < 9) {
            return;
        }

        if (!actionDone) {

            mob.swing(InteractionHand.MAIN_HAND);

            boolean movedItem = false;

            if (!mob.getMainHandItem().isEmpty()) {

                ItemStack heldBefore = mob.getMainHandItem().copy();

                System.out.println("[SMART-GOLEM DEPOSIT START] chest=" + currentTarget
                        + " held=" + heldBefore);

                Container targetContainer = chest;

                if (level.getBlockState(currentTarget).getBlock() instanceof ChestBlock chestBlock) {
                    Container combined
                            = ChestBlock.getContainer(
                                    chestBlock,
                                    level.getBlockState(currentTarget),
                                    level,
                                    currentTarget,
                                    true
                            );

                    if (combined != null) {
                        targetContainer = combined;
                    }
                }

                ItemStack remaining
                        = insertIntoChest(level, currentTarget, targetContainer, heldBefore);

                mob.setItemInHand(InteractionHand.MAIN_HAND, remaining);

                chest.setChanged();
                level.blockEntityChanged(currentTarget);

                movedItem = remaining.getCount() < heldBefore.getCount();

                System.out.println("[SMART-GOLEM DEPOSIT END] chest=" + currentTarget
                        + " remainingHand=" + mob.getMainHandItem());
            }

            if (mob instanceof CopperGolem copperGolem) {

                if (movedItem) {
                    copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_DROP);
                    copperGolem.setState(CopperGolemState.DROPPING_ITEM);
                } else {
                    copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_NO_DROP);
                    copperGolem.setState(CopperGolemState.DROPPING_NO_ITEM);
                }
            }

            actionDone = true;

            closeOpenedContainer();
            unlockChest();

            BlockPos sourceToReturn
                    = returnToSourceChest != null
                            ? returnToSourceChest
                            : lastPickupChest;

            if (sourceToReturn != null) {

                returnToSourceChest = sourceToReturn;
                switchState(mob, TaskState.RETURN_TO_SOURCE, sourceToReturn);

                System.out.println("[SMART-GOLEM RETURN-TO-SOURCE] returning to=" + sourceToReturn);

            } else {

                System.out.println("[SMART-GOLEM RETURN-FAILED] No saved source chest.");

                taskState = TaskState.IDLE;
                currentTarget = null;
            }

            return;
        }
    }

    private void searchForTarget(ServerLevel level, PathfinderMob mob, long gameTime) {

        if (gameTime < nextSearchTick) {
            return;
        }

        nextSearchTick = gameTime + SEARCH_COOLDOWN_TICKS;

        boolean carrying = !mob.getMainHandItem().isEmpty();

        BlockPos best = carrying
                ? findDestinationChest(level, mob)
                : findSourceChest(level, mob);

        if (best == null) {
            return;
        }

        openedContainer = null;
        openedCopperGolem = null;

        if (carrying) {
            System.out.println("[SMART-GOLEM MATCHED-DEPOSIT] Selected destination chest=" + best);
            switchState(mob, TaskState.WALK_TO_DESTINATION, best);
        } else {
            System.out.println("[SMART-GOLEM SOURCE] Selected source chest=" + best);
            switchState(mob, TaskState.WALK_TO_SOURCE, best);
        }
    }

    private BlockPos findSourceChest(ServerLevel level, PathfinderMob mob) {

        BlockPos mobPos = mob.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int x = -horizontalSearchDistance; x <= horizontalSearchDistance; x++) {
            for (int y = -verticalSearchDistance; y <= verticalSearchDistance; y++) {
                for (int z = -horizontalSearchDistance; z <= horizontalSearchDistance; z++) {

                    BlockPos pos = mobPos.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    if (!sourceBlockType.test(state)) {
                        continue;
                    }

                    BlockEntity blockEntity = level.getBlockEntity(pos);

                    if (!(blockEntity instanceof ChestBlockEntity chest)) {
                        continue;
                    }

                    if (!hasAnyItem(chest)) {
                        continue;
                    }

                    double pathCost = getPathCost(mob, pos);

                    if (pathCost == Double.MAX_VALUE) {
                        continue;
                    }

                    if (pathCost < bestDist) {
                        bestDist = pathCost;
                        best = pos;
                    }
                }
            }
        }

        return best;
    }

    private BlockPos findDestinationChest(ServerLevel level, PathfinderMob mob) {

        BlockPos mobPos = mob.blockPosition();
        ItemStack held = mob.getMainHandItem();

        if (held.isEmpty()) {
            return null;
        }

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int x = -horizontalSearchDistance; x <= horizontalSearchDistance; x++) {
            for (int y = -verticalSearchDistance; y <= verticalSearchDistance; y++) {
                for (int z = -horizontalSearchDistance; z <= horizontalSearchDistance; z++) {

                    BlockPos pos = mobPos.offset(x, y, z);

                    if (lastPickupChest != null && pos.equals(lastPickupChest)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(pos);

                    if (!destinationBlockType.test(state)) {
                        continue;
                    }

                    BlockEntity blockEntity = level.getBlockEntity(pos);

                    if (!(blockEntity instanceof ChestBlockEntity chest)) {
                        continue;
                    }

                    Container targetContainer = getActualContainer(level, pos, chest);

                    if (!hasSpaceFor(targetContainer, held)) {
                        System.out.println("[SMART-GOLEM FULL-CHEST] Skipping matching chest because full: " + pos);
                        continue;
                    }

                    ItemStack frameItem = getFramedItem(level, pos);

                    if (frameItem.isEmpty()) {
                        continue;
                    }

                    boolean matches = ItemStack.isSameItemSameComponents(frameItem, held);

                    System.out.println("[SMART-GOLEM MATCH-CHECK] chest=" + pos
                            + " frameItem=" + frameItem
                            + " holding=" + held
                            + " matches=" + matches);

                    if (!matches) {
                        continue;
                    }

                    double pathCost = getPathCost(mob, pos);

                    if (pathCost == Double.MAX_VALUE) {
                        continue;
                    }

                    if (returnToSourceChest != null) {
                        pathCost += Math.abs(pos.getY() - returnToSourceChest.getY()) * 200;
                    }

                    if (pathCost < bestDist) {
                        bestDist = pathCost;
                        best = pos;
                    }
                }
            }
        }

        if (best != null) {
            return best;
        }

        System.out.println("[SMART-GOLEM FALLBACK-SEARCH] No matching framed chest for "
                + held + ". Searching unfiltered destination chest.");

        bestDist = Double.MAX_VALUE;

        for (int x = -horizontalSearchDistance; x <= horizontalSearchDistance; x++) {
            for (int y = -verticalSearchDistance; y <= verticalSearchDistance; y++) {
                for (int z = -horizontalSearchDistance; z <= horizontalSearchDistance; z++) {

                    BlockPos pos = mobPos.offset(x, y, z);

                    if (lastPickupChest != null && pos.equals(lastPickupChest)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(pos);

                    if (!destinationBlockType.test(state)) {
                        continue;
                    }

                    BlockEntity blockEntity = level.getBlockEntity(pos);

                    if (!(blockEntity instanceof ChestBlockEntity chest)) {
                        continue;
                    }

                    if (!getFramedItem(level, pos).isEmpty()) {
                        System.out.println("[SMART-GOLEM FALLBACK-SKIP] Chest is filtered: " + pos);
                        continue;
                    }

                    Container targetContainer = getActualContainer(level, pos, chest);

                    if (!hasSpaceFor(targetContainer, held)) {
                        System.out.println("[SMART-GOLEM FULL-CHEST] Skipping fallback chest because full: " + pos);
                        continue;
                    }

                    double pathCost = getPathCost(mob, pos);

                    if (pathCost == Double.MAX_VALUE) {
                        continue;
                    }

                    if (returnToSourceChest != null) {
                        pathCost += Math.abs(pos.getY() - returnToSourceChest.getY()) * 200;
                    }

                    if (pathCost < bestDist) {
                        bestDist = pathCost;
                        best = pos;
                    }
                }
            }
        }

        if (best != null) {
            System.out.println("[SMART-GOLEM FALLBACK-DEPOSIT] Selected unfiltered chest=" + best);
        } else {
            System.out.println("[SMART-GOLEM NO-TARGET] No matching chest and no unfiltered fallback chest found for " + held);
        }

        return best;
    }

    private void closeOpenedContainer() {

        if (openedContainer != null && openedCopperGolem != null) {

            if (openedContainer.getEntitiesWithContainerOpen().contains(openedCopperGolem)) {
                openedContainer.stopOpen(openedCopperGolem);
            }

            openedCopperGolem.clearOpenedChestPos();
            openedCopperGolem.setState(CopperGolemState.IDLE);

            System.out.println("[SMART-GOLEM VANILLA-CLOSE]");
        }

        openedContainer = null;
        openedCopperGolem = null;
    }

    private boolean pickupFromChest(ServerLevel level, PathfinderMob mob, ChestBlockEntity chest) {

        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);

            if (!stack.isEmpty()) {

                int takeAmount = Math.min(stack.getCount(), stack.getMaxStackSize());
                ItemStack taken = stack.copyWithCount(takeAmount);

                System.out.println("[SMART-GOLEM PICKUP BEFORE] tick=" + level.getGameTime()
                        + " chest=" + currentTarget
                        + " slot=" + i
                        + " stack=" + stack
                        + " mobHand=" + mob.getMainHandItem());

                stack.shrink(takeAmount);

                chest.setChanged();
                level.blockEntityChanged(currentTarget);

                mob.setItemInHand(InteractionHand.MAIN_HAND, taken);

                lastPickupChest = currentTarget;
                returnToSourceChest = currentTarget;

                System.out.println("[SMART-GOLEM RETURN-SOURCE-SAVED] source=" + returnToSourceChest);

                System.out.println("[SMART-GOLEM PICKUP AFTER] tick=" + level.getGameTime()
                        + " chest=" + currentTarget
                        + " slot=" + i
                        + " remainingStack=" + chest.getItem(i)
                        + " mobHand=" + mob.getMainHandItem());

                return true;
            }
        }

        return false;
    }

    private ItemStack insertIntoChest(
            ServerLevel level,
            BlockPos chestPos,
            Container chest,
            ItemStack held
    ) {

        ItemStack remaining = held.copy();

        for (int i = 0; i < chest.getContainerSize(); i++) {

            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }

            ItemStack slot = chest.getItem(i);

            if (slot.isEmpty()) {
                continue;
            }

            if (!ItemStack.isSameItemSameComponents(slot, remaining)) {
                continue;
            }

            if (slot.getCount() >= slot.getMaxStackSize()) {
                continue;
            }

            int moveAmount = Math.min(
                    remaining.getCount(),
                    slot.getMaxStackSize() - slot.getCount()
            );

            System.out.println("[SMART-GOLEM STACK-DEPOSIT BEFORE] chest=" + chestPos
                    + " slot=" + i
                    + " slotBefore=" + slot
                    + " remaining=" + remaining
                    + " moveAmount=" + moveAmount);

            slot.grow(moveAmount);
            remaining.shrink(moveAmount);

            chest.setChanged();
            level.blockEntityChanged(chestPos);

            System.out.println("[SMART-GOLEM STACK-DEPOSIT AFTER] chest=" + chestPos
                    + " slot=" + i
                    + " slotAfter=" + chest.getItem(i)
                    + " remaining=" + remaining);
        }

        for (int i = 0; i < chest.getContainerSize(); i++) {

            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }

            ItemStack slot = chest.getItem(i);

            if (!slot.isEmpty()) {
                continue;
            }

            System.out.println("[SMART-GOLEM EMPTY-DEPOSIT BEFORE] chest=" + chestPos
                    + " slot=" + i
                    + " remaining=" + remaining);

            chest.setItem(i, remaining.copy());
            remaining = ItemStack.EMPTY;

            chest.setChanged();
            level.blockEntityChanged(chestPos);

            System.out.println("[SMART-GOLEM EMPTY-DEPOSIT AFTER] chest=" + chestPos
                    + " slot=" + i
                    + " slotAfter=" + chest.getItem(i));

            return ItemStack.EMPTY;
        }

        return remaining;
    }

    private boolean isChestBusy(ChestBlockEntity chest) {
        return !chest.getEntitiesWithContainerOpen().isEmpty();
    }

    private Container getActualContainer(
            ServerLevel level,
            BlockPos pos,
            ChestBlockEntity chest
    ) {

        Container targetContainer = chest;

        if (level.getBlockState(pos).getBlock() instanceof ChestBlock chestBlock) {

            Container combined = ChestBlock.getContainer(
                    chestBlock,
                    level.getBlockState(pos),
                    level,
                    pos,
                    true
            );

            if (combined != null) {
                targetContainer = combined;
            }
        }

        return targetContainer;
    }

    private boolean hasSpaceFor(Container chest, ItemStack item) {

        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack slot = chest.getItem(i);

            if (slot.isEmpty()) {
                return true;
            }

            if (ItemStack.isSameItemSameComponents(slot, item)
                    && slot.getCount() < slot.getMaxStackSize()) {
                return true;
            }
        }

        return false;
    }

    private boolean hasAnyItem(ChestBlockEntity chest) {

        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (!chest.getItem(i).isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private ItemStack getFramedItem(ServerLevel level, BlockPos chestPos) {

        AABB box = new AABB(chestPos).inflate(1.0D);

        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box)) {

            BlockPos attachedPos
                    = frame.blockPosition().relative(frame.getDirection().getOpposite());

            if (!attachedPos.equals(chestPos)) {

                boolean matchesDoubleChest = false;

                BlockState state = level.getBlockState(chestPos);

                if (state.getBlock() instanceof ChestBlock) {

                    Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

                    for (Direction dir : directions) {
                        BlockPos nearby = chestPos.relative(dir);
                        BlockState nearbyState = level.getBlockState(nearby);

                        if (nearbyState.getBlock() == state.getBlock()
                                && attachedPos.equals(nearby)) {

                            // Found adjacent chest with frame attached to it
                            // For double chests on shared edges, only accept the frame if its 
                            // attachment direction points TOWARD this chest (indicates this chest owns it)
                            Direction frameAttachDir = frame.getDirection();
                            Direction pointsTowardThis = dir.getOpposite();

                            // Accept if frame is pointing toward this chest
                            if (frameAttachDir.equals(pointsTowardThis)) {
                                matchesDoubleChest = true;
                                System.out.println("[SMART-GOLEM FRAME-ACCEPT] Frame points toward this chest: "
                                        + " chestPos=" + chestPos
                                        + " adjacentPos=" + nearby
                                        + " frameDir=" + frameAttachDir);
                                break;
                            } else {
                                System.out.println("[SMART-GOLEM FRAME-SKIP] Frame points away from this chest: "
                                        + " chestPos=" + chestPos
                                        + " adjacentPos=" + nearby
                                        + " frameDir=" + frameAttachDir
                                        + " pointsTowardThis=" + pointsTowardThis);
                            }
                        }
                    }
                }

                if (!matchesDoubleChest) {
                    continue;
                }
            }

            if (!frame.getItem().isEmpty()) {
                return frame.getItem();
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    protected void stop(ServerLevel level, PathfinderMob mob, long gameTime) {
        closeOpenedContainer();
        unlockChest();

        ticksAtTarget = 0;
        actionDone = false;

        if (returnToSourceChest != null) {
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest);

            System.out.println("[SMART-GOLEM STOP-KEEP-RETURN] returning to=" + returnToSourceChest);
            return;
        }

        taskState = TaskState.IDLE;
        currentTarget = null;

        onTravelling.accept(mob);
    }
}
