package com.dagxam.moveship.core;

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
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ActiveShip {

    private final Player pilot;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final ArmorStand rootEntity;

    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay> displayEntities = new ArrayList<>();

    private static final double MAX_FWD = 0.40;
    private static final double MAX_BACK = 0.15;
    private static final double ACCEL = 0.025;
    private static final double DECEL = 0.88;

    private static final float TURN_MAX = 3.0f;
    private static final float TURN_ACCEL = 0.4f;
    private static final float TURN_DECEL = 0.78f;

    private boolean kFwd;
    private boolean kBack;
    private boolean kLeft;
    private boolean kRight;

    /**
     * Физическое положение опорной точки корабля.
     *
     * X/Z — центр исходного блока ядра.
     * Y — нижняя координата исходного блока ядра.
     */
    private Location anchorCenter;

    /**
     * Начальный yaw определяет исходное направление корпуса.
     * Визуально корабль начинает с delta=0, поэтому внешний вид не зависит
     * от того, куда смотрел игрок в момент активации.
     */
    private float shipYaw;
    private final float initialYaw;

    private double currentSpeed;
    private float currentTurn;

    private BukkitTask task;

    /**
     * Визуальный pivot игрока/корабля. Root не вращается.
     * Это намеренно: игрок должен оставаться на месте относительно штурвала
     * и не получать принудительный поворот камеры при повороте корабля.
     */
    private final double rootYOffset;

    private static final List<BlockFace> ROT16 = List.of(
            BlockFace.NORTH,
            BlockFace.NORTH_NORTH_EAST,
            BlockFace.NORTH_EAST,
            BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST,
            BlockFace.EAST_SOUTH_EAST,
            BlockFace.SOUTH_EAST,
            BlockFace.SOUTH_SOUTH_EAST,
            BlockFace.SOUTH,
            BlockFace.SOUTH_SOUTH_WEST,
            BlockFace.SOUTH_WEST,
            BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST,
            BlockFace.WEST_NORTH_WEST,
            BlockFace.NORTH_WEST,
            BlockFace.NORTH_NORTH_WEST
    );

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("Корабль не содержит блоков");
        }
        if (anchorLocation == null || anchorLocation.getWorld() == null) {
            throw new IllegalArgumentException("У корабля отсутствует мир");
        }

        this.pilot = pilot;
        this.plugin = JavaPlugin.getPlugin(com.dagxam.moveship.MoveShipPlugin.class);

        this.anchorCenter = anchorLocation.getBlock().getLocation().add(0.5, 0.0, 0.5);
        this.anchorCenter.setWorld(anchorLocation.getWorld());

        this.shipYaw = pilot.getLocation().getYaw();
        this.initialYaw = this.shipYaw;

        Block blockUnder = pilot.getLocation().getBlock().getRelative(BlockFace.DOWN);
        double activationBlockY = blockUnder.getY() + 1.0;

        /*
         * Root стоит там, где раньше стоял отдельный seatEntity.
         * Он не вращается. Игрок становится его пассажиром и поэтому
         * перемещается вместе с кораблем, не "ездя" сам по себе при повороте.
         */
        this.rootYOffset = activationBlockY - anchorCenter.getY();

        /*
         * КРИТИЧНО ДЛЯ СОХРАННОСТИ:
         * СНАЧАЛА снимаем снимок ВСЕХ блоков, включая TileState,
         * и только ПОСЛЕ этого очищаем контейнеры и удаляем блоки из мира.
         *
         * Это особенно важно для двойных сундуков: второй snapshot нельзя
         * получать после того, как первый контейнер уже был очищен.
         */
        for (Block block : blocks) {
            if (block.getWorld() != anchorLocation.getWorld()) {
                throw new IllegalArgumentException("Все блоки корабля должны находиться в одном мире");
            }

            int localX = block.getX() - anchorLocation.getBlockX();
            int localY = block.getY() - anchorLocation.getBlockY();
            int localZ = block.getZ() - anchorLocation.getBlockZ();

            BlockData blockData = block.getBlockData().clone();
            BlockState snapshot = block.getState(true);

            originalBlocks.add(
                    new ShipBlockData(
                            localX,
                            localY,
                            localZ,
                            blockData,
                            snapshot
                    )
            );
        }

        /*
         * Теперь мир можно очищать. Мы не полагаемся на выпадение предметов:
         * содержимое уже находится внутри сохраненного BlockState snapshot.
         */
        for (Block block : blocks) {
            if (block.getState() instanceof Container container) {
                container.getInventory().clear();
            }
            block.setType(Material.AIR, false);
        }

        Location rootLocation = getRootLocation();
        this.rootEntity = rootLocation.getWorld().spawn(rootLocation, ArmorStand.class, entity -> {
            entity.setInvisible(true);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setMarker(true);
            entity.setSmall(true);
            entity.setBasePlate(false);
            entity.setPersistent(false);
            entity.setSilent(true);
            entity.setRotation(0.0f, 0.0f);
        });

        /*
         * Все BlockDisplay являются пассажирами rootEntity.
         *
         * При прямом движении мы теперь телепортируем только rootEntity.
         * Поэтому 1000 блоков корабля не превращаются в 1000 teleport()
         * вызовов каждый тик.
         */
        for (ShipBlockData block : originalBlocks) {
            BlockDisplay display = rootLocation.getWorld().spawn(
                    rootLocation,
                    BlockDisplay.class,
                    entity -> {
                        entity.setBlock(block.getBlockData().clone());
                        entity.setPersistent(false);

                        // Движение выполняет rootEntity, поэтому отдельная
                        // teleport-интерполяция display здесь не нужна.
                        entity.setTeleportDuration(0);

                        entity.setInterpolationDelay(0);
                        entity.setInterpolationDuration(1);
                    }
            );

            rootEntity.addPassenger(display);
            displayEntities.add(display);
        }

        /*
         * Игрок тоже пассажир rootEntity.
         * Y root выбран так, чтобы использовать ту же высоту, которая
         * раньше применялась seatEntity.
         */
        rootEntity.addPassenger(pilot);

        // Первичная отрисовка с delta=0.
        updateDisplayTransforms(0.0f);

        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void setInput(boolean forward, boolean backward, boolean left, boolean right) {
        this.kFwd = forward;
        this.kBack = backward;
        this.kLeft = left;
        this.kRight = right;
    }

    private void tick() {
        if (!pilot.isOnline() || !rootEntity.isValid()) {
            restoreBlocks();
            return;
        }

        updatePhysics();

        boolean moved = false;
        boolean rotated = false;

        if (Math.abs(currentTurn) > 0.0001f) {
            float nextYaw = norm(shipYaw + currentTurn);

            if (canTransform(anchorCenter, nextYaw)) {
                shipYaw = nextYaw;
                moved = true;
                rotated = true;
            } else {
                currentTurn = 0.0f;
                currentSpeed *= 0.5;
            }
        }

        if (Math.abs(currentSpeed) > 0.001) {
            Vector direction = yawDir(shipYaw).multiply(currentSpeed);
            Location next = anchorCenter.clone().add(direction);

            if (canTransform(next, shipYaw)) {
                anchorCenter = next;
                moved = true;
            } else {
                currentSpeed = 0.0;
            }
        }

        if (!moved) {
            return;
        }

        /*
         * Один teleport на root вместо teleport() для каждого BlockDisplay.
         * Root yaw намеренно ВСЕГДА 0: корабль вращается визуально через
         * transformation, а игрок не вращается вместе с корпусом.
         */
        Location rootTarget = getRootLocation();
        rootTarget.setYaw(0.0f);
        rootTarget.setPitch(0.0f);

        if (!rootEntity.teleport(rootTarget)) {
            restoreBlocks();
            return;
        }

        if (rotated) {
            float delta = shipYaw - initialYaw;
            updateDisplayTransforms(delta);
        }
    }

    private void updatePhysics() {
        if (kFwd && !kBack) {
            currentSpeed = Math.min(MAX_FWD, currentSpeed + ACCEL);
        } else if (kBack && !kFwd) {
            currentSpeed = Math.max(-MAX_BACK, currentSpeed - ACCEL);
        } else {
            currentSpeed *= DECEL;
            if (Math.abs(currentSpeed) < 0.001) {
                currentSpeed = 0.0;
            }
        }

        if (kLeft && !kRight) {
            currentTurn = Math.max(-TURN_MAX, currentTurn - TURN_ACCEL);
        } else if (kRight && !kLeft) {
            currentTurn = Math.min(TURN_MAX, currentTurn + TURN_ACCEL);
        } else {
            currentTurn *= TURN_DECEL;
            if (Math.abs(currentTurn) < 0.02f) {
                currentTurn = 0.0f;
            }
        }
    }

    /**
     * Проверяет положение/поворот всего occupied-профиля корабля.
     *
     * В отличие от старого canMoveTo() здесь координаты заранее хранятся
     * как целые локальные клетки. Это убирает лишние Vector/Location allocations
     * и делает проверку детерминированной.
     */
    private boolean canTransform(Location target, float yaw) {
        if (target.getWorld() == null) {
            return false;
        }

        float delta = yaw - initialYaw;
        double rad = Math.toRadians(delta);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        int targetBlockX;
        int targetBlockZ;

        for (ShipBlockData block : originalBlocks) {
            double rotatedX = block.getLocalX() * cos - block.getLocalZ() * sin;
            double rotatedZ = block.getLocalX() * sin + block.getLocalZ() * cos;

            targetBlockX = floorToInt(target.getX() + rotatedX);
            int targetY = target.getBlockY() + block.getLocalY();
            targetBlockZ = floorToInt(target.getZ() + rotatedZ);

            Block worldBlock = target.getWorld().getBlockAt(
                    targetBlockX,
                    targetY,
                    targetBlockZ
            );

            if (worldBlock.getType().isSolid()) {
                return false;
            }
        }

        return true;
    }

    private void updateDisplayTransforms(float delta) {
        double rad = Math.toRadians(delta);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        float rotation = (float) Math.toRadians(-delta);

        for (int i = 0; i < originalBlocks.size(); i++) {
            BlockDisplay display = displayEntities.get(i);

            if (!display.isValid()) {
                continue;
            }

            ShipBlockData block = originalBlocks.get(i);

            /*
             * Центр блока относительно root:
             * X/Z — локальные координаты относительно центра блока ядра.
             * Y — root находится на 0.5 блока ниже исходной отметки ядра.
             */
            float centerX = (float) (
                    block.getLocalX() * cos
                            - block.getLocalZ() * sin
            );

            float centerY = block.getLocalY() + 1.0f;

            float centerZ = (float) (
                    block.getLocalX() * sin
                            + block.getLocalZ() * cos
            );

            /*
             * Полная матрица:
             *
             * T(центр блока) · R(shipYaw) · T(-0.5,-0.5,-0.5)
             *
             * Это вращает сам куб вокруг его центра и одновременно
             * переносит его вокруг центра корабля.
             */
            Matrix4f matrix = new Matrix4f()
                    .translate(centerX, centerY, centerZ)
                    .rotateY(rotation)
                    .translate(-0.5f, -0.5f, -0.5f);

            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTransformationMatrix(matrix);
        }
    }

    public void restoreBlocks() {
        stopInternal();

        /*
         * Сначала освобождаем игрока. Root больше не вращается,
         * поэтому его текущая ориентация игрока не должна неожиданно
         * переключаться на yaw корабля.
         */
        Location playerLocation = rootEntity.getLocation().clone();
        float playerYaw = pilot.getLocation().getYaw();
        float playerPitch = pilot.getLocation().getPitch();

        if (rootEntity.isValid()) {
            rootEntity.eject();
        }

        /*
         * Конечная мировая сетка. Блоки физически восстанавливаются
         * только по целым клеткам; визуальный yaw допускается свободный.
         * Выбираем ближайшие 90°, чтобы BlockData Vanilla корректно совпадали
         * с реальной сеткой.
         */
        int snap = Math.floorMod(Math.round((shipYaw - initialYaw) / 90.0f) * 90, 360);
        int rotations = snap / 90;

        double rad = Math.toRadians(snap);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        int originX = floorToInt(anchorCenter.getX());
        int originY = anchorCenter.getBlockY();
        int originZ = floorToInt(anchorCenter.getZ());

        /*
         * Сначала ставим ВСЕ физические блоки и только после этого
         * восстанавливаем BlockState. Это особенно важно для двойных
         * сундуков и других TileState.
         */
        List<RestoreEntry> entries = new ArrayList<>(originalBlocks.size());

        for (ShipBlockData data : originalBlocks) {
            int dx = (int) Math.round(
                    data.getLocalX() * cos
                            - data.getLocalZ() * sin
            );

            int dz = (int) Math.round(
                    data.getLocalX() * sin
                            + data.getLocalZ() * cos
            );

            int x = originX + dx;
            int y = originY + data.getLocalY();
            int z = originZ + dz;

            Block target = anchorCenter.getWorld().getBlockAt(x, y, z);

            BlockData restoredData = data.getBlockData().clone();
            rotateData(restoredData, rotations);

            entries.add(new RestoreEntry(
                    target,
                    restoredData,
                    data.getStateSnapshot()
            ));
        }

        for (RestoreEntry entry : entries) {
            entry.block().setType(entry.blockData().getMaterial(), false);
            entry.block().setBlockData(entry.blockData(), false);
        }

        for (RestoreEntry entry : entries) {
            try {
                /*
                 * copy(Location) переносит сохранённое состояние TileState
                 * на новую мировую координату. Благодаря этому нам не нужно
                 * вручную восстанавливать только Inventory: сохраняется
                 * полный BlockState, включая данные контейнера и PDC.
                 */
                BlockState state = entry.snapshot().copy(entry.block().getLocation());
                state.setBlockData(entry.blockData());
                state.update(true, false);
            } catch (Exception ex) {
                plugin.getLogger().warning(
                        "Не удалось восстановить BlockState на "
                                + entry.block().getLocation() + ": "
                                + ex.getMessage()
                );
            }
        }

        /*
         * Возвращаем игрока на палубу.
         */
        double safeY = findSafeDeckY(originX, originY, originZ, cos, sin);

        Location land = playerLocation.clone();
        land.setX(anchorCenter.getX());
        land.setZ(anchorCenter.getZ());
        land.setY(safeY);
        land.setYaw(playerYaw);
        land.setPitch(playerPitch);

        pilot.teleport(land);

        for (BlockDisplay display : displayEntities) {
            if (display.isValid()) {
                display.remove();
            }
        }
        displayEntities.clear();

        if (rootEntity.isValid()) {
            rootEntity.remove();
        }
    }

    private double findSafeDeckY(
            int originX,
            int originY,
            int originZ,
            double cos,
            double sin
    ) {
        int playerX = floorToInt(anchorCenter.getX());
        int playerZ = floorToInt(anchorCenter.getZ());
        int top = Integer.MIN_VALUE;

        for (ShipBlockData data : originalBlocks) {
            int dx = (int) Math.round(
                    data.getLocalX() * cos
                            - data.getLocalZ() * sin
            );
            int dz = (int) Math.round(
                    data.getLocalX() * sin
                            + data.getLocalZ() * cos
            );

            int x = originX + dx;
            int y = originY + data.getLocalY();
            int z = originZ + dz;

            if (x == playerX
                    && z == playerZ
                    && data.getBlockData().getMaterial().isSolid()
                    && y > top) {
                top = y;
            }
        }

        return top != Integer.MIN_VALUE
                ? top + 1.0
                : Math.floor(playerLocationY()) + 1.0;
    }

    private double playerLocationY() {
        return pilot.getLocation().getY();
    }

    private Location getRootLocation() {
        Location root = anchorCenter.clone();
        root.setY(anchorCenter.getY() + rootYOffset);
        root.setYaw(0.0f);
        root.setPitch(0.0f);
        return root;
    }

    private void stopInternal() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }

    private static float norm(float yaw) {
        yaw %= 360.0f;
        return yaw < 0.0f ? yaw + 360.0f : yaw;
    }

    private static Vector yawDir(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0.0, Math.cos(r));
    }

    private void rotateData(BlockData data, int rotations) {
        if (data instanceof Directional directional) {
            BlockFace face = directional.getFacing();

            for (int i = 0; i < rotations; i++) {
                face = clockwise(face);
            }

            if (directional.getFaces().contains(face)) {
                directional.setFacing(face);
            }

        } else if (data instanceof Orientable orientable) {
            if (rotations % 2 != 0) {
                if (orientable.getAxis() == Axis.X) {
                    orientable.setAxis(Axis.Z);
                } else if (orientable.getAxis() == Axis.Z) {
                    orientable.setAxis(Axis.X);
                }
            }

        } else if (data instanceof Rotatable rotatable) {
            int index = ROT16.indexOf(rotatable.getRotation());

            if (index >= 0) {
                rotatable.setRotation(
                        ROT16.get(Math.floorMod(index + rotations * 4, ROT16.size()))
                );
            }

        } else if (data instanceof MultipleFacing multipleFacing) {
            Set<BlockFace> current = new HashSet<>(multipleFacing.getFaces());

            for (BlockFace face : multipleFacing.getAllowedFaces()) {
                multipleFacing.setFace(face, false);
            }

            for (BlockFace face : current) {
                BlockFace rotated = face;

                for (int i = 0; i < rotations; i++) {
                    rotated = clockwise(rotated);
                }

                if (multipleFacing.getAllowedFaces().contains(rotated)) {
                    multipleFacing.setFace(rotated, true);
                }
            }

        } else if (data instanceof org.bukkit.block.data.type.Wall wall) {
            Map<BlockFace, org.bukkit.block.data.type.Wall.Height> heights = new HashMap<>();

            for (BlockFace face : new BlockFace[]{
                    BlockFace.NORTH,
                    BlockFace.EAST,
                    BlockFace.SOUTH,
                    BlockFace.WEST
            }) {
                heights.put(face, wall.getHeight(face));
            }

            for (Map.Entry<BlockFace, org.bukkit.block.data.type.Wall.Height> entry : heights.entrySet()) {
                BlockFace rotated = entry.getKey();

                for (int i = 0; i < rotations; i++) {
                    rotated = clockwise(rotated);
                }

                wall.setHeight(rotated, entry.getValue());
            }
        }
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;

            case NORTH_EAST -> BlockFace.SOUTH_EAST;
            case SOUTH_EAST -> BlockFace.SOUTH_WEST;
            case SOUTH_WEST -> BlockFace.NORTH_WEST;
            case NORTH_WEST -> BlockFace.NORTH_EAST;

            case NORTH_NORTH_EAST -> BlockFace.EAST_NORTH_EAST;
            case EAST_NORTH_EAST -> BlockFace.EAST_SOUTH_EAST;
            case EAST_SOUTH_EAST -> BlockFace.SOUTH_SOUTH_EAST;
            case SOUTH_SOUTH_EAST -> BlockFace.SOUTH_SOUTH_WEST;
            case SOUTH_SOUTH_WEST -> BlockFace.WEST_SOUTH_WEST;
            case WEST_SOUTH_WEST -> BlockFace.WEST_NORTH_WEST;
            case WEST_NORTH_WEST -> BlockFace.NORTH_NORTH_WEST;
            case NORTH_NORTH_WEST -> BlockFace.NORTH_NORTH_EAST;

            default -> face;
        };
    }

    public Player getPilot() {
        return pilot;
    }

    private record RestoreEntry(
            Block block,
            BlockData blockData,
            BlockState snapshot
    ) {
    }
}
