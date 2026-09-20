package com.dagxam.moveship.core;

import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public class ShipBlockData {
    private final Vector relativeOffset;
    private final BlockData blockData;
    private final BlockState stateSnapshot;
    private final ItemStack[] items;

    public ShipBlockData(Vector relativeOffset, BlockData blockData, BlockState stateSnapshot, ItemStack[] items) {
        this.relativeOffset = relativeOffset;
        this.blockData = blockData;
        this.stateSnapshot = stateSnapshot;
        this.items = items;
    }

    public ShipBlockData(Vector relativeOffset, BlockData blockData, BlockState stateSnapshot) {
        this(relativeOffset, blockData, stateSnapshot, null);
    }

    public Vector getRelativeOffset() {
        return relativeOffset;
    }

    public BlockData getBlockData() {
        return blockData;
    }

    public BlockState getStateSnapshot() {
        return stateSnapshot;
    }

    public ItemStack[] getItems() {
        return items;
    }
}
