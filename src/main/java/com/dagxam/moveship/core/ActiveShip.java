package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ActiveShip {
    private final Player pilot;
    private final ArmorStand coreEntity; // Невидимое сиденье для игрока
    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay> displayEntities = new ArrayList<>();

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot = pilot;

        // 1. Создаем "ядро" корабля (кресло пилота) прямо по центру кафедры
        Location coreLoc = anchorLocation.clone().add(0.5, 0, 0.5);
        this.coreEntity = (ArmorStand) anchorLocation.getWorld().spawnEntity(coreLoc, EntityType.ARMOR_STAND);
        this.coreEntity.setInvisible(true);
        this.coreEntity.setInvulnerable(true);
        this.coreEntity.setGravity(false);
        this.coreEntity.setSmall(true); // Чтобы игрок сидел ниже, ближе к полу

        // 2. Обрабатываем каждый блок
        for (Block block : blocks) {
            Location blockLoc = block.getLocation();
            
            // Вектор смещения относительно кафедры (чтобы при остановке собрать обратно)
            Vector offset = blockLoc.toVector().subtract(anchorLocation.toVector());

            // ДЕЛАЕМ СЛЕПОК: Сохраняет инвентари, текст на табличках и т.д.
            BlockState snapshot = block.getState();
            originalBlocks.add(new ShipBlockData(offset, block.getBlockData(), snapshot));

            // ПРЕДОТВРАЩЕНИЕ ДРОПА: Если это сундук/печь, удаляем вещи в мире ДО разрушения блока
            BlockState stateToClear = block.getState();
            if (stateToClear instanceof Container container) {
                container.getInventory().clear();
                container.update(true, false); // Применяем очистку без обновления физики
            }

            // Удаляем физический блок
            block.setType(Material.AIR, false);

            // Спавним визуальную копию (BlockDisplay)
            BlockDisplay display = (BlockDisplay) anchorLocation.getWorld().spawnEntity(blockLoc, EntityType.BLOCK_DISPLAY);
            display.setBlock(snapshot.getBlockData());
            displayEntities.add(display);
        }

        // 3. Сажаем пилота на корабль
        this.coreEntity.addPassenger(pilot);
    }

    public Player getPilot() {
        return pilot;
    }
    
    // Позже мы добавим сюда методы move() и stop()
}
