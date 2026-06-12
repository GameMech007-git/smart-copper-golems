package com.anantaya.smartcgolem.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class ItemFrameHelper {

    public static ItemStack getFramedItem(
            ServerLevel level,
            BlockPos chestPos
    ) {

        List<ItemStack> framedItems =
                getFramedItems(level, chestPos);

        if (framedItems.isEmpty()) {
            return ItemStack.EMPTY;
        }

        return framedItems.get(0);
    }

    public static List<ItemStack> getFramedItems(
            ServerLevel level,
            BlockPos chestPos
    ) {

        List<ItemStack> framedItems =
                new ArrayList<>();

        for (Direction direction : Direction.values()) {

            BlockPos framePos =
                    chestPos.relative(direction);

            AABB searchBox =
                    new AABB(framePos).inflate(0.5);

            List<ItemFrame> frames =
                    level.getEntitiesOfClass(
                            ItemFrame.class,
                            searchBox
                    );

            for (ItemFrame frame : frames) {

                Direction attachedFace =
                        frame.getDirection();

                if (attachedFace != direction) {
                    continue;
                }

                ItemStack displayed =
                        frame.getItem();

                if (!displayed.isEmpty()) {
                    framedItems.add(displayed.copy());
                }
            }
        }

        return framedItems;
    }

    public static boolean hasMatchingFrame(
            ServerLevel level,
            BlockPos chestPos,
            ItemStack stack
    ) {

        if (stack.isEmpty()) {
            return false;
        }

        List<ItemStack> framedItems =
                getFramedItems(level, chestPos);

        // No item frames means chest accepts any item.
        if (framedItems.isEmpty()) {
            return true;
        }

        for (ItemStack framedItem : framedItems) {

            if (framedItem.isEmpty()) {
                continue;
            }

            if (ItemStack.isSameItemSameComponents(
                    framedItem,
                    stack
            )) {
                return true;
            }
        }

        return false;
    }
}