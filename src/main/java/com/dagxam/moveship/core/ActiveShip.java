package com.dagxam.moveship.core;

import com.dagxam.moveship.MoveShipPlugin;
import org.bukkit.Axis;
import org.bukkit.Input;
import org.bukkit.Bukkit;
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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Активный корабль MoveShip.
 *
 * Архитектура основана на проверенной схеме современных ship-плагинов:
 *
 * 1. Реальные блоки временно снимаются из мира после полного snapshot.
 * 2. Невидимый ArmorStand является только якорем штурвала и пассажира.
 * 3. Все BlockDisplay имеют одну общую мировую позицию — позицию якоря.
 * 4. Фактическое положение каждого блока внутри корабля задается
 *    Transformation Matrix.
 * 5. Поворот корабля хранится отдельно во внутреннем float shipYaw.
 *
 * Важно: yaw ArmorStand после активации НЕ изменяется. Это предотвращает
 * вмешательство entity tracker в взгляд игрока. Курс корабля существует
 * только внутри этой модели.
 */
public class ActiveShip {

    private static final double HELM_VERTICAL_OFFSET = 0.45;

    /*
     * Профиль движения.
     *
     * Скорости близки к современной лодочной/корабельной модели, но
     * разгон и торможение сглажены, чтобы W/S не давали резкий скачок.
     */
    private static final double MAX_FORWARD_SPEED = 0.40;
    private static final double MAX_REVERSE_SPEED = 0.20;

    /*
     * Плавная физика без резкого скачка скорости:
     * ускорение и торможение идут небольшими фиксированными шагами.
     */
    private static final double SPEED_ACCELERATION = 0.025;
    private static final double SPEED_DECELERATION = 0.025;

    /*
     * Внутренняя угловая скорость.
     * Она не записывается в yaw ArmorStand.
     */
    private static final float MAX_TURN_SPEED = 1.50f;
    private static final float TURN_ACCELERATION = 0.10f;
    private static final float TURN_DECELERATION = 0.14f;

    /*
     * Длительность визуальной интерполяции Transformation.
     * Положение Display наследуется от passenger-chain, поэтому
     * teleportDuration для дочерних Display равен 0.
     */
    private static final int DISPLAY_INTERPOLATION_DURATION = 2;

    private final Player pilot;
    private final MoveShipPlugin plugin;

    /**
     * Настоящий vehicle/seat игрока.
     */
    private final ArmorStand helmAnchor;

    /**
     * Collision построенного корабля, полностью независимая от Entity hitbox.
     */
    private final ShipCollision collisionModel;

    private final List<ShipBlockData> originalBlocks =
            new ArrayList<>();

    /**
     * Визуальный root корабля.
     *
     * Цепочка:
     * ArmorStand carrier -> parent BlockDisplay -> block displays.
     *
     * Положение parent наследуется от carrier, а дочерние Display меняют
     * только локальную Transformation.
     */
    private final BlockDisplay displayRoot;

    private final List<BlockDisplay> displayEntities =
            new ArrayList<>();

    /**
     * Матрица на каждый Display. Один объект переиспользуется.
     */
    private final List<Matrix4f> displayMatrices =
            new ArrayList<>();

    /**
     * Центр исходного anchor-блока корабля.
     *
     * Эта точка является физическим центром вращения корабля.
     */
    private Location anchorCenter;

    /**
     * Реальный внутренний курс корабля.
     * ArmorStand yaw намеренно не синхронизируется с этим значением.
     */
    private float shipYaw;

    private final float initialYaw;

    private double currentSpeed;
    private float currentTurn;

    private boolean forwardPressed;
    private boolean backwardPressed;
    private boolean leftPressed;
    private boolean rightPressed;

    /*
     * Диагностические флаги. Они не меняют физику, но позволяют точно
     * установить по server.log, приходит ли W/A/S/D, блокирует ли collision
     * движение и успешно ли перемещается carrier.
     */
    private boolean inputDiagnosticLogged;
    private boolean collisionDiagnosticLogged;
    private boolean teleportDiagnosticLogged;

