package com.dagxam.moveship.listeners;

import com.dagxam.moveship.core.ActiveShip;
import com.dagxam.moveship.core.ShipScanner;
import com.dagxam.moveship.gui.ShipGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ShipGUIListener implements Listener {

    // Глобальное хранилище активных кораблей (Привязываем к UUID игрока)
    public static final Map<UUID, ActiveShip> ACTIVE_SHIPS = new HashMap<>();

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof ShipGUI gui) {
            
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getInventory())) return;

            Location coreLoc = gui.getCoreLocation();

            switch (event.getSlot()) {
                case 0: // Кнопка: Сканировать
                    player.sendMessage(Component.text("Запуск сканирования...", NamedTextColor.AQUA));
                    
                    Set<Block> shipBlocks = ShipScanner.scanShip(coreLoc);
                    gui.setScannedBlocks(shipBlocks); // Сохраняем в память интерфейса!
                    
                    player.sendMessage(Component.text("Корабль отсканирован! Блоков: " + shipBlocks.size(), NamedTextColor.GREEN));
                    break;
                    
                case 3: // Кнопка: Активировать
                    Set<Block> blocksToActivate = gui.getScannedBlocks();
                    
                    if (blocksToActivate == null || blocksToActivate.isEmpty()) {
                        player.sendMessage(Component.text("Сначала отсканируйте корабль!", NamedTextColor.RED));
                        return;
                    }

                    if (ACTIVE_SHIPS.containsKey(player.getUniqueId())) {
                        player.sendMessage(Component.text("Вы уже управляете кораблем!", NamedTextColor.RED));
                        return;
                    }

                    player.sendMessage(Component.text("Активация! Держитесь крепче!", NamedTextColor.GREEN));
                    
                    // Запускаем процесс превращения!
                    ActiveShip ship = new ActiveShip(player, coreLoc, blocksToActivate);
                    ACTIVE_SHIPS.put(player.getUniqueId(), ship);
                    
                    player.closeInventory();
                    break;
                    
                case 5: // Кнопка: Остановить
                    player.sendMessage(Component.text("Кнопка остановки пока в разработке...", NamedTextColor.YELLOW));
                    // TODO: Реализовать остановку и возврат блоков
                    player.closeInventory();
                    break;
                    
                case 8: // Кнопка: Выход
                    player.closeInventory();
                    break;
            }
        }
    }
}
