package com.dagxam.moveship.core;

import com.dagxam.moveship.MoveShipPlugin;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Boat;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Активный корабль.
 *
 * После активации реальные блоки временно снимаются из мира и отображаются
 * через BlockDisplay, а скрытая стандартная Boat используется как настоящий
 * Minecraft Vehicle для движения и пассажира.
 *
 * Серверная ShipCollision отдельно описывает реальную форму построенного
 * корабля и не зависит от маленькой штатной коллизии Boat.
 */
public class ActiveShip {

    private final Player pilot;
    private final MoveShipPlugin plugin;

    /**
     * Настоящий Minecraft Vehicle.
     * Игрок сидит непосредственно в Boat.
     */
    private final Boat carrier;

    /**
     * Точная collision-модель исходного корабля.
     */
    private final ShipCollision collisionModel;

    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay> displayEntities = new ArrayList<>();
    private final List<Location> displayLocations = new ArrayList<>();
    private final List<Matrix4f> displayMatrices = new ArrayList<>();

    /**
     * Геометрический anchor корабля остается локально привязанным
     * к точке Boat, а не к мировым координатам старта.
     */
    private final double carrierLocalAnchorX;
    private final double carrierLocalAnchorY;
    private final double carrierLocalAnchorZ;

    private Location anchorCenter;

    /**
     * Курс корабля берется от Boat.
     * Мышь игрока здесь вообще не используется.
     */
    private float shipYaw;
    private final float initialYaw;

    private boolean kForward;
    private boolean kBackward;
    private boolean kLeft;
    private boolean kRight;

    private float pilotYaw;
    private float pilotPitch;

    private static final double CARRIER_MAX_FORWARD = 0.38;
    private static final double CARRIER_MAX_BACKWARD = 0.16;
    private static final double CARRIER_ACCELERATION = 0.12;
    private static final double CARRIER_BRAKE = 0.22;
    private static final float CARRIER_TURN_SPEED = 1.6f;

    private Location lastCarrierLocation;

    /**
     * Защищает от повторной обработки VehicleMoveEvent после corrective teleport.
     */
    private boolean correctingCarrier;

    private BukkitTask task;

    /**
     * Координаты подводной части корпуса, которые должны оставаться водой
     * после удаления физических блоков и после прохождения корабля.
     */
    private final Set<BP> submergedWake = new HashSet<>();

    private record BP(int x, int y, int z) {
    }

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

