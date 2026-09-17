package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ActiveShip {
    
    private final Player pilot;
    private final ArmorStand vehicle;
    private final List<BlockDisplay> displays = new ArrayList<>();
    private final List<ShipBlockData> originalBlocks = new ArrayList<>();

    public ActiveShip(Player pilot, Location coreLocation, Set<Block> blocks) {
        this.pilot = pilot;
        World world = coreLocation.getWorld();

        // 1. Создаем невидимое ядро, на котором будет сидеть игрок (ArmorStand)
        // Смещаем на 0.5, чтобы ядро было ровно по центру блока
        Location spawnLoc = coreLocation.clone().add(0.5, 0, 0.5);
        this.vehicle = world.spawn(spawnLoc, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setInvulnerable(true);
            stand.setGravity(false);
            stand.setMarker(true); // Чтобы не мешал кликать мышкой
            stand.setSmall(true);
        });

        // Сажаем пилота на ядро (теперь он не может ходить, но может крутить камерой)
        this.vehicle.addPassenger(pilot);

        // 2. Обрабатываем каждый отсканированный блок
        for (Block block : blocks) {
            Location blockLoc = block.getLocation();
            BlockState state = block.getState();
            ItemStack[] savedItems = null;

            // Если это сундук, печь или бочка
            if (state instanceof Container container) {
                ItemStack[] originalItems = container.getInventory().getContents();
                savedItems = new ItemStack[originalItems.length];
                
                // Аккуратно копируем предметы в память
                for (int i = 0; i < originalItems.length; i++) {
                    if (originalItems[i] != null) {
                        savedItems[i] = originalItems[i].clone();
                    }
                }
                // Очищаем реальный сундук, чтобы при удалении блока вещи не выпали на землю
                container.getInventory().clear();
            }

            // Вычисляем смещение относительно кафедры
            int offsetX = blockLoc.getBlockX() - coreLocation.getBlockX();
            int offsetY = blockLoc.getBlockY() - coreLocation.getBlockY();
            int offsetZ = blockLoc.getBlockZ() - coreLocation.getBlockZ();

            // Сохраняем все данные в наш класс
            originalBlocks.add(new ShipBlockData(block.getBlockData(), savedItems, offsetX, offsetY, offsetZ));

            // 3. Создаем визуальную сущность BlockDisplay
            BlockDisplay display = world.spawn(blockLoc.clone().add(0.5, 0, 0.5), BlockDisplay.class, d -> {
                d.setBlock(block.getBlockData());
            });
            displays.add(display);

            // 4. Удаляем физический блок из мира (false означает "без дропа предметов")
            block.setType(Material.AIR, false);
        }
    }

    public Player getPilot() { return pilot; }
    public ArmorStand getVehicle() { return vehicle; }
    public List<BlockDisplay> getDisplays() { return displays; }
    public List<ShipBlockData> getOriginalBlocks() { return originalBlocks; }
}
