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
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
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

    // Физика лодки
    private static final double MAX_SPEED = 0.45;
    private static final double MAX_REVERSE_SPEED = 0.20;
    private static final double ACCELERATION = 0.03;
    private static final double WATER_FRICTION = 0.96;

    private static final float MAX_TURN_SPEED = 2.8f;
    private static final float TURN_ACCEL = 0.40f;
    private static final float TURN_FRICTION = 0.80f;

    // ФИКС БАГА №3: клиентская интерполяция BlockDisplay через Transformation.
    // TELEPORT_DURATION должен быть 0, а вся плавность идёт через setInterpolationDuration.
    private static final int INTERPOLATION_TICKS = 3;

    private Location currentAnchorCenter;
    private final Vector initialSeatOffset;

    private final float initialShipYaw;
    private float shipYaw;

    private double currentSpeed = 0.0;
    private float currentTurnSpeed = 0.0f;

    private boolean pressingForward = false;
    private boolean pressingBackward = false;
    private boolean pressingLeft = false;
    private boolean pressingRight = false;

    // ФИКС БАГА №1: увеличен grace до 10 тиков (500мс). Клиент шлёт пакеты steerVehicle
    // не каждый тик, а по факту нажатия. Между ними могут быть пропуски 3-5 тиков.
    private static final int INPUT_GRACE_TICKS = 10;
    private int forwardReleaseGrace = -1;
    private int backwardReleaseGrace = -1;
    private int leftReleaseGrace = -1;
    private int rightReleaseGrace = -1;

    private final BukkitTask movementTask;

    private final Set<Block> submergedWakeBlocks = new HashSet<>();
    private record BlockPos(int x, int y, int z) {}

    private static final List<BlockFace> ROTATABLE_FACES = List.of(
        BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
        BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST,
        BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
        BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST
    );

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot = pilot;

        this.currentAnchorCenter = anchorLocation.getBlock().getLocation().add(0.5, 0.0, 0.5);
        this.shipYaw = pilot.getLocation().getYaw();
        this.initialShipYaw = this.shipYaw;

        this.initialSeatOffset = pilot.getLocation().toVector().subtract(currentAnchorCenter.toVector());
        this.initialSeatOffset.setY(pilot.getLocation().getY() - currentAnchorCenter.getY() - 0.7);

        Location seatLoc = getCalculatedSeatLocation();
        this.coreEntity = (ArmorStand) seatLoc.getWorld().spawnEntity(seatLoc, EntityType.ARMOR_STAND);
        this.coreEntity.setInvisible(true);
        this.coreEntity.setInvulnerable(true);
        this.coreEntity.setGravity(false);
        this.coreEntity.setMarker(true);
        this.coreEntity.setSmall(true);

        // Определение ватерлинии
        int seaLevel = Integer.MIN_VALUE;
        for (Block block : blocks) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN}) {
                Block neighbor = block.getRelative(face);
                if (!blocks.contains(neighbor)) {
                    Material mat = neighbor.getType();
                    if (mat == Material.WATER || mat == Material.SEAGRASS || mat == Material.KELP || mat == Material.TALL_SEAGRASS) {
                        if (neighbor.getY() > seaLevel) {
                            seaLevel = neighbor.getY();
                        }
                    }
                }
            }
        }

        if (seaLevel != Integer.MIN_VALUE) {
            for (Block block : blocks) {
                if (block.getY() <= seaLevel) {
                    this.submergedWakeBlocks.add(block);
                }
            }
        }

        // ФИКС БАГА №2: клонируем инвентарь в отдельный массив ItemStack ДО получения BlockState.
        // BlockState в Paper 1.20.6 для контейнеров хранит ссылку на TileEntity инвентарь,
        // который очищается при inv.clear(). Поэтому сохраняем items ПЕРВЫМИ.
        for (Block block : blocks) {
            Location blockCenter = block.getLocation().add(0.5, 0.0, 0.5);
            Vector offset = blockCenter.toVector().subtract(currentAnchorCenter.toVector());

            BlockData originalBlockData = block.getBlockData().clone();

            ItemStack[] savedItems = null;
            BlockState snapshotForMeta = null;

            if (block.getState() instanceof Container liveContainer) {
                Inventory inv = (liveContainer instanceof org.bukkit.block.Chest chest)
                        ? chest.getBlockInventory()
                        : liveContainer.getInventory();

                // Глубокое клонирование ДО каких-либо операций
                int size = inv.getSize();
                savedItems = new ItemStack[size];
                for (int slot = 0; slot < size; slot++) {
                    ItemStack item = inv.getItem(slot);
                    if (item != null) {
                        savedItems[slot] = item.clone();
                    }
                }

                // Только теперь берём snapshot (для Lectern/CommandBlock метаданных)
                snapshotForMeta = block.getState(true);

                // Чистим live инвентарь чтобы вещи не выпали при setType(AIR)
                inv.clear();
                liveContainer.update(true, false);
            } else {
                snapshotForMeta = block.getState(true);
            }

            originalBlocks.add(new ShipBlockData(offset, originalBlockData, snapshotForMeta, savedItems));

            block.setType(Material.AIR, false);

            // ФИКС БАГА №3: спавним BlockDisplay СРАЗУ в финальной позиции с центрирующим оффсетом
            BlockDisplay display = (BlockDisplay) currentAnchorCenter.getWorld().spawnEntity(blockCenter, EntityType.BLOCK_DISPLAY);
            display.setBlock(originalBlockData);

            // КРИТИЧНО: teleportDuration = 0. Плавность идёт через Transformation интерполяцию.
            display.setTeleportDuration(0);
            display.setInterpolationDuration(INTERPOLATION_TICKS);
            display.setInterpolationDelay(0);

            // Transformation центрирует блок относительно entity origin (BlockDisplay спавнится в углу).
            Transformation transform = new Transformation(
                    new Vector3f(-0.5f, 0f, -0.5f),
                    new Quaternionf(),
                    new Vector3f(1f, 1f, 1f),
                    new Quaternionf()
            );
            display.setTransformation(transform);

            displayEntities.add(display);
        }

        this.coreEntity.addPassenger(pilot);

        MoveShipPlugin plugin = JavaPlugin.getPlugin(MoveShipPlugin.class);

        // ФИКС БАГА №3: интервал таска = INTERPOLATION_TICKS.
        // Это критично: клиент должен успеть проиграть анимацию перед следующим обновлением.
        this.movementTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {

            // Обработка grace таймеров
            if (forwardReleaseGrace > 0) forwardReleaseGrace--;
            else if (forwardReleaseGrace == 0) { pressingForward = false; forwardReleaseGrace = -1; }

            if (backwardReleaseGrace > 0) backwardReleaseGrace--;
            else if (backwardReleaseGrace == 0) { pressingBackward = false; backwardReleaseGrace = -1; }

            if (leftReleaseGrace > 0) leftReleaseGrace--;
            else if (leftReleaseGrace == 0) { pressingLeft = false; leftReleaseGrace = -1; }

            if (rightReleaseGrace > 0) rightReleaseGrace--;
            else if (rightReleaseGrace == 0) { pressingRight = false; rightReleaseGrace = -1; }

            // Разгон
            if (pressingForward) {
                currentSpeed = Math.min(MAX_SPEED, currentSpeed + ACCELERATION);
            } else if (pressingBackward) {
                currentSpeed = Math.max(-MAX_REVERSE_SPEED, currentSpeed - ACCELERATION);
            } else {
                currentSpeed *= WATER_FRICTION;
                if (Math.abs(currentSpeed) < 0.005) currentSpeed = 0.0;
            }

            // Поворот
            if (pressingLeft) {
                currentTurnSpeed = Math.max(-MAX_TURN_SPEED, currentTurnSpeed - TURN_ACCEL);
            } else if (pressingRight) {
                currentTurnSpeed = Math.min(MAX_TURN_SPEED, currentTurnSpeed + TURN_ACCEL);
            } else {
                currentTurnSpeed *= TURN_FRICTION;
                if (Math.abs(currentTurnSpeed) < 0.05f) currentTurnSpeed = 0f;
            }

            // Умножаем на INTERPOLATION_TICKS т.к. таск теперь реже
            double appliedSpeed = currentSpeed * INTERPOLATION_TICKS;
            float appliedTurn = currentTurnSpeed * INTERPOLATION_TICKS;

            boolean stateChanged = false;

            if (appliedTurn != 0) {
                float nextYaw = (this.shipYaw + appliedTurn) % 360f;
                if (nextYaw < 0) nextYaw += 360f;
                if (canMove(currentAnchorCenter, nextYaw)) {
                    this.shipYaw = nextYaw;
                    stateChanged = true;
                } else {
                    currentTurnSpeed = 0f;
                }
            }

            if (appliedSpeed != 0) {
                double rad = Math.toRadians(this.shipYaw);
                double x = -Math.sin(rad) * appliedSpeed;
                double z = Math.cos(rad) * appliedSpeed;
                Vector direction = new Vector(x, 0, z);
                Location targetAnchor = currentAnchorCenter.clone().add(direction);

                if (canMove(targetAnchor, this.shipYaw)) {
                    currentAnchorCenter = targetAnchor;
                    stateChanged = true;
                } else {
                    currentSpeed = 0.0;
                }
            }

            if (stateChanged) {
                coreEntity.teleport(getCalculatedSeatLocation());
                updateDisplayEntities();
                fillWaterBehindShip();
            }
        }, 0L, INTERPOLATION_TICKS);
    }

    public void setInput(float forward, float side) {
        // ФИКС БАГА №1: логика упрощена и разделена на независимые оси.
        // Grace взводится ТОЛЬКО когда приходит явный "0" и клавиша была нажата.
        // Grace НЕ сбрасывается при повторном нажатии - только продлевается.

        if (forward > 0.01f) {
            pressingForward = true;
            pressingBackward = false;
            forwardReleaseGrace = -1;
            backwardReleaseGrace = -1;
        } else if (forward < -0.01f) {
            pressingBackward = true;
            pressingForward = false;
            forwardReleaseGrace = -1;
            backwardReleaseGrace = -1;
        } else {
            if (pressingForward && forwardReleaseGrace < 0) {
                forwardReleaseGrace = INPUT_GRACE_TICKS;
            }
            if (pressingBackward && backwardReleaseGrace < 0) {
                backwardReleaseGrace = INPUT_GRACE_TICKS;
            }
        }

        if (side < -0.01f) {
            pressingLeft = true;
            pressingRight = false;
            leftReleaseGrace = -1;
            rightReleaseGrace = -1;
        } else if (side > 0.01f) {
            pressingRight = true;
            pressingLeft = false;
            leftReleaseGrace = -1;
            rightReleaseGrace = -1;
        } else {
            if (pressingLeft && leftReleaseGrace < 0) {
                leftReleaseGrace = INPUT_GRACE_TICKS;
            }
            if (pressingRight && rightReleaseGrace < 0) {
                rightReleaseGrace = INPUT_GRACE_TICKS;
            }
        }
    }

    private void fillWaterBehindShip() {
        if (submergedWakeBlocks.isEmpty()) return;

        float deltaYaw = this.shipYaw - this.initialShipYaw;
        double rad = Math.toRadians(deltaYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        Set<BlockPos> occupiedPositions = new HashSet<>();
        for (ShipBlockData data : originalBlocks) {
            Vector offset = data.getRelativeOffset();
            double newX = offset.getX() * cos - offset.getZ() * sin;
            double newZ = offset.getX() * sin + offset.getZ() * cos;
            int bx = (int) Math.floor(currentAnchorCenter.getX() + newX);
            int by = (int) Math.floor(currentAnchorCenter.getY() + offset.getY() + 0.5);
            int bz = (int) Math.floor(currentAnchorCenter.getZ() + newZ);
            occupiedPositions.add(new BlockPos(bx, by, bz));
        }

        submergedWakeBlocks.removeIf(block -> {
            BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
            if (!occupiedPositions.contains(pos)) {
                block.setType(Material.WATER, true);
                return true;
            }
            return false;
        });
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

    private void updateDisplayEntities() {
        // ФИКС БАГА №3: используем Transformation с Quaternion поворотом вместо setYaw()
        // и setInterpolationDelay(0) для активации новой интерполяции КАЖДЫЙ раз.
        // setYaw() на BlockDisplay работает криво в Paper 1.20.6.

        float deltaYaw = this.shipYaw - this.initialShipYaw;
        double rad = Math.toRadians(deltaYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        // Quaternion поворота корабля вокруг оси Y
        Quaternionf shipRotation = new Quaternionf()
                .rotateY((float) Math.toRadians(-deltaYaw));

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay display = displayEntities.get(i);
            ShipBlockData data = originalBlocks.get(i);

            Vector offset = data.getRelativeOffset();
            double newX = offset.getX() * cos - offset.getZ() * sin;
            double newZ = offset.getX() * sin + offset.getZ() * cos;

            Location newLoc = currentAnchorCenter.clone().add(newX, offset.getY(), newZ);
            // Yaw entity оставляем 0 - вся ротация через Transformation
            newLoc.setYaw(0f);
            newLoc.setPitch(0f);

            // teleport с duration=0 = мгновенно (без клиентской интерполяции позиции)
            display.teleport(newLoc);

            // Transformation с поворотом + центрирующим оффсетом (-0.5, 0, -0.5)
            // Оффсет применяется ПОСЛЕ поворота, поэтому нужно повернуть и его
            Vector3f rotatedCenterOffset = new Vector3f(-0.5f, 0f, -0.5f);
            shipRotation.transform(rotatedCenterOffset);

            Transformation transform = new Transformation(
                    rotatedCenterOffset,
                    shipRotation,
                    new Vector3f(1f, 1f, 1f),
                    new Quaternionf()
            );

            // КРИТИЧНО: сброс delay=0 запускает новую интерполяцию с текущей позиции
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(INTERPOLATION_TICKS);
            display.setTransformation(transform);
        }
    }

    public void restoreBlocks() {
        if (this.movementTask != null) {
            this.movementTask.cancel();
        }

        // ФИКС БАГА №4: НЕ поднимаем игрока сразу. Сначала СПЕШИВАЕМ, потом ставим блоки,
        // потом телепортируем на конкретную высоту палубы.
        coreEntity.removePassenger(pilot);

        for (Block remaining : submergedWakeBlocks) {
            remaining.setType(Material.WATER, true);
        }
        submergedWakeBlocks.clear();

        Location gridAnchor = new Location(
                currentAnchorCenter.getWorld(),
                Math.floor(currentAnchorCenter.getX()) + 0.5,
                Math.floor(currentAnchorCenter.getY()),
                Math.floor(currentAnchorCenter.getZ()) + 0.5
        );

        float deltaYaw = this.shipYaw - this.initialShipYaw;
        int snappedDeltaYaw = Math.round(deltaYaw / 90.0f) * 90;
        snappedDeltaYaw = (snappedDeltaYaw % 360 + 360) % 360;
        int rotations = snappedDeltaYaw / 90;

        double rad = Math.toRadians(snappedDeltaYaw);
        double cos = Math.round(Math.cos(rad));
        double sin = Math.round(Math.sin(rad));

        record PreparedBlock(Block targetBlock, BlockData blockData, BlockState snapshot, ItemStack[] items) {}

        List<PreparedBlock> passOne = new ArrayList<>();
        List<PreparedBlock> passTwo = new ArrayList<>();
        List<PreparedBlock> passThreeContainers = new ArrayList<>();

        for (int i = 0; i < displayEntities.size(); i++) {
            ShipBlockData data = originalBlocks.get(i);

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
                if (directional.getFaces().contains(face)) {
                    directional.setFacing(face);
                }
            }
            else if (blockData instanceof Orientable orientable) {
                if (rotations % 2 != 0) {
                    if (orientable.getAxis() == Axis.X) orientable.setAxis(Axis.Z);
                    else if (orientable.getAxis() == Axis.Z) orientable.setAxis(Axis.X);
                }
            }
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

            PreparedBlock pb = new PreparedBlock(newBlock, blockData, data.getStateSnapshot(), data.getItems());

            if (data.getItems() != null || data.getStateSnapshot() instanceof org.bukkit.block.Lectern) {
                passThreeContainers.add(pb);
            }

            boolean isTopHalf = (blockData instanceof Bisected b && b.getHalf() == Bisected.Half.TOP);
            boolean isAttachment = (blockData instanceof org.bukkit.block.data.FaceAttachable);

            if (isTopHalf || isAttachment) {
                passTwo.add(pb);
            } else {
                passOne.add(pb);
            }
        }

        // ПРОХОД 1
        for (PreparedBlock pb : passOne) {
            boolean applyPhysics = (pb.blockData() instanceof MultipleFacing || pb.blockData() instanceof Wall);
            pb.targetBlock().setType(pb.blockData().getMaterial(), false);
            pb.targetBlock().setBlockData(pb.blockData(), applyPhysics);
        }

        // ПРОХОД 2
        for (PreparedBlock pb : passTwo) {
            pb.targetBlock().setType(pb.blockData().getMaterial(), false);
            pb.targetBlock().setBlockData(pb.blockData(), false);
        }

        // ФИКС БАГА №2: ПРОХОД 3 - восстановление инвентарей.
        // В Paper 1.20.6 после setBlockData TileEntity уже создан, но getState()
        // должен вызываться ПОСЛЕ полной установки блока. Используем правильный порядок.
        for (PreparedBlock pb : passThreeContainers) {
            applyContainerData(pb.targetBlock(), pb.snapshot(), pb.items());
        }

        // ФИКС БАГА №4: телепортируем игрока на конкретную высоту палубы (гриданкор Y + 1.0)
        // ПОСЛЕ того как блоки физически поставлены. Ищем самый верхний блок корабля под игроком.
        Location pilotLoc = pilot.getLocation();
        double safeY = findSafeDeckY(pilotLoc, gridAnchor);

        Location deckLoc = new Location(
                pilotLoc.getWorld(),
                pilotLoc.getX(),
                safeY,
                pilotLoc.getZ(),
                pilotLoc.getYaw(),
                pilotLoc.getPitch()
        );
        pilot.teleport(deckLoc);

        // Удаляем визуал ПОСЛЕДНИМ - чтобы игрок не увидел момент пропажи блоков
        for (BlockDisplay display : displayEntities) {
            display.remove();
        }
        coreEntity.remove();
    }

    // ФИКС БАГА №4: находим безопасную Y координату палубы под игроком
    private double findSafeDeckY(Location pilotLoc, Location gridAnchor) {
        int px = pilotLoc.getBlockX();
        int pz = pilotLoc.getBlockZ();

        // Ищем самый высокий блок корабля под ногами игрока
        int highestY = Integer.MIN_VALUE;

        float deltaYaw = this.shipYaw - this.initialShipYaw;
        int snappedDeltaYaw = Math.round(deltaYaw / 90.0f) * 90;
        snappedDeltaYaw = (snappedDeltaYaw % 360 + 360) % 360;

        double rad = Math.toRadians(snappedDeltaYaw);
        double cos = Math.round(Math.cos(rad));
        double sin = Math.round(Math.sin(rad));

        for (ShipBlockData data : originalBlocks) {
            Vector offset = data.getRelativeOffset();
            int dx = (int) Math.round(offset.getX() * cos - offset.getZ() * sin);
            int dz = (int) Math.round(offset.getX() * sin + offset.getZ() * cos);
            int dy = (int) Math.round(offset.getY());

            int bx = gridAnchor.getBlockX() + dx;
            int by = gridAnchor.getBlockY() + dy;
            int bz = gridAnchor.getBlockZ() + dz;

            if (bx == px && bz == pz && by <= pilotLoc.getY() + 0.5) {
                if (by > highestY) highestY = by;
            }
        }

        if (highestY != Integer.MIN_VALUE) {
            // Ставим игрока на верхнюю грань блока
            return highestY + 1.0;
        }
        // Если под ногами нет блока корабля - оставляем текущую Y
        return pilotLoc.getY();
    }

    private void applyContainerData(Block block, BlockState snapshot, ItemStack[] items) {
        // ФИКС БАГА №2: правильный порядок для Paper 1.20.6
        if (items != null) {
            BlockState freshState = block.getState();
            if (freshState instanceof Container newContainer) {
                Inventory inv = (newContainer instanceof org.bukkit.block.Chest chest)
                        ? chest.getBlockInventory()
                        : newContainer.getInventory();

                // Заполняем слоты через живой инвентарь TileEntity
                for (int slot = 0; slot < Math.min(items.length, inv.getSize()); slot++) {
                    if (items[slot] != null) {
                        inv.setItem(slot, items[slot].clone());
                    }
                }
                // update с force=true, applyPhysics=false - критично для Paper 1.20.6
                newContainer.update(true, false);
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
