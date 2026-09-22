package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;

public final class ShipScanner {

    /*
     * Защита от случайного сканирования гигантской области.
     * При превышении лимита активация не выполняется частично.
     */
    public static final int MAX_SHIP_SIZE = 8192;

    private static final BlockFace[] NEIGHBORS = {
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN
    };

    private ShipScanner() {
    }

    public record ScanResult(
            Set<Block> blocks,
            boolean limitReached
    ) {
        public ScanResult {
            blocks = Collections.unmodifiableSet(
                    new LinkedHashSet<>(blocks)
            );
        }
    }

    public static Set<Block> scanShip(Location startLocation) {
        return scanShipDetailed(startLocation).blocks();
    }

    public static ScanResult scanShipDetailed(Location startLocation) {
        if (startLocation == null || startLocation.getWorld() == null) {
            return new ScanResult(Set.of(), false);
        }

        Block startBlock = startLocation.getBlock();

        if (!isValidShipBlock(startBlock.getType())) {
            return new ScanResult(Set.of(), false);
        }

        Set<Block> shipBlocks = new LinkedHashSet<>();
        Set<Block> visited = new LinkedHashSet<>();
        Queue<Block> queue = new ArrayDeque<>();

        queue.add(startBlock);
        visited.add(startBlock);

        boolean limitReached = false;

        while (!queue.isEmpty()) {
            if (shipBlocks.size() >= MAX_SHIP_SIZE) {
                limitReached = true;
                break;
            }

            Block current = queue.poll();
            shipBlocks.add(current);

            for (BlockFace face : NEIGHBORS) {
                Block neighbor = current.getRelative(face);

                if (!visited.add(neighbor)) {
                    continue;
                }

                if (isValidShipBlock(neighbor.getType())) {
                    queue.add(neighbor);
                }
            }
        }

        return new ScanResult(shipBlocks, limitReached);
    }

    private static boolean isValidShipBlock(Material type) {
        if (type == null || type.isAir()) {
            return false;
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
                || name.contains("RAIL")
                || name.endsWith("_CONCRETE")
                || name.endsWith("_CONCRETE_POWDER")
                || name.endsWith("_TERRACOTTA")
                || name.endsWith("_GLAZED_TERRACOTTA")) {
            return true;
        }

        return switch (type) {
            case GLASS,
                 GLASS_PANE,
                 TINTED_GLASS,
                 IRON_BARS,
                 CHAIN,
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
                 ENCHANTING_TABLE,
                 IRON_BLOCK,
                 GOLD_BLOCK,
                 COPPER_BLOCK,
                 EXPOSED_COPPER,
                 WEATHERED_COPPER,
                 OXIDIZED_COPPER,
                 WAXED_COPPER_BLOCK,
                 WAXED_EXPOSED_COPPER,
                 WAXED_WEATHERED_COPPER,
                 WAXED_OXIDIZED_COPPER,
                 DIAMOND_BLOCK,
                 EMERALD_BLOCK,
                 LAPIS_BLOCK,
                 REDSTONE_BLOCK,
                 QUARTZ_BLOCK,
                 SMOOTH_QUARTZ,
                 QUARTZ_PILLAR,
                 BRICKS,
                 NETHER_BRICKS,
                 RED_NETHER_BRICKS,
                 PRISMARINE,
                 PRISMARINE_BRICKS,
                 DARK_PRISMARINE,
                 SEA_LANTERN,
                 PURPUR_BLOCK,
                 PURPUR_PILLAR,
                 MUD_BRICKS ->
                true;
            default ->
                false;
        };
    }
}
