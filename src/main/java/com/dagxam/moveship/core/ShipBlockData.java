package com.dagxam.moveship.core;

import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;

public class ShipBlockData {
    private final Vector relativeOffset;
    private final BlockData blockData;
    private final BlockState stateSnapshot;

    public ShipBlockData(Vector relativeOffset, BlockData blockData, BlockState stateSnapshot) {
        this.relativeOffset = relativeOffset;
        this.blockData = blockData;
        this.stateSnapshot = stateSnapshot;
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
}
