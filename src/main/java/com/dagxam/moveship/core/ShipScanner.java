package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

public class ShipScanner {

    private static final int MAX_SHIP_SIZE = 9999;

    /*
     * Только 6 граней куба.
     *
     * Диагональный контакт больше не считается соединением корабля.
     * Это принципиально важно после достройки палубы: иначе корабль может
     * случайно "перепрыгнуть" по диагонали на соседнюю конструкцию/землю.
     *
     * Такой же принцип используется в ShipDetector из BlockShips.
     */
    private static final BlockFace[] DIRECTIONS = {
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.SOUTH,
            BlockFace.NORTH
    };

    public static Set<Block> scanShip(Location startLocation) {
        Set<Block> shipBlocks = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();
        Set<Location> visited = new HashSet<>();

        if (startLocation == null || startLocation.getWorld() == null) {
            return shipBlocks;
        }

        Block startBlock = startLocation.getBlock();

        if (!isValidShipBlock(startBlock)) {
            return shipBlocks;
        }

        queue.add(startBlock);
        visited.add(startBlock.getLocation());

        while (!queue.isEmpty()) {
            /*
             * Никогда не возвращаем частично отсканированный корабль.
             */
            if (shipBlocks.size() >= MAX_SHIP_SIZE) {
                return null;
            }

            Block current = queue.poll();
            shipBlocks.add(current);

            for (BlockFace direction : DIRECTIONS) {
                Block neighbor = current.getRelative(direction);
                Location neighborLoc = neighbor.getLocation();

                if (!visited.add(neighborLoc)) {
                    continue;
                }

                if (isValidShipBlock(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        return shipBlocks;
    }

    public static int getMaxShipSize() {
        return MAX_SHIP_SIZE;
    }

    private static boolean isValidShipBlock(Block block) {
        Material type = block.getType();

        if (type.isAir()) {
            return false;
        }

        /*
         * ВАЖНО: нельзя принимать любой solid-блок.
         *
         * Иначе сканер захватывает обычную землю, камень, песок, гравий,
         * берег, дно океана и любые соседние строительные конструкции.
         * После этого такие блоки становятся частью collision-модели корабля
         * и могут полностью заблокировать движение.
         *
         * Корабль собирается только из явно разрешённых ниже категорий.
         * Воздух, вода, земля, камень и прочие неописанные блоки игнорируются.
         */
        /*
         * Все светящиеся блоки (фонари, факелы, лампы и т.п.).
         */
        try {
            if (block.getBlockData().getLightEmission() > 0) {
                return true;
            }
        } catch (Exception ignored) {
            // Защита от нестандартных BlockData.
        }

        if (Tag.PLANKS.isTagged(type)) return true;
        if (Tag.LOGS.isTagged(type)) return true;

        if (Tag.STAIRS.isTagged(type)) return true;
        if (Tag.SLABS.isTagged(type)) return true;
        if (Tag.WALLS.isTagged(type)) return true;
        if (Tag.FENCES.isTagged(type)) return true;
        if (Tag.FENCE_GATES.isTagged(type)) return true;
        if (Tag.DOORS.isTagged(type)) return true;
        if (Tag.TRAPDOORS.isTagged(type)) return true;
        if (Tag.BUTTONS.isTagged(type)) return true;
        if (Tag.PRESSURE_PLATES.isTagged(type)) return true;
        if (Tag.BEDS.isTagged(type)) return true;
        if (Tag.ALL_SIGNS.isTagged(type)) return true;
        if (Tag.BANNERS.isTagged(type)) return true;
        if (Tag.CAMPFIRES.isTagged(type)) return true;
        if (Tag.ANVIL.isTagged(type)) return true;
        if (Tag.FLOWER_POTS.isTagged(type)) return true;
        if (Tag.CANDLES.isTagged(type)) return true;

        String name = type.name();

        if (name.endsWith("_GLASS")
                || name.endsWith("_GLASS_PANE")
                || name.endsWith("_WOOL")
                || name.endsWith("_CARPET")
                || name.endsWith("_SHULKER_BOX")
                || name.endsWith("_TORCH")
                || name.endsWith("_LANTERN")
                || name.endsWith("_CHAIN")
                || name.contains("RAIL")) {
            return true;
        }

        return switch (type) {
            case GLASS,
                 GLASS_PANE,
                 TINTED_GLASS,
                 IRON_BARS,
                 LIGHTNING_ROD,
                 END_ROD,
                 BELL,
                 BOOKSHELF,
                 CHISELED_BOOKSHELF,
                 LADDER,
                 VINE,
                 LEVER,
                 DAYLIGHT_DETECTOR,
                 TRIPWIRE_HOOK,
                 REPEATER,
                 COMPARATOR,
                 REDSTONE_WIRE,
                 HOPPER,
                 DISPENSER,
                 DROPPER,
                 OBSERVER,
                 PISTON,
                 STICKY_PISTON,
                 SLIME_BLOCK,
                 HONEY_BLOCK,
                 TARGET,
                 TNT,
                 CHEST,
                 TRAPPED_CHEST,
                 BARREL,
                 ENDER_CHEST,
                 FURNACE,
                 BLAST_FURNACE,
                 SMOKER,
                 CRAFTING_TABLE,
                 CARTOGRAPHY_TABLE,
                 FLETCHING_TABLE,
                 SMITHING_TABLE,
                 GRINDSTONE,
                 LOOM,
                 STONECUTTER,
                 NOTE_BLOCK,
                 JUKEBOX,
                 CAULDRON,
                 BREWING_STAND,
                 COMPOSTER,
                 LECTERN,
                 ENCHANTING_TABLE -> true;
            default -> false;
        };
    }
}
