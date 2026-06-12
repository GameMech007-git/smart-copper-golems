package com.anantaya.smartcgolem.ai;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.HashMap;
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
    private static final Map<BlockPos, Long> LOCKED_CHESTS_TIMESTAMP = new HashMap<>();

    private static final long LOCK_STALE_TICKS = 600;

    private static final int MAGIC_DEPOSIT_STUCK_TICKS = 100;
    private static final double STUCK_MOVEMENT_EPSILON_SQR = 0.01D; //

    private boolean currentDestinationIsFramedMatch = false;
    private boolean currentDestinationHadReachablePath = true;
    private double closestDistanceToWalkTargetSqr = Double.MAX_VALUE;

    private Vec3 lastStuckCheckPos = null;
    private long stuckStartedAt = -1L;

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
        super(ImmutableMap.of(), 200);

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

    private boolean isChestLocked(BlockPos pos, long gameTime) {
        synchronized (LOCKED_CHESTS) {
            if (!LOCKED_CHESTS.contains(pos)) {
                return false;
            }

            Long lockedAt = LOCKED_CHESTS_TIMESTAMP.get(pos);

            if (lockedAt != null && gameTime - lockedAt > LOCK_STALE_TICKS) {
                System.out.println("[SMART-GOLEM CHEST-LOCK-STALE] clearing stale lock=" + pos);
                LOCKED_CHESTS.remove(pos);
                LOCKED_CHESTS_TIMESTAMP.remove(pos);
                return false;
            }

            return true;
        }
    }

    private boolean tryLockChest(BlockPos pos, long gameTime) {
        if (pos == null) {
            return false;
        }

        if (pos.equals(lockedChest)) {

            synchronized (LOCKED_CHESTS) {
                LOCKED_CHESTS_TIMESTAMP.put(pos, gameTime);
            }
            return true;
        }

        synchronized (LOCKED_CHESTS) {
            if (LOCKED_CHESTS.contains(pos)) {
                Long lockedAt = LOCKED_CHESTS_TIMESTAMP.get(pos);

                if (lockedAt != null && gameTime - lockedAt > LOCK_STALE_TICKS) {
                    System.out.println("[SMART-GOLEM CHEST-LOCK-STALE] reclaiming stale lock=" + pos);
                } else {
                    return false;
                }
            }

            LOCKED_CHESTS.add(pos);
            LOCKED_CHESTS_TIMESTAMP.put(pos, gameTime);
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
            LOCKED_CHESTS_TIMESTAMP.remove(lockedChest);
            System.out.println("[SMART-GOLEM CHEST-UNLOCK] unlocked=" + lockedChest);
        }

        lockedChest = null;
    }

    private void switchState(PathfinderMob mob, TaskState newState, BlockPos target, long gameTime) {

        if (needsChestLock(newState)) {
            if (!tryLockChest(target, gameTime)) {
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
                resetStuckTracking(mob, gameTime);

                if (newState != TaskState.WALK_TO_DESTINATION) {
                    markDestinationSelection(false, true);
                }
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
        resetStuckTracking(mob, gameTime);

        if (newState != TaskState.WALK_TO_DESTINATION) {
            markDestinationSelection(false, true);
        }

        System.out.println("[SMART-GOLEM STATE] -> " + newState
                + " chestTarget=" + target
                + " walkTarget=" + currentWalkTarget);
    }

    @Override
    protected void tick(ServerLevel level, PathfinderMob mob, long gameTime) {

        switch (taskState) {

            case IDLE -> searchForTarget(level, mob, gameTime);

            case WALK_TO_SOURCE -> {
                walkToTarget(mob, gameTime);

                if (hasArrived(mob)) {
                    switchState(mob, TaskState.WAIT_FOR_SOURCE, currentTarget, gameTime);
                }
            }

            case WAIT_FOR_SOURCE -> {
                walkToTarget(mob, gameTime);

                if (currentTarget == null) {
                    switchState(mob, TaskState.IDLE, null, gameTime);
                    return;
                }

                BlockEntity blockEntity = level.getBlockEntity(currentTarget);

                if (!(blockEntity instanceof ChestBlockEntity chest)) {
                    switchState(mob, TaskState.IDLE, null, gameTime);
                    return;
                }

                if (!isChestLocked(currentTarget, gameTime) && !isChestBusy(chest)) {
                    switchState(mob, TaskState.INTERACT_SOURCE, currentTarget, gameTime);
                }
            }

            case INTERACT_SOURCE -> interactWithSource(level, mob, gameTime);

            case WALK_TO_DESTINATION -> {
                walkToTarget(mob, gameTime);

                if (tryMagicDepositIfStuck(level, mob, gameTime)) {
                    return;
                }

                if (hasArrived(mob)) {
                    switchState(mob, TaskState.WAIT_FOR_DESTINATION, currentTarget, gameTime);
                }
            }

            case WAIT_FOR_DESTINATION -> {
                walkToTarget(mob, gameTime);

                if (currentTarget == null) {
                    switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
                    return;
                }

                BlockEntity blockEntity = level.getBlockEntity(currentTarget);

                if (!(blockEntity instanceof ChestBlockEntity chest)) {
                    switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
                    return;
                }

                if (!isChestLocked(currentTarget, gameTime) && !isChestBusy(chest)) {
                    switchState(mob, TaskState.INTERACT_DESTINATION, currentTarget, gameTime);
                }
            }

            case INTERACT_DESTINATION -> interactWithDestination(level, mob, gameTime);

            case RETURN_TO_SOURCE -> {
                walkToTarget(mob, gameTime);

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

    private void walkToTarget(PathfinderMob mob, long gameTime) {

        if (currentWalkTarget == null) {
            switchState(mob, TaskState.IDLE, null, gameTime);
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

    /**
     * Cheap squared-distance estimate used to rank candidate chests before
     * we spend any time on real pathfinding. This avoids running
     * createPath() for every block in the search volume.
     */
    private double getRoughDistance(PathfinderMob mob, BlockPos pos) {
        return mob.blockPosition().distSqr(pos);
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

    private void interactWithSource(ServerLevel level, PathfinderMob mob, long gameTime) {

        if (currentTarget == null) {
            closeOpenedContainer();
            switchState(mob, TaskState.IDLE, null, gameTime);
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(currentTarget);

        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            System.out.println("[SMART-GOLEM ERROR] Source target is not ChestBlockEntity at " + currentTarget);
            closeOpenedContainer();
            switchState(mob, TaskState.IDLE, null, gameTime);
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
                    switchState(mob, TaskState.WALK_TO_DESTINATION, bestDestination, gameTime);
                } else {
                    System.out.println("[SMART-GOLEM NO-DESTINATION] No valid destination found after pickup.");
                    switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
                }

                return;
            }

            switchState(mob, TaskState.IDLE, null, gameTime);
        }
    }

    private void interactWithDestination(ServerLevel level, PathfinderMob mob, long gameTime) {

        if (currentTarget == null) {
            closeOpenedContainer();
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(currentTarget);

        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            System.out.println("[SMART-GOLEM ERROR] Destination target is not ChestBlockEntity at " + currentTarget);
            closeOpenedContainer();
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
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

                Container targetContainer = getActualContainer(level, currentTarget, chest);

                ItemStack remaining =
                        insertIntoChest(level, currentTarget, targetContainer, heldBefore);

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


            if (!movedItem && !mob.getMainHandItem().isEmpty()) {

                System.out.println("[SMART-GOLEM DEPOSIT-FAILED] chest=" + currentTarget
                        + " still holding=" + mob.getMainHandItem()
                        + " searching for another destination");

                BlockPos alternateDestination = findDestinationChest(level, mob);

                if (alternateDestination != null && !alternateDestination.equals(currentTarget)) {
                    switchState(mob, TaskState.WALK_TO_DESTINATION, alternateDestination, gameTime);
                    return;
                }
            }

            BlockPos sourceToReturn =
                    returnToSourceChest != null
                            ? returnToSourceChest
                            : lastPickupChest;

            if (sourceToReturn != null) {

                returnToSourceChest = sourceToReturn;
                switchState(mob, TaskState.RETURN_TO_SOURCE, sourceToReturn, gameTime);

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
            switchState(mob, TaskState.WALK_TO_DESTINATION, best, gameTime);
        } else {
            System.out.println("[SMART-GOLEM SOURCE] Selected source chest=" + best);
            switchState(mob, TaskState.WALK_TO_SOURCE, best, gameTime);
        }
    }

    /**
     * Collects all candidate positions matching the predicate/filter within
     * the search box, sorted by cheap squared-distance (closest first).
     * Real pathfinding (getPathCost) is only ever evaluated for these
     * candidates in distance order, and we stop as soon as one is reachable
     * and accepted — avoiding O(volume * 27) pathfinding calls.
     */
    private java.util.List<BlockPos> collectCandidates(
            ServerLevel level,
            PathfinderMob mob,
            Predicate<BlockState> blockType
    ) {
        BlockPos mobPos = mob.blockPosition();
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();

        for (int x = -horizontalSearchDistance; x <= horizontalSearchDistance; x++) {
            for (int y = -verticalSearchDistance; y <= verticalSearchDistance; y++) {
                for (int z = -horizontalSearchDistance; z <= horizontalSearchDistance; z++) {

                    BlockPos pos = mobPos.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    if (!blockType.test(state)) {
                        continue;
                    }

                    candidates.add(pos.immutable());
                }
            }
        }

        candidates.sort((a, b) -> Double.compare(getRoughDistance(mob, a), getRoughDistance(mob, b)));

        return candidates;
    }

    private BlockPos findSourceChest(ServerLevel level, PathfinderMob mob) {

        for (BlockPos pos : collectCandidates(level, mob, sourceBlockType)) {

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

            return pos;
        }

        return null;
    }

    private BlockPos findDestinationChest(ServerLevel level, PathfinderMob mob) {

        ItemStack held = mob.getMainHandItem();

        if (held.isEmpty()) {
            markDestinationSelection(false, true);
            return null;

        }

        java.util.List<BlockPos> candidates = collectCandidates(level, mob, destinationBlockType);


        for (BlockPos pos : candidates) {

            if (lastPickupChest != null && pos.equals(lastPickupChest)) {
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
            boolean reachable = pathCost != Double.MAX_VALUE;

            if (!reachable) {
                System.out.println("[SMART-GOLEM MATCHED-NO-PATH] Matched framed chest is unreachable. "
                        + "Will allow magic deposit after stuck timeout. chest=" + pos);
            } else {
                System.out.println("[SMART-GOLEM MATCHED-PATH] Matched framed chest is reachable. chest=" + pos);
            }

            markDestinationSelection(true, reachable);
            return pos;
        }


        System.out.println("[SMART-GOLEM FALLBACK-SEARCH] No matching framed chest for "
                + held + ". Searching unfiltered destination chest.");

        for (BlockPos pos : candidates) {

            if (lastPickupChest != null && pos.equals(lastPickupChest)) {
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

            System.out.println("[SMART-GOLEM FALLBACK-DEPOSIT] Selected unfiltered chest=" + pos);
            markDestinationSelection(false, true);
            return pos;
        }

        for (BlockPos pos : candidates) {

            if (!pos.equals(lastPickupChest)) {
                continue;
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (!(blockEntity instanceof ChestBlockEntity chest)) {
                continue;
            }

            Container targetContainer = getActualContainer(level, pos, chest);

            if (!hasSpaceFor(targetContainer, held)) {
                continue;
            }

            ItemStack frameItem = getFramedItem(level, pos);

            if (!frameItem.isEmpty() && !ItemStack.isSameItemSameComponents(frameItem, held)) {
                continue;
            }

            double pathCost = getPathCost(mob, pos);

            if (pathCost == Double.MAX_VALUE) {
                continue;
            }

            System.out.println("[SMART-GOLEM LAST-RESORT-DEPOSIT] Depositing back into source chest=" + pos);
            markDestinationSelection(!frameItem.isEmpty(), true);
            return pos;
        }

        System.out.println("[SMART-GOLEM NO-TARGET] No matching chest and no unfiltered fallback chest found for " + held);

        return null;
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

            BlockPos attachedPos =
                    frame.blockPosition().relative(frame.getDirection().getOpposite());

            if (!attachedPos.equals(chestPos)) {

                boolean matchesDoubleChest = false;

                BlockState state = level.getBlockState(chestPos);

                if (state.getBlock() instanceof ChestBlock) {

                    for (BlockPos nearby : new BlockPos[] {
                            chestPos.north(),
                            chestPos.south(),
                            chestPos.east(),
                            chestPos.west()
                    }) {

                        BlockState nearbyState = level.getBlockState(nearby);

                        if (nearbyState.getBlock() == state.getBlock()
                                && attachedPos.equals(nearby)) {

                            matchesDoubleChest = true;
                            break;
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


        if (!mob.getMainHandItem().isEmpty()) {
            System.out.println("[SMART-GOLEM STOP-CARRYING] keeping source memory, holding="
                    + mob.getMainHandItem()
                    + " returnToSourceChest=" + returnToSourceChest);

            taskState = TaskState.IDLE;
            currentTarget = null;
            currentWalkTarget = null;

            onTravelling.accept(mob);
            return;
        }

        if (returnToSourceChest != null) {
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);

            System.out.println("[SMART-GOLEM STOP-KEEP-RETURN] returning to=" + returnToSourceChest);
            onTravelling.accept(mob);
            return;
        }

        taskState = TaskState.IDLE;
        currentTarget = null;
        currentWalkTarget = null;

        onTravelling.accept(mob);
    }

    private void markDestinationSelection(boolean framedMatch, boolean hadReachablePath) {
        this.currentDestinationIsFramedMatch = framedMatch;
        this.currentDestinationHadReachablePath = hadReachablePath;
    }

    private void resetStuckTracking(PathfinderMob mob, long gameTime) {
        this.lastStuckCheckPos = mob.position();
        this.stuckStartedAt = gameTime;

        if (currentWalkTarget == null) {
            this.closestDistanceToWalkTargetSqr = Double.MAX_VALUE;
        } else {
            this.closestDistanceToWalkTargetSqr = distanceToCurrentWalkTargetSqr(mob);
        }
    }

    private boolean hasReachablePathToCurrentWalkTarget(PathfinderMob mob) {
        if (currentWalkTarget == null) {
            return false;
        }

        Path path = mob.getNavigation().createPath(currentWalkTarget, 1);
        return path != null && path.canReach();
    }

    private boolean tryMagicDepositIfStuck(ServerLevel level, PathfinderMob mob, long gameTime) {

        if (!currentDestinationIsFramedMatch) {
            return false;
        }

        if (currentTarget == null || currentWalkTarget == null) {
            return false;
        }

        if (mob.getMainHandItem().isEmpty()) {
            return false;
        }

        if (hasArrived(mob)) {
            resetStuckTracking(mob, gameTime);
            return false;
        }

        double currentDistance = distanceToCurrentWalkTargetSqr(mob);

        if (closestDistanceToWalkTargetSqr == Double.MAX_VALUE) {
            closestDistanceToWalkTargetSqr = currentDistance;
            stuckStartedAt = gameTime;
            return false;
        }


        if (currentDistance < closestDistanceToWalkTargetSqr - 0.25D) {
            closestDistanceToWalkTargetSqr = currentDistance;
            stuckStartedAt = gameTime;
            return false;
        }

        if (stuckStartedAt < 0L) {
            stuckStartedAt = gameTime;
            return false;
        }

        if (gameTime - stuckStartedAt < MAGIC_DEPOSIT_STUCK_TICKS) {
            return false;
        }

        System.out.println("[SMART-GOLEM MAGIC-DEPOSIT-TRY] target=" + currentTarget
                + " holding=" + mob.getMainHandItem()
                + " reachableAtSearch=" + currentDestinationHadReachablePath
                + " distanceSqr=" + currentDistance
                + " bestDistanceSqr=" + closestDistanceToWalkTargetSqr);

        return magicDepositIntoMatchedChest(level, mob, gameTime);
    }

    private boolean magicDepositIntoMatchedChest(ServerLevel level, PathfinderMob mob, long gameTime) {

        BlockPos depositTarget = currentTarget;

        BlockEntity blockEntity = level.getBlockEntity(depositTarget);

        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            System.out.println("[SMART-GOLEM MAGIC-DEPOSIT-CANCEL] Target is not chest: " + depositTarget);
            markDestinationSelection(false, true);
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
            return true;
        }

        if (isChestBusy(chest)) {
            System.out.println("[SMART-GOLEM MAGIC-DEPOSIT-WAIT] Chest busy: " + depositTarget);
            resetStuckTracking(mob, gameTime);
            return false;
        }

        ItemStack heldBefore = mob.getMainHandItem().copy();

        if (heldBefore.isEmpty()) {
            return false;
        }

        ItemStack frameItem = getFramedItem(level, depositTarget);

        if (frameItem.isEmpty()
                || !ItemStack.isSameItemSameComponents(frameItem, heldBefore)) {

            System.out.println("[SMART-GOLEM MAGIC-DEPOSIT-CANCEL] Frame no longer matches. chest="
                    + depositTarget + " frameItem=" + frameItem + " held=" + heldBefore);

            markDestinationSelection(false, true);

            BlockPos alternateDestination = findDestinationChest(level, mob);

            if (alternateDestination != null && !alternateDestination.equals(depositTarget)) {
                switchState(mob, TaskState.WALK_TO_DESTINATION, alternateDestination, gameTime);
            } else {
                switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
            }

            return true;
        }

        Container targetContainer = getActualContainer(level, depositTarget, chest);

        if (!hasSpaceFor(targetContainer, heldBefore)) {
            System.out.println("[SMART-GOLEM MAGIC-DEPOSIT-CANCEL] Matched chest full: " + depositTarget);

            markDestinationSelection(false, true);

            BlockPos alternateDestination = findDestinationChest(level, mob);

            if (alternateDestination != null && !alternateDestination.equals(depositTarget)) {
                switchState(mob, TaskState.WALK_TO_DESTINATION, alternateDestination, gameTime);
            } else {
                switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
            }

            return true;
        }

        if (!tryLockChest(depositTarget, gameTime)) {
            System.out.println("[SMART-GOLEM MAGIC-DEPOSIT-WAIT] Chest locked by another golem: " + depositTarget);
            resetStuckTracking(mob, gameTime);
            return false;
        }

        mob.getNavigation().stop();

        ItemStack remaining;

        try {
            remaining = insertIntoChest(level, depositTarget, targetContainer, heldBefore);
        } finally {
            unlockChest();
        }

        mob.setItemInHand(InteractionHand.MAIN_HAND, remaining);

        chest.setChanged();
        level.blockEntityChanged(depositTarget);

        boolean movedItem = remaining.getCount() < heldBefore.getCount();

        if (movedItem) {
            spawnMagicDepositParticles(level, mob, depositTarget);

            if (mob instanceof CopperGolem copperGolem) {
                copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_DROP);
                copperGolem.setState(CopperGolemState.DROPPING_ITEM);
            }

            System.out.println("[SMART-GOLEM MAGIC-DEPOSIT-SUCCESS] chest=" + depositTarget
                    + " heldBefore=" + heldBefore
                    + " remaining=" + remaining);
        } else {
            if (mob instanceof CopperGolem copperGolem) {
                copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_NO_DROP);
                copperGolem.setState(CopperGolemState.DROPPING_NO_ITEM);
            }

            System.out.println("[SMART-GOLEM MAGIC-DEPOSIT-FAILED] Nothing moved. chest=" + depositTarget);
        }

        markDestinationSelection(false, true);
        resetStuckTracking(mob, gameTime);


        if (!mob.getMainHandItem().isEmpty()) {

            BlockPos alternateDestination = findDestinationChest(level, mob);

            if (alternateDestination != null && !alternateDestination.equals(depositTarget)) {
                switchState(mob, TaskState.WALK_TO_DESTINATION, alternateDestination, gameTime);
                return true;
            }
        }

        BlockPos sourceToReturn = returnToSourceChest != null
                ? returnToSourceChest
                : lastPickupChest;

        if (sourceToReturn != null) {
            returnToSourceChest = sourceToReturn;
            switchState(mob, TaskState.RETURN_TO_SOURCE, sourceToReturn, gameTime);

            System.out.println("[SMART-GOLEM MAGIC-RETURN-TO-SOURCE] returning to=" + sourceToReturn);
        } else {
            taskState = TaskState.IDLE;
            currentTarget = null;
            currentWalkTarget = null;

            System.out.println("[SMART-GOLEM MAGIC-RETURN-FAILED] No saved source chest.");
        }

        return true;
    }

    private double distanceToCurrentWalkTargetSqr(PathfinderMob mob) {
        if (currentWalkTarget == null) {
            return Double.MAX_VALUE;
        }

        double dx = mob.getX() - (currentWalkTarget.getX() + 0.5D);
        double dy = mob.getY() - currentWalkTarget.getY();
        double dz = mob.getZ() - (currentWalkTarget.getZ() + 0.5D);

        return dx * dx + dy * dy + dz * dz;
    }

    private void spawnMagicDepositParticles(ServerLevel level, PathfinderMob mob, BlockPos chestPos) {

        ColorParticleOption magicParticle =
                ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xB85CFF);

        level.sendParticles(
                magicParticle,
                mob.getX(),
                mob.getY() + 1.0D,
                mob.getZ(),
                24,
                0.35D,
                0.45D,
                0.35D,
                0.05D
        );

        level.sendParticles(
                magicParticle,
                chestPos.getX() + 0.5D,
                chestPos.getY() + 0.8D,
                chestPos.getZ() + 0.5D,
                24,
                0.45D,
                0.35D,
                0.45D,
                0.05D
        );
    }
}