package com.dagxam.moveship.core;

import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;

/**
 * Неизменяемый снимок одного блока корабля.
 *
 * Локальные координаты считаются относительно исходного блока ядра
 * (оси мира сохраняются, пока корабль не будет повернут визуально).
 *
 * BlockState хранится ДО удаления блока из мира и используется для полного
 * восстановления TileState/инвентарей/прочих данных блока.
 */
public final class ShipBlockData {

    private final int localX;
    private final int localY;
    private final int localZ;
    private final BlockData blockData;
    private final BlockState stateSnapshot;
    private final org.bukkit.inventory.ItemStack[] items;

    /**
     * Создает полный снимок блока, который используется ядром движения.
     */
    public ShipBlockData(
            int localX,
            int localY,
            int localZ,
            BlockData blockData,
            BlockState stateSnapshot,
            org.bukkit.inventory.ItemStack[] items
    ) {
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
        this.blockData = blockData;
        this.stateSnapshot = stateSnapshot;
        this.items = items;
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

    public org.bukkit.inventory.ItemStack[] getItems() {
        return items;
    }

}
