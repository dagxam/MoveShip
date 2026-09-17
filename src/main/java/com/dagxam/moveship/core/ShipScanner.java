package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

public class ShipScanner {

    private static final int MAX_SHIP_SIZE = 2048;

    public static Set<Block> scanShip(Location startLocation) {
        Set<Block> shipBlocks = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();
        Set<Location> visited = new HashSet<>();

        Block startBlock = startLocation.getBlock();
        queue.add(startBlock);
        visited.add(startBlock.getLocation());

        while (!queue.isEmpty() && shipBlocks.size() < MAX_SHIP_SIZE) {
            Block current = queue.poll();
            shipBlocks.add(current);

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;

                        Block neighbor = current.getRelative(x, y, z);
                        Location neighborLoc = neighbor.getLocation();

                        if (!visited.contains(neighborLoc)) {
                            visited.add(neighborLoc);

                            if (isValidShipBlock(neighbor.getType())) {
                                queue.add(neighbor);
                            }
                        }
                    }
                }
            }
        }

        return shipBlocks;
    }

    private static boolean isValidShipBlock(Material type) {
        // 1. Дерево и основные стройматериалы
        if (Tag.PLANKS.isTagged(type)) return true;
        if (Tag.LOGS.isTagged(type)) return true;
        if (Tag.WOODEN_STAIRS.isTagged(type)) return true;
        if (Tag.WOODEN_SLABS.isTagged(type)) return true;
        if (Tag.WOODEN_FENCES.isTagged(type)) return true;
        if (Tag.WOODEN_TRAPDOORS.isTagged(type)) return true;
        if (Tag.WOODEN_DOORS.isTagged(type)) return true;
        if (Tag.WOODEN_BUTTONS.isTagged(type)) return true;
        if (Tag.WOODEN_PRESSURE_PLATES.isTagged(type)) return true;
        if (Tag.FENCE_GATES.isTagged(type)) return true;
        if (Tag.BEDS.isTagged(type)) return true;
        if (Tag.ALL_SIGNS.isTagged(type)) return true;
        if (Tag.BANNERS.isTagged(type)) return true;
        if (Tag.CAMPFIRES.isTagged(type)) return true;
        if (Tag.ANVIL.isTagged(type)) return true;

        // 2. Освещение, декор, паруса и стекло
        if (type == Material.TORCH || type == Material.WALL_TORCH || 
            type == Material.SOUL_TORCH || type == Material.SOUL_WALL_TORCH ||
            type == Material.REDSTONE_TORCH || type == Material.REDSTONE_WALL_TORCH ||
            type == Material.LANTERN || type == Material.SOUL_LANTERN ||
            type == Material.LIGHTNING_ROD || type == Material.CHAIN || 
            type == Material.IRON_BARS || type == Material.BELL ||
            type == Material.GLASS || type == Material.GLASS_PANE) return true;

        // Проверка на цветную шерсть, ковры (для парусов) и окрашенное стекло
        String name = type.name();
        if (name.endsWith("_GLASS") || name.endsWith("_GLASS_PANE") || 
            name.endsWith("_WOOL") || name.endsWith("_CARPET")) return true;

        // 3. Функциональные блоки, механизмы и хранилища
        return type == Material.CHEST || 
               type == Material.TRAPPED_CHEST ||
               type == Material.BARREL || 
               type == Material.FURNACE || 
               type == Material.BLAST_FURNACE || 
               type == Material.SMOKER ||
               type == Material.CRAFTING_TABLE ||
               type == Material.CARTOGRAPHY_TABLE ||
               type == Material.FLETCHING_TABLE ||
               type == Material.SMITHING_TABLE ||
               type == Material.GRINDSTONE ||
               type == Material.LOOM ||
               type == Material.STONECUTTER ||
               type == Material.DISPENSER ||
               type == Material.DROPPER ||
               type == Material.HOPPER ||
               type == Material.OBSERVER ||
               type == Material.NOTE_BLOCK ||
               type == Material.JUKEBOX ||
               type == Material.CAULDRON ||
               type == Material.BREWING_STAND ||
               type == Material.COMPOSTER ||
               type == Material.LECTERN;
    }
}
