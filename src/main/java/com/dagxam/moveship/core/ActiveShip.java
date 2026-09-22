package com.dagxam.moveship.core;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private ArmorStand rootEntity;

    /**
     * Отдельный carrier только для визуального корпуса.
     *
     * Игрок продолжает сидеть на rootEntity, а displayCarrier используется
     * исключительно как транспорт для цепочки:
     *
     * displayCarrier -> displayRoot -> все BlockDisplay.
     *
     * Благодаря этому сами BlockDisplay больше не телепортируются каждый тик.
     * Их движение получает клиент из пассажирской цепочки, а поворот идет
     * только через transformation interpolation.
     */
    private ArmorStand displayCarrier;
    private BlockDisplay displayRoot;

    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay> displayEntities = new ArrayList<>();
    private final List<Location> displayLocations = new ArrayList<>();
    private final List<Matrix4f> displayMatrices = new ArrayList<>();

    /**
     * Светящиеся блоки корабля. BlockDisplay отвечает за внешний вид,
     * а временные LIGHT-блоки ниже поддерживают настоящее освещение мира.
     */
    private final List<LightSource> lightSources = new ArrayList<>();

    /**
     * Для каждой временной LIGHT-клетки сохраняем ровно тот BlockData,
     * который находился там до размещения виртуального света.
     */
    private final java.util.Map<BlockKey, BlockData> activeLightCells =
            new java.util.HashMap<>();

    /*
     * Скорость в блоках за тик.
     * Управление сделано через target -> current, поэтому изменение скорости
     * не имеет резких ступенек.
     */
    private static final double MAX_FWD = 0.40;
    private static final double MAX_BACK = 0.15;
    private static final double SPEED_RESPONSE = 0.16;

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
    private boolean restored;

    private final int originBlockX;
    private final int originBlockY;
    private final int originBlockZ;

    /**
     * Высота поверхности воды, определённая до удаления физического корпуса.
     * Заполняем водой только бывшие клетки корабля на этой высоте и ниже,
     * поэтому надводная часть не превращается в водяную стену.
     */
    private int originalWaterLevel = Integer.MIN_VALUE;

    /**
     * Положение игрока относительно центра корабля в момент старта.
     * Этот offset вращается вместе с корпусом, поэтому игрок остается
     * прикрепленным к тому же месту штурвала.
     */
    private final double seatLocalX;
    private final double seatLocalY;
    private final double seatLocalZ;

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

        this.originBlockX = anchorLocation.getBlockX();
        this.originBlockY = anchorLocation.getBlockY();
        this.originBlockZ = anchorLocation.getBlockZ();

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
            ItemStack[] items = null;

            int lightEmission = blockData.getLightEmission();
            if (lightEmission > 0) {
                lightSources.add(
                        new LightSource(
                                localX,
                                localY,
                                localZ,
                                lightEmission
                        )
                );
            }

            if (snapshot instanceof io.papermc.paper.block.TileStateInventoryHolder tileInventory) {
                /*
                 * Сохраняем содержимое ОТДЕЛЬНО от BlockState.
                 *
                 * После этого очищаем именно snapshot-инвентарь, который Paper
                 * использует при записи TileEntity. Так при setType(AIR)
                 * контейнер уже не содержит предметов, которые Minecraft может
                 * выбросить в мир.
                 */
                items = deepCopy(tileInventory.getSnapshotInventory());

                ItemStack[] emptyContents =
                        new ItemStack[tileInventory.getSnapshotInventory().getSize()];

                tileInventory.getSnapshotInventory().setContents(emptyContents);
                tileInventory.update(true, false);
            }

            updateOriginalWaterLevel(block);

            originalBlocks.add(
                    new ShipBlockData(
                            localX,
                            localY,
                            localZ,
                            blockData,
                            snapshot,
                            items
                    )
            );
        }

        /*
         * Создаем root и все BlockDisplay, пока физические блоки еще
         * находятся в мире. Лишь после успешного создания всей визуальной
         * части убираем физическую конструкцию.
         */
        try {
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
                throw new IllegalStateException(
                        "Не удалось посадить игрока на штурвал"
                );
            }

            /*
             * Визуальный корпус живет в отдельной пассажирской цепочке.
             * displayCarrier находится в точке anchorLocation, поэтому
             * перемещение всей конструкции происходит одним движением
             * carrier-а вместо отдельного teleport() для каждого блока.
             */
            this.displayCarrier =
                    anchorLocation.getWorld().spawn(
                            anchorLocation,
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

            this.displayRoot =
                    anchorLocation.getWorld().spawn(
                            anchorLocation,
                            BlockDisplay.class,
                            entity -> {
                                entity.setBlock(
                                        Material.AIR.createBlockData()
                                );
                                entity.setPersistent(false);
                                entity.setTeleportDuration(0);
                                entity.setInterpolationDelay(0);
                                entity.setInterpolationDuration(2);
                                entity.setViewRange(64.0f);
                                entity.setGravity(false);
                            }
                    );

            if (!displayCarrier.addPassenger(displayRoot)) {
                throw new IllegalStateException(
                        "Не удалось создать carrier для корпуса корабля"
                );
            }

            for (ShipBlockData block : originalBlocks) {
                /*
                 * Положение блока теперь задается LOCAL transformation-ом
                 * относительно displayRoot. World-coordinate teleport() больше
                 * не нужен.
                 */
                Matrix4f matrix =
                        createBlockMatrix(
                                block.getLocalX(),
                                block.getLocalY(),
                                block.getLocalZ(),
                                0.0f
                        );

                BlockDisplay display =
                        anchorLocation.getWorld().spawn(
                                anchorLocation,
                                BlockDisplay.class,
                                entity -> {
                                    entity.setBlock(
                                            block.getBlockData().clone()
                                    );
                                    entity.setPersistent(false);

                                    /*
                                     * Положение приходит через passenger chain.
                                     * Teleport interpolation здесь не используется,
                                     * иначе два интерполятора начинают конкурировать.
                                     */
                                    entity.setTeleportDuration(0);
                                    entity.setInterpolationDelay(0);
                                    entity.setInterpolationDuration(2);
                                    entity.setViewRange(64.0f);
                                    entity.setGravity(false);
                                }
                        );

                displayEntities.add(display);
                displayMatrices.add(matrix);

                display.setTransformationMatrix(matrix);

                if (!displayRoot.addPassenger(display)) {
                    throw new IllegalStateException(
                            "Не удалось прикрепить блок корпуса к displayRoot"
                    );
                }
            }

            /*
             * Инвентари уже очищены через Paper snapshot-инвентарь выше.
             * Дополнительный clear живого Container здесь не выполняем.
             */
            for (Block block : blocks) {
                block.setType(Material.AIR, false);
            }

            /*
             * Корабль находился в воде. Восстанавливаем воду только в
             * бывших клетках корпуса, которые были не выше исходной
             * поверхности воды. Надводная часть остаётся воздухом.
             */
            restoreWaterAtOriginalPosition();

            /*
             * После удаления физического корпуса устанавливаем реальные
             * источники освещения в тех же клетках, где находятся фонари
             * и светящие блоки. Они невидимы и не участвуют в коллизии.
             */
            updateLightBlocks();
        } catch (RuntimeException ex) {
            for (BlockDisplay display : displayEntities) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
            }

            displayEntities.clear();
            displayLocations.clear();
            displayMatrices.clear();

            if (displayRoot != null && displayRoot.isValid()) {
                displayRoot.eject();
                displayRoot.remove();
            }

            if (displayCarrier != null && displayCarrier.isValid()) {
                displayCarrier.eject();
                displayCarrier.remove();
            }

            if (rootEntity != null && rootEntity.isValid()) {
                rootEntity.eject();
                rootEntity.remove();
            }

            clearLightBlocks();
            restoreOriginalBlocks();

            throw ex;
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
        if (!pilot.isOnline() || rootEntity == null || !rootEntity.isValid()) {
            ShipManager.stopShip(pilot);
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
            ShipManager.stopShip(pilot);
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
         * Штурвал поддерживается каждый тик, даже в покое.
         * Это не даёт игроку накапливать микросмещение от физики/плагинов.
         */
        updateSeat();

        if (moved) {
            /*
             * Весь корпус двигается одним carrier-ом.
             * Это ключевое отличие от старой схемы, где каждый BlockDisplay
             * отдельно получал teleport() каждый тик.
             */
            updateDisplayCarrier();
            updateDisplays();
        }

        /*
         * Свет переносится независимо от того, был ли этот тик только
         * поворотом, движением или одновременно обоими.
         */
        if (moved) {
            updateLightBlocks();
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
                SPEED_RESPONSE
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

        Location seat = rootEntity.getLocation();

        double dx = seatX - seat.getX();
        double dy = seatY - seat.getY();
        double dz = seatZ - seat.getZ();

        if (Math.abs(dx) > 0.0001
                || Math.abs(dy) > 0.0001
                || Math.abs(dz) > 0.0001) {

            /*
             * Сначала принимаем точную серверную позицию carrier.
             * Затем передаем то же фактическое смещение через velocity.
             *
             * Такой порядок использовался в ветке с рабочей плавностью:
             * teleport задает точное состояние сервера, а velocity сообщает
             * клиенту фактическое движение за тик и делает перевозку
             * пассажира визуально заметно плавнее.
             */
            seat.setX(seatX);
            seat.setY(seatY);
            seat.setZ(seatZ);
            seat.setYaw(0.0f);
            seat.setPitch(0.0f);

            rootEntity.teleport(seat);

            Vector seatVelocity = new Vector(dx, dy, dz);
            rootEntity.setVelocity(seatVelocity);

            /*
             * Дополнительный position-sync пакет не заменяет teleport/velocity:
             * он только не даёт клиентскому entity tracker накапливать задержку.
             */
            sendPositionSync(rootEntity, seat, seatVelocity);
        } else {
            rootEntity.setVelocity(new Vector());
        }

        rootEntity.setRotation(0.0f, 0.0f);

        /*
         * Игрок уже является пассажиром carrier-а.
         * Не обнуляем его velocity вручную: это конкурировало с движением
         * carrier и могло давать ощущение рывка/потери плавности.
         */
        pilot.setFallDistance(0.0f);
    }

    /**
     * До удаления корпуса определяет реальную поверхность воды по ближайшим
     * водяным клеткам, соприкасающимся с кораблём.
     *
     * Используется один общий уровень для всего корабля — как в классической
     * логике watercraft-плагинов: подводная часть заполняется водой, надводная
     * остаётся воздухом. Movecraft также описывает восстановление воды через
     * определение уровня воды вокруг craft.
     */
    private void updateOriginalWaterLevel(Block block) {
        BlockFace[] faces = {
                BlockFace.UP,
                BlockFace.DOWN,
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.EAST,
                BlockFace.WEST
        };

        for (BlockFace face : faces) {
            Block adjacent = block.getRelative(face);
            Material type = adjacent.getType();

            if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
                originalWaterLevel = Math.max(
                        originalWaterLevel,
                        adjacent.getY()
                );
            }
        }
    }

    /**
     * Восстанавливает воду только в тех бывших клетках корабля,
     * которые находились на уровне воды или ниже.
     *
     * Важно: мы больше не заполняем водой все клетки, просто соприкасавшиеся
     * с водой. Именно это раньше создавало на скриншоте огромные водяные стены.
     */
    private void restoreWaterAtOriginalPosition() {
        World world = anchorCenter.getWorld();

        if (world == null
                || originalWaterLevel == Integer.MIN_VALUE) {
            return;
        }

        for (ShipBlockData data : originalBlocks) {
            int y = originBlockY + data.getLocalY();

            if (y > originalWaterLevel) {
                continue;
            }

            Block block = world.getBlockAt(
                    originBlockX + data.getLocalX(),
                    y,
                    originBlockZ + data.getLocalZ()
            );

            if (block.getType().isAir()) {
                block.setType(Material.WATER, false);
            }
        }
    }

    /*
     * Optional ProtocolLib position synchronization.
     *
     * Paper's normal entity tracker may not send ArmorStand position updates
     * every tick. Similar modern ship plugins use an extra per-tick position
     * sync for the root vehicle to prevent visible rider/display rubber-banding.
     * When ProtocolLib is absent or a packet format is unsupported, the normal
     * Bukkit teleport/velocity path remains active.
     */
    private static boolean positionSyncInitialized;
    private static boolean positionSyncAvailable;
    private static Object positionSyncProtocolManager;
    private static Object positionSyncPacketType;
    private static java.lang.reflect.Method positionSyncCreatePacket;
    private static java.lang.reflect.Method positionSyncSendPacket;
    private static java.lang.reflect.Method positionSyncGetModifier;
    private static java.lang.reflect.Method positionSyncModifierWrite;
    private static java.lang.reflect.Constructor<?> positionSyncVec3Constructor;
    private static java.lang.reflect.Constructor<?> positionSyncPositionMoveRotationConstructor;

    private void sendPositionSync(
            ArmorStand carrier,
            Location location,
            Vector velocity
    ) {
        initializePositionSync();

        if (!positionSyncAvailable
                || carrier == null
                || !carrier.isValid()
                || location == null) {
            return;
        }

        try {
            Object packet = positionSyncCreatePacket.invoke(
                    positionSyncProtocolManager,
                    positionSyncPacketType
            );

            Object modifier = positionSyncGetModifier.invoke(packet);

            positionSyncModifierWrite.invoke(
                    modifier,
                    0,
                    carrier.getEntityId()
            );

            Object position = positionSyncVec3Constructor.newInstance(
                    location.getX(),
                    location.getY(),
                    location.getZ()
            );

            double vx = velocity == null ? 0.0 : velocity.getX();
            double vy = velocity == null ? 0.0 : velocity.getY();
            double vz = velocity == null ? 0.0 : velocity.getZ();

            Object deltaMovement = positionSyncVec3Constructor.newInstance(
                    vx,
                    vy,
                    vz
            );

            Object positionMoveRotation =
                    positionSyncPositionMoveRotationConstructor.newInstance(
                            position,
                            deltaMovement,
                            location.getYaw(),
                            location.getPitch()
                    );

            positionSyncModifierWrite.invoke(
                    modifier,
                    1,
                    positionMoveRotation
            );

            for (Player tracked : carrier.getTrackedBy()) {
                if (tracked != null && tracked.isOnline()) {
                    positionSyncSendPacket.invoke(
                            positionSyncProtocolManager,
                            tracked,
                            packet
                    );
                }
            }
        } catch (Throwable ex) {
            positionSyncAvailable = false;

            if (plugin != null) {
                plugin.getLogger().warning(
                        "Синхронизация позиции корабля через ProtocolLib отключена: "
                                + ex.getMessage()
                );
            }
        }
    }

    private void initializePositionSync() {
        if (positionSyncInitialized) {
            return;
        }

        synchronized (ActiveShip.class) {
            if (positionSyncInitialized) {
                return;
            }

            positionSyncInitialized = true;

            try {
                Class<?> protocolLibrary =
                        Class.forName("com.comphenix.protocol.ProtocolLibrary");

                Class<?> packetTypeServer =
                        Class.forName(
                                "com.comphenix.protocol.PacketType$Play$Server"
                        );

                positionSyncPacketType =
                        packetTypeServer.getField("ENTITY_TELEPORT").get(null);

                Object manager =
                        protocolLibrary
                                .getMethod("getProtocolManager")
                                .invoke(null);

                Class<?> packetTypeClass =
                        Class.forName("com.comphenix.protocol.PacketType");

                Class<?> packetContainerClass =
                        Class.forName(
                                "com.comphenix.protocol.events.PacketContainer"
                        );

                positionSyncProtocolManager = manager;

                positionSyncCreatePacket =
                        manager.getClass().getMethod(
                                "createPacket",
                                packetTypeClass
                        );

                positionSyncSendPacket =
                        manager.getClass().getMethod(
                                "sendServerPacket",
                                Player.class,
                                packetContainerClass
                        );

                positionSyncGetModifier =
                        packetContainerClass.getMethod("getModifier");

                Class<?> modifierClass =
                        Class.forName(
                                "com.comphenix.protocol.reflect.StructureModifier"
                        );

                positionSyncModifierWrite =
                        modifierClass.getMethod(
                                "write",
                                int.class,
                                Object.class
                        );

                Class<?> vec3Class =
                        Class.forName("net.minecraft.world.phys.Vec3");

                Class<?> positionMoveRotationClass =
                        Class.forName(
                                "net.minecraft.world.entity.PositionMoveRotation"
                        );

                positionSyncVec3Constructor =
                        vec3Class.getConstructor(
                                double.class,
                                double.class,
                                double.class
                        );

                positionSyncPositionMoveRotationConstructor =
                        positionMoveRotationClass.getConstructor(
                                vec3Class,
                                vec3Class,
                                float.class,
                                float.class
                        );

                positionSyncAvailable = true;

                plugin.getLogger().info(
                        "Ежетиковая синхронизация движения корабля через ProtocolLib включена."
                );
            } catch (Throwable ex) {
                positionSyncAvailable = false;
                plugin.getLogger().info(
                        "ProtocolLib не найден или формат позиции не поддерживается. "
                                + "Корабль продолжит работать через обычную Bukkit-синхронизацию."
                );
            }
        }
    }

    /**
     * Перемещает единый carrier визуального корпуса в текущую позицию anchor.
     *
     * У carrier фиксирован yaw=0. Реальный поворот корабля не записываем в
     * entity rotation — он полностью находится в display transformation.
     */
    private void updateDisplayCarrier() {
        if (displayCarrier == null
                || !displayCarrier.isValid()
                || anchorCenter == null
                || anchorCenter.getWorld() == null) {
            return;
        }

        Location current = displayCarrier.getLocation();
        Location target = anchorCenter.clone().add(-0.5, 0.0, -0.5);

        double dx = target.getX() - current.getX();
        double dy = target.getY() - current.getY();
        double dz = target.getZ() - current.getZ();

        if (Math.abs(dx) < 0.000001
                && Math.abs(dy) < 0.000001
                && Math.abs(dz) < 0.000001) {
            displayCarrier.setVelocity(new Vector());
            return;
        }

        target.setYaw(0.0f);
        target.setPitch(0.0f);

        displayCarrier.teleport(target);

        Vector velocity = new Vector(dx, dy, dz);
        displayCarrier.setVelocity(velocity);

        /*
         * Ежетиковая синхронизация применяется именно к display carrier,
         * а не к каждому блоку. Это существенно уменьшает количество
         * сетевых обновлений и устраняет накопление задержки.
         */
        sendPositionSync(displayCarrier, target, velocity);
    }

    private void updateDisplays() {
        double delta = Math.toRadians(shipYaw - initialYaw);
        boolean rotationChanged = Math.abs(currentTurn) > 0.00001f;

        /*
         * На прямой движение получает весь корпус через displayCarrier.
         * Transformation меняем только при повороте, чтобы не сбрасывать
         * клиентскую интерполяцию на каждом тике движения.
         */
        if (!rotationChanged) {
            return;
        }

        float rotation = (float) -delta;

        for (int i = 0; i < originalBlocks.size(); i++) {
            BlockDisplay display = displayEntities.get(i);

            if (!display.isValid()) {
                continue;
            }

            ShipBlockData block = originalBlocks.get(i);
            Matrix4f matrix = displayMatrices.get(i);

            matrix.identity()
                    .translate(0.5f, 0.5f, 0.5f)
                    .rotateY(rotation)
                    .translate(-0.5f, -0.5f, -0.5f)
                    .translate(
                            block.getLocalX(),
                            block.getLocalY(),
                            block.getLocalZ()
                    );

            display.setInterpolationDelay(0);
            display.setInterpolationDuration(2);
            display.setTransformationMatrix(matrix);
        }
    }

    private static Matrix4f createBlockMatrix(
            int localX,
            int localY,
            int localZ,
            float rotation
    ) {
        return new Matrix4f()
                .translate(0.5f, 0.5f, 0.5f)
                .rotateY(rotation)
                .translate(-0.5f, -0.5f, -0.5f)
                .translate(localX, localY, localZ);
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
     * Проверяет, можно ли кораблю занять новое положение/угол.
     *
     * Проверка остается серверной и выполняется до изменения anchorCenter,
     * поэтому визуальная интерполяция не сможет "протолкнуть" корабль
     * через занятый блок.
     */
    private boolean canTransform(Location target, float yaw) {
        World world = target.getWorld();

        if (world == null) {
            return false;
        }

        double delta = Math.toRadians(yaw - initialYaw);
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        Set<BlockKey> checked = new HashSet<>();

        for (ShipBlockData block : originalBlocks) {
            double rotatedX =
                    block.getLocalX() * cos
                            - block.getLocalZ() * sin;

            double rotatedZ =
                    block.getLocalX() * sin
                            + block.getLocalZ() * cos;

            int x = floorToInt(target.getX() + rotatedX);
            int y = target.getBlockY() + block.getLocalY();
            int z = floorToInt(target.getZ() + rotatedZ);

            if (y < world.getMinHeight()
                    || y >= world.getMaxHeight()) {
                return false;
            }

            BlockKey key = new BlockKey(x, y, z);

            if (!checked.add(key)) {
                continue;
            }

            Block worldBlock = world.getBlockAt(x, y, z);

            /*
             * Вода и воздух не являются препятствиями.
             * Коллизию учитываем только по реально занимаемой целевой клетке.
             */
            if (worldBlock.getType().isSolid()) {
                return false;
            }
        }

        return true;
    }

    public void restoreBlocks() {
        if (restored) {
            return;
        }

        restored = true;
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
                            data.getStateSnapshot(),
                            data.getItems()
                    )
            );
        }

        /*
         * Временный свет должен исчезнуть ДО восстановления настоящих
         * светящихся блоков. Иначе при совпадении координат LIGHT мог бы
         * затереть только что восстановленный фонарь/светящий блок.
         */
        clearLightBlocks();

        /*
         * Сначала весь physical block layout.
         */
        for (RestoreEntry entry : entries) {
            entry.block().setType(entry.blockData().getMaterial(), false);
            entry.block().setBlockData(entry.blockData(), false);
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

        restoreContainerInventories(entries);

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

        if (pilot.isOnline()) {
            pilot.teleport(land);
        }

        for (BlockDisplay display : displayEntities) {
            if (display.isValid()) {
                display.remove();
            }
        }

        displayEntities.clear();
        displayLocations.clear();
        displayMatrices.clear();

        if (displayRoot != null && displayRoot.isValid()) {
            displayRoot.eject();
            displayRoot.remove();
        }

        if (displayCarrier != null && displayCarrier.isValid()) {
            displayCarrier.eject();
            displayCarrier.remove();
        }

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

    private void restoreOriginalBlocks() {
        /*
         * При неудачной активации физические блоки должны остаться ровно
         * такими, какими они были до запуска.
         */
        for (ShipBlockData data : originalBlocks) {
            Block target =
                    anchorCenter.getWorld().getBlockAt(
                            anchorCenter.getBlockX() + data.getLocalX(),
                            anchorCenter.getBlockY() + data.getLocalY(),
                            anchorCenter.getBlockZ() + data.getLocalZ()
                    );

            target.setType(
                    data.getBlockData().getMaterial(),
                    false
            );
            target.setBlockData(
                    data.getBlockData().clone(),
                    false
            );

            try {
                BlockState state =
                        data.getStateSnapshot()
                                .copy(target.getLocation());
                state.setBlockData(data.getBlockData().clone());
                state.update(true, false);
            } catch (Exception ex) {
                plugin.getLogger().warning(
                        "Не удалось восстановить блок после ошибки активации "
                                + target.getLocation()
                                + ": "
                                + ex.getMessage()
                );
            }

            restoreContainerInventory(target, data.getItems());
        }
    }

    private void restoreContainerInventories(
            List<RestoreEntry> entries
    ) {
        for (RestoreEntry entry : entries) {
            restoreContainerInventory(
                    entry.block(),
                    entry.items()
            );
        }

        /*
         * Дополнительные попытки нужны для TileEntity, которым Paper
         * завершает внутреннюю инициализацию после установки блока.
         * Здесь НЕ проверяем restored: к этому моменту restoreBlocks()
         * уже поставил restored=true, но восстановление инвентарей все равно
         * должно быть разрешено.
         */
        if (!entries.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> {
                        for (RestoreEntry entry : entries) {
                            restoreContainerInventory(
                                    entry.block(),
                                    entry.items()
                            );
                        }
                    },
                    1L
            );

            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> {
                        for (RestoreEntry entry : entries) {
                            restoreContainerInventory(
                                    entry.block(),
                                    entry.items()
                            );
                        }
                    },
                    5L
            );
        }
    }

    private void restoreContainerInventory(
            Block block,
            ItemStack[] items
    ) {
        if (block == null || items == null) {
            return;
        }

        /*
         * Сундуки, бочки, печи, коптильни и плавильни являются Container.
         * Восстанавливаем именно их живой Inventory, а не только snapshot.
         */
        if (block.getState() instanceof Container container) {
            Inventory inventory = container.getInventory();
            inventory.clear();

            for (int i = 0; i < Math.min(items.length, inventory.getSize()); i++) {
                ItemStack item = items[i];

                if (item != null) {
                    inventory.setItem(i, item.clone());
                }
            }

            container.update(true, false);
            return;
        }

        /*
         * Запасной путь для Paper TileStateInventoryHolder, который не является
         * обычным Container.
         */
        if (block.getState()
                instanceof io.papermc.paper.block.TileStateInventoryHolder tileInventory) {
            Inventory inventory = tileInventory.getInventory();
            inventory.clear();

            for (int i = 0; i < Math.min(items.length, inventory.getSize()); i++) {
                ItemStack item = items[i];

                if (item != null) {
                    inventory.setItem(i, item.clone());
                }
            }

            tileInventory.update(true, false);
        }
    }

    private static ItemStack[] deepCopy(Inventory inventory) {
        ItemStack[] items = new ItemStack[inventory.getSize()];

        for (int i = 0; i < items.length; i++) {
            ItemStack item = inventory.getItem(i);

            if (item != null && !item.getType().isAir()) {
                items[i] = item.clone();
            }
        }

        return items;
    }

    /**
     * Обновляет реальные невидимые источники света в соответствии
     * с текущей позицией и углом виртуального корабля.
     */
    /**
     * Обновляет реальные невидимые источники света в соответствии
     * с текущей позицией и углом виртуального корабля.
     *
     * BlockDisplay отвечает за внешний вид светящегося блока.
     * Material.LIGHT нужен только для настоящего освещения мира.
     */
    private void updateLightBlocks() {
        if (lightSources.isEmpty()
                || anchorCenter == null
                || anchorCenter.getWorld() == null) {
            return;
        }

        double delta = Math.toRadians(shipYaw - initialYaw);
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        java.util.Map<BlockKey, Integer> desired =
                new java.util.HashMap<>();

        for (LightSource source : lightSources) {
            int x = floorToInt(
                    anchorCenter.getX()
                            + source.localX() * cos
                            - source.localZ() * sin
            );

            int y = anchorCenter.getBlockY()
                    + source.localY();

            int z = floorToInt(
                    anchorCenter.getZ()
                            + source.localX() * sin
                            + source.localZ() * cos
            );

            BlockKey key = new BlockKey(x, y, z);

            /*
             * При редком совпадении нескольких источников после поворота
             * используем максимальную яркость.
             */
            desired.merge(
                    key,
                    source.level(),
                    Math::max
            );
        }

        /*
         * Убираем источники, оставшиеся в старых координатах,
         * и восстанавливаем точный исходный BlockData.
         */
        for (BlockKey old : new HashSet<>(activeLightCells.keySet())) {
            if (desired.containsKey(old)) {
                continue;
            }

            Block block = anchorCenter.getWorld().getBlockAt(
                    old.x(),
                    old.y(),
                    old.z()
            );

            if (block.getType() == Material.LIGHT) {
                BlockData previous =
                        activeLightCells.get(old);

                if (previous != null) {
                    block.setBlockData(
                            previous.clone(),
                            false
                    );
                } else {
                    block.setType(
                            Material.AIR,
                            false
                    );
                }
            }

            activeLightCells.remove(old);
        }

        /*
         * Ставим новые источники.
         *
         * Важное правило: LIGHT разрешено размещать только вместо воздуха
         * или воды/bubble-column. Лаву, растения и другие реальные блоки
         * мы не заменяем временным светом.
         */
        for (java.util.Map.Entry<BlockKey, Integer> entry
                : desired.entrySet()) {

            BlockKey key = entry.getKey();

            if (activeLightCells.containsKey(key)) {
                Block existing = anchorCenter.getWorld().getBlockAt(
                        key.x(),
                        key.y(),
                        key.z()
                );

                if (existing.getType() == Material.LIGHT) {
                    int wantedLevel = Math.max(
                            1,
                            Math.min(15, entry.getValue())
                    );

                    org.bukkit.block.data.type.Light light =
                            (org.bukkit.block.data.type.Light)
                                    existing.getBlockData().clone();

                    if (light.getLevel() != wantedLevel) {
                        light.setLevel(wantedLevel);
                        existing.setBlockData(light, false);
                    }
                }

                continue;
            }

            Block block = anchorCenter.getWorld().getBlockAt(
                    key.x(),
                    key.y(),
                    key.z()
            );

            Material previousType = block.getType();

            if (!previousType.isAir()
                    && previousType != Material.WATER
                    && previousType != Material.BUBBLE_COLUMN) {
                continue;
            }

            activeLightCells.put(
                    key,
                    block.getBlockData().clone()
            );

            int wantedLevel = Math.max(
                    1,
                    Math.min(15, entry.getValue())
            );

            org.bukkit.block.data.type.Light light =
                    (org.bukkit.block.data.type.Light)
                            Material.LIGHT.createBlockData();

            light.setLevel(wantedLevel);

            light.setWaterlogged(
                    previousType == Material.WATER
                            || previousType == Material.BUBBLE_COLUMN
            );

            block.setBlockData(
                    light,
                    false
            );
        }
    }

    /**
     * Удаляет все временные LIGHT-блоки при остановке/откате активации
     * и восстанавливает содержимое занятых ими клеток.
     */
    private void clearLightBlocks() {
        if (activeLightCells.isEmpty()
                || anchorCenter == null
                || anchorCenter.getWorld() == null) {
            return;
        }

        for (java.util.Map.Entry<BlockKey, BlockData> entry
                : new java.util.HashMap<>(activeLightCells).entrySet()) {

            BlockKey key = entry.getKey();

            Block block = anchorCenter.getWorld().getBlockAt(
                    key.x(),
                    key.y(),
                    key.z()
            );

            if (block.getType() == Material.LIGHT) {
                block.setBlockData(
                        entry.getValue().clone(),
                        false
                );
            }
        }

        activeLightCells.clear();
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

    private static void rotateData(BlockData data, int rotations) {
        switch (Math.floorMod(rotations, 4)) {
            case 1 ->
                    data.rotate(StructureRotation.CLOCKWISE_90);
            case 2 ->
                    data.rotate(StructureRotation.CLOCKWISE_180);
            case 3 ->
                    data.rotate(StructureRotation.COUNTERCLOCKWISE_90);
            default -> {
                // Без поворота ничего делать не нужно.
            }
        }
    }

    public Player getPilot() {
        return pilot;
    }

    public ArmorStand getRootEntity() {
        return rootEntity;
    }

    private record BlockKey(int x, int y, int z) {
    }

    private record LightSource(
            int localX,
            int localY,
            int localZ,
            int level
    ) {
    }

    private record RestoreEntry(
            Block block,
            BlockData blockData,
            BlockState snapshot,
            ItemStack[] items
    ) {
    }
}
