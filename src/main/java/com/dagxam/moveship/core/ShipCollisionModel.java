package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Серверная collision-модель построенного корабля.
 *
 * Модель состоит из точных collision-boxes всех исходных BlockData.
 * Она не зависит от визуальных BlockDisplay и не использует маленький
 * bounding box технической Boat.
 */
public final class ShipCollisionModel {

    private static final double EPSILON = 1.0E-7;

    private final List<BoundingBox> localBoxes;
    private final BoundingBox localBounds;

    public ShipCollisionModel(List<BoundingBox> localBoxes) {
        this.localBoxes = new ArrayList<>(localBoxes);

        BoundingBox bounds = null;

        for (BoundingBox box : this.localBoxes) {
            if (box.getVolume() <= 0.0) {
                continue;
            }

            if (bounds == null) {
                bounds = box.clone();
            } else {
                bounds.union(box);
            }
        }

        this.localBounds = bounds == null
                ? new BoundingBox(0, 0, 0, 0, 0, 0)
                : bounds;
    }

    public List<BoundingBox> getLocalBoxes() {
        return List.copyOf(localBoxes);
    }

    /**
     * Проверяет всю форму корабля в заданной позиции и с заданным yaw.
     */
    public boolean collides(
            World world,
            Location shipCenter,
            float shipYaw
    ) {
        if (localBoxes.isEmpty()) {
            return false;
        }

        double radians = Math.toRadians(shipYaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        for (BoundingBox localBox : localBoxes) {
            BoundingBox worldBox = rotateAndTranslate(
                    localBox,
                    shipCenter.getX(),
                    shipCenter.getY(),
                    shipCenter.getZ(),
                    cos,
                    sin
            );

            if (collidesWithBlocks(world, worldBox)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Быстрая coarse-проверка общей AABB корабля.
     *
     * Используется перед детальной проверкой, чтобы не перебирать мир,
     * если общая форма точно не пересекает блоки.
     */
    public boolean coarseCollides(
            World world,
            Location shipCenter,
            float shipYaw
    ) {
        if (localBounds.getVolume() <= 0.0) {
            return false;
        }

        double radians = Math.toRadians(shipYaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        BoundingBox box = rotateAndTranslate(
                localBounds,
                shipCenter.getX(),
                shipCenter.getY(),
                shipCenter.getZ(),
                cos,
                sin
        );

        return collidesWithBlocks(world, box);
    }

    private static BoundingBox rotateAndTranslate(
            BoundingBox box,
            double centerX,
            double centerY,
            double centerZ,
            double cos,
            double sin
    ) {
        double[] xs = {
                box.getMinX(),
                box.getMaxX()
        };

        double[] zs = {
                box.getMinZ(),
                box.getMaxZ()
        };

        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (double x : xs) {
            for (double z : zs) {
                double rx = x * cos - z * sin;
                double rz = x * sin + z * cos;

                minX = Math.min(minX, rx);
                minZ = Math.min(minZ, rz);
                maxX = Math.max(maxX, rx);
                maxZ = Math.max(maxZ, rz);
            }
        }

        return new BoundingBox(
                centerX + minX,
                centerY + box.getMinY(),
                centerZ + minZ,
                centerX + maxX,
                centerY + box.getMaxY(),
                centerZ + maxZ
        );
    }

    private static boolean collidesWithBlocks(
            World world,
            BoundingBox query
    ) {
        int minX = floor(query.getMinX());
        int minY = floor(query.getMinY());
        int minZ = floor(query.getMinZ());

        int maxX = ceilToBlock(query.getMaxX());
        int maxY = ceilToBlock(query.getMaxY());
        int maxZ = ceilToBlock(query.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    continue;
                }

                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);

                    /*
                     * Читаем реальную collision shape мира.
                     * Воздух, вода и прочие блоки без collision shape
                     * автоматически дают пустую форму.
                     */
                    if (!block.getType().hasCollision()) {
                        continue;
                    }

                    if (block.getCollisionShape().overlaps(
                            query.expand(EPSILON)
                    )) {
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

    private static int ceilToBlock(double value) {
        return (int) Math.ceil(value - EPSILON);
    }
}