    public ActiveShip(
            Set<Block> blocks,
            Location anchorLocation,
            Player pilot
    ) {
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
        this.plugin = JavaPlugin.getPlugin(MoveShipPlugin.class);

        this.anchorCenter = anchorLocation
                .getBlock()
                .getLocation()
                .add(0.5, 0.0, 0.5);

        Location pilotStart = pilot.getLocation().clone();

        this.shipYaw = norm(pilotStart.getYaw());
        this.initialYaw = this.shipYaw;
        this.pilotYaw = pilotStart.getYaw();
        this.pilotPitch = pilotStart.getPitch();

        /*
         * После появления Boat она будет двигать весь корабль вместе с собой.
         * Поэтому сохраняем положение исходного anchor относительно Boat.
         */
        this.carrierLocalAnchorX =
                anchorCenter.getX() - pilotStart.getX();
        this.carrierLocalAnchorY =
                anchorCenter.getY() - pilotStart.getY();
        this.carrierLocalAnchorZ =
                anchorCenter.getZ() - pilotStart.getZ();

        /*
         * Вода определяется до удаления исходных блоков.
         */
        captureSubmergedWake(blocks);

        /*
         * Сначала полный snapshot ВСЕХ блоков.
         * Только после этого мир очищается.
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
         * ShipCollision строится пока реальные блоки еще доступны.
         */
        this.collisionModel = new ShipCollision(
                originalBlocks,
                anchorCenter,
                initialYaw
        );

        /*
         * Удаляем физические блоки мира.
         * Состояние уже сохранено выше.
         */
        for (Block block : blocks) {
            block.setType(Material.AIR, false);
        }

        /*
         * Сразу закрываем подводную часть водой.
         */
        fillInitialWater();

        /*
         * Создаем скрытую стандартную OakBoat.
         *
         * Именно Boat теперь является Vehicle и получает штатную
         * обработку пассажира/движения Minecraft.
         */
        Entity entity = anchorLocation.getWorld().spawnEntity(
                pilotStart,
                EntityType.OAK_BOAT
        );

        if (!(entity instanceof Boat boat)) {
            entity.remove();
            throw new IllegalStateException(
                    "Paper не смог создать OakBoat для корабля"
            );
        }

        this.carrier = boat;

        /*
         * Boat остается настоящим серверным entity, но ее собственную
         * модель не показываем как часть корабля.
         *
         * setInvisible для не-Living Entity в Minecraft формально имеет
         * неопределенное визуальное поведение, поэтому additionally:
         * - убираем стандартный звук;
         * - не сохраняем Entity в мир.
         *
         * Если клиент все равно рисует тень/часть модели, это будет
         * отдельным визуальным слоем, который можно скрыть персонально.
         */
        /*
         * ВАЖНО: Boat — non-living entity, поэтому setInvisible() для неё
         * не гарантирует полное скрытие. Paper предоставляет visibility API
         * именно для таких случаев.
         */
        /*
         * setInvisible() для Boat не гарантирует скрытие модели в Minecraft.
         * Поэтому carrier сначала остается обычной отслеживаемой Entity,
         * а после установки passenger скрывается персонально у наблюдателей
         * через Player#hideEntity().
         */
        carrier.setInvisible(false);
        carrier.setInvulnerable(true);
        carrier.setPersistent(false);
        carrier.setSilent(true);
        carrier.setPortalCooldown(20);
        carrier.setRotation(shipYaw, 0.0f);

        /*
         * На случай игроков, которые уже начали tracking entity до смены
         * visibility. Новые игроки автоматически не увидят carrier из-за
         * setVisibleByDefault(false).
         */
        for (Player viewer : anchorLocation.getWorld().getPlayers()) {
            viewer.hideEntity(plugin, carrier);
        }

        /*
         * Игрок становится настоящим пассажиром Boat.
         */
        if (!carrier.addPassenger(pilot)) {
            carrier.remove();
            throw new IllegalStateException(
                    "Не удалось посадить игрока в Boat"
            );
        }

        this.lastCarrierLocation = carrier.getLocation().clone();


        /*
         * Визуальный корпус.
         *
         * Display перемещается штатным teleport interpolation.
         * Transformation используется только для ориентации блока при повороте.
         */
        for (ShipBlockData block : originalBlocks) {
            Location initialLocation =
                    blockWorldLocation(
                            block,
                            anchorCenter,
                            0.0f
                    );

            Matrix4f matrix = createBlockMatrix(0.0f);

            BlockDisplay display = anchorLocation.getWorld().spawn(
                    initialLocation,
                    BlockDisplay.class,
                    d -> {
                        d.setBlock(block.getBlockData().clone());
                        d.setPersistent(false);
                        d.setTeleportDuration(1);

                        /*
                         * Не режем большой корабль client-side culling.
                         */
                        d.setDisplayWidth(0.0f);
                        d.setDisplayHeight(0.0f);
                        d.setViewRange(64.0f);

                        d.setInterpolationDelay(0);
                        d.setInterpolationDuration(1);
                    }
            );

            displayEntities.add(display);
            displayLocations.add(initialLocation);
            displayMatrices.add(matrix);

            display.setTransformationMatrix(matrix);
        }

        /*
         * Watchdog:
         * VehicleMoveEvent является основным источником движения.
         * Этот тик отвечает только за жизненный цикл и за случай,
         * когда другое событие/плагин переместило Boat без ожидаемого callback.
         */
        this.task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tick,
                1L,
                1L
        );
    }

    /**
     * Старый setter оставлен для совместимости listener-слоя.
     *
     * Теперь W/S/A/D обрабатываются самой стандартной Boat Minecraft.
     */
    public void setInput(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right
    ) {
        this.kForward = forward;
        this.kBackward = backward;
        this.kLeft = left;
        this.kRight = right;
    }

    /**
     * Запоминает направление взгляда игрока.
     * Положение не меняется — курс Boat здесь не участвует.
     */
    public void setPilotView(float yaw, float pitch) {
        this.pilotYaw = yaw;
        this.pilotPitch = pitch;
    }

    private void tick() {
        if (!pilot.isOnline() || !carrier.isValid()) {
            restoreBlocks();
            return;
        }

        if (pilot.getVehicle() != carrier) {
            /*
             * Клиент мог потерять passenger-состояние после visibility/packet
             * рассинхронизации. Восстанавливаем посадку вместо мгновенного
             * уничтожения активного корабля.
             */
            if (!carrier.getPassengers().contains(pilot)) {
                carrier.addPassenger(pilot);
            }

            if (pilot.getVehicle() != carrier) {
                return;
            }
        }

        if (correctingCarrier) {
            return;
        }

        controlCarrier();

        pilot.setRotation(
                pilotYaw,
                pilotPitch
        );

        Location current = carrier.getLocation();

        if (!sameTransform(lastCarrierLocation, current)) {
            processCarrierMove(
                    lastCarrierLocation,
                    current
            );
        }
    }

    /**
     * Серверное управление Boat как fallback/authoritative controller.
     *
     * Vanilla Boat должна получать input от клиента сама, но для нашего
     * скрытого carrier это ненадежно: кроме passenger-обмена нам нужен
     * гарантированный серверный ход. Поэтому input из PlayerInputEvent
     * преобразуется в плавную скорость Boat.
     */
    private void controlCarrier() {
        Vector currentVelocity = carrier.getVelocity();

        double targetSpeed = 0.0;

        if (kForward && !kBackward) {
            targetSpeed = CARRIER_MAX_FORWARD;
        } else if (kBackward && !kForward) {
            targetSpeed = -CARRIER_MAX_BACKWARD;
        }

        float currentYaw = carrier.getYaw();
        double radians = Math.toRadians(currentYaw);

        Vector direction = new Vector(
                -Math.sin(radians),
                0.0,
                Math.cos(radians)
        );

        double targetX =
                direction.getX() * targetSpeed;

        double targetZ =
                direction.getZ() * targetSpeed;

        double response =
                targetSpeed == 0.0
                        ? CARRIER_BRAKE
                        : CARRIER_ACCELERATION;

        double nextX =
                currentVelocity.getX()
                        + (targetX - currentVelocity.getX())
                        * response;

        double nextZ =
                currentVelocity.getZ()
                        + (targetZ - currentVelocity.getZ())
                        * response;

        /*
         * Boat сохраняет свою вертикальную физику на воде.
         */
        carrier.setVelocity(
                new Vector(
                        nextX,
                        currentVelocity.getY(),
                        nextZ
                )
        );

        if (kLeft ^ kRight) {
            float directionSign = kLeft ? -1.0f : 1.0f;

            float nextYaw =
                    norm(
                            currentYaw
                                    + directionSign
                                    * CARRIER_TURN_SPEED
                    );

            carrier.setRotation(
                    nextYaw,
                    0.0f
            );
        }
    }

    /**
     * Обрабатывает изменение настоящего Vehicle.
     *
     * Сначала проверяет полную collision-модель корабля.
     * Если новая позиция свободна — визуальная модель следует за Boat.
     * Если занята — Boat возвращается к последнему допустимому состоянию.
     */
    public void processCarrierMove(
            Location from,
            Location to
    ) {
        if (correctingCarrier || to == null || to.getWorld() == null) {
            return;
        }

        if (!carrier.isValid()) {
            restoreBlocks();
            return;
        }

        if (from == null) {
            from = lastCarrierLocation != null
                    ? lastCarrierLocation.clone()
                    : carrier.getLocation().clone();
        }

        float nextYaw = norm(to.getYaw());
        Location nextAnchor = anchorFromCarrier(to, nextYaw);

        /*
         * ShipCollision хранит shape в исходной ориентации корабля.
         * Поэтому ей нужен не абсолютный yaw мира, а delta относительно
         * исходного курса при активации.
         */
        float currentCollisionYaw =
                normalizeDelta(shipYaw - initialYaw);

        float nextCollisionYaw =
                normalizeDelta(nextYaw - initialYaw);

        boolean collision = collisionModel.collidesBetweenTransforms(
                to.getWorld(),
                anchorCenter,
                currentCollisionYaw,
                nextAnchor,
                nextCollisionYaw
        );

        if (collision) {
            correctingCarrier = true;

            try {
                carrier.teleport(from);
                carrier.setVelocity(new Vector());

                /*
                 * Не оставляем лодку с накопленным вращательным/линейным
                 * импульсом после столкновения.
                 */
                carrier.setRotation(
                        norm(from.getYaw()),
                        0.0f
                );
            } finally {
                correctingCarrier = false;
            }

            return;
        }

        float yawDelta = normalizeDelta(nextYaw - shipYaw);

        anchorCenter = nextAnchor;
        shipYaw = nextYaw;
        lastCarrierLocation = to.clone();

        updateDisplays(Math.abs(yawDelta) > 0.0001f);
        fillWater();
    }

    /**
     * Получает точные collision-boxes BlockData в мировой позиции
     * и переводит их в локальные координаты относительно anchorCenter.
     */
    private Location anchorFromCarrier(
            Location carrierLocation,
            float yaw
    ) {
        double delta = Math.toRadians(
                yaw - initialYaw
        );

        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        double x =
                carrierLocation.getX()
                        + carrierLocalAnchorX * cos
                        - carrierLocalAnchorZ * sin;

        double y =
                carrierLocation.getY()
                        + carrierLocalAnchorY;

        double z =
                carrierLocation.getZ()
                        + carrierLocalAnchorX * sin
                        + carrierLocalAnchorZ * cos;

        Location result = carrierLocation.clone();
        result.setX(x);
        result.setY(y);
        result.setZ(z);

        return result;
    }

    private void updateDisplays(boolean rotated) {
        double delta = Math.toRadians(
                shipYaw - initialYaw
        );

        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        float rotation = (float) -delta;

        for (int i = 0; i < originalBlocks.size(); i++) {
            BlockDisplay display = displayEntities.get(i);

            if (!display.isValid()) {
                continue;
            }

            ShipBlockData block = originalBlocks.get(i);
            Location target = displayLocations.get(i);

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

            target.setX(centerX - 0.5);
            target.setY(centerY - 0.5);
            target.setZ(centerZ - 0.5);
            target.setYaw(0.0f);
            target.setPitch(0.0f);

            /*
             * Один новый серверный target на тик.
             * teleportDuration=1 дает ровно один тик клиентской интерполяции.
             */
            display.setTeleportDuration(1);
            display.teleport(target);

            if (rotated) {
                Matrix4f matrix = displayMatrices.get(i);

                matrix.identity()
                        .translate(0.5f, 0.5f, 0.5f)
                        .rotateY(rotation)
                        .translate(-0.5f, -0.5f, -0.5f);

                display.setInterpolationDelay(0);
                display.setInterpolationDuration(1);
                display.setTransformationMatrix(matrix);
            }
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
                    seaLevel = Math.max(
                            seaLevel,
                            neighbor.getY()
                    );
                }
            }
        }

        if (seaLevel == Integer.MIN_VALUE) {
            return;
        }

        for (Block block : blocks) {
            if (block.getY() <= seaLevel) {
                submergedWake.add(
                        new BP(
                                block.getX(),
                                block.getY(),
                                block.getZ()
                        )
                );
            }
        }
    }

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

            if (block.getType().isAir()
                    || block.getType() == Material.WATER) {
                block.setType(Material.WATER, false);
            }
        }
    }

    private void fillWater() {
        if (submergedWake.isEmpty()) {
            return;
        }

        Set<BP> occupied = new HashSet<>();

        double delta = Math.toRadians(
                shipYaw - initialYaw
        );

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

            occupied.add(
                    new BP(x, y, z)
            );
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

    public void restoreBlocks() {
        stopInternal();

        Location playerLocation = pilot.getLocation().clone();
        float playerYaw = playerLocation.getYaw();
        float playerPitch = playerLocation.getPitch();

        if (carrier.isValid()) {
            carrier.eject();
            carrier.remove();
        }

        /*
         * Физическая сетка после остановки должна соответствовать Minecraft grid.
         */
        int snap = Math.floorMod(
                Math.round(
                        normalizeDelta(shipYaw - initialYaw)
                                / 90.0f
                ) * 90,
                360
        );

        int rotations = snap / 90;

        double rad = Math.toRadians(snap);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        int originX = floorToInt(
                anchorCenter.getX()
        );

        int originY = anchorCenter.getBlockY();

        int originZ = floorToInt(
                anchorCenter.getZ()
        );

        List<RestoreEntry> entries =
                new ArrayList<>(
                        originalBlocks.size()
                );

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
                    anchorCenter.getWorld().getBlockAt(
                            x,
                            y,
                            z
                    );

            BlockData restoredData =
                    data.getBlockData().clone();

            rotateData(
                    restoredData,
                    rotations
            );

            entries.add(
                    new RestoreEntry(
                            target,
                            restoredData,
                            data.getStateSnapshot()
                    )
            );
        }

        /*
         * Сначала физические блоки.
         */
        for (RestoreEntry entry : entries) {
            BlockData data = entry.blockData();

            if (entry.block().getType() == Material.WATER
                    && data instanceof Waterlogged waterlogged) {
                waterlogged.setWaterlogged(true);
            }

            entry.block().setType(
                    data.getMaterial(),
                    false
            );

            entry.block().setBlockData(
                    data,
                    false
            );
        }

        /*
         * Затем TileState/инвентари/PDC.
         */
        for (RestoreEntry entry : entries) {
            try {
                BlockState state =
                        entry.snapshot().copy(
                                entry.block().getLocation()
                        );

                state.setBlockData(
                        entry.blockData()
                );

                state.update(
                        true,
                        false
                );
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
         * Игрок остается смотреть туда, куда смотрел до остановки.
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
        land.setX(
                anchorCenter.getX()
        );
        land.setZ(
                anchorCenter.getZ()
        );
        land.setY(safeY);
        land.setYaw(playerYaw);
        land.setPitch(playerPitch);

        pilot.teleport(land);
        pilot.setVelocity(new Vector());

        for (BlockDisplay display : displayEntities) {
            if (display.isValid()) {
                display.remove();
            }
        }

        displayEntities.clear();
        displayLocations.clear();
        displayMatrices.clear();
    }

    private double findSafeDeckY(
            int originX,
            int originY,
            int originZ,
            double cos,
            double sin
    ) {
        int playerX =
                floorToInt(anchorCenter.getX());

        int playerZ =
                floorToInt(anchorCenter.getZ());

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
                    && data.getBlockData()
                    .getMaterial()
                    .isSolid()
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

    private static boolean sameTransform(
            Location a,
            Location b
    ) {
        if (a == null || b == null) {
            return false;
        }

        if (a.getWorld() != b.getWorld()) {
            return false;
        }

        return Math.abs(
                a.getX() - b.getX()
        ) < 0.00001
                && Math.abs(
                a.getY() - b.getY()
        ) < 0.00001
                && Math.abs(
                a.getZ() - b.getZ()
        ) < 0.00001
                && Math.abs(
                normalizeDelta(
                        a.getYaw() - b.getYaw()
                )
        ) < 0.00001f;
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

    private static float normalizeDelta(float delta) {
        delta %= 360.0f;

        if (delta > 180.0f) {
            delta -= 360.0f;
        }

        if (delta < -180.0f) {
            delta += 360.0f;
        }

        return delta;
    }

    private void rotateData(
            BlockData data,
            int rotations
    ) {
        if (rotations == 0) {
            return;
        }

        if (data instanceof Directional directional) {
            BlockFace face =
                    directional.getFacing();

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
            int index =
                    ROT16.indexOf(
                            rotatable.getRotation()
                    );

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
                    new HashSet<>(
                            multipleFacing.getFaces()
                    );

            for (BlockFace face :
                    multipleFacing.getAllowedFaces()) {
                multipleFacing.setFace(
                        face,
                        false
                );
            }

            for (BlockFace face : current) {
                BlockFace rotated = face;

                for (int i = 0; i < rotations; i++) {
                    rotated = clockwise(rotated);
                }

                if (multipleFacing
                        .getAllowedFaces()
                        .contains(rotated)) {
                    multipleFacing.setFace(
                            rotated,
                            true
                    );
                }
            }

        } else if (data
                instanceof org.bukkit.block.data.type.Wall wall) {

            Map<BlockFace,
                    org.bukkit.block.data.type.Wall.Height> heights =
                    new HashMap<>();

            for (BlockFace face :
                    new BlockFace[]{
                            BlockFace.NORTH,
                            BlockFace.EAST,
                            BlockFace.SOUTH,
                            BlockFace.WEST
                    }) {
                heights.put(
                        face,
                        wall.getHeight(face)
                );
            }

            for (Map.Entry<
                    BlockFace,
                    org.bukkit.block.data.type.Wall.Height
                    > entry : heights.entrySet()) {

                BlockFace rotated =
                        entry.getKey();

                for (int i = 0; i < rotations; i++) {
                    rotated = clockwise(rotated);
                }

                wall.setHeight(
                        rotated,
                        entry.getValue()
                );
            }
        }
    }

    private static BlockFace clockwise(
            BlockFace face
    ) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;

            case NORTH_EAST ->
                    BlockFace.SOUTH_EAST;
            case SOUTH_EAST ->
                    BlockFace.SOUTH_WEST;
            case SOUTH_WEST ->
                    BlockFace.NORTH_WEST;
            case NORTH_WEST ->
                    BlockFace.NORTH_EAST;

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

    public Boat getCarrier() {
        return carrier;
    }

    /**
     * Совместимый alias для старого кода.
     */
    public Boat getRootEntity() {
        return carrier;
    }

    private record RestoreEntry(
            Block block,
            BlockData blockData,
            BlockState snapshot
    ) {
    }
}
