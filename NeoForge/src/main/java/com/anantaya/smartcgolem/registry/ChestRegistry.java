package com.anantaya.smartcgolem.registry;

import com.anantaya.smartcgolem.util.ItemFrameHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChestRegistry {

    private static final Map<Item, BlockPos> ITEM_CHESTS =
            new HashMap<>();

    public static void registerChest(
            ServerLevel level,
            BlockPos chestPos
    ) {

        BlockEntity blockEntity =
                level.getBlockEntity(chestPos);

        if (!(blockEntity instanceof ChestBlockEntity)) {
            return;
        }

        List<ItemStack> framedStacks =
                ItemFrameHelper.getFramedItems(
                        level,
                        chestPos
                );

        if (framedStacks.isEmpty()) {
            return;
        }

        for (ItemStack framedStack : framedStacks) {

            if (framedStack.isEmpty()) {
                continue;
            }

            Item item = framedStack.getItem();

            if (ITEM_CHESTS.containsKey(item)) {
                continue;
            }

            ITEM_CHESTS.put(
                    item,
                    chestPos
            );
        }
    }

    public static BlockPos findChestForItem(
            ItemStack stack
    ) {

        return ITEM_CHESTS.get(
                stack.getItem()
        );
    }

    public static boolean hasChestForItem(
            ItemStack stack
    ) {

        return ITEM_CHESTS.containsKey(
                stack.getItem()
        );
    }

    public static void removeChest(Item item) {

        ITEM_CHESTS.remove(item);
    }
}