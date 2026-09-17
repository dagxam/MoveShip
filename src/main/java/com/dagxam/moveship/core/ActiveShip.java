package com.dagxam.moveship.core;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ActiveShip {
    private final Player pilot;
    private final ArmorStand coreEntity;
    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay> displayEntities = new ArrayList<>();

    private static final double SPEED = 0.3;
    private static final float ROTATION_SPEED = 3.0f; // Градусов за тик

    // Отслеживаем текущий угол поворота корпуса корабля (от 0 до 360)
    private float currentShipYaw = 0f;

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot = pilot;

        // Определяем математический центр кафедры как точку вращения
        Location gridAnchorCenter = anchorLocation.getBlock().getLocation().add(0.5, 0.0, 0.5);

        // Создаем невидимое кресло пилота
        Location seatLoc = gridAnchorCenter.clone().add(0, 0.2, 0);
        seatLoc.setYaw(pilot.getLocation().getYaw()); // Пилот смотрит туда же, куда смотрел
        
        this.coreEntity = (ArmorStand) seatLoc.getWorld().spawnEntity(seatLoc, EntityType.ARMOR_STAND);
        this.coreEntity.setInvisible(true);
        this.coreEntity.setInvulnerable(true);
        this.coreEntity.setGravity(false);
        this.coreEntity.setSmall(true);

        for (Block block : blocks) {
            // Берем координаты центра каждого блока
            Location blockCenter = block.getLocation().add(0.5, 0.0, 0.5);
            
            // Вектор смещения относительно центра вращения
            Vector offset = blockCenter.toVector().subtract(gridAnchorCenter.toVector());

            BlockState snapshot = block.getState();
            originalBlocks.add(new ShipBlockData(offset, block.getBlockData(), snapshot));

            if (block.getState() instanceof Container container) {
                container.getInventory().clear();
                container.update(true, false);
            }

            block.setType(Material.AIR, false);

            BlockDisplay display = (BlockDisplay) gridAnchorCenter.getWorld().spawnEntity(blockCenter, EntityType.BLOCK_DISPLAY);
            display.setBlock(snapshot.getBlockData());
            display.setTeleportDuration(2); // Плавная интерполяция движения

            // ВАЖНО: Смещаем визуальный центр (пивот) блока в его середину, иначе он будет крутиться вокруг угла
            Transformation transform = new Transformation(
                    new Vector3f(-0.5f, 0f, -0.5f), 
                    new AxisAngle4f(0, 0, 1, 0), 
                    new Vector3f(1f, 1f, 1f), 
                    new AxisAngle4f(0, 0, 1, 0)
            );
            display.setTransformation(transform);
            
            displayEntities.add(display);
        }

        this.coreEntity.addPassenger(pilot);
    }

    public void move(float forwardParams) {
        if (forwardParams == 0) return;

        Vector direction = coreEntity.getLocation().getDirection().normalize();
        direction.multiply(forwardParams > 0 ? SPEED : -SPEED);

        coreEntity.teleport(coreEntity.getLocation().add(direction));
        updateDisplayEntities();
    }

    public void rotate(float sideParams) {
        if (sideParams == 0) return;

        float yawChange = sideParams > 0 ? ROTATION_SPEED : -ROTATION_SPEED;
        
        // Меняем угол самого корабля
        currentShipYaw = (currentShipYaw + yawChange) % 360;
        if (currentShipYaw < 0) currentShipYaw += 360;

        // Поворачиваем камеру пилота
        Location loc = coreEntity.getLocation();
        loc.setYaw(loc.getYaw() + yawChange);
        coreEntity.teleport(loc);
        
        updateDisplayEntities();
    }

    // МАТЕМАТИКА ВРАЩЕНИЯ: Перерасчет координат всех блоков
    private void updateDisplayEntities() {
        Location anchorCenter = coreEntity.getLocation().clone().subtract(0, 0.2, 0);
        
        // Переводим градусы в радианы для тригонометрии
        double rad = Math.toRadians(currentShipYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);
            
            Vector offset = data.getRelativeOffset();

            // Формула 2D-вращения для координат Minecraft (где X=Восток, Z=Юг)
            double newX = offset.getX() * cos - offset.getZ() * sin;
            double newZ = offset.getX() * sin + offset.getZ() * cos;

            Location newLoc = anchorCenter.clone().add(newX, offset.getY(), newZ);
            
            // Вращаем сам блок
            newLoc.setYaw(currentShipYaw);
            
            display.teleport(newLoc);
        }
    }

    public void restoreBlocks() {
        coreEntity.removePassenger(pilot);

        // 1. Привязка к сетке блоков (округляем координаты)
        Location currentAnchor = coreEntity.getLocation().subtract(0, 0.2, 0);
        Location gridAnchor = new Location(
                currentAnchor.getWorld(),
                Math.floor(currentAnchor.getX()) + 0.5,
                currentAnchor.getY(),
                Math.floor(currentAnchor.getZ()) + 0.5
        );

        coreEntity.remove();

        // 2. Округление угла до ближайших 90 градусов (0, 90, 180, 270)
        int snappedYaw = Math.round(currentShipYaw / 90.0f) * 90;
        snappedYaw = (snappedYaw % 360 + 360) % 360;
        int rotations = snappedYaw / 90; // Количество поворотов на 90 градусов вправо

        double rad = Math.toRadians(snappedYaw);
        double cos = Math.round(Math.cos(rad)); // Используем round для идеальных 1, 0, -1
        double sin = Math.round(Math.sin(rad));

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);

            display.remove();

            Vector offset = data.getRelativeOffset();

            // Пересчитываем координаты с учетом выровненного угла
            int dx = (int) Math.round(offset.getX() * cos - offset.getZ() * sin);
            int dz = (int) Math.round(offset.getX() * sin + offset.getZ() * cos);
            int dy = offset.getBlockY();

            Block newBlock = gridAnchor.clone().add(dx, dy, dz).getBlock();
            BlockData blockData = data.getBlockData().clone();

            // 3. Вращение лицевой стороны блоков (сундуки, ступеньки)
            if (blockData instanceof Directional directional) {
                BlockFace face = directional.getFacing();
                for (int r = 0; r < rotations; r++) {
                    face = rotateFaceRight(face);
                }
                directional.setFacing(face);
            } 
            // Вращение бревен
            else if (blockData instanceof Orientable orientable) {
                if (rotations % 2 != 0) { // Если корабль повернут на 90 или 270
                    if (orientable.getAxis() == Axis.X) orientable.setAxis(Axis.Z);
                    else if (orientable.getAxis() == Axis.Z) orientable.setAxis(Axis.X);
                }
            }

            // Устанавливаем блок и восстанавливаем инвентарь
            newBlock.setBlockData(blockData, false);

            if (data.getStateSnapshot() instanceof Container oldContainer) {
                if (newBlock.getState() instanceof Container newContainer) {
                    newContainer.getInventory().setContents(oldContainer.getSnapshotInventory().getContents());
                    newContainer.update();
                }
            }
        }
    }

    // Хелпер для поворота блоков (на 90 градусов по часовой стрелке)
    private BlockFace rotateFaceRight(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            case NORTH_EAST -> BlockFace.SOUTH_EAST;
            case SOUTH_EAST -> BlockFace.SOUTH_WEST;
            case SOUTH_WEST -> BlockFace.NORTH_WEST;
            case NORTH_WEST -> BlockFace.NORTH_EAST;
            default -> face;
        };
    }

    public Player getPilot() {
        return pilot;
    }
}
