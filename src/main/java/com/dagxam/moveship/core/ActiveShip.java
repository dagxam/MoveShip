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

    // Скорость движения (блоков за тик)
    private static final double SPEED = 0.3;
    // Скорость поворота (в градусах)
    private static final float ROTATION_SPEED = 3.0f;

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot = pilot;
        Location gridAnchor = anchorLocation.getBlock().getLocation();

        // Создаем невидимое кресло
        Location seatLoc = gridAnchor.clone().add(0.5, 0.2, 0.5);
        // Задаем начальное направление кресла (куда смотрит игрок)
        seatLoc.setYaw(pilot.getLocation().getYaw());
        
        this.coreEntity = (ArmorStand) seatLoc.getWorld().spawnEntity(seatLoc, EntityType.ARMOR_STAND);
        this.coreEntity.setInvisible(true);
        this.coreEntity.setInvulnerable(true);
        this.coreEntity.setGravity(false);
        this.coreEntity.setSmall(true);

        for (Block block : blocks) {
            Location blockLoc = block.getLocation();
            Vector offset = blockLoc.toVector().subtract(gridAnchor.toVector());

            BlockState snapshot = block.getState();
            originalBlocks.add(new ShipBlockData(offset, block.getBlockData(), snapshot));

            if (block.getState() instanceof Container container) {
                container.getInventory().clear();
                container.update(true, false);
            }

            block.setType(Material.AIR, false);

            BlockDisplay display = (BlockDisplay) gridAnchor.getWorld().spawnEntity(blockLoc, EntityType.BLOCK_DISPLAY);
            display.setBlock(snapshot.getBlockData());
            // Делаем движение визуально плавным (интерполяция)
            display.setTeleportDuration(2); 
            displayEntities.add(display);
        }

        this.coreEntity.addPassenger(pilot);
    }

    // Движение вперед/назад
    public void move(float forwardParams) {
        if (forwardParams == 0) return;

        // Определяем направление (вперед или назад)
        Vector direction = coreEntity.getLocation().getDirection().normalize();
        direction.multiply(forwardParams > 0 ? SPEED : -SPEED);

        Location newLocation = coreEntity.getLocation().add(direction);
        coreEntity.teleport(newLocation);
        
        updateDisplayEntities();
    }

    // Поворот влево/вправо
    public void rotate(float sideParams) {
        if (sideParams == 0) return;

        Location loc = coreEntity.getLocation();
        // Вправо (D) - положительный угол, Влево (A) - отрицательный
        float yawChange = sideParams > 0 ? ROTATION_SPEED : -ROTATION_SPEED;
        loc.setYaw(loc.getYaw() + yawChange);
        
        coreEntity.teleport(loc);
        updateDisplayEntities();
    }

    // Синхронизация блоков-голограмм с главным якорем
    private void updateDisplayEntities() {
        Location anchor = coreEntity.getLocation().clone().subtract(0.5, 0.2, 0.5);
        
        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);
            
            // В будущем здесь понадобится матричная математика (cos/sin) для вращения вектора offset
            // Пока мы просто двигаем блоки вслед за якорем (без вращения самой формы корабля)
            Location newLoc = anchor.clone().add(data.getRelativeOffset());
            display.teleport(newLoc);
        }
    }

    public void restoreBlocks() {
        Location currentGridAnchor = coreEntity.getLocation().getBlock().getLocation();

        coreEntity.removePassenger(pilot);
        coreEntity.remove();

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);

            display.remove();

            Location newLoc = currentGridAnchor.clone().add(data.getRelativeOffset());
            Block newBlock = newLoc.getBlock();

            newBlock.setBlockData(data.getBlockData(), false);

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
