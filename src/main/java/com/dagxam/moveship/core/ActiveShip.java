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
    private static final float ROTATION_SPEED = 3.0f;

    private float currentShipYaw = 0f;

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot = pilot;

        Location gridAnchorCenter = anchorLocation.getBlock().getLocation().add(0.5, 0.0, 0.5);

        Location seatLoc = gridAnchorCenter.clone().add(0, 0.2, 0);
        seatLoc.setYaw(pilot.getLocation().getYaw());
        
        this.coreEntity = (ArmorStand) seatLoc.getWorld().spawnEntity(seatLoc, EntityType.ARMOR_STAND);
        this.coreEntity.setInvisible(true);
        this.coreEntity.setInvulnerable(true);
        this.coreEntity.setGravity(false);
        this.coreEntity.setSmall(true);

        for (Block block : blocks) {
            Location blockCenter = block.getLocation().add(0.5, 0.0, 0.5);
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
            display.setTeleportDuration(2);

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

    // СИСТЕМА КОЛЛИЗИИ: Проверяет, можно ли сдвинуть корабль в новую точку
    private boolean canMove(Location targetCoreLoc, float targetYaw) {
        Location targetAnchorCenter = targetCoreLoc.clone().subtract(0, 0.2, 0);
        
        double rad = Math.toRadians(targetYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        for (ShipBlockData data : originalBlocks) {
            Vector offset = data.getRelativeOffset();

            // Вычисляем будущие координаты каждого блока
            double newX = offset.getX() * cos - offset.getZ() * sin;
            double newZ = offset.getX() * sin + offset.getZ() * cos;

            Location targetBlockLoc = targetAnchorCenter.clone().add(newX, offset.getY(), newZ);
            Block targetWorldBlock = targetBlockLoc.getBlock();

            // Если блок твердый (камень, земля, дерево) — отменяем движение.
            // Вода, воздух, высокая трава пропустят корабль.
            if (targetWorldBlock.getType().isSolid()) {
                return false;
            }
        }
        return true;
    }

    public void move(float forwardParams) {
        if (forwardParams == 0) return;

        Vector direction = coreEntity.getLocation().getDirection().normalize();
        direction.multiply(forwardParams > 0 ? SPEED : -SPEED);

        Location targetLocation = coreEntity.getLocation().add(direction);

        // Перед тем как сдвинуть, проверяем препятствия!
        if (canMove(targetLocation, currentShipYaw)) {
            coreEntity.teleport(targetLocation);
            updateDisplayEntities();
        }
    }

    public void rotate(float sideParams) {
        if (sideParams == 0) return;

        float yawChange = sideParams > 0 ? ROTATION_SPEED : -ROTATION_SPEED;
        
        float targetShipYaw = (currentShipYaw + yawChange) % 360;
        if (targetShipYaw < 0) targetShipYaw += 360;

        Location targetLocation = coreEntity.getLocation().clone();
        targetLocation.setYaw(targetLocation.getYaw() + yawChange);

        // Перед тем как повернуть корпус, проверяем, не заденет ли он стену!
        if (canMove(coreEntity.getLocation(), targetShipYaw)) {
            currentShipYaw = targetShipYaw;
            coreEntity.teleport(targetLocation);
            updateDisplayEntities();
        }
    }

    private void updateDisplayEntities() {
        Location anchorCenter = coreEntity.getLocation().clone().subtract(0, 0.2, 0);
        
        double rad = Math.toRadians(currentShipYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);
            
            Vector offset = data.getRelativeOffset();

            double newX = offset.getX() * cos - offset.getZ() * sin;
            double newZ = offset.getX() * sin + offset.getZ() * cos;

            Location newLoc = anchorCenter.clone().add(newX, offset.getY(), newZ);
            newLoc.setYaw(currentShipYaw);
            
            display.teleport(newLoc);
        }
    }

    public void restoreBlocks() {
        coreEntity.removePassenger(pilot);

        Location currentAnchor = coreEntity.getLocation().subtract(0, 0.2, 0);
        Location gridAnchor = new Location(
                currentAnchor.getWorld(),
                Math.floor(currentAnchor.getX()) + 0.5,
                currentAnchor.getY(),
                Math.floor(currentAnchor.getZ()) + 0.5
        );

        coreEntity.remove();

        int snappedYaw = Math.round(currentShipYaw / 90.0f) * 90;
        snappedYaw = (snappedYaw % 360 + 360) % 360;
        int rotations = snappedYaw / 90;

        double rad = Math.toRadians(snappedYaw);
        double cos = Math.round(Math.cos(rad));
        double sin = Math.round(Math.sin(rad));

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);

            display.remove();

            Vector offset = data.getRelativeOffset();

            int dx = (int) Math.round(offset.getX() * cos - offset.getZ() * sin);
            int dz = (int) Math.round(offset.getX() * sin + offset.getZ() * cos);
            int dy = offset.getBlockY();

            Block newBlock = gridAnchor.clone().add(dx, dy, dz).getBlock();
            BlockData blockData = data.getBlockData().clone();

            if (blockData instanceof Directional directional) {
                BlockFace face = directional.getFacing();
                for (int r = 0; r < rotations; r++) {
                    face = rotateFaceRight(face);
                }
                directional.setFacing(face);
            } 
            else if (blockData instanceof Orientable orientable) {
                if (rotations % 2 != 0) {
                    if (orientable.getAxis() == Axis.X) orientable.setAxis(Axis.Z);
                    else if (orientable.getAxis() == Axis.Z) orientable.setAxis(Axis.X);
                }
            }

            // Возвращаем блок (если там была трава или вода - они заменятся кораблем)
            newBlock.setBlockData(blockData, false);

            if (data.getStateSnapshot() instanceof Container oldContainer) {
                if (newBlock.getState() instanceof Container newContainer) {
                    newContainer.getInventory().setContents(oldContainer.getSnapshotInventory().getContents());
                    newContainer.update();
                }
            }
        }
    }

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
