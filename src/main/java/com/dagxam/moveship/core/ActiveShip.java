package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ActiveShip {
    private final Player pilot;
    private final ArmorStand coreEntity;
    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay> displayEntities = new ArrayList<>();

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot = pilot;

        // Строгая привязка к сетке блоков
        Location gridAnchor = anchorLocation.getBlock().getLocation();

        // Создаем невидимое сиденье по центру блока кафедры
        Location seatLoc = gridAnchor.clone().add(0.5, 0.2, 0.5);
        this.coreEntity = (ArmorStand) seatLoc.getWorld().spawnEntity(seatLoc, EntityType.ARMOR_STAND);
        this.coreEntity.setInvisible(true);
        this.coreEntity.setInvulnerable(true);
        this.coreEntity.setGravity(false);
        this.coreEntity.setSmall(true);

        for (Block block : blocks) {
            Location blockLoc = block.getLocation(); // Точные целочисленные координаты блока
            
            // Вектор смещения относительно кафедры
            Vector offset = blockLoc.toVector().subtract(gridAnchor.toVector());

            BlockState snapshot = block.getState();
            originalBlocks.add(new ShipBlockData(offset, block.getBlockData(), snapshot));

            // Защита от выпадения вещей
            if (block.getState() instanceof Container container) {
                container.getInventory().clear();
                container.update(true, false);
            }

            block.setType(Material.AIR, false);

            BlockDisplay display = (BlockDisplay) gridAnchor.getWorld().spawnEntity(blockLoc, EntityType.BLOCK_DISPLAY);
            display.setBlock(snapshot.getBlockData());
            displayEntities.add(display);
        }

        this.coreEntity.addPassenger(pilot);
    }

    public void restoreBlocks() {
        // Получаем текущие координаты корабля и привязываем к сетке
        Location currentGridAnchor = coreEntity.getLocation().getBlock().getLocation();

        // Освобождаем игрока и удаляем кресло
        coreEntity.removePassenger(pilot);
        coreEntity.remove();

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);

            display.remove();

            // Вычисляем новую позицию блока
            Location newLoc = currentGridAnchor.clone().add(data.getRelativeOffset());
            Block newBlock = newLoc.getBlock();

            // Восстанавливаем сам блок (доски, сундук и т.д.)
            newBlock.setBlockData(data.getBlockData(), false);

            // Восстанавливаем инвентари (переносим вещи из памяти в новый сундук)
            if (data.getStateSnapshot() instanceof Container oldContainer) {
                if (newBlock.getState() instanceof Container newContainer) {
                    newContainer.getInventory().setContents(oldContainer.getSnapshotInventory().getContents());
                    newContainer.update();
                }
            }
        }
    }

    public Player getPilot() {
        return pilot;
    }
}
