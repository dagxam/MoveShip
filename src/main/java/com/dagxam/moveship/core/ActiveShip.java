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

    /**
     * Физические блоки корабля удаляются не при самой активации,
     * а только при первом реальном движении/повороте.
     *
     * Это не оставляет пустую площадку сразу после нажатия
     * «Активировать» и одновременно не мешает кораблю затем
     * покинуть исходное место.
     */
    private boolean physicalBlocksCleared;

    private final int originBlockX;
    private final int originBlockY;
    private final int originBlockZ;

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

            if (snapshot instanceof Container container) {
                /*
                 * На этапе активации живой контейнер НЕ очищаем.
                 * Мы пока не удаляем физический корабль из мира.
                 *
                 * Инвентарь только копируется в snapshot.
                 * Физическое очищение контейнера выполняется атомарно
                 * вместе с удалением блоков в момент первого движения.
                 */
                items = deepCopy(container.getInventory());
            }

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

            for (ShipBlockData block : originalBlocks) {
                Location initialLocation =
                        blockWorldLocation(block, anchorCenter, 0.0f);
                Matrix4f matrix = createBlockMatrix(0.0f);

                BlockDisplay display =
                        anchorLocation.getWorld().spawn(
                                initialLocation,
                                BlockDisplay.class,
                                entity -> {
                                    entity.setBlock(
                                            block.getBlockData().clone()
                                    );
                                    entity.setPersistent(false);
                                    entity.setTeleportDuration(1);
                                    entity.setInterpolationDelay(0);
                                    entity.setInterpolationDuration(1);
                                }
                        );

                displayEntities.add(display);
                displayLocations.add(initialLocation);
                displayMatrices.add(matrix);
                display.setTransformationMatrix(matrix);
            }

            /*
             * ВАЖНО:
             * при самой активации физический корабль пока НЕ удаляем.
             *
             * BlockDisplay уже создан поверх реальной конструкции, поэтому
             * игрок сразу видит активный корабль, а исходное место не
             * превращается в пустоту.
             *
             * Физические блоки и контейнеры будут безопасно сняты только
             * при первом реальном движении/повороте.
             */
        } catch (RuntimeException ex) {
            for (BlockDisplay display : displayEntities) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
            }

            displayEntities.clear();
            displayLocations.clear();
            displayMatrices.clear();

            if (rootEntity != null && rootEntity.isValid()) {
                rootEntity.eject();
                rootEntity.remove();
            }

            if (physicalBlocksCleared) {
                clearLightBlocks();
                restoreOriginalBlocks();
            }

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
             * До первого реального движения физическая конструкция
             * остаётся в мире.
             *
             * Как только корабль действительно начал двигаться или
             * поворачиваться, безопасно удаляем исходные блоки. После этого
             * BlockDisplay становится единственным визуальным корпусом.
             */
            clearPhysicalBlocks();

            /*
             * Корпус обновляется КАЖДЫЙ тик:
             * это одновременно перемещение и вращение.
             */
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
            rootEntity.setVelocity(new Vector(dx, dy, dz));
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
     * Удаляет физический корабль из его ИСХОДНОЙ позиции только тогда,
     * когда корабль действительно начал движение/поворот.
     *
     * Все контейнеры очищаются непосредственно перед setType(AIR),
     * чтобы сундуки, бочки, печи и другие TileEntity не выбросили предметы.
     */
    private void clearPhysicalBlocks() {
        if (physicalBlocksCleared) {
            return;
        }

        World world = anchorCenter.getWorld();

        if (world == null) {
            return;
        }

        for (ShipBlockData data : originalBlocks) {
            Block block = world.getBlockAt(
                    originBlockX + data.getLocalX(),
                    originBlockY + data.getLocalY(),
                    originBlockZ + data.getLocalZ()
            );

            /*
             * Защита от удаления чужого блока, если другой плагин или игрок
             * успел изменить исходную клетку до первого движения.
             */
            if (!block.getBlockData().getMaterial().equals(
                    data.getBlockData().getMaterial()
            )) {
                continue;
            }

            if (data.getItems() != null
                    && block.getState() instanceof Container container) {
                container.getInventory().clear();
                container.update(true, false);
            }

            block.setType(Material.AIR, false);
        }

        physicalBlocksCleared = true;

        /*
         * После удаления исходного корпуса устанавливаем временные
         * невидимые источники реального освещения.
         */
        updateLightBlocks();
    }

    private void updateDisplays() {
        double delta = Math.toRadians(shipYaw - initialYaw);
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        boolean rotationChanged = Math.abs(currentTurn) > 0.00001f;
        float rotation = (float) -delta;

        for (int i = 0; i < originalBlocks.size(); i++) {
            BlockDisplay display = displayEntities.get(i);

            if (!display.isValid()) {
                continue;
            }

            ShipBlockData block = originalBlocks.get(i);

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

            Location location = displayLocations.get(i);

            location.setX(centerX - 0.5);
            location.setY(centerY - 0.5);
            location.setZ(centerZ - 0.5);
            location.setYaw(0.0f);
            location.setPitch(0.0f);

            /*
             * Один teleport за тик. teleportDuration=1 позволяет клиенту
             * плавно интерполировать поступательное движение.
             */
            display.teleport(location);

            /*
             * Не меняем Transformation во время обычного прямолинейного
             * движения. Частая запись transformation сбрасывает клиентскую
             * интерполяцию и именно из-за этого движение начинает выглядеть
             * рывками.
             */
            if (rotationChanged) {
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
         * Если корабль был только активирован и ни разу не двигался,
         * физические блоки всё ещё находятся на месте. В таком случае
         * повторно перестраивать их не нужно.
         *
         * Их восстановление требуется только после фактического снятия
         * корпуса в момент первого движения.
         */
        if (!physicalBlocksCleared) {
            clearLightBlocks();

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

            return;
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
        if (block == null
                || items == null
                || !(block.getState() instanceof Container container)) {
            return;
        }

        Inventory inventory = container.getInventory();
        inventory.clear();

        for (int i = 0; i < Math.min(items.length, inventory.getSize()); i++) {
            ItemStack item = items[i];

            if (item != null) {
                inventory.setItem(i, item.clone());
            }
        }

        container.update(true, false);
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
