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
        // 1. Базовые строительные материалы (дерево)
        if (Tag.PLANKS.isTagged(type)) return true;
        if (Tag.LOGS.isTagged(type)) return true;

        // 2. Универсальные теги для всех видов декора и строительных элементов 
        // (включает кварц, камень и другие материалы, если они в виде декора)
        if (Tag.STAIRS.isTagged(type)) return true;          // Все ступеньки
        if (Tag.SLABS.isTagged(type)) return true;           // Все полублоки
        if (Tag.WALLS.isTagged(type)) return true;           // Все ограды
        if (Tag.FENCES.isTagged(type)) return true;          // Все заборы
        if (Tag.FENCE_GATES.isTagged(type)) return true;     // Все калитки
        if (Tag.DOORS.isTagged(type)) return true;           // Все двери
        if (Tag.TRAPDOORS.isTagged(type)) return true;       // Все люки
        if (Tag.BUTTONS.isTagged(type)) return true;         // Все кнопки
        if (Tag.PRESSURE_PLATES.isTagged(type)) return true; // Все нажимные плиты
        if (Tag.BEDS.isTagged(type)) return true;            // Все кровати
        if (Tag.ALL_SIGNS.isTagged(type)) return true;       // Все таблички (вкл. подвесные)
        if (Tag.BANNERS.isTagged(type)) return true;         // Все флаги
        if (Tag.CAMPFIRES.isTagged(type)) return true;       // Костры
        if (Tag.ANVIL.isTagged(type)) return true;           // Наковальни
        if (Tag.FLOWER_POTS.isTagged(type)) return true;     // Горшки
        if (Tag.CANDLES.isTagged(type)) return true;         // Свечи

        // 3. Проверка по суффиксам (цвета, блоки из дополнений)
        String name = type.name();
        if (name.endsWith("_GLASS") || name.endsWith("_GLASS_PANE") || 
            name.endsWith("_WOOL") || name.endsWith("_CARPET") ||
            name.endsWith("_SHULKER_BOX") || name.endsWith("_TORCH") || 
            name.endsWith("_LANTERN") || name.contains("RAIL")) {
            return true;
        }

        // 4. Специфичный декор, механизмы и хранилища
        switch (type) {
            // Стекло и решетки
            case GLASS:
            case GLASS_PANE:
            case TINTED_GLASS:
            case IRON_BARS:
            case CHAIN:
            // Спец. декор
            case LIGHTNING_ROD:
            case END_ROD:
            case BELL:
            case BOOKSHELF:
            case CHISELED_BOOKSHELF:
            case LADDER: // <---- ИСПРАВЛЕНИЕ: Добавлена настенная лестница
            case VINE:   // Заодно добавил лианы, если захотите декоративные паруса или заросли
            // Механизмы
            case LEVER:
            case DAYLIGHT_DETECTOR:
            case TRIPWIRE_HOOK:
            case REPEATER:
            case COMPARATOR:
            case REDSTONE_WIRE:
            case HOPPER:
            case DISPENSER:
            case DROPPER:
            case OBSERVER:
            case PISTON:
            case STICKY_PISTON:
            case SLIME_BLOCK:
            case HONEY_BLOCK:
            case TARGET:
            case TNT:
            // Функционал
            case CHEST:
            case TRAPPED_CHEST:
            case BARREL:
            case ENDER_CHEST:
            case FURNACE:
            case BLAST_FURNACE:
            case SMOKER:
            case CRAFTING_TABLE:
            case CARTOGRAPHY_TABLE:
            case FLETCHING_TABLE:
            case SMITHING_TABLE:
            case GRINDSTONE:
            case LOOM:
            case STONECUTTER:
            case NOTE_BLOCK:
            case JUKEBOX:
            case CAULDRON:
            case BREWING_STAND:
            case COMPOSTER:
            case LECTERN:
            case ENCHANTING_TABLE:
                return true;
            default:
                return false;
        }
    }
}
