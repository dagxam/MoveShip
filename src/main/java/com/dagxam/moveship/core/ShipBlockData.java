package com.dagxam.moveship.core;

import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private final List<BoundingBox> collisionBoxes;

    /**
     * Создает полный снимок блока, который используется ядром движения.
     */
    public ShipBlockData(
            int localX,
            int localY,
            int localZ,
            BlockData blockData,
            BlockState stateSnapshot,
            List<BoundingBox> collisionBoxes
    ) {
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
        this.blockData = blockData;
        this.stateSnapshot = stateSnapshot;
        this.collisionBoxes = Collections.unmodifiableList(
                new ArrayList<>(collisionBoxes)
        );
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

    /**
     * Точные локальные collision-boxes блока.
     *
     * Координаты уже переведены относительно центра корабельного блока-якоря.
     */
    public List<BoundingBox> getCollisionBoxes() {
        return collisionBoxes;
    }
}
