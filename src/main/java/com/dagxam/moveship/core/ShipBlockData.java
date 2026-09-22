package com.dagxam.moveship.core;

import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

public class ShipBlockData {

    private final int localX;
    private final int localY;
    private final int localZ;
    private final BlockData blockData;
    private final BlockState stateSnapshot;
    private final ItemStack[] items;

    public ShipBlockData(
            int localX,
            int localY,
            int localZ,
            BlockData blockData,
            BlockState stateSnapshot,
            ItemStack[] items
    ) {
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
        this.blockData = blockData;
        this.stateSnapshot = stateSnapshot;
        this.items = cloneItems(items);
    }

    public int getLocalX() {
        return localX;
    }

    public int getLocalY() {
        return localY;
    }

    public int getLocalZ() {
        return localZ;
    }

    public BlockData getBlockData() {
        return blockData;
    }

    public BlockState getStateSnapshot() {
        return stateSnapshot;
    }

    public ItemStack[] getItems() {
        return cloneItems(items);
    }

    private static ItemStack[] cloneItems(ItemStack[] source) {
        if (source == null) {
            return null;
        }

        ItemStack[] copy = new ItemStack[source.length];

        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }

        return copy;
    }
}
