package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Точная серверная collision-модель построенного корабля.
 *
 * Форма строится ДО удаления физических блоков из мира:
 * для каждого исходного BlockData берется его реальный VoxelShape.
 *
 * После активации модель независима от Boat и BlockDisplay.
 */
public final class ShipCollision {

    private static final double EPSILON = 1.0E-7;

    private final List<LocalBox> localBoxes;

    public ShipCollision(
            Iterable<ShipBlockData> blocks,
            Location anchorCenter
    ) {
        if (anchorCenter == null || anchorCenter.getWorld() == null) {
            throw new IllegalArgumentException(
                    "Для collision-модели нужен мир"
            );
        }

        this.localBoxes = new ArrayList<>();

        for (ShipBlockData data : blocks) {
            int blockX =
                    anchorCenter.getBlockX()
                            + data.getLocalX();

            int blockY =
                    anchorCenter.getBlockY()
                            + data.getLocalY();

            int blockZ =
                    anchorCenter.getBlockZ()
                            + data.getLocalZ();

            Location blockLocation = new Location(
                    anchorCenter.getWorld(),
                    blockX,
                    blockY,
                    blockZ
            );

            BlockData blockData =
                    data.getBlockData();

            VoxelShape shape =
                    blockData.getCollisionShape(
                            blockLocation
                    );

            for (BoundingBox box :
                    shape.getBoundingBoxes()) {

                double minX =
                        box.getMinX()
                                - anchorCenter.getX();

                double minY =
                        box.getMinY()
                                - anchorCenter.getY();

                double minZ =
                        box.getMinZ()
                                - anchorCenter.getZ();

                double maxX =
                        box.getMaxX()
                                - anchorCenter.getX();

                double maxY =
                        box.getMaxY()
                                - anchorCenter.getY();

                double maxZ =
                        box.getMaxZ()
                                - anchorCenter.getZ();

                if (maxX - minX <= EPSILON
                        || maxY - minY <= EPSILON
                        || maxZ - minZ <= EPSILON) {
                    continue;
                }

                localBoxes.add(
                        new LocalBox(
                                minX,
                                minY,
                                minZ,
                                maxX,
                                maxY,
                                maxZ
                        )
                );
            }
        }
    }

    /**
     * Проверяет одновременно перемещение и поворот корабля.
     *
     * Между двумя состояниями берутся промежуточные точки, поэтому Boat
     * не сможет перескочить через препятствие на большом шаге.
     */
    public boolean collidesBetweenTransforms(
            World world,
            Location fromCenter,
            float fromYaw,
            Location toCenter,
            float toYaw
    ) {
        if (localBoxes.isEmpty()) {
            return false;
        }

        double dx =
                toCenter.getX()
                        - fromCenter.getX();

        double dy =
                toCenter.getY()
                        - fromCenter.getY();

        double dz =
                toCenter.getZ()
                        - fromCenter.getZ();

        double distance =
                Math.sqrt(
                        dx * dx
                                + dy * dy
                                + dz * dz
                );

        float yawDelta =
                normalizeDelta(
                        toYaw - fromYaw
                );

        int movementSteps =
                (int) Math.ceil(
                        distance / 0.25
                );

        int rotationSteps =
                (int) Math.ceil(
                        Math.abs(yawDelta) / 2.0
                );

        int steps =
                Math.max(
                        1,
                        Math.max(
                                movementSteps,
                                rotationSteps
                        )
                );

        for (int i = 1; i <= steps; i++) {
            double progress =
                    (double) i / steps;

            Location center =
                    fromCenter.clone();

            center.add(
                    dx * progress,
                    dy * progress,
                    dz * progress
            );

            float yaw =
                    fromYaw
                            + yawDelta
                            * (float) progress;

            if (collides(
                    world,
                    center,
                    yaw
            )) {
                return true;
            }
        }

        return false;
    }

    /**
     * Проверяет всю collision-форму корабля.
     */
    public boolean collides(
            World world,
            Location center,
            float yaw
    ) {
        if (localBoxes.isEmpty()) {
            return false;
        }

        double radians =
                Math.toRadians(yaw);

        double cos =
                Math.cos(radians);

        double sin =
                Math.sin(radians);

        for (LocalBox box : localBoxes) {
            BoundingBox worldBox =
                    rotateBox(
                            box,
                            center,
                            cos,
                            sin
                    );

            if (collidesWithBlocks(
                    world,
                    worldBox
            )) {
                return true;
            }
        }

        return false;
    }

