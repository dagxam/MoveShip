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
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Wall;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ActiveShip {
    private final Player pilot;
    private final ArmorStand coreEntity;
    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay> displayEntities = new ArrayList<>();

    // Параметры физики лодки (инерция, разгон и сопротивление воды)
    private static final double MAX_SPEED = 0.38;
    private static final double MAX_REVERSE_SPEED = 0.16;
    private static final double ACCELERATION = 0.02;     // Скорость разгона
    private static final double WATER_FRICTION = 0.94;   // Плавное скольжение по воде после отпускания газа

    private static final float MAX_TURN_SPEED = 2.4f;    // Максимальная угловая скорость
    private static final float TURN_ACCEL = 0.32f;       // Плавность поворота штурвала
    private static final float TURN_FRICTION = 0.80f;    // Затухание вращения

    private Location currentAnchorCenter;
    private final Vector initialSeatOffset; 

    // Единая координатная ось судна
    private final float initialShipYaw;
    private float shipYaw;

    // Текущие динамические скорости
    private double currentSpeed = 0.0;
    private float currentTurnSpeed = 0.0f;

    private float targetForward = 0f;
    private float targetSide = 0f;
    
    private int forwardStopTicks = -1;
    private int sideStopTicks = -1;
    
    private final BukkitTask movementTask; 

    private static final List<BlockFace> ROTATABLE_FACES = List.of(
        BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
        BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST,
        BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
        BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST
    );

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot = pilot;
        
        this.currentAnchorCenter = anchorLocation.getBlock().getLocation().add(0.5, 0.0, 0.5);
        
        // Курс корабля при старте равен взгляду пилота
        this.shipYaw = pilot.getLocation().getYaw();
        this.initialShipYaw = this.shipYaw;

        // Фиксация сиденья: +0.55 от палубы поднимает игрока НАД блоком (полный обзор, нет погружения в дерево)
        this.initialSeatOffset = pilot.getLocation().toVector().subtract(currentAnchorCenter.toVector());
        this.initialSeatOffset.setY(pilot.getLocation().getY() - currentAnchorCenter.getY() + 0.55); 

        Location seatLoc = getCalculatedSeatLocation();
        this.coreEntity = (ArmorStand) seatLoc.getWorld().spawnEntity(seatLoc, EntityType.ARMOR_STAND);
        this.coreEntity.setInvisible(true);
        this.coreEntity.setInvulnerable(true);
        this.coreEntity.setGravity(false);
        this.coreEntity.setMarker(true); 
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

            BlockDisplay display = (BlockDisplay) currentAnchorCenter.getWorld().spawnEntity(blockCenter, EntityType.BLOCK_DISPLAY);
            display.setBlock(snapshot.getBlockData());
            
            // Буферизация интерполяции на 2 тика дает непрерывное движение без микрофризов
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
        // Цикл мотора и гидродинамики (каждый тик)
        this.movementTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            
            // Сброс удержания кнопок при отсутствии сигнала
            if (forwardStopTicks > 0) {
                forwardStopTicks--;
            } else if (forwardStopTicks == 0) {
                targetForward = 0;
                forwardStopTicks = -1;
            }

            if (sideStopTicks > 0) {
                sideStopTicks--;
            } else if (sideStopTicks == 0) {
                targetSide = 0;
                sideStopTicks = -1;
            }

            // Плавное ускорение / торможение (инерция водной лодки)
            if (targetForward > 0) {
                currentSpeed = Math.min(MAX_SPEED, currentSpeed + ACCELERATION);
            } else if (targetForward < 0) {
                currentSpeed = Math.max(-MAX_REVERSE_SPEED, currentSpeed - ACCELERATION);
            } else {
                currentSpeed *= WATER_FRICTION;
                if (Math.abs(currentSpeed) < 0.003) currentSpeed = 0.0;
            }

            // Плавный поворот рулевого колеса
            if (targetSide != 0) {
                currentTurnSpeed += (targetSide > 0 ? -TURN_ACCEL : TURN_ACCEL);
                if (Math.abs(currentTurnSpeed) > MAX_TURN_SPEED) {
                    currentTurnSpeed = Math.signum(currentTurnSpeed) * MAX_TURN_SPEED;
                }
            } else {
                currentTurnSpeed *= TURN_FRICTION;
                if (Math.abs(currentTurnSpeed) < 0.05f) currentTurnSpeed = 0f;
            }

            // Применение расчетов к миру
            if (currentTurnSpeed != 0) {
                applyRotation(currentTurnSpeed);
            }
            if (currentSpeed != 0) {
                applyMovement(currentSpeed);
            }
        }, 0L, 1L);
    }

    public void setInput(float forward, float side) {
        this.targetForward = forward;
        this.targetSide = side;

        if (forward != 0) {
            this.forwardStopTicks = -1;
        } else if (this.forwardStopTicks == -1) {
            this.forwardStopTicks = 6;
        }

        if (side != 0) {
            this.sideStopTicks = -1;
        } else if (this.sideStopTicks == -1) {
            this.sideStopTicks = 6;
        }
    }

    private Location getCalculatedSeatLocation() {
        float deltaYaw = this.shipYaw - this.initialShipYaw;
        double rad = Math.toRadians(deltaYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        double newX = initialSeatOffset.getX() * cos - initialSeatOffset.getZ() * sin;
        double newZ = initialSeatOffset.getX() * sin + initialSeatOffset.getZ() * cos;

        Location seatLoc = currentAnchorCenter.clone().add(newX, initialSeatOffset.getY(), newZ);
        seatLoc.setYaw(this.shipYaw);
        return seatLoc;
    }

    private boolean canMove(Location targetAnchorCenter, float testShipYaw) {
        float deltaYaw = testShipYaw - this.initialShipYaw;
        double rad = Math.toRadians(deltaYaw);
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

    private void applyMovement(double speed) {
        // Движение строго по носу корабля (независимо от того, куда смотрит пилот)
        double rad = Math.toRadians(this.shipYaw);
        double x = -Math.sin(rad) * speed;
        double z = Math.cos(rad) * speed;
        
        Vector direction = new Vector(x, 0, z);
        Location targetAnchor = currentAnchorCenter.clone().add(direction);

        if (canMove(targetAnchor, this.shipYaw)) {
            currentAnchorCenter = targetAnchor;
            coreEntity.teleport(getCalculatedSeatLocation());
            updateDisplayEntities();
        } else {
            currentSpeed = 0.0; // Мягкая остановка при ударе о препятствие
        }
    }

    private void applyRotation(float turnDelta) {
        float nextYaw = (this.shipYaw + turnDelta) % 360f;
        if (nextYaw < 0) nextYaw += 360f;

        if (canMove(currentAnchorCenter, nextYaw)) {
            this.shipYaw = nextYaw;
            coreEntity.teleport(getCalculatedSeatLocation());
            updateDisplayEntities();
        } else {
            currentTurnSpeed = 0f;
        }
    }

    // Синхронный расчет положения всех блоков без перекосов и смещений
    private void updateDisplayEntities() {
        float deltaYaw = this.shipYaw - this.initialShipYaw;
        double rad = Math.toRadians(deltaYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);
            
            Vector offset = data.getRelativeOffset();
            double newX = offset.getX() * cos - offset.getZ() * sin;
            double newZ = offset.getX() * sin + offset.getZ() * cos;

            Location newLoc = currentAnchorCenter.clone().add(newX, offset.getY(), newZ);
            newLoc.setYaw(deltaYaw);
            
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

        // Округление финального угла до ближайших 90 градусов
        float deltaYaw = this.shipYaw - this.initialShipYaw;
        int snappedDeltaYaw = Math.round(deltaYaw / 90.0f) * 90;
        snappedDeltaYaw = (snappedDeltaYaw % 360 + 360) % 360;
        int rotations = snappedDeltaYaw / 90;

        double rad = Math.toRadians(snappedDeltaYaw);
        double cos = Math.round(Math.cos(rad));
        double sin = Math.round(Math.sin(rad));

        record PreparedBlock(Block targetBlock, BlockData blockData, BlockState snapshot) {}

        List<PreparedBlock> passOne = new ArrayList<>();
        List<PreparedBlock> passTwo = new ArrayList<>();

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);

            display.remove();

            Vector offset = data.getRelativeOffset();
            int dx = (int) Math.round(offset.getX() * cos - offset.getZ() * sin);
            int dz = (int) Math.round(offset.getX() * sin + offset.getZ() * cos);
            int dy = (int) Math.round(offset.getY());

            Block newBlock = gridAnchor.clone().add(dx, dy, dz).getBlock();
            BlockData blockData = data.getBlockData().clone();

            // 1. Поворот Directional (сундуки, ступени, печи)
            if (blockData instanceof Directional directional) {
                BlockFace face = directional.getFacing();
                for (int r = 0; r < rotations; r++) {
                    face = rotateFaceRight(face);
                }
                if (directional.getFaces().contains(face)) {
                    directional.setFacing(face);
                }
            } 
            // 2. Поворот Orientable (брёвна)
            else if (blockData instanceof Orientable orientable) {
                if (rotations % 2 != 0) {
                    if (orientable.getAxis() == Axis.X) orientable.setAxis(Axis.Z);
                    else if (orientable.getAxis() == Axis.Z) orientable.setAxis(Axis.X);
                }
            }
            // 3. Поворот Rotatable (таблички, головы, баннеры)
            else if (blockData instanceof Rotatable rotatable) {
                BlockFace currentFace = rotatable.getRotation();
                int index = ROTATABLE_FACES.indexOf(currentFace);
                if (index != -1) {
                    int steps = Math.round((snappedDeltaYaw % 360) / 22.5f);
                    int newIndex = (index + steps) % ROTATABLE_FACES.size();
                    if (newIndex < 0) newIndex += ROTATABLE_FACES.size();
                    rotatable.setRotation(ROTATABLE_FACES.get(newIndex));
                }
            }
            // 4. Поворот MultipleFacing (заборы, стеклянные панели, решётки)
            else if (blockData instanceof MultipleFacing multipleFacing) {
                Set<BlockFace> currentFaces = new HashSet<>(multipleFacing.getFaces());
                for (BlockFace face : multipleFacing.getAllowedFaces()) {
                    multipleFacing.setFace(face, false);
                }
                for (BlockFace face : currentFaces) {
                    BlockFace rotated = face;
                    for (int r = 0; r < rotations; r++) {
                        rotated = rotateFaceRight(rotated);
                    }
                    if (multipleFacing.getAllowedFaces().contains(rotated)) {
                        multipleFacing.setFace(rotated, true);
                    }
                }
            }
            // 5. Поворот Wall (каменные ограды)
            else if (blockData instanceof Wall wall) {
                Map<BlockFace, Wall.Height> heights = new HashMap<>();
                for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
                    heights.put(face, wall.getHeight(face));
                }
                for (Map.Entry<BlockFace, Wall.Height> entry : heights.entrySet()) {
                    BlockFace rotated = entry.getKey();
                    for (int r = 0; r < rotations; r++) {
                        rotated = rotateFaceRight(rotated);
                    }
                    wall.setHeight(rotated, entry.getValue());
                }
            }

            // Защита двойных сундуков при развороте на 180 градусов
            if (blockData instanceof org.bukkit.block.data.type.Chest chest) {
                if (snappedDeltaYaw == 180 && chest.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
                    chest.setType(chest.getType() == org.bukkit.block.data.type.Chest.Type.LEFT 
                        ? org.bukkit.block.data.type.Chest.Type.RIGHT 
                        : org.bukkit.block.data.type.Chest.Type.LEFT);
                }
            }

            if (newBlock.getType() == Material.WATER && blockData instanceof Waterlogged wl) {
                wl.setWaterlogged(true);
            }

            PreparedBlock pb = new PreparedBlock(newBlock, blockData, data.getStateSnapshot());

            boolean isTopHalf = (blockData instanceof Bisected b && b.getHalf() == Bisected.Half.TOP);
            boolean isAttachment = (blockData instanceof org.bukkit.block.data.FaceAttachable);

            if (isTopHalf || isAttachment) {
                passTwo.add(pb);
            } else {
                passOne.add(pb);
            }
        }

        // Проход 1: каркас, пол, основания и заборы
        for (PreparedBlock pb : passOne) {
            // Для заборов и панелей включаем физику соседей, чтобы они соединялись намертво
            boolean applyPhysics = (pb.blockData() instanceof MultipleFacing || pb.blockData() instanceof Wall);
            pb.targetBlock().setBlockData(pb.blockData(), applyPhysics);
            applyContainerData(pb.targetBlock(), pb.snapshot());
        }

        // Проход 2: верхние половины дверей, кроватей и навесной декор
        for (PreparedBlock pb : passTwo) {
            pb.targetBlock().setBlockData(pb.blockData(), false);
            applyContainerData(pb.targetBlock(), pb.snapshot());
        }
    }

    private void applyContainerData(Block block, BlockState snapshot) {
        if (snapshot instanceof Container oldContainer) {
            if (block.getState() instanceof Container newContainer) {
                newContainer.getInventory().setContents(oldContainer.getSnapshotInventory().getContents());
                newContainer.update();
            }
        }

        if (snapshot instanceof org.bukkit.block.Lectern oldLectern) {
            if (oldLectern.getPersistentDataContainer().has(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE)) {
                if (block.getState() instanceof org.bukkit.block.Lectern newLectern) {
                    newLectern.getPersistentDataContainer().set(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE, (byte) 1);
                    newLectern.update(true); 
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
