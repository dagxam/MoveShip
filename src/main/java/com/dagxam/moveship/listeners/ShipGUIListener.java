package com.dagxam.moveship.listeners;

import com.dagxam.moveship.core.ShipManager;
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
                    Set<Block> shipBlocks = ShipScanner.scanShip(coreLoc);
                    player.sendMessage(Component.text("Корабль отсканирован! Найдено блоков: " + shipBlocks.size(), NamedTextColor.GREEN));
                    player.closeInventory();
                    break;
                    
                case 3: // Активировать
                    // Сначала быстро сканируем корабль снова, чтобы убедиться, что данные свежие
                    Set<Block> blocksToActivate = ShipScanner.scanShip(coreLoc);
                    
                    if (blocksToActivate.isEmpty()) {
                        player.sendMessage(Component.text("Корабль не найден или поврежден!", NamedTextColor.RED));
                        return;
                    }

                    boolean success = ShipManager.activateShip(player, coreLoc, blocksToActivate);
                    
                    if (success) {
                        player.sendMessage(Component.text("Корабль активирован! Вы за штурвалом.", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Вы уже управляете кораблем!", NamedTextColor.RED));
                    }
                    player.closeInventory();
                    break;
                    
                case 5: // Остановить
                    player.sendMessage(Component.text("Корабль остановлен.", NamedTextColor.RED));
                    // TODO: Вызвать метод деактивации и возвращения блоков
                    player.closeInventory();
                    break;
                    
                case 8: // Выход
                    player.closeInventory();
                    break;
            }
        }
    }
}
