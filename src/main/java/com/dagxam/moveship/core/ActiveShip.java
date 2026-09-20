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
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // Секрет плавности: 16 точных направлений для Rotatable элементов (таблички, головы, баннеры)
    private static final List<BlockFace> ROTATABLE_FACES = List.of(
            BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST,
            BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST
    );

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot = pilot;
        
        this.currentAnchorCenter = pilot.getLocation().getBlock().getLocation().add(0.5, 0.0, 0.5);
        this.initialPilotYaw = pilot.getLocation().getYaw();

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

            boolean shouldBeWater = (block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged());

            if (shouldBeWater) {
                block.setType(Material.WATER, false);
            } else {
                block.setType(Material.AIR, false);
            }

            // Секрет идеальной плавности BlockDisplay: значения строго согласованы с таймером (2 тика)
            BlockDisplay display = (BlockDisplay) currentAnchorCenter.getWorld().spawnEntity(blockCenter, EntityType.BLOCK_DISPLAY);
            display.setBlock(snapshot.getBlockData());
            display.setTeleportDuration(2);
            display.setInterpolationDuration(2);
            display.setInterpolationDelay(0);

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

    // БОМБАРДИРОВКА ОШИБОК ПАРКОВКИ: Двухпроходная материализация мира
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
        int totalAngleDegrees = snappedYaw;

        double rad = Math.toRadians(snappedYaw);
        double cos = Math.round(Math.cos(rad));
        double sin = Math.round(Math.sin(rad));

        // Подготавливаем данные к размещению в два прохода
        List<BlockPlacement> firstPass = new ArrayList<>(); // Базовые блоки, нижние части
        List<BlockPlacement> secondPass = new ArrayList<>(); // Верхние части, настенный декор

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);

            display.remove();

            Vector offset = data.getRelativeOffset();
            int dx = (int) Math.round(offset.getX() * cos - offset.getZ() * sin);
            int dz = (int) Math.round(offset.getX() * sin + offset.getZ() * cos);
            int dy = (int) Math.round(offset.getY());

            Location targetLoc = gridAnchor.clone().add(dx, dy, dz);
            BlockData blockData = data.getBlockData().clone();

            // 1. Поворот Directional блоков (сундуки, ступеньки, печки)
            if (blockData instanceof Directional directional) {
                BlockFace face = directional.getFacing();
                for (int r = 0; r < rotations; r++) {
                    face = rotateFaceRight(face);
                }
                if (directional.getFaces().contains(face)) {
                    directional.setFacing(face);
                }
            } 
            // 2. Поворот Orientable блоков (бревна)
            else if (blockData instanceof Orientable orientable) {
                if (rotations % 2 != 0) {
                    if (orientable.getAxis() == Axis.X) orientable.setAxis(Axis.Z);
                    else if (orientable.getAxis() == Axis.Z) orientable.setAxis(Axis.X);
                }
            }
            // 3. Точный поворот Rotatable блоков (таблички, черепа, баннеры) на 16 направлений
            else if (blockData instanceof Rotatable rotatable) {
                BlockFace face = rotatable.getRotation();
                rotatable.setRotation(rotateRotatableFace(face, totalAngleDegrees));
            }

            // 4. Защита двойных сундуков при повороте на 180 градусов (чтобы не рвались на одиночные)
            if (blockData instanceof org.bukkit.block.data.type.Chest chest) {
                if (totalAngleDegrees == 180 && chest.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
                    chest.setType(chest.getType() == org.bukkit.block.data.type.Chest.Type.LEFT 
                            ? org.bukkit.block.data.type.Chest.Type.RIGHT 
                            : org.bukkit.block.data.type.Chest.Type.LEFT);
                }
            }

            if (targetLoc.getBlock().getType() == Material.WATER && blockData instanceof Waterlogged wl) {
                wl.setWaterlogged(true);
            }

            BlockPlacement placement = new BlockPlacement(targetLoc, blockData, data.getStateSnapshot());

            // Распределяем по проходам: верхние части дверей/кроватей и навесной декор ставим во втором проходе
            boolean isTopHalf = (blockData instanceof Bisected bisected && bisected.getHalf() == Bisected.Half.TOP);
            boolean isWallAttached = (blockData.getMaterial() == Material.LADDER || 
                                      blockData.getMaterial().name().contains("TORCH") || 
                                      blockData.getMaterial().name().contains("BUTTON") ||
                                      blockData.getMaterial() == Material.LEVER);

            if (isTopHalf || isWallAttached) {
                secondPass.add(placement);
            } else {
                firstPass.add(placement);
            }
        }

        // Проход 1: Ставим сплошные блоки и опоры (без физики)
        for (BlockPlacement p : firstPass) {
            Block block = p.loc.getBlock();
            block.setBlockData(p.data, false);
            restoreContainerOrLectern(block, p.snapshot);
        }

        // Проход 2: Ставим верхние части и навесной декор, опирающийся на проход 1
        for (BlockPlacement p : secondPass) {
            Block block = p.loc.getBlock();
            block.setBlockData(p.data, false);
            restoreContainerOrLectern(block, p.snapshot);
        }
    }

    private void restoreContainerOrLectern(Block block, BlockState snapshot) {
        if (snapshot instanceof Container oldContainer) {
            if (block.getState() instanceof Container newContainer) {
                newContainer.getInventory().setContents(oldContainer.getSnapshotInventory().getContents());
                newContainer.update();
            }
        }

        if (snapshot instanceof org.bukkit.block.Lectern oldLectern) {
            if (oldLectern.getPersistentDataContainer().has(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE)) {
                BlockState newState = block.getState();
                if (newState instanceof org.bukkit.block.Lectern newLectern) {
                    newLectern.getPersistentDataContainer().set(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE, (byte) 1);
                    newLectern.update(true); 
                }
            }
        }
    }

    // Вспомогательный класс для сортировки проходов парковки
    private static class BlockPlacement {
        final Location loc;
        final BlockData data;
        final BlockState snapshot;

        BlockPlacement(Location loc, BlockData data, BlockState snapshot) {
            this.loc = loc;
            this.data = data;
            this.snapshot = snapshot;
        }
    }

    // Метод точного циклического сдвига для Rotatable элементов (16 градаций)
    private static BlockFace rotateRotatableFace(BlockFace currentFace, int angleDegrees) {
        int index = ROTATABLE_FACES.indexOf(currentFace);
        if (index == -1) return currentFace;
        int steps = Math.round((angleDegrees % 360) / 22.5f);
        int newIndex = (index + steps) % ROTATABLE_FACES.size();
        if (newIndex < 0) newIndex += ROTATABLE_FACES.size();
        return ROTATABLE_FACES.get(newIndex);
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
            
            case NORTH_NORTH_EAST -> BlockFace.EAST_SOUTH_EAST;
            case EAST_SOUTH_EAST -> BlockFace.SOUTH_SOUTH_WEST;
            case SOUTH_SOUTH_WEST -> BlockFace.WEST_NORTH_WEST;
            case WEST_NORTH_WEST -> BlockFace.NORTH_NORTH_EAST;

            case EAST_NORTH_EAST -> BlockFace.SOUTH_SOUTH_EAST;
            case SOUTH_SOUTH_EAST -> BlockFace.WEST_SOUTH_WEST;
            case WEST_SOUTH_WEST -> BlockFace.NORTH_NORTH_WEST;
            case NORTH_NORTH_WEST -> BlockFace.EAST_NORTH_EAST;

            default -> face;
        };
    }

    public Player getPilot() {
        return pilot;
    }
}
