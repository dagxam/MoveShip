package com.dagxam.moveship.core;

import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;

public class ShipBlockData {

    private final int localX;
    private final int localY;
    private final int localZ;
    private final BlockData blockData;
    private final BlockState stateSnapshot;

    public ShipBlockData(
            int localX,
            int localY,
            int localZ,
            BlockData blockData,
            BlockState stateSnapshot
    ) {
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
        this.blockData = blockData;
        this.stateSnapshot = stateSnapshot;
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
}