    private static BoundingBox rotateBox(
            LocalBox box,
            Location center,
            double cos,
            double sin
    ) {
        Point p1 =
                rotate(
                        box.minX,
                        box.minZ,
                        center,
                        cos,
                        sin
                );

        Point p2 =
                rotate(
                        box.minX,
                        box.maxZ,
                        center,
                        cos,
                        sin
                );

        Point p3 =
                rotate(
                        box.maxX,
                        box.minZ,
                        center,
                        cos,
                        sin
                );

        Point p4 =
                rotate(
                        box.maxX,
                        box.maxZ,
                        center,
                        cos,
                        sin
                );

        double minX =
                Math.min(
                        Math.min(
                                p1.x,
                                p2.x
                        ),
                        Math.min(
                                p3.x,
                                p4.x
                        )
                );

        double maxX =
                Math.max(
                        Math.max(
                                p1.x,
                                p2.x
                        ),
                        Math.max(
                                p3.x,
                                p4.x
                        )
                );

        double minZ =
                Math.min(
                        Math.min(
                                p1.z,
                                p2.z
                        ),
                        Math.min(
                                p3.z,
                                p4.z
                        )
                );

        double maxZ =
                Math.max(
                        Math.max(
                                p1.z,
                                p2.z
                        ),
                        Math.max(
                                p3.z,
                                p4.z
                        )
                );

        return new BoundingBox(
                minX,
                center.getY() + box.minY,
                minZ,
                maxX,
                center.getY() + box.maxY,
                maxZ
        );
    }

    private static Point rotate(
            double x,
            double z,
            Location center,
            double cos,
            double sin
    ) {
        return new Point(
                center.getX()
                        + x * cos
                        - z * sin,

                center.getZ()
                        + x * sin
                        + z * cos
        );
    }

    private static boolean collidesWithBlocks(
            World world,
            BoundingBox query
    ) {
        double minX =
                query.getMinX()
                        + EPSILON;

        double minY =
                query.getMinY()
                        + EPSILON;

        double minZ =
                query.getMinZ()
                        + EPSILON;

        double maxX =
                query.getMaxX()
                        - EPSILON;

        double maxY =
                query.getMaxY()
                        - EPSILON;

        double maxZ =
                query.getMaxZ()
                        - EPSILON;

        int fromX =
                (int) Math.floor(minX);

        int toX =
                (int) Math.floor(
                        maxX - EPSILON
                );

        int fromY =
                (int) Math.floor(minY);

        int toY =
                (int) Math.floor(
                        maxY - EPSILON
                );

        int fromZ =
                (int) Math.floor(minZ);

        int toZ =
                (int) Math.floor(
                        maxZ - EPSILON
                );

        if (toX < fromX
                || toY < fromY
                || toZ < fromZ) {
            return false;
        }

        for (int x = fromX; x <= toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                if (y < world.getMinHeight()
                        || y >= world.getMaxHeight()) {
                    continue;
                }

                for (int z = fromZ; z <= toZ; z++) {
                    if (!world.isChunkLoaded(
                            x >> 4,
                            z >> 4
                    )) {
                        continue;
                    }

                    Block block =
                            world.getBlockAt(
                                    x,
                                    y,
                                    z
                            );

                    /*
                     * Вода и другие жидкости не являются препятствием
                     * для корабля. Корабль должен взаимодействовать с водой
                     * как со средой плавания, а не как с твердой коллизией.
                     */
                    if (block.getType().isAir()
                            || block.isLiquid()) {
                        continue;
                    }

                    VoxelShape shape =
                            block.getCollisionShape();

                    if (shape.getBoundingBoxes().isEmpty()) {
                        continue;
                    }

                    if (shape.overlaps(
                            query
                                    .clone()
                                    .expand(EPSILON)
                    )) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static float normalizeDelta(
            float delta
    ) {
        delta %= 360.0f;

        if (delta > 180.0f) {
            delta -= 360.0f;
        } else if (delta < -180.0f) {
            delta += 360.0f;
        }

        return delta;
    }

    public int getCollisionBoxCount() {
        return localBoxes.size();
    }

    private record LocalBox(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ
    ) {
    }

    private record Point(
            double x,
            double z
    ) {
    }
}
