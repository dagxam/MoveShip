package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Серверная collision-модель корабля.
 *
 * В момент активации реальные блоки еще находятся в мире, поэтому их точные
 * VoxelShape сохраняются в локальных координатах относительно anchorCenter.
 *
 * После удаления блоков из мира эта модель становится единственным источником
 * физической формы корабля.
 */
public final class ShipCollision {

    private static final double EPSILON = 1.0E-7;

    private final List<LocalBox> boxes;

    public ShipCollision(Iterable<ShipBlockData> blocks, Location anchorCenter) {
        this.boxes = new ArrayList<>();

        if (anchorCenter == null || anchorCenter.getWorld() == null) {
            throw new IllegalArgumentException("Для collision-модели нужен мир");
        }

        /*
         * ShipBlockData содержит BlockData, но для точной VoxelShape нужен
         * исходный world-location каждого блока. Его восстанавливаем из
         * локальных координат snapshot'а.
         */
        for (ShipBlockData data : blocks) {
            int blockX = anchorCenter.getBlockX() + data.getLocalX();
            int blockY = anchorCenter.getBlockY() + data.getLocalY();
            int blockZ = anchorCenter.getBlockZ() + data.getLocalZ();

            Location blockLocation = new Location(
                    anchorCenter.getWorld(),
                    blockX,
                    blockY,
                    blockZ
            );

            VoxelShape shape = data.getBlockData().getCollisionShape(blockLocation);

            for (BoundingBox box : shape.getBoundingBoxes()) {
                double minX = box.getMinX() - anchorCenter.getX();
                double minY = box.getMinY() - anchorCenter.getY();
                double minZ = box.getMinZ() - anchorCenter.getZ();

                double maxX = box.getMaxX() - anchorCenter.getX();
                double maxY = box.getMaxY() - anchorCenter.getY();
                double maxZ = box.getMaxZ() - anchorCenter.getZ();

                if (maxX - minX <= EPSILON
                        || maxY - minY <= EPSILON
                        || maxZ - minZ <= EPSILON) {
                    continue;
                }

                boxes.add(new LocalBox(
                        minX,
                        minY,
                        minZ,
                        maxX,
                        maxY,
                        maxZ
                ));
            }
        }
    }