    private BukkitTask task;

    private final Set<BP> submergedWake =
            new HashSet<>();

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
            throw new IllegalArgumentException(
                    "Корабль не содержит блоков"
            );
        }

        if (anchorLocation == null
                || anchorLocation.getWorld() == null) {
            throw new IllegalArgumentException(
                    "У корабля отсутствует мир"
            );
        }

        if (pilot == null || !pilot.isOnline()) {
            throw new IllegalArgumentException(
                    "Пилот недоступен"
            );
        }

        this.pilot = pilot;
        this.plugin =
                JavaPlugin.getPlugin(
                        MoveShipPlugin.class
                );

        /*
         * Anchor блока управления:
         *
         * x/z = центр блока,
         * y   = центр блока + 0.45 — это точка посадки штурвала.
         */
        this.anchorCenter = anchorLocation
                .getBlock()
                .getLocation()
                .add(0.5, 0.0, 0.5);

        Location helmLocation =
                anchorCenter.clone();

        helmLocation.add(
                0.0,
                HELM_VERTICAL_OFFSET,
                0.0
        );

        /*
         * Направление корабля определяется лицевой стороной блока управления.
         *
         * Это принципиально важно: W должен вести корабль туда, куда
         * «смотрит» установленная кафедра управления, а не в сторону,
         * куда в момент активации повернут игрок.
         *
         * Если по какой-то причине блок управления не directional,
         * используем направление игрока как fallback.
         */
        float controllerYaw =
                resolveControllerYaw(
                        anchorLocation,
                        pilot.getLocation().getYaw()
                );

        this.shipYaw = norm(controllerYaw);
        this.initialYaw = this.shipYaw;

        /*
         * Вода фиксируется до удаления реальных блоков.
         */
        captureSubmergedWake(blocks);

        /*
         * ПОЛНЫЙ snapshot до изменения мира.
         */
        for (Block block : blocks) {
            if (block.getWorld()
                    != anchorLocation.getWorld()) {
                throw new IllegalArgumentException(
                        "Все блоки корабля должны находиться в одном мире"
                );
            }

            int localX =
                    block.getX()
                            - anchorLocation.getBlockX();

            int localY =
                    block.getY()
                            - anchorLocation.getBlockY();

            int localZ =
                    block.getZ()
                            - anchorLocation.getBlockZ();

            BlockData blockData =
                    block.getBlockData().clone();

            BlockState snapshot =
                    block.getState(true);

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
         * Collision строится по сохраненному BlockData пока физические
         * блоки еще существуют/доступна исходная геометрия.
         */
        this.collisionModel =
                new ShipCollision(
                        originalBlocks,
                        anchorCenter,
                        initialYaw
                );

        /*
         * Теперь реальные блоки убираются из мира.
         */
        for (Block block : blocks) {
            if (block.getState() instanceof
                    org.bukkit.block.Container container) {
                /*
                 * Состояние уже сохранено. Очищаем world inventory, чтобы
                 * одновременно не существовало двух копий содержимого.
                 */
                container.getInventory().clear();
            }

            block.setType(
                    Material.AIR,
                    false
            );
        }

        fillInitialWater();

        /*
         * Невидимый ArmorStand — тот же базовый подход, который использует
         * SimpleShips. В отличие от Boat его модель не появляется в мире.
         *
         * marker=false сохраняет нормальную пассажирскую высоту.
         */
        this.helmAnchor =
                anchorLocation.getWorld().spawn(
                        helmLocation,
                        ArmorStand.class,
                        stand -> {
                            stand.setSmall(true);
                            stand.setInvisible(true);
                            stand.setMarker(false);
                            /*
                             * ArmorStand используется как отдельный root carrier.
                             * Гравитация выключена: вертикальная позиция корабля
                             * управляется только нашей физикой, а горизонтальное
                             * движение передается через Entity#setVelocity().
                             */
                            stand.setGravity(false);
                            stand.setCanMove(true);
                            stand.setInvulnerable(true);
                            stand.setPersistent(false);
                            stand.setSilent(true);

                            /*
                             * Курс Entity замораживаем на исходном yaw.
                             * Внутренний shipYaw будет меняться отдельно.
                             */
                            stand.setRotation(
                                    this.initialYaw,
                                    0.0f
                            );
                        }
                );

        /*
         * Игрок является пассажиром собственного невидимого штурвала.
         * Мы не изменяем его yaw/pitch во время движения.
         */
        if (!helmAnchor.addPassenger(pilot)) {
            helmAnchor.remove();

            throw new IllegalStateException(
                    "Не удалось посадить игрока на штурвал"
            );
        }

        /*
         * Визуальный root является пассажиром carrier.
         *
         * Позиция root больше не телепортируется из tick(). Она наследуется
         * от движущегося ArmorStand.
         */
        this.displayRoot =
                anchorLocation.getWorld().spawn(
                        helmLocation,
                        BlockDisplay.class,
                        entity -> {
                            entity.setBlock(
                                    Bukkit.createBlockData(Material.AIR)
                            );
                            entity.setPersistent(false);
                            entity.setGravity(false);
                            entity.setTeleportDuration(0);
                            entity.setInterpolationDelay(0);
                            entity.setInterpolationDuration(
                                    DISPLAY_INTERPOLATION_DURATION
                            );
                            entity.setDisplayWidth(0.0f);
                            entity.setDisplayHeight(0.0f);
                            entity.setViewRange(64.0f);
                        }
                );

        /*
         * Все BlockDisplay создаются рядом с визуальным root.
         * После passenger-chain их мировая позиция следует за carrier.
         */
        for (ShipBlockData block : originalBlocks) {
            Matrix4f matrix =
                    createDisplayTransform(
                            block,
                            0.0f
                    );

            BlockDisplay display =
                    anchorLocation.getWorld().spawn(
                            helmLocation,
                            BlockDisplay.class,
                            entity -> {
                                entity.setBlock(
                                        block.getBlockData().clone()
                                );

                                entity.setPersistent(false);
                                entity.setGravity(false);

                                /*
                                 * Положение child наследуется от parent
                                 * BlockDisplay через passenger-chain.
                                 */
                                entity.setTeleportDuration(0);
                                entity.setInterpolationDelay(0);
                                entity.setInterpolationDuration(
                                        DISPLAY_INTERPOLATION_DURATION
                                );

                                /*
                                 * Большой корабль не должен исчезать при
                                 * перемещении относительно entity origin.
                                 */
                                entity.setDisplayWidth(0.0f);
                                entity.setDisplayHeight(0.0f);
                                entity.setViewRange(64.0f);
                            }
                    );

            displayEntities.add(display);
            displayMatrices.add(matrix);

            display.setTransformationMatrix(
                    matrix
            );
        }

        /*
         * Собираем визуальную passenger-chain:
         *
         * ArmorStand carrier
         *        |
         *        +-- parent BlockDisplay
         *               |
         *               +-- BlockDisplay x N
         *
         * Player остаётся отдельным пассажиром carrier.
         */
        for (BlockDisplay display : displayEntities) {
            if (!displayRoot.addPassenger(display)) {
                displayRoot.remove();
                for (BlockDisplay spawned : displayEntities) {
                    if (spawned.isValid()) {
                        spawned.remove();
                    }
                }
                helmAnchor.remove();
                throw new IllegalStateException(
                        "Не удалось собрать passenger-chain корабля"
                );
            }
        }

        if (!helmAnchor.addPassenger(displayRoot)) {
            displayRoot.remove();
            for (BlockDisplay spawned : displayEntities) {
                if (spawned.isValid()) {
                    spawned.remove();
                }
            }
            helmAnchor.remove();
            throw new IllegalStateException(
                    "Не удалось привязать визуальный root к carrier"
            );
        }

        /*
         * Один server task на корабль.
         */
        this.task =
                Bukkit.getScheduler().runTaskTimer(
                        plugin,
                        this::tick,
                        1L,
                        1L
                );

        plugin.getLogger().info(
                "Корабль активирован: блоков="
                        + originalBlocks.size()
                        + ", collision-boxes="
                        + collisionModel.getCollisionBoxCount()
                        + ", yaw="
                        + String.format(java.util.Locale.ROOT, "%.2f", shipYaw)
                        + ", core="
                        + anchorLocation.getBlockX()
                        + ","
                        + anchorLocation.getBlockY()
                        + ","
                        + anchorLocation.getBlockZ()
        );
    }

    /**
     * Получение input от Paper PlayerInputEvent.
     */
    public void setInput(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right
    ) {
        this.forwardPressed = forward;
        this.backwardPressed = backward;
        this.leftPressed = left;
        this.rightPressed = right;
    }

    private void tick() {
        if (!pilot.isOnline()
                || !helmAnchor.isValid()) {
            restoreBlocks();
            return;
        }

        /*
         * Должен оставаться пассажиром.
         * Если другой механизм его снял — считаем это выходом со штурвала.
         */
        if (pilot.getVehicle() != helmAnchor) {
            restoreBlocks();
            return;
        }

        /*
         * После прошлого тика ArmorStand уже получил velocity и был
         * перемещен обычной entity-физикой. Используем его фактическую
         * позицию как источник истины.
         *
         * Это принципиально отличается от постоянного teleport() seat-anchor:
         * пассажир движется вместе с реальным velocity carrier-а, как в
         * BlockShips, а не получает новый teleport каждый тик.
         */
        Location actualHelmLocation =
                helmAnchor.getLocation();

        anchorCenter =
                actualHelmLocation.clone()
                        .subtract(
                                0.0,
                                HELM_VERTICAL_OFFSET,
                                0.0
                        );

        /*
         * Не полагаемся только на PlayerInputEvent для удержания клавиш.
         * Paper предоставляет текущее состояние input игрока через
         * Player#getCurrentInput(), поэтому W/A/S/D считываются каждый тик.
         * Это исключает ситуацию: нажал W -> корабль чуть двинулся ->
         * состояние больше не обновилось -> корабль остановился.
         */
        Input input = pilot.getCurrentInput();

        setInput(
                input.isForward(),
                input.isBackward(),
                input.isLeft(),
                input.isRight()
        );

        if (!inputDiagnosticLogged
                && (input.isForward()
                || input.isBackward()
                || input.isLeft()
                || input.isRight())) {
            inputDiagnosticLogged = true;

            plugin.getLogger().info(
                    "Управление кораблем получено: W="
                            + input.isForward()
                            + ", S="
                            + input.isBackward()
                            + ", A="
                            + input.isLeft()
                            + ", D="
                            + input.isRight()
            );
        }

        updatePhysics();

        Location oldCenter =
                anchorCenter.clone();

        double speed =
                currentSpeed;

        /*
         * Сначала вычисляем новый курс, затем по нему строим движение.
         *
         * Раньше позиция считалась по старому shipYaw, а корпус в том же
         * тике уже разворачивался на desiredYaw. Из-за этого при W + A/D
         * корпус визуально поворачивал раньше точки движения и корабль
         * начинал "ехать боком".
         *
         * Теперь один тик движения является частью плавной дуги поворота:
         * корабль одновременно меняет курс и проходит соответствующий
         * участок траектории.
         */
        float desiredYaw =
                norm(
                        shipYaw
                                + currentTurn
                );

        /*
         * Направление движения строится из ТОГО ЖЕ yaw, который станет
         * курсом корабля в этом тике.
         *
         * Это тот же принцип, что используется в SimpleShips/BlockShips:
         * forward = (-sin(yaw), 0, cos(yaw)).
         *
         * Никакой отдельной дуговой поправки здесь нет. Поэтому W всегда
         * означает строго вперед по продольной оси корабля, а A/D только
         * меняют курс.
         */
        double yawRadians =
                Math.toRadians(desiredYaw);

        Vector forward =
                new Vector(
                        -Math.sin(yawRadians),
                        0.0,
                        Math.cos(yawRadians)
                );

        Location desiredCenter =
                anchorCenter.clone().add(
                        forward.getX() * speed,
                        0.0,
                        forward.getZ() * speed
                );

        boolean wantsMove =
                Math.abs(speed) > 0.00001;

        boolean wantsTurn =
                Math.abs(
                        normalizeDelta(
                                desiredYaw
                                        - shipYaw
                        )
                ) > 0.00001f;

        if (wantsMove || wantsTurn) {
            /*
             * Сначала пробуем выполнить полный шаг одновременно:
             * движение + поворот.
             */
            boolean blocked =
                    collisionModel.collidesBetweenTransforms(
                            anchorCenter.getWorld(),
                            anchorCenter,
                            shipYaw,
                            desiredCenter,
                            desiredYaw
                    );

            if (!blocked) {
                collisionDiagnosticLogged = false;

                anchorCenter =
                        desiredCenter;

                shipYaw =
                        desiredYaw;
            } else {
                if (wantsMove && !collisionDiagnosticLogged) {
                    collisionDiagnosticLogged = true;

                    plugin.getLogger().warning(
                            "Корабль заблокирован collision: "
                                    + "speed="
                                    + String.format(java.util.Locale.ROOT, "%.4f", speed)
                                    + ", boxes="
                                    + collisionModel.getCollisionBoxCount()
                                    + ", from="
                                    + formatLocation(anchorCenter)
                                    + ", to="
                                    + formatLocation(desiredCenter)
                    );
                }
                /*
                 * Если движение уперлось, все равно разрешаем чистый
                 * поворот на месте, если он безопасен.
                 */
                if (wantsTurn
                        && collisionModel.collidesBetweenTransforms(
                        anchorCenter.getWorld(),
                        anchorCenter,
                        shipYaw,
                        anchorCenter,
                        desiredYaw
                )) {
                    currentTurn *= 0.25f;
                } else {
                    shipYaw =
                            desiredYaw;

                    if (wantsMove) {
                        currentSpeed *= 0.20;
                    }
                }
            }
        }

        /*
         * Перемещаем carrier по принятому физикой состоянию.
         *
         * Его yaw остается исходным — мышь игрока полностью независима
         * от курса корабля.
         *
         * Сначала ставим точную позицию carrier, затем передаем фактическое
         * смещение через velocity для корректной клиентской синхронизации.
         */
        /*
         * Физическое положение carrier должно быть установлено напрямую.
         *
         * Это важная часть схемы BlockShips:
         * 1) физика вычисляет принятую точку;
         * 2) carrier перемещается в эту точку;
         * 3) velocity получает фактическое смещение за тик.
         *
         * Velocity здесь не является единственным механизмом перемещения.
         * Он нужен для корректной синхронизации/предсказания клиента.
         */
        Location acceptedHelmLocation =
                anchorCenter.clone()
                        .add(
                                0.0,
                                HELM_VERTICAL_OFFSET,
                                0.0
                        );

        acceptedHelmLocation.setYaw(
                initialYaw
        );
        acceptedHelmLocation.setPitch(
                0.0f
        );

        boolean teleported =
                helmAnchor.teleport(
                        acceptedHelmLocation
                );

        if (!teleported && !teleportDiagnosticLogged) {
            teleportDiagnosticLogged = true;

            plugin.getLogger().warning(
                    "Не удалось телепортировать carrier корабля в "
                            + formatLocation(acceptedHelmLocation)
            );
        } else if (teleported) {
            teleportDiagnosticLogged = false;
        }

        Vector carrierVelocity =
                anchorCenter
                        .toVector()
                        .subtract(
                                actualHelmLocation.toVector()
                                        .subtract(
                                                new Vector(
                                                        0.0,
                                                        HELM_VERTICAL_OFFSET,
                                                        0.0
                                                )
                                        )
                        );

        helmAnchor.setVelocity(
                carrierVelocity
        );

        /*
         * Визуальный корпус следует за carrier через passenger-chain.
         * Дочерние Display не телепортируются в мировые координаты.
         * Здесь обновляется только их локальная Transformation.
         */
        updateDisplays();

        /*
         * Вода обновляется после принятия нового состояния.
         */
        if (!sameCenter(oldCenter, anchorCenter)) {
            fillWater();
        }
    }

    private void updatePhysics() {
        double targetSpeed = 0.0;

        if (forwardPressed
                && !backwardPressed) {
            targetSpeed =
                    MAX_FORWARD_SPEED;
        } else if (backwardPressed
                && !forwardPressed) {
            targetSpeed =
                    -MAX_REVERSE_SPEED;
        }

        double speedStep =
                Math.abs(targetSpeed) < Math.abs(currentSpeed)
                        ? SPEED_DECELERATION
                        : SPEED_ACCELERATION;

        currentSpeed =
                moveTowards(
                        currentSpeed,
                        targetSpeed,
                        speedStep
                );

        if (Math.abs(currentSpeed)
                < 0.0005) {
            currentSpeed = 0.0;
        }

        float targetTurn =
                0.0f;

        if (leftPressed
                && !rightPressed) {
            /*
             * В системе Minecraft yaw:
             * A (влево) уменьшает yaw,
             * D (вправо) увеличивает yaw.
             *
             * Именно такую схему использует SimpleShips.
             */
            targetTurn =
                    -MAX_TURN_SPEED;
        } else if (rightPressed
                && !leftPressed) {
            targetTurn =
                    MAX_TURN_SPEED;
        }

        float turnStep =
                Math.abs(targetTurn)
                        < Math.abs(currentTurn)
                        ? TURN_DECELERATION
                        : TURN_ACCELERATION;

        currentTurn =
                (float) moveTowards(
                        currentTurn,
                        targetTurn,
                        turnStep
                );

        if (Math.abs(currentTurn)
                < 0.005f) {
            currentTurn = 0.0f;
        }
    }

    private void updateDisplays() {
        for (int i = 0;
             i < originalBlocks.size();
             i++) {

            BlockDisplay display =
                    displayEntities.get(i);

            if (!display.isValid()) {
                continue;
            }

            /*
             * Position наследуется от displayRoot -> helmAnchor.
             * Здесь меняется только локальная Transformation:
             * геометрия блока + текущий yaw корабля.
             */

            Matrix4f matrix =
                    displayMatrices.get(i);

            double delta =
                    Math.toRadians(
                            normalizeDelta(
                                    shipYaw
                                            - initialYaw
                            )
                    );

            /*
             * Визуальный поворот должен иметь тот же знак, что и физический
             * forward-вектор. BlockShips применяет -deltaYaw для Display.
             */
            matrix.identity()
                    .rotateY(
                            (float) -delta
                    );

            ShipBlockData block =
                    originalBlocks.get(i);

            matrix.translate(
                    block.getLocalX()
                            - 0.5f,
                    block.getLocalY()
                            - (float) HELM_VERTICAL_OFFSET,
                    block.getLocalZ()
                            - 0.5f
            );

            /*
             * Matrix меняем каждый tick потому, что внутренний shipYaw
             * является непрерывным float-значением.
             *
             * Carrier перемещает корабль через velocity, а клиент сглаживает
             * изменение Transformation на два тика.
             */
            display.setTransformationMatrix(
                    matrix
            );
        }
    }

    private static Matrix4f createDisplayTransform(
            ShipBlockData block,
            float deltaDegrees
    ) {
        Matrix4f matrix =
                new Matrix4f().identity();

        matrix.rotateY(
                (float) Math.toRadians(
                        deltaDegrees
                )
        );

        matrix.translate(
                block.getLocalX()
                        - 0.5f,
                block.getLocalY()
                        - (float) HELM_VERTICAL_OFFSET,
                block.getLocalZ()
                        - 0.5f
        );

        return matrix;
    }

    private static String formatLocation(Location location) {
        return String.format(
                java.util.Locale.ROOT,
                "(%.3f, %.3f, %.3f)",
                location.getX(),
                location.getY(),
                location.getZ()
        );
    }

    /**
     * Проверяет, изменился ли центр корабля.
     */
    private static boolean sameCenter(
            Location a,
            Location b
    ) {
        return a.getWorld()
                == b.getWorld()
                && Math.abs(
                a.getX() - b.getX()
        ) < 0.000001
                && Math.abs(
                a.getY() - b.getY()
        ) < 0.000001
                && Math.abs(
                a.getZ() - b.getZ()
        ) < 0.000001;
    }

    private static double moveTowards(
            double current,
            double target,
            double maxStep
    ) {
        double delta = target - current;

        if (Math.abs(delta) <= maxStep) {
            return target;
        }

        return current + Math.copySign(maxStep, delta);
    }

    private static float moveTowards(
            float current,
            float target,
            float maxStep
    ) {
        float delta = target - current;

        if (Math.abs(delta) <= maxStep) {
            return target;
        }

        return current + Math.copySign(maxStep, delta);
    }

    /**
     * Возвращает yaw направления, куда смотрит установленная кафедра.
     *
     * Minecraft yaw:
     * SOUTH = 0, WEST = 90, NORTH = 180, EAST = -90.
     *
     * Directional BlockFace переводится напрямую в эту систему координат.
     */
    private static float resolveControllerYaw(
            Location controllerLocation,
            float fallbackYaw
    ) {
        if (controllerLocation != null) {
            BlockData data =
                    controllerLocation.getBlock()
                            .getBlockData();

            if (data instanceof Directional directional) {
                BlockFace facing =
                        directional.getFacing()
                                .getOppositeFace();

                if (facing.getModX() != 0
                        || facing.getModZ() != 0) {
                    /*
                     * Lectern facing смотрит на сторону игрока.
                     * Нос корабля направляем в противоположную сторону —
                     * от игрока, от штурвала к носу.
                     *
                     * Minecraft yaw:
                     * SOUTH = 0, WEST = 90,
                     * NORTH = 180, EAST = -90.
                     */
                    return norm(
                            (float) Math.toDegrees(
                                    Math.atan2(
                                            -facing.getModX(),
                                            facing.getModZ()
                                    )
                            )
                    );
                }
            }
        }

        return norm(fallbackYaw);
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

        /*
         * Геометрия корпуса визуально поворачивается на -deltaYaw,
         * поэтому водяной след использует тот же знак.
         */
        double delta = Math.toRadians(
                -(shipYaw - initialYaw)
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

        if (helmAnchor.isValid()) {
            helmAnchor.setVelocity(new Vector());
        }

        Location playerLocation = pilot.getLocation().clone();
        float playerYaw = playerLocation.getYaw();
        float playerPitch = playerLocation.getPitch();

        if (helmAnchor.isValid()) {
            helmAnchor.eject();
            helmAnchor.remove();
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

        if (displayRoot.isValid()) {
            displayRoot.remove();
        }

        for (BlockDisplay display : displayEntities) {
            if (display.isValid()) {
                display.remove();
            }
        }

        displayEntities.clear();
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

    private record RestoreEntry(
            Block block,
            BlockData blockData,
            BlockState snapshot
    ) {
    }
}