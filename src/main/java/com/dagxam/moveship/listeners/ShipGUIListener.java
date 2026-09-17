package com.dagxam.moveship.listeners;

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

import java.util.Set;

public class ShipGUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof ShipGUI gui) {
            
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getInventory())) return;

            Location coreLoc = gui.getCoreLocation();

            switch (event.getSlot()) {
                case 0: // Сканировать
                    player.sendMessage(Component.text("Запуск сканирования...", NamedTextColor.AQUA));
                    
                    // Вызываем наш алгоритм сканирования
                    Set<Block> shipBlocks = ShipScanner.scanShip(coreLoc);
                    
                    player.sendMessage(Component.text("Корабль отсканирован! Найдено блоков: " + shipBlocks.size(), NamedTextColor.GREEN));
                    player.closeInventory();
                    break;
                    
                case 3: // Активировать
                    player.sendMessage(Component.text("Корабль активирован!", NamedTextColor.GREEN));
                    player.closeInventory();
                    break;
                    
                case 5: // Остановить
                    player.sendMessage(Component.text("Корабль остановлен.", NamedTextColor.RED));
                    player.closeInventory();
                    break;
                    
                case 8: // Выход
                    player.closeInventory();
                    break;
            }
        }
    }
}
