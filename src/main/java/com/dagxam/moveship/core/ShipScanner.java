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

    // Максимальный размер корабля, чтобы не "повесить" сервер
    private static final int MAX_SHIP_SIZE = 2048;

    public static Set<Block> scanShip(Location startLocation) {
        Set<Block> shipBlocks = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();
        Set<Location> visited = new HashSet<>();

        Block startBlock = startLocation.getBlock();
        queue.add(startBlock);
        visited.add(startLocation);

        while (!queue.isEmpty() && shipBlocks.size() < MAX_SHIP_SIZE) {
            Block current = queue.poll();
            shipBlocks.add(current);

            // Проверяем 26 соседних блоков (все стороны и диагонали - "углами")
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        // Пропускаем сам себя
                        if (x == 0 && y == 0 && z == 0) continue;

                        Block neighbor = current.getRelative(x, y, z);
                        Location neighborLoc = neighbor.getLocation();

                        // Если мы тут еще не были
                        if (!visited.contains(neighborLoc)) {
                            visited.add(neighborLoc);

                            // Если материал разрешен для корабля
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

    // Жесткий фильтр: что может быть частью корабля
    private static boolean isValidShipBlock(Material type) {
        // Дерево в любых проявлениях
        if (Tag.PLANKS.isTagged(type)) return true;
        if (Tag.LOGS.isTagged(type)) return true;
        if (Tag.WOODEN_STAIRS.isTagged(type)) return true;
        if (Tag.WOODEN_SLABS.isTagged(type)) return true;
        if (Tag.WOODEN_FENCES.isTagged(type)) return true;
        if (Tag.WOODEN_TRAPDOORS.isTagged(type)) return true;
        if (Tag.WOODEN_DOORS.isTagged(type)) return true;
        
        // Кровати
        if (Tag.BEDS.isTagged(type)) return true;

        // Функциональные блоки
        return type == Material.CHEST || 
               type == Material.TRAPPED_CHEST ||
               type == Material.BARREL || 
               type == Material.FURNACE || 
               type == Material.BLAST_FURNACE || 
               type == Material.SMOKER ||
               type == Material.CRAFTING_TABLE ||
               type == Material.LECTERN; // Сама кафедра
               
        // Все остальное (вода, камень, земля, воздух) вернет false и не будет добавлено
    }
}
