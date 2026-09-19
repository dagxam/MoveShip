package com.dagxam.moveship.core;

import com.dagxam.moveship.MoveShipPlugin;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
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

    private static final double SPEED = 0.5;
    private static final float ROTATION_SPEED = 6.0f;

    private Location currentAnchorCenter;
    private final Vector initialSeatOffset; 
    private final float initialPilotYaw;

    private float currentShipYaw = 0f;

    private float currentForward = 0f;
    private float currentSide = 0f;
    
    private int forwardStopTicks = -1;
    private int sideStopTicks = -1;
    
    private final BukkitTask movementTask; 

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot = pilot;
        
        // ИСПРАВЛЕНИЕ 2: Центр вращения ОБЯЗАН быть ровно по сетке (0.5), иначе при остановке блоки разорвет на части
        this.currentAnchorCenter = pilot.getLocation().getBlock().getLocation().add(0.5, 0.0, 0.5);
        this.initialPilotYaw = pilot.getLocation().getYaw();

        // Смещение кресла пилота относительно идеального центра
        this.initialSeatOffset = pilot.getLocation().toVector().subtract(currentAnchorCenter.toVector());
        this.initialSeatOffset.setY(this.initialSeatOffset.getY() - 1.2); 

        Location seatLoc = getCalculatedSeatLocation();
        this.coreEntity = (ArmorStand) seatLoc.getWorld().spawnEntity(seatLoc, EntityType.ARMOR_STAND);
        this.coreEntity.setInvisible(true);
        this.coreEntity.setInvulnerable(true);
        this.coreEntity.setGravity(false);
        this.coreEntity.setSmall(true);

        for (Block block : blocks) {
            Location blockCenter = block.getLocation().add(0.5, 0.0, 0.5);
            Vector offset = blockCenter.toVector().subtract(currentAnchorCenter.toVector());

            BlockState snapshot = block.getState();
            originalBlocks.add(new ShipBlockData(offset, block.getBlockData(), snapshot));

            if (block.getState() instanceof Container container) {
                container.getInventory().clear();
                container.update(true, false);
            }

            // ИСПРАВЛЕНИЕ 1: Больше никаких проверок соседей. Водой заливаем только затопленные полублоки.
            boolean shouldBeWater = (block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged());

            if (shouldBeWater) {
                block.setType(Material.WATER, false);
            } else {
                block.setType(Material.AIR, false);
            }

            BlockDisplay display = (BlockDisplay) currentAnchorCenter.getWorld().spawnEntity(blockCenter, EntityType.BLOCK_DISPLAY);
            display.setBlock(snapshot.getBlockData());
            display.setTeleportDuration(3);

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

        MoveShipPlugin plugin = JavaPlugin.getPlugin(MoveShipPlugin.class);
        this.movementTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            
            if (forwardStopTicks > 0) {
                forwardStopTicks -= 2;
            } else if (forwardStopTicks <= 0 && forwardStopTicks != -1) {
                currentForward = 0;
                forwardStopTicks = -1;
            }

            if (sideStopTicks > 0) {
                sideStopTicks -= 2;
            } else if (sideStopTicks <= 0 && sideStopTicks != -1) {
                currentSide = 0;
                sideStopTicks = -1;
            }

            if (currentForward != 0) {
                move(currentForward);
            }
            if (currentSide != 0) {
                rotate(currentSide);
            }
        }, 0L, 2L);
    }

    public void setInput(float forward, float side) {
        if (forward != 0) {
            this.currentForward = forward;
            this.forwardStopTicks = -1;
        } else if (this.forwardStopTicks == -1) {
            this.forwardStopTicks = 6;
        }

        if (side != 0) {
            this.currentSide = side;
            this.sideStopTicks = -1;
        } else if (this.sideStopTicks == -1) {
            this.sideStopTicks = 6;
        }
    }

    private Location getCalculatedSeatLocation() {
        double rad = Math.toRadians(currentShipYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        double newX = initialSeatOffset.getX() * cos - initialSeatOffset.getZ() * sin;
        double newZ = initialSeatOffset.getX() * sin + initialSeatOffset.getZ() * cos;

        Location seatLoc = currentAnchorCenter.clone().add(newX, initialSeatOffset.getY(), newZ);
        seatLoc.setYaw(initialPilotYaw + currentShipYaw);
        return seatLoc;
    }

    private boolean canMove(Location targetAnchorCenter, float targetYaw) {
        double rad = Math.toRadians(targetYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        for (ShipBlockData data : originalBlocks) {
            Vector offset = data.getRelativeOffset();
            double newX = offset.getX() * cos - offset.getZ() * sin;
            double newZ = offset.getX() * sin + offset.getZ() * cos;

            Location targetBlockLoc = targetAnchorCenter.clone().add(newX, offset.getY() + 0.5, newZ);
            Block targetWorldBlock = targetBlockLoc.getBlock();

            if (targetWorldBlock.getType().isSolid()) {
                return false;
            }
        }
        return true;
    }

    public void move(float forwardParams) {
        if (forwardParams == 0) return;

        Vector direction = pilot.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() > 0.0001) {
            direction.normalize();
        } else {
            direction = new Vector(0, 0, 1);
        }
        
        direction.multiply(forwardParams > 0 ? SPEED : -SPEED);

        Location targetAnchor = currentAnchorCenter.clone().add(direction);

        if (canMove(targetAnchor, currentShipYaw)) {
            currentAnchorCenter = targetAnchor;
            coreEntity.teleport(getCalculatedSeatLocation());
            updateDisplayEntities();
        }
    }

    public void rotate(float sideParams) {
        if (sideParams == 0) return;

        float yawChange = sideParams > 0 ? -ROTATION_SPEED : ROTATION_SPEED;
        float targetShipYaw = (currentShipYaw + yawChange) % 360;
        if (targetShipYaw < 0) targetShipYaw += 360;

        if (canMove(currentAnchorCenter, targetShipYaw)) {
            currentShipYaw = targetShipYaw;
            coreEntity.teleport(getCalculatedSeatLocation());
            updateDisplayEntities();
        }
    }

    private void updateDisplayEntities() {
        double rad = Math.toRadians(currentShipYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);
            
            Vector offset = data.getRelativeOffset();
            double newX = offset.getX() * cos - offset.getZ() * sin;
            double newZ = offset.getX() * sin + offset.getZ() * cos;

            Location newLoc = currentAnchorCenter.clone().add(newX, offset.getY(), newZ);
            newLoc.setYaw(currentShipYaw);
            
            display.teleport(newLoc);
        }
    }

    public void restoreBlocks() {
        if (this.movementTask != null) {
            this.movementTask.cancel();
        }

        coreEntity.removePassenger(pilot);

        Location gridAnchor = new Location(
                currentAnchorCenter.getWorld(),
                Math.floor(currentAnchorCenter.getX()) + 0.5,
                Math.floor(currentAnchorCenter.getY()), 
                Math.floor(currentAnchorCenter.getZ()) + 0.5
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

            // Использование точных целых чисел гарантирует, что корабль больше не развалится
            Vector offset = data.getRelativeOffset();
            int dx = (int) Math.round(offset.getX() * cos - offset.getZ() * sin);
            int dz = (int) Math.round(offset.getX() * sin + offset.getZ() * cos);
            int dy = (int) Math.round(offset.getY());

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

            if (newBlock.getType() == Material.WATER && blockData instanceof Waterlogged wl) {
                wl.setWaterlogged(true);
            }

            newBlock.setBlockData(blockData, false);

            if (data.getStateSnapshot() instanceof Container oldContainer) {
                if (newBlock.getState() instanceof Container newContainer) {
                    newContainer.getInventory().setContents(oldContainer.getSnapshotInventory().getContents());
                    newContainer.update();
                }
            }

            if (data.getStateSnapshot() instanceof org.bukkit.block.Lectern oldLectern) {
                if (oldLectern.getPersistentDataContainer().has(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE)) {
                    BlockState newState = newBlock.getState();
                    if (newState instanceof org.bukkit.block.Lectern newLectern) {
                        newLectern.getPersistentDataContainer().set(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE, (byte) 1);
                        newLectern.update(true); 
                    }
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
