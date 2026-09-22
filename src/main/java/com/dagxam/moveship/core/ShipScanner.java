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

    public static Set<Block> scanShip(Location startLocation) {
        Set<Block> shipBlocks = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();
        Set<Location> visited = new HashSet<>();

        Block startBlock = startLocation.getBlock();
        queue.add(startBlock);
        visited.add(startBlock.getLocation());

        boolean limitExceeded = false;

        while (!queue.isEmpty()) {
            /*
             * Никогда не возвращаем частично отсканированный корабль.
             *
             * Если очередь ещё содержит блоки, а лимит достигнут, значит
             * структура больше допустимого размера. Возвращаем null ниже,
             * чтобы активация была полностью отменена.
             */
            if (shipBlocks.size() >= MAX_SHIP_SIZE) {
                limitExceeded = true;
                break;
            }

            Block current = queue.poll();
            shipBlocks.add(current);

            /*
             * Корабль сканируется по всем 26 соседним клеткам.
             * Это сохраняет части корпуса, которые соединены ступенями,
             * полублоками, декоративными элементами или угловым стыком.
             *
             * Вода, воздух и прочие недопустимые блоки не проходят
             * isValidShipBlock(), поэтому сканирование не уходит в океан.
             */
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) {
                            continue;
                        }

                        Block neighbor = current.getRelative(x, y, z);
                        Location neighborLoc = neighbor.getLocation();

                        if (!visited.contains(neighborLoc)) {
                            visited.add(neighborLoc);

                            if (isValidShipBlock(neighbor)) {
                                queue.add(neighbor);
                            }
                        }
                    }
                }
            }
        }

        if (limitExceeded) {
            return null;
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
         * Любой твердый строительный блок автоматически является частью
         * корабля. Это убирает зависимость от постоянно меняющегося списка
         * Material и сохраняет новые декоративные блоки Paper/Minecraft.
         */
        if (type.isSolid()) {
            return true;
        }

        /*
         * Все светящиеся блоки (фонари, факелы, лампы, медные лампы/бульбы,
         * светящиеся блоки и т.д.) должны сканироваться независимо от того,
         * как называется конкретный Material.
         */
        try {
            if (block.getBlockData().getLightEmission() > 0) {
                return true;
            }
        } catch (Exception ignored) {
            // Некоторые нестандартные Material могут не иметь BlockData.
        }

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
            name.endsWith("_LANTERN") || name.endsWith("_CHAIN") || name.contains("RAIL")) {
            return true;
        }

        // 4. Специфичный декор, механизмы и хранилища
        switch (type) {
            // Стекло и решетки
            case GLASS:
            case GLASS_PANE:
            case TINTED_GLASS:
            case IRON_BARS:
            // Спец. декор
            case LIGHTNING_ROD:
            case END_ROD:
            case BELL:
            case BOOKSHELF:
            case CHISELED_BOOKSHELF:
            case LADDER:
            case VINE:
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
