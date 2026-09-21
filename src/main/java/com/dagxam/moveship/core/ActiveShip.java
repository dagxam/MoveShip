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
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ActiveShip {

    private final Player pilot;
    private final JavaPlugin plugin;

    /**
     * Отдельный невидимый root только для игрока.
     *
     * BlockDisplay больше НЕ являются его пассажирами.
     * Это важно: поворот корпуса больше не может менять локальную посадку игрока
     * через цепочку пассажиров.
     */
    private final ArmorStand rootEntity;

    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay> displayEntities = new ArrayList<>();
    private final List<Location> displayLocations = new ArrayList<>();
    private final List<Matrix4f> displayMatrices = new ArrayList<>();

    /*
     * Скорость в блоках за тик.
     * Управление сделано через target -> current, поэтому изменение скорости
     * не имеет резких ступенек.
     */
    private static final double MAX_FWD = 0.40;
    private static final double MAX_BACK = 0.15;
    private static final double SPEED_RESPONSE = 0.22;
    private static final double BRAKE_RESPONSE = 0.18;

    /*
     * Угловая скорость в градусах за тик.
     * 2.0°/tick = 40°/сек на максимуме.
     */
    private static final float TURN_MAX = 2.0f;
    private static final float TURN_RESPONSE = 0.15f;

    private boolean kFwd;
    private boolean kBack;
    private boolean kLeft;
    private boolean kRight;

    /**
     * Физический центр корабля.
     *
     * X/Z — центр исходного блока ядра.
     * Y — нижняя координата исходного блока ядра.
     */
    private Location anchorCenter;

    private float shipYaw;
    private final float initialYaw;

    private double currentSpeed;
    private float currentTurn;

    private BukkitTask task;

    /**
     * Координаты клеток, которые были частью погруженной части корабля.
     * После удаления физических блоков они сразу заменяются водой, а затем
     * бывшие клетки корабля остаются заполненными водой как кильватерный след.
     */
    private final Set<BP> submergedWake = new HashSet<>();

    private record BP(int x, int y, int z) {
    }

    /**
     * Положение игрока относительно центра корабля в момент старта.
     * Этот offset вращается вместе с корпусом, поэтому игрок остается
     * прикрепленным к тому же месту штурвала.
     */
    private final double seatLocalX;
    private final double seatLocalY;
    private final double seatLocalZ;

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
        if (pilot == null || !pilot.isOnline()) {
            throw new IllegalArgumentException("Пилот недоступен");
        }

        this.pilot = pilot;
        this.plugin = JavaPlugin.getPlugin(com.dagxam.moveship.MoveShipPlugin.class);

        this.anchorCenter = anchorLocation.getBlock().getLocation().add(0.5, 0.0, 0.5);
        this.anchorCenter.setWorld(anchorLocation.getWorld());

        Location pilotStart = pilot.getLocation().clone();

        this.shipYaw = pilotStart.getYaw();
        this.initialYaw = this.shipYaw;

        /*
         * Самое важное для посадки:
         * сохраняем точный offset игрока от центра корабля.
         *
         * При повороте не телепортируем игрока в "новый центр".
         * Вместо этого двигаем только его seat-root по вращённому offset.
         */
        this.seatLocalX = pilotStart.getX() - anchorCenter.getX();
        this.seatLocalY = pilotStart.getY() - anchorCenter.getY();
        this.seatLocalZ = pilotStart.getZ() - anchorCenter.getZ();

        /*
         * До удаления блоков вычисляем клетки под ватерлинией, которые будут
         * освобождены кораблем. Они должны сразу стать водой, иначе после
         * активации на поверхности остается пустой "котлован".
         */
        captureSubmergedWake(blocks);

        /*
         * КРИТИЧНО ДЛЯ СОХРАННОСТИ:
         * сначала снимаем полный snapshot ВСЕХ блоков и TileState,
         * только после этого удаляем блоки из мира.
         */
        for (Block block : blocks) {
            if (block.getWorld() != anchorLocation.getWorld()) {
                throw new IllegalArgumentException(
                        "Все блоки корабля должны находиться в одном мире"
                );
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
         * После snapshot мир очищается.
         * Инвентари отдельно не переносятся: их состояние находится
         * в сохраненном BlockState.
         */
        for (Block block : blocks) {
            if (block.getState() instanceof Container container) {
                container.getInventory().clear();
            }
            block.setType(Material.AIR, false);
        }

        // Сразу закрываем оставшиеся на воде отверстия от корпуса.
        fillInitialWater();

        /*
         * Root только для посадки игрока.
         * Marker ArmorStand не имеет обычной видимой геометрии/хитбокса.
         * Он всегда смотрит на yaw=0 и не вращается вместе с кораблем.
         */
        this.rootEntity = anchorLocation.getWorld().spawn(
                pilotStart,
                ArmorStand.class,
                entity -> {
                    entity.setInvisible(true);
                    entity.setInvulnerable(true);
                    entity.setGravity(false);
                    entity.setMarker(true);
                    entity.setSmall(true);
                    entity.setBasePlate(false);
                    entity.setPersistent(false);
                    entity.setSilent(true);
                    entity.setRotation(0.0f, 0.0f);
                }
        );

        if (!rootEntity.addPassenger(pilot)) {
            rootEntity.remove();
            throw new IllegalStateException("Не удалось посадить игрока на штурвал");
        }

        /*
         * Каждый BlockDisplay управляется непосредственно.
         *
         * setTeleportDuration(1):
         * клиент интерполирует переход между двумя серверными позициями
         * за один тик. Это дает непрерывное движение без мгновенных скачков.
         *
         * Матрица имеет interpolationDuration=1 для плавного поворота самого блока.
         */
        for (ShipBlockData block : originalBlocks) {
            Location initialLocation = blockWorldLocation(block, anchorCenter, 0.0f);
            Matrix4f matrix = createBlockMatrix(0.0f);

            BlockDisplay display = anchorLocation.getWorld().spawn(
                    initialLocation,
                    BlockDisplay.class,
                    entity -> {
                        entity.setBlock(block.getBlockData().clone());
                        entity.setPersistent(false);
                        entity.setTeleportDuration(0);
                        entity.setInterpolationDelay(0);
                        entity.setInterpolationDuration(3);
                    }
            );

            displayEntities.add(display);
            displayLocations.add(initialLocation);
            displayMatrices.add(matrix);
            display.setTransformationMatrix(matrix);
        }

        this.task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tick,
                1L,
                1L
        );
    }

    public void setInput(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right
    ) {
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

        /*
         * Если игрок каким-либо другим плагином/механикой оказался не на root,
         * считаем это выходом со штурвала.
         *
         * EntityDismountEvent обычно обработает это раньше, но проверка здесь
         * закрывает случаи, когда транспортирование произошло нестандартно.
         */
        if (pilot.getVehicle() != rootEntity) {
            restoreBlocks();
            return;
        }

        updatePhysics();

        float nextYaw = shipYaw + currentTurn;
        boolean rotated = Math.abs(currentTurn) > 0.00001f;
        boolean moved = false;

        if (rotated) {
            nextYaw = norm(nextYaw);

            /*
             * При невозможности поворота не обнуляем управление навсегда.
             * Угловая скорость мягко гасится и на следующем тике снова
             * вычисляется из input.
             */
            if (canTransform(anchorCenter, nextYaw)) {
                shipYaw = nextYaw;
                moved = true;
            } else {
                currentTurn *= 0.35f;
                rotated = false;
            }
        }

        if (Math.abs(currentSpeed) > 0.0001) {
            Vector direction = yawDir(shipYaw);
            Location next = anchorCenter.clone().add(direction.multiply(currentSpeed));

            if (canTransform(next, shipYaw)) {
                anchorCenter = next;
                moved = true;
            } else {
                /*
                 * Не делаем жесткий обрыв "скорость = 0":
                 * корабль тормозит плавно, чтобы не было рывка у корпуса.
                 */
                currentSpeed *= 0.25;
            }
        }

        /*
         * Даже когда ship почти остановился, один раз поддерживаем точное
         * положение игрока на штурвале. Это предотвращает накопление
         * микросмещения пассажира.
         */
        /*
         * Штурвал поддерживается каждый тик, даже в покое.
         * Это не даёт игроку накапливать микросмещение от физики/плагинов.
         */
        updateSeat();

        if (moved) {
            /*
             * При прямом движении используется только teleport-интерполяция.
             * Transformation меняется только когда действительно изменился
             * угол корпуса. Это не сбивает клиентскую интерполяцию движения.
             */
            updateDisplays(rotated);
            fillWater();
        }

    }

    private void updatePhysics() {
        double targetSpeed = 0.0;

        if (kFwd && !kBack) {
            targetSpeed = MAX_FWD;
        } else if (kBack && !kFwd) {
            targetSpeed = -MAX_BACK;
        }

        /*
         * Экспоненциально-подобное приближение к целевой скорости.
         * В отличие от старого +ACCEL/-ACCEL оно значительно мягче
         * при отпускании клавиши и при переключении вперед/назад.
         */
        currentSpeed = approach(
                currentSpeed,
                targetSpeed,
                Math.abs(targetSpeed) < Math.abs(currentSpeed)
                        ? BRAKE_RESPONSE
                        : SPEED_RESPONSE
        );

        float targetTurn = 0.0f;

        if (kLeft && !kRight) {
            targetTurn = -TURN_MAX;
        } else if (kRight && !kLeft) {
            targetTurn = TURN_MAX;
        }

        currentTurn = (float) approach(
                currentTurn,
                targetTurn,
                TURN_RESPONSE
        );

        if (Math.abs(currentSpeed) < 0.0005) {
            currentSpeed = 0.0;
        }

        if (Math.abs(currentTurn) < 0.005f) {
            currentTurn = 0.0f;
        }
    }

    private static double approach(double current, double target, double response) {
        return current + (target - current) * response;
    }

    /**
     * Поддерживает пассажирскую точку на том же месте корабля.
     *
     * Игрок не переводится в world-coordinate напрямую:
     * сначала поворачиваем его локальный offset вокруг anchorCenter.
     */
    /**
     * Жестко удерживает XYZ пилота на рассчитанной точке штурвала,
     * но полностью сохраняет yaw/pitch, пришедшие от мыши.
     *
     * Благодаря этому вращение камеры не вращает корабль и не уводит
     * самого игрока с посадочного места.
     */
    public void constrainPilotMove(PlayerMoveEvent event) {
        if (event.getPlayer() != pilot || pilot.getVehicle() != rootEntity) {
            return;
        }

        Location to = event.getTo();
        if (to == null) {
            return;
        }

        double delta = Math.toRadians(shipYaw - initialYaw);
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        double seatX = anchorCenter.getX()
                + seatLocalX * cos
                - seatLocalZ * sin;
        double seatY = anchorCenter.getY() + seatLocalY;
        double seatZ = anchorCenter.getZ()
                + seatLocalX * sin
                + seatLocalZ * cos;

        if (Math.abs(to.getX() - seatX) > 0.001
                || Math.abs(to.getY() - seatY) > 0.001
                || Math.abs(to.getZ() - seatZ) > 0.001) {
            Location corrected = to.clone();
            corrected.setX(seatX);
            corrected.setY(seatY);
            corrected.setZ(seatZ);
            // Yaw и pitch намеренно НЕ меняем: это движение головы мышью.
            event.setTo(corrected);
        }
    }

    /**
     * Немедленно синхронизирует только направление взгляда пилота
     * с его посадочным root. Курс корабля здесь не используется.
     */
    public void updatePilotView(float yaw, float pitch) {
        if (pilot.getVehicle() != rootEntity) {
            return;
        }

        rootEntity.setRotation(yaw, 0.0f);
        pilot.setRotation(yaw, pitch);
    }

    private void updateSeat() {
        double delta = Math.toRadians(shipYaw - initialYaw);
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        double seatX =
                anchorCenter.getX()
                        + seatLocalX * cos
                        - seatLocalZ * sin;

        double seatY = anchorCenter.getY() + seatLocalY;

        double seatZ =
                anchorCenter.getZ()
                        + seatLocalX * sin
                        + seatLocalZ * cos;

        float pilotYaw = pilot.getLocation().getYaw();
        float pilotPitch = pilot.getLocation().getPitch();

        Location seat = rootEntity.getLocation();

        double dx = seatX - seat.getX();
        double dy = seatY - seat.getY();
        double dz = seatZ - seat.getZ();

        /*
         * ArmorStand не имеет Display-interpolation. Для игрока это особенно
         * важно: teleport() каждый тик дает заметный ступенчатый перенос камеры.
         *
         * Поэтому обычное движение штурвала выполняется через Entity velocity.
         * Velocity у Entity задается в блоках за тик и позволяет серверу вести
         * пассажира непрерывно вместе с машиной.
         *
         * Если каким-либо плагином/телепортом возник большой рассинхрон,
         * выполняем одну корректирующую телепортацию.
         */
        double errorSquared = dx * dx + dy * dy + dz * dz;

        if (errorSquared > 0.75 * 0.75) {
            seat.setX(seatX);
            seat.setY(seatY);
            seat.setZ(seatZ);
            seat.setYaw(0.0f);
            seat.setPitch(0.0f);

            rootEntity.teleport(seat);
            rootEntity.setVelocity(new Vector());
        } else {
            rootEntity.setVelocity(new Vector(dx, dy, dz));
        }

        rootEntity.setRotation(pilotYaw, 0.0f);
        preservePilotRotation(pilotYaw, pilotPitch);

        /*
         * Пока игрок является пассажиром штурвала, его собственное физическое
         * ускорение не должно сдвигать его относительно посадочного места.
         */
        pilot.setFallDistance(0.0f);
        pilot.setVelocity(new Vector());
    }

    /**
     * Обновляет визуальное состояние корабля только через Display
     * Transformation. Сам entity больше не телепортируется каждый тик.
     *
     * Это особенно важно для движения вперед/назад: клиент получает
     * последовательность гладких трансформаций вместо цепочки телепортов,
     * которые конкурируют между собой за интерполяцию.
     */
    private void updateDisplays(boolean rotated) {
        double delta = Math.toRadians(shipYaw - initialYaw);
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);
        float rotation = (float) -delta;

        for (int i = 0; i < originalBlocks.size(); i++) {
            BlockDisplay display = displayEntities.get(i);

            if (!display.isValid()) {
                continue;
            }

            ShipBlockData block = originalBlocks.get(i);
            Location initial = displayLocations.get(i);

            double centerX =
                    anchorCenter.getX()
                            + block.getLocalX() * cos
                            - block.getLocalZ() * sin;

            double centerY =
                    anchorCenter.getY()
                            + block.getLocalY()
                            + 0.5;

            double centerZ =
                    anchorCenter.getZ()
                            + block.getLocalX() * sin
                            + block.getLocalZ() * cos;

            double currentCornerX = centerX - 0.5;
            double currentCornerY = centerY - 0.5;
            double currentCornerZ = centerZ - 0.5;

            double tx = currentCornerX - initial.getX();
            double ty = currentCornerY - initial.getY();
            double tz = currentCornerZ - initial.getZ();

            Matrix4f matrix = displayMatrices.get(i);

            /*
             * Entity location остается неизменным.
             * Matrix переводит блок на новое мировое положение и вращает его
             * вокруг собственного центра.
             */
            matrix.identity()
                    .translate(
                            (float) (tx + 0.5),
                            (float) (ty + 0.5),
                            (float) (tz + 0.5)
                    )
                    .rotateY(rotation)
                    .translate(-0.5f, -0.5f, -0.5f);

            display.setInterpolationDelay(0);
            display.setInterpolationDuration(3);
            display.setTransformationMatrix(matrix);
        }
    }

    private static Matrix4f createBlockMatrix(float rotation) {
        return new Matrix4f()
                .translate(0.5f, 0.5f, 0.5f)
                .rotateY(rotation)
                .translate(-0.5f, -0.5f, -0.5f);
    }

    private static Location blockWorldLocation(
            ShipBlockData block,
            Location anchor,
            float deltaDegrees
    ) {
        double delta = Math.toRadians(deltaDegrees);
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        double centerX =
                anchor.getX()
                        + block.getLocalX() * cos
                        - block.getLocalZ() * sin;

        double centerY =
                anchor.getY()
                        + block.getLocalY()
                        + 0.5;

        double centerZ =
                anchor.getZ()
                        + block.getLocalX() * sin
                        + block.getLocalZ() * cos;

        return new Location(
                anchor.getWorld(),
                centerX - 0.5,
                centerY - 0.5,
                centerZ - 0.5
        );
    }

    /**
     * Определяет ватерлинию по соседним жидкостям и запоминает клетки
     * корпуса, которые находились в воде.
     */
    private void captureSubmergedWake(Set<Block> blocks) {
        int seaLevel = Integer.MIN_VALUE;

        for (Block block : blocks) {
            for (BlockFace face : new BlockFace[]{
                    BlockFace.NORTH,
                    BlockFace.SOUTH,
                    BlockFace.EAST,
                    BlockFace.WEST,
                    BlockFace.DOWN
            }) {
                Block neighbor = block.getRelative(face);
                if (blocks.contains(neighbor)) {
                    continue;
                }

                Material material = neighbor.getType();
                if (material == Material.WATER
                        || material == Material.BUBBLE_COLUMN
                        || material == Material.SEAGRASS
                        || material == Material.TALL_SEAGRASS
                        || material == Material.KELP) {
                    seaLevel = Math.max(seaLevel, neighbor.getY());
                }
            }
        }

        if (seaLevel == Integer.MIN_VALUE) {
            return;
        }

        for (Block block : blocks) {
            if (block.getY() <= seaLevel) {
                submergedWake.add(new BP(
                        block.getX(),
                        block.getY(),
                        block.getZ()
                ));
            }
        }
    }

    /**
     * Немедленно заменяет удаленные подводные клетки корабля источниками воды.
     * Physics=false исключает лавинообразное распространение воды на активации.
     */
    private void fillInitialWater() {
        if (submergedWake.isEmpty()) {
            return;
        }

        for (BP cell : submergedWake) {
            Block block = anchorCenter.getWorld().getBlockAt(
                    cell.x(),
                    cell.y(),
                    cell.z()
            );

            if (block.getType().isAir() || block.getType() == Material.WATER) {
                block.setType(Material.WATER, false);
            }
        }
    }

    /**
     * После движения закрывает старые клетки следа водой и не перезаписывает
     * блок, который уже успел поставить другой игрок/плагин.
     */
    private void fillWater() {
        if (submergedWake.isEmpty()) {
            return;
        }

        Set<BP> occupied = new HashSet<>();
        double delta = Math.toRadians(shipYaw - initialYaw);
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        for (ShipBlockData data : originalBlocks) {
            int x = floorToInt(
                    anchorCenter.getX()
                            + data.getLocalX() * cos
                            - data.getLocalZ() * sin
            );
            int y = floorToInt(
                    anchorCenter.getY()
                            + data.getLocalY()
            );
            int z = floorToInt(
                    anchorCenter.getZ()
                            + data.getLocalX() * sin
                            + data.getLocalZ() * cos
            );

            occupied.add(new BP(x, y, z));
        }

        submergedWake.removeIf(cell -> {
            if (occupied.contains(cell)) {
                return false;
            }

            Block block = anchorCenter.getWorld().getBlockAt(
                    cell.x(),
                    cell.y(),
                    cell.z()
            );

            if (block.getType().isAir()) {
                block.setType(Material.WATER, false);
            }

            return true;
        });
    }

    private void preservePilotRotation(float yaw, float pitch) {
        pilot.setRotation(yaw, pitch);
    }

    /**
     * Проверяет, можно ли кораблю занять новое положение/угол.
     *
     * Проверка остается серверной и выполняется до изменения anchorCenter,
     * поэтому визуальная интерполяция не сможет "протолкнуть" корабль
     * через занятый блок.
     */
    private boolean canTransform(Location target, float yaw) {
        if (target.getWorld() == null) {
            return false;
        }

        double delta = Math.toRadians(yaw - initialYaw);
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        for (ShipBlockData block : originalBlocks) {
            double rotatedX =
                    block.getLocalX() * cos
                            - block.getLocalZ() * sin;

            double rotatedZ =
                    block.getLocalX() * sin
                            + block.getLocalZ() * cos;

            int targetBlockX = floorToInt(target.getX() + rotatedX);
            int targetBlockY = target.getBlockY() + block.getLocalY();
            int targetBlockZ = floorToInt(target.getZ() + rotatedZ);

            Block worldBlock = target.getWorld().getBlockAt(
                    targetBlockX,
                    targetBlockY,
                    targetBlockZ
            );

            if (worldBlock.getType().isSolid()) {
                return false;
            }
        }

        return true;
    }

    public void restoreBlocks() {
        stopInternal();

        /*
         * Сохраняем реальное место игрока перед освобождением от root.
         * Ориентация камеры НЕ меняется на yaw корабля.
         */
        Location playerLocation = pilot.getLocation().clone();
        float playerYaw = playerLocation.getYaw();
        float playerPitch = playerLocation.getPitch();

        if (rootEntity.isValid()) {
            rootEntity.eject();
        }

        /*
         * Физическая сетка после остановки должна совпадать с Minecraft-grid.
         * Поэтому для финального восстановления используем ближайший угол 90°.
         */
        int snap = Math.floorMod(
                Math.round((shipYaw - initialYaw) / 90.0f) * 90,
                360
        );

        int rotations = snap / 90;

        double rad = Math.toRadians(snap);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        int originX = floorToInt(anchorCenter.getX());
        int originY = anchorCenter.getBlockY();
        int originZ = floorToInt(anchorCenter.getZ());

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

            Block target =
                    anchorCenter.getWorld().getBlockAt(x, y, z);

            BlockData restoredData = data.getBlockData().clone();
            rotateData(restoredData, rotations);

            entries.add(
                    new RestoreEntry(
                            target,
                            restoredData,
                            data.getStateSnapshot()
                    )
            );
        }

        /*
         * Сначала весь physical block layout.
         */
        for (RestoreEntry entry : entries) {
            BlockData data = entry.blockData();

            // Восстанавливаем waterlogged-состояние, если под блоком есть вода.
            if (entry.block().getType() == Material.WATER && data instanceof Waterlogged waterlogged) {
                waterlogged.setWaterlogged(true);
            }

            entry.block().setType(data.getMaterial(), false);
            entry.block().setBlockData(data, false);
        }

        /*
         * Затем TileState / inventories / PDC.
         */
        for (RestoreEntry entry : entries) {
            try {
                BlockState state =
                        entry.snapshot().copy(entry.block().getLocation());

                state.setBlockData(entry.blockData());
                state.update(true, false);
            } catch (Exception ex) {
                plugin.getLogger().warning(
                        "Не удалось восстановить BlockState на "
                                + entry.block().getLocation()
                                + ": "
                                + ex.getMessage()
                );
            }
        }

        /*
         * Игрок возвращается на корабль, сохраняя собственный yaw/pitch.
         */
        double safeY =
                findSafeDeckY(
                        originX,
                        originY,
                        originZ,
                        cos,
                        sin
                );

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
        displayLocations.clear();
        displayMatrices.clear();

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
                : pilot.getLocation().getY();
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

        if (yaw < 0.0f) {
            yaw += 360.0f;
        }

        return yaw;
    }

    private static Vector yawDir(float yaw) {
        double radians = Math.toRadians(yaw);

        return new Vector(
                -Math.sin(radians),
                0.0,
                Math.cos(radians)
        );
    }

    private void rotateData(BlockData data, int rotations) {
        if (rotations == 0) {
            return;
        }

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
                        ROT16.get(
                                Math.floorMod(
                                        index + rotations * 4,
                                        ROT16.size()
                                )
                        )
                );
            }

        } else if (data instanceof MultipleFacing multipleFacing) {
            Set<BlockFace> current =
                    new HashSet<>(multipleFacing.getFaces());

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
            Map<BlockFace, org.bukkit.block.data.type.Wall.Height> heights =
                    new HashMap<>();

            for (BlockFace face : new BlockFace[]{
                    BlockFace.NORTH,
                    BlockFace.EAST,
                    BlockFace.SOUTH,
                    BlockFace.WEST
            }) {
                heights.put(face, wall.getHeight(face));
            }

            for (Map.Entry<BlockFace, org.bukkit.block.data.type.Wall.Height> entry
                    : heights.entrySet()) {

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

            case NORTH_NORTH_EAST ->
                    BlockFace.EAST_NORTH_EAST;
            case EAST_NORTH_EAST ->
                    BlockFace.EAST_SOUTH_EAST;
            case EAST_SOUTH_EAST ->
                    BlockFace.SOUTH_SOUTH_EAST;
            case SOUTH_SOUTH_EAST ->
                    BlockFace.SOUTH_SOUTH_WEST;
            case SOUTH_SOUTH_WEST ->
                    BlockFace.WEST_SOUTH_WEST;
            case WEST_SOUTH_WEST ->
                    BlockFace.WEST_NORTH_WEST;
            case WEST_NORTH_WEST ->
                    BlockFace.NORTH_NORTH_WEST;
            case NORTH_NORTH_WEST ->
                    BlockFace.NORTH_NORTH_EAST;

            default -> face;
        };
    }

    public Player getPilot() {
        return pilot;
    }

    public ArmorStand getRootEntity() {
        return rootEntity;
    }

    private record RestoreEntry(
            Block block,
            BlockData blockData,
            BlockState snapshot
    ) {
    }
}