    /**
     * Проверяет столкновение корабля с физическими блоками мира
     * в указанной позиции и под указанным углом.
     *
     * Сам корабль в мире уже удален, поэтому его собственные блоки
     * не могут дать ложное столкновение.
     */
    public boolean collides(
            World world,
            Location anchorCenter,
            float yaw
    ) {
        if (boxes.isEmpty()) {
            return false;
        }

        double radians = Math.toRadians(yaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        for (LocalBox local : boxes) {
            BoundingBox worldBox = rotateBox(
                    local,
                    anchorCenter,
                    cos,
                    sin
            );

            if (collidesWithWorld(world, worldBox)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Проверка конкретного движения:
     * тестируем конечную позицию, а при больших шагах дополнительно
     * проверяем несколько промежуточных точек, чтобы корабль не мог
     * "перепрыгнуть" тонкое препятствие за один tick.
     */
    public boolean collidesOnMove(
            World world,
            Location anchorCenter,
            float yaw,
            Location targetCenter
    ) {
        double dx = targetCenter.getX() - anchorCenter.getX();
        double dy = targetCenter.getY() - anchorCenter.getY();
        double dz = targetCenter.getZ() - anchorCenter.getZ();

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distance <= EPSILON) {
            return collides(world, targetCenter, yaw);
        }

        int steps = Math.max(1, (int) Math.ceil(distance / 0.25));

        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;

            Location sample = targetCenter.clone();
            sample.add(
                    dx * t,
                    dy * t,
                    dz * t
            );

            if (collides(world, sample, yaw)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Проверяет поворот на месте через несколько промежуточных углов.
     * Это не позволяет длинному борту "перескочить" стену между двумя
     * дискретными серверными состояниями.
     */
    public boolean collidesOnRotation(
            World world,
            Location anchorCenter,
            float fromYaw,
            float toYaw
    ) {
        float delta = normalizeDelta(toYaw - fromYaw);

        int steps = Math.max(
                1,
                (int) Math.ceil(Math.abs(delta) / 2.0f)
        );

        for (int i = 1; i <= steps; i++) {
            float yaw = fromYaw + delta * ((float) i / steps);

            if (collides(world, anchorCenter, yaw)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Полная swept-проверка перехода от старого состояния корабля
     * к новому: одновременно учитываются перемещение и поворот.
     */
    public boolean collidesBetweenTransforms(
            World world,
            Location fromCenter,
            float fromYaw,
            Location toCenter,
            float toYaw
    ) {
        double dx = toCenter.getX() - fromCenter.getX();
        double dy = toCenter.getY() - fromCenter.getY();
        double dz = toCenter.getZ() - fromCenter.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        float angle = normalizeDelta(toYaw - fromYaw);

        int movementSteps = (int) Math.ceil(distance / 0.25);
        int rotationSteps = (int) Math.ceil(Math.abs(angle) / 2.0);
        int steps = Math.max(1, Math.max(movementSteps, rotationSteps));

        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;

            Location sampleCenter = fromCenter.clone();
            sampleCenter.add(
                    dx * t,
                    dy * t,
                    dz * t
            );

            float sampleYaw = fromYaw + angle * (float) t;

            if (collides(world, sampleCenter, sampleYaw)) {
                return true;
            }
        }

        return false;
    }

    public int getCollisionBoxCount() {
        return boxes.size();
    }

    private static BoundingBox rotateBox(
            LocalBox local,
            Location anchor,
            double cos,
            double sin
    ) {
        /*
         * Поворачиваем 4 угла по X/Z. Y не меняется.
         * Результат остается AABB, который полностью содержит
         * повернутую часть collision shape.
         */
        Point p1 = rotate(local.minX, local.minZ, anchor, cos, sin);
        Point p2 = rotate(local.minX, local.maxZ, anchor, cos, sin);
        Point p3 = rotate(local.maxX, local.minZ, anchor, cos, sin);
        Point p4 = rotate(local.maxX, local.maxZ, anchor, cos, sin);

        double minX = Math.min(
                Math.min(p1.x, p2.x),
                Math.min(p3.x, p4.x)
        );

        double maxX = Math.max(
                Math.max(p1.x, p2.x),
                Math.max(p3.x, p4.x)
        );

        double minZ = Math.min(
                Math.min(p1.z, p2.z),
                Math.min(p3.z, p4.z)
        );

        double maxZ = Math.max(
                Math.max(p1.z, p2.z),
                Math.max(p3.z, p4.z)
        );

        return new BoundingBox(
                minX,
                anchor.getY() + local.minY,
                minZ,
                maxX,
                anchor.getY() + local.maxY,
                maxZ
        );
    }

    private static Point rotate(
            double localX,
            double localZ,
            Location anchor,
            double cos,
            double sin
    ) {
        return new Point(
                anchor.getX() + localX * cos - localZ * sin,
                anchor.getZ() + localX * sin + localZ * cos
        );
    }

    private static boolean collidesWithWorld(
            World world,
            BoundingBox shipBox
    ) {
        /*
         * Небольшой epsilon не дает касанию гранью считаться полноценным
         * пересечением и снижает дрожание при скольжении вдоль стены.
         */
        double minX = shipBox.getMinX() + EPSILON;
        double minY = shipBox.getMinY() + EPSILON;
        double minZ = shipBox.getMinZ() + EPSILON;

        double maxX = shipBox.getMaxX() - EPSILON;
        double maxY = shipBox.getMaxY() - EPSILON;
        double maxZ = shipBox.getMaxZ() - EPSILON;

        int fromX = floor(minX);
        int toX = floor(maxX - EPSILON);
        int fromY = floor(minY);
        int toY = floor(maxY - EPSILON);
        int fromZ = floor(minZ);
        int toZ = floor(maxZ - EPSILON);

        /*
         * Защита от слишком больших/плохих диапазонов.
         */
        if (toX < fromX || toY < fromY || toZ < fromZ) {
            return false;
        }

        for (int x = fromX; x <= toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                for (int z = fromZ; z <= toZ; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        /*
                         * Целевой chunk еще не загружен самим сервером.
                         * Не заставляем collision-систему принудительно грузить
                         * мир внутри каждого tick.
                         */
                        continue;
                    }

                    Block block = world.getBlockAt(x, y, z);
                    Material material = block.getType();

                    if (material.isAir()) {
                        continue;
                    }

                    VoxelShape shape = block.getCollisionShape();

                    if (shape.getBoundingBoxes().isEmpty()) {
                        continue;
                    }

                    if (shape.overlaps(shipBox)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static float normalizeDelta(float delta) {
        delta %= 360.0f;

        if (delta > 180.0f) {
            delta -= 360.0f;
        } else if (delta < -180.0f) {
            delta += 360.0f;
        }

        return delta;
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

    private record Point(double x, double z) {
    }
}
