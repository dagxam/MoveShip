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
import org.bukkit.block.Furnace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Wall;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
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
    private final ArmorStand rootEntity;   // якорь всего корабля (displays = passengers)
    private final ArmorStand seatEntity;   // кресло пилота
    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay> displayEntities = new ArrayList<>();

    // ===== физика как у лодки =====
    private static final double MAX_SPEED = 0.40;
    private static final double MAX_REVERSE_SPEED = 0.18;
    private static final double ACCELERATION = 0.04;
    private static final double WATER_FRICTION = 0.92;

    private static final float MAX_TURN_SPEED = 3.2f;
    private static final float TURN_ACCEL = 0.55f;
    private static final float TURN_FRICTION = 0.75f;

    // Удержание ввода (пакеты STEER_VEHICLE приходят не каждый тик)
    private static final int INPUT_GRACE_TICKS = 8;

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

    private int forwardGrace = 0;
    private int backwardGrace = 0;
    private int leftGrace = 0;
    private int rightGrace = 0;

    private final BukkitTask movementTask;
    private final MoveShipPlugin plugin;

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
        this.plugin = JavaPlugin.getPlugin(MoveShipPlugin.class);

        this.currentAnchorCenter = anchorLocation.getBlock().getLocation().add(0.5, 0.0, 0.5);
        this.shipYaw = pilot.getLocation().getYaw();
        this.initialShipYaw = this.shipYaw;

        // Смещение кресла в локальных координатах корабля
        this.initialSeatOffset = pilot.getLocation().toVector().subtract(currentAnchorCenter.toVector());
        // Чуть ниже глаз игрока, чтобы сидеть НА палубе, а не в блоке
        this.initialSeatOffset.setY(0.05);

        // ===== ROOT: единый якорь =====
        Location rootLoc = currentAnchorCenter.clone();
        rootLoc.setYaw(this.shipYaw);
        rootLoc.setPitch(0f);

        this.rootEntity = (ArmorStand) rootLoc.getWorld().spawnEntity(rootLoc, EntityType.ARMOR_STAND);
        this.rootEntity.setInvisible(true);
        this.rootEntity.setInvulnerable(true);
        this.rootEntity.setGravity(false);
        this.rootEntity.setMarker(true);
        this.rootEntity.setSmall(true);
        this.rootEntity.setBasePlate(false);
        this.rootEntity.setPersistent(false);

        // ===== SEAT: отдельное кресло =====
        Location seatLoc = getCalculatedSeatLocation();
        this.seatEntity = (ArmorStand) seatLoc.getWorld().spawnEntity(seatLoc, EntityType.ARMOR_STAND);
        this.seatEntity.setInvisible(true);
        this.seatEntity.setInvulnerable(true);
        this.seatEntity.setGravity(false);
        this.seatEntity.setMarker(true);
        this.seatEntity.setSmall(true);
        this.seatEntity.setBasePlate(false);
        this.seatEntity.setPersistent(false);

        // Ватерлиния
        int seaLevel = Integer.MIN_VALUE;
        for (Block block : blocks) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN}) {
                Block neighbor = block.getRelative(face);
                if (!blocks.contains(neighbor)) {
                    Material mat = neighbor.getType();
                    if (mat == Material.WATER || mat == Material.SEAGRASS || mat == Material.KELP
                            || mat == Material.TALL_SEAGRASS || mat == Material.BUBBLE_COLUMN) {
                        if (neighbor.getY() > seaLevel) seaLevel = neighbor.getY();
                    }
                }
            }
        }
        if (seaLevel != Integer.MIN_VALUE) {
            for (Block block : blocks) {
                if (block.getY() <= seaLevel) submergedWakeBlocks.add(block);
            }
        }

        // ===== Сохранение блоков + инвентарей + спавн Display как пассажиров root =====
        for (Block block : blocks) {
            Location blockCenter = block.getLocation().add(0.5, 0.0, 0.5);
            Vector offset = blockCenter.toVector().subtract(currentAnchorCenter.toVector());

            BlockData originalBlockData = block.getBlockData().clone();
            ItemStack[] savedItems = null;
            BlockState snapshotForMeta;

            // ВАЖНО: сначала глубоко копируем предметы, ПОТОМ clear, ПОТОМ snapshot мета
            if (block.getState() instanceof Container liveContainer) {
                Inventory inv = getContainerInventory(liveContainer);
                savedItems = deepCopyItems(inv);

                inv.clear();
                liveContainer.update(true, false);

                snapshotForMeta = block.getState(true);
            } else {
                snapshotForMeta = block.getState(true);
            }

            originalBlocks.add(new ShipBlockData(offset.clone(), originalBlockData, snapshotForMeta, savedItems));

            // Удаляем физический блок без физики (фонари/цепи не отвалятся с дропом)
            block.setType(Material.AIR, false);

            // Display спавнится В ТОЧКЕ ROOT, смещение — только через Transformation (локальное)
            BlockDisplay display = (BlockDisplay) rootLoc.getWorld().spawnEntity(rootLoc, EntityType.BLOCK_DISPLAY);
            display.setBlock(originalBlockData);
            display.setPersistent(false);

            // Пассажир root → клиент двигает ВСЕ блоки как один объект (без рассинхрона)
            display.setTeleportDuration(0);
            display.setInterpolationDuration(0);
            display.setInterpolationDelay(0);

            // Локальный оффсет в пространстве корабля + центрирование модели блока
            Vector3f local = new Vector3f(
                    (float) offset.getX() - 0.5f,
                    (float) offset.getY(),
                    (float) offset.getZ() - 0.5f
            );

            Transformation transform = new Transformation(
                    local,
                    new Quaternionf(),
                    new Vector3f(1f, 1f, 1f),
                    new Quaternionf()
            );
            display.setTransformation(transform);

            rootEntity.addPassenger(display);
            displayEntities.add(display);
        }

        // Пилот в кресло
        seatEntity.addPassenger(pilot);

        // ===== Движок каждый тик (как лодка) =====
        this.movementTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    // ====== ВВОД: 4 кнопки из ShipMovementListener ======
    public void setInput(boolean forward, boolean backward, boolean left, boolean right) {
        if (forward) {
            pressingForward = true;
            forwardGrace = INPUT_GRACE_TICKS;
        }
        if (backward) {
            pressingBackward = true;
            backwardGrace = INPUT_GRACE_TICKS;
        }
        if (left) {
            pressingLeft = true;
            leftGrace = INPUT_GRACE_TICKS;
        }
        if (right) {
            pressingRight = true;
            rightGrace = INPUT_GRACE_TICKS;
        }

        // Если пакет явно говорит "кнопка не нажата" — не сбрасываем сразу,
        // grace добьёт в tick(). Но если пришёл пакет с true другой оси — ок.
        if (!forward && !backward && !left && !right) {
            // пустой пакет — просто даём grace тикать
        }
    }

    private void tickGrace() {
        if (forwardGrace > 0) {
            forwardGrace--;
        } else {
            pressingForward = false;
        }

        if (backwardGrace > 0) {
            backwardGrace--;
        } else {
            pressingBackward = false;
        }

        if (leftGrace > 0) {
            leftGrace--;
        } else {
            pressingLeft = false;
        }

        if (rightGrace > 0) {
            rightGrace--;
        } else {
            pressingRight = false;
        }
    }

    private void tick() {
        if (rootEntity == null || !rootEntity.isValid() || !pilot.isOnline()) {
            if (movementTask != null) movementTask.cancel();
            return;
        }

        tickGrace();

        // ----- скорость (инерция лодки) -----
        if (pressingForward && !pressingBackward) {
            currentSpeed = Math.min(MAX_SPEED, currentSpeed + ACCELERATION);
        } else if (pressingBackward && !pressingForward) {
            currentSpeed = Math.max(-MAX_REVERSE_SPEED, currentSpeed - ACCELERATION);
        } else {
            currentSpeed *= WATER_FRICTION;
            if (Math.abs(currentSpeed) < 0.003) currentSpeed = 0.0;
        }

        // ----- руль (ИСПРАВЛЕННЫЕ стороны) -----
        // A (left)  = нос влево  = уменьшение yaw в MC
        // D (right) = нос вправо = увеличение yaw в MC
        if (pressingLeft && !pressingRight) {
            currentTurnSpeed = Math.max(-MAX_TURN_SPEED, currentTurnSpeed - TURN_ACCEL);
        } else if (pressingRight && !pressingLeft) {
            currentTurnSpeed = Math.min(MAX_TURN_SPEED, currentTurnSpeed + TURN_ACCEL);
        } else {
            currentTurnSpeed *= TURN_FRICTION;
            if (Math.abs(currentTurnSpeed) < 0.04f) currentTurnSpeed = 0f;
        }

        boolean moved = false;

        if (currentTurnSpeed != 0f) {
            float nextYaw = normalizeYaw(this.shipYaw + currentTurnSpeed);
            if (canMove(currentAnchorCenter, nextYaw)) {
                this.shipYaw = nextYaw;
                moved = true;
            } else {
                currentTurnSpeed = 0f;
            }
        }

        if (currentSpeed != 0.0) {
            Vector direction = yawToDirection(this.shipYaw).multiply(currentSpeed);
            Location targetAnchor = currentAnchorCenter.clone().add(direction);
            if (canMove(targetAnchor, this.shipYaw)) {
                currentAnchorCenter = targetAnchor;
                moved = true;
            } else {
                currentSpeed = 0.0;
            }
        }

        // Даже без moved обновляем seat если нужно — но root двигаем только при изменении
        if (moved) {
            // Двигаем ТОЛЬКО root — все BlockDisplay-пассажиры едут с ним монолитом
            Location rootLoc = currentAnchorCenter.clone();
            rootLoc.setYaw(this.shipYaw);
            rootLoc.setPitch(0f);
            rootEntity.teleport(rootLoc);

            // Кресло отдельно по локальному оффсету
            seatEntity.teleport(getCalculatedSeatLocation());

            fillWaterBehindShip();
        }
    }

    private static float normalizeYaw(float yaw) {
        yaw %= 360f;
        if (yaw < 0f) yaw += 360f;
        return yaw;
    }

    private static Vector yawToDirection(float yaw) {
        double rad = Math.toRadians(yaw);
        // Minecraft: yaw 0 = SOUTH (+Z)
        return new Vector(-Math.sin(rad), 0.0, Math.cos(rad));
    }

    private void fillWaterBehindShip() {
        if (submergedWakeBlocks.isEmpty()) return;

        float deltaYaw = this.shipYaw - this.initialShipYaw;
        double rad = Math.toRadians(deltaYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        Set<BlockPos> occupied = new HashSet<>();
        for (ShipBlockData data : originalBlocks) {
            Vector offset = data.getRelativeOffset();
            double newX = offset.getX() * cos - offset.getZ() * sin;
            double newZ = offset.getX() * sin + offset.getZ() * cos;
            int bx = (int) Math.floor(currentAnchorCenter.getX() + newX);
            int by = (int) Math.floor(currentAnchorCenter.getY() + offset.getY() + 0.5);
            int bz = (int) Math.floor(currentAnchorCenter.getZ() + newZ);
            occupied.add(new BlockPos(bx, by, bz));
        }

        submergedWakeBlocks.removeIf(block -> {
            BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
            if (!occupied.contains(pos)) {
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
        seatLoc.setPitch(0f);
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
            Material type = targetBlockLoc.getBlock().getType();
            if (type.isSolid()) {
                return false;
            }
        }
        return true;
    }

    public void restoreBlocks() {
        if (this.movementTask != null) {
            this.movementTask.cancel();
        }

        // Спешиваем пилота, НЕ удаляя displays пока блоки не стоят
        if (seatEntity != null && seatEntity.isValid()) {
            seatEntity.eject();
        }
        if (pilot.isInsideVehicle()) {
            pilot.leaveVehicle();
        }

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

        for (ShipBlockData data : originalBlocks) {
            Vector offset = data.getRelativeOffset();
            int dx = (int) Math.round(offset.getX() * cos - offset.getZ() * sin);
            int dz = (int) Math.round(offset.getX() * sin + offset.getZ() * cos);
            int dy = (int) Math.round(offset.getY());

            Block newBlock = gridAnchor.clone().add(dx, dy, dz).getBlock();
            BlockData blockData = data.getBlockData().clone();

            rotateBlockData(blockData, rotations, snappedDeltaYaw);

            if (newBlock.getType() == Material.WATER && blockData instanceof Waterlogged wl) {
                wl.setWaterlogged(true);
            }

            PreparedBlock pb = new PreparedBlock(newBlock, blockData, data.getStateSnapshot(), data.getItems());

            if (data.getItems() != null || isSpecialTile(data.getStateSnapshot())) {
                passThreeContainers.add(pb);
            }

            if (isSecondaryPlace(blockData)) {
                passTwo.add(pb);
            } else {
                passOne.add(pb);
            }
        }

        // ПРОХОД 1: каркас
        for (PreparedBlock pb : passOne) {
            boolean phys = pb.blockData() instanceof MultipleFacing || pb.blockData() instanceof Wall;
            pb.targetBlock().setType(pb.blockData().getMaterial(), false);
            pb.targetBlock().setBlockData(pb.blockData(), phys);
        }

        // ПРОХОД 2: фонари, двери top, кнопки, цепи и т.д.
        for (PreparedBlock pb : passTwo) {
            pb.targetBlock().setType(pb.blockData().getMaterial(), false);
            pb.targetBlock().setBlockData(pb.blockData(), false);
        }

        // ПРОХОД 3: контейнеры сразу
        for (PreparedBlock pb : passThreeContainers) {
            applyContainerData(pb.targetBlock(), pb.snapshot(), pb.items());
        }

        // Повтор через 1 тик — Paper иногда инициализирует TileEntity на следующий тик
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (PreparedBlock pb : passThreeContainers) {
                applyContainerData(pb.targetBlock(), pb.snapshot(), pb.items());
            }
        }, 1L);

        // Игрок на палубу
        double safeY = findSafeDeckY(pilot.getLocation(), gridAnchor, snappedDeltaYaw, cos, sin);
        Location deckLoc = pilot.getLocation().clone();
        deckLoc.setY(safeY);
        deckLoc.setPitch(pilot.getLocation().getPitch());
        pilot.teleport(deckLoc);

        // Снос визуала после материализации
        for (BlockDisplay display : displayEntities) {
            if (display != null && display.isValid()) {
                rootEntity.removePassenger(display);
                display.remove();
            }
        }
        displayEntities.clear();

        if (rootEntity != null && rootEntity.isValid()) rootEntity.remove();
        if (seatEntity != null && seatEntity.isValid()) seatEntity.remove();
    }

    private boolean isSpecialTile(BlockState state) {
        return state instanceof org.bukkit.block.Lectern
                || state instanceof Furnace
                || state instanceof Container;
    }

    private boolean isSecondaryPlace(BlockData blockData) {
        if (blockData instanceof Bisected b && b.getHalf() == Bisected.Half.TOP) return true;
        if (blockData instanceof FaceAttachable) return true;
        if (blockData instanceof Lantern) return true;
        Material m = blockData.getMaterial();
        return m == Material.LANTERN
                || m == Material.SOUL_LANTERN
                || m == Material.CHAIN
                || m == Material.END_ROD
                || m == Material.TORCH
                || m == Material.SOUL_TORCH
                || m == Material.WALL_TORCH
                || m == Material.SOUL_WALL_TORCH
                || m == Material.REDSTONE_TORCH
                || m == Material.REDSTONE_WALL_TORCH
                || m.name().endsWith("_BUTTON")
                || m.name().endsWith("_SIGN")
                || m.name().endsWith("_HANGING_SIGN")
                || m.name().endsWith("_WALL_SIGN")
                || m.name().endsWith("_BANNER")
                || m.name().endsWith("_WALL_BANNER");
    }

    private void rotateBlockData(BlockData blockData, int rotations, int snappedDeltaYaw) {
        if (blockData instanceof Directional directional) {
            BlockFace face = directional.getFacing();
            for (int r = 0; r < rotations; r++) face = rotateFaceRight(face);
            if (directional.getFaces().contains(face)) directional.setFacing(face);
        } else if (blockData instanceof Orientable orientable) {
            if (rotations % 2 != 0) {
                if (orientable.getAxis() == Axis.X) orientable.setAxis(Axis.Z);
                else if (orientable.getAxis() == Axis.Z) orientable.setAxis(Axis.X);
            }
        } else if (blockData instanceof Rotatable rotatable) {
            BlockFace currentFace = rotatable.getRotation();
            int index = ROTATABLE_FACES.indexOf(currentFace);
            if (index != -1) {
                int steps = Math.round((snappedDeltaYaw % 360) / 22.5f);
                int newIndex = Math.floorMod(index + steps, ROTATABLE_FACES.size());
                rotatable.setRotation(ROTATABLE_FACES.get(newIndex));
            }
        } else if (blockData instanceof MultipleFacing multipleFacing) {
            Set<BlockFace> currentFaces = new HashSet<>(multipleFacing.getFaces());
            for (BlockFace face : multipleFacing.getAllowedFaces()) {
                multipleFacing.setFace(face, false);
            }
            for (BlockFace face : currentFaces) {
                BlockFace rotated = face;
                for (int r = 0; r < rotations; r++) rotated = rotateFaceRight(rotated);
                if (multipleFacing.getAllowedFaces().contains(rotated)) {
                    multipleFacing.setFace(rotated, true);
                }
            }
        } else if (blockData instanceof Wall wall) {
            Map<BlockFace, Wall.Height> heights = new HashMap<>();
            for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
                heights.put(face, wall.getHeight(face));
            }
            for (Map.Entry<BlockFace, Wall.Height> entry : heights.entrySet()) {
                BlockFace rotated = entry.getKey();
                for (int r = 0; r < rotations; r++) rotated = rotateFaceRight(rotated);
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
    }

    private double findSafeDeckY(Location pilotLoc, Location gridAnchor, int snappedDeltaYaw, double cos, double sin) {
        int px = pilotLoc.getBlockX();
        int pz = pilotLoc.getBlockZ();
        int highestY = Integer.MIN_VALUE;

        for (ShipBlockData data : originalBlocks) {
            Vector offset = data.getRelativeOffset();
            int dx = (int) Math.round(offset.getX() * cos - offset.getZ() * sin);
            int dz = (int) Math.round(offset.getX() * sin + offset.getZ() * cos);
            int dy = (int) Math.round(offset.getY());

            int bx = gridAnchor.getBlockX() + dx;
            int by = gridAnchor.getBlockY() + dy;
            int bz = gridAnchor.getBlockZ() + dz;

            if (bx == px && bz == pz) {
                Material mat = data.getBlockData().getMaterial();
                if (mat.isSolid() && by > highestY) {
                    highestY = by;
                }
            }
        }

        if (highestY != Integer.MIN_VALUE) {
            return highestY + 1.0;
        }
        return Math.floor(pilotLoc.getY()) + 1.0;
    }

    private void applyContainerData(Block block, BlockState snapshot, ItemStack[] items) {
        if (items != null) {
            BlockState fresh = block.getState();
            if (fresh instanceof Container container) {
                Inventory inv = getContainerInventory(container);
                // полная перезапись
                ItemStack[] fill = new ItemStack[inv.getSize()];
                for (int i = 0; i < Math.min(items.length, fill.length); i++) {
                    if (items[i] != null) {
                        fill[i] = items[i].clone();
                    }
                }
                inv.setContents(fill);
                container.update(true, false);
            }
        }

        if (snapshot instanceof org.bukkit.block.Lectern oldLectern) {
            if (oldLectern.getPersistentDataContainer().has(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE)) {
                if (block.getState() instanceof org.bukkit.block.Lectern newLectern) {
                    newLectern.getPersistentDataContainer().set(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE, (byte) 1);
                    newLectern.update(true, false);
                }
            }
        }
    }

    private static Inventory getContainerInventory(Container container) {
        if (container instanceof org.bukkit.block.Chest chest) {
            return chest.getBlockInventory();
        }
        return container.getInventory();
    }

    private static ItemStack[] deepCopyItems(Inventory inv) {
        ItemStack[] copy = new ItemStack[inv.getSize()];
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && !stack.getType().isAir()) {
                copy[i] = stack.clone();
            }
        }
        return copy;
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
