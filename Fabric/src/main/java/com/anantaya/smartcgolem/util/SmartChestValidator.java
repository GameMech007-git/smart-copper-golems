package com.anantaya.smartcgolem.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public class SmartChestValidator {

    public static boolean canAcceptItem(
            ServerLevel level,
            BlockPos chestPos,
            ItemStack stack
    ) {

        if (stack.isEmpty()) {
            return false;
        }

        return ItemFrameHelper.hasMatchingFrame(
                level,
                chestPos,
                stack
        );
    }
}