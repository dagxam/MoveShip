package com.yourname.moveship.listeners;

import com.yourname.moveship.gui.ShipGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public class ShipGUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // Проверяем, является ли инвентарь нашим интерфейсом
        if (event.getInventory().getHolder() instanceof ShipGUI gui) {
            
            // Обязательно отменяем клик, чтобы игрок не забрал кнопки себе
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player player)) return;
            
            // Защита от кликов по нижнему инвентарю (своему инвентарю игрока)
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getInventory())) return;

            // Получаем координаты кафедры, к которой привязано это меню
            Location coreLoc = gui.getCoreLocation();

            // Проверяем, по какому слоту кликнули
            switch (event.getSlot()) {
                case 0: // Сканировать
                    player.sendMessage(Component.text("Запуск сканирования...", NamedTextColor.AQUA));
                    // TODO: Здесь мы вызовем алгоритм Flood Fill
                    player.closeInventory();
                    break;
                    
                case 3: // Активировать
                    player.sendMessage(Component.text("Корабль активирован!", NamedTextColor.GREEN));
                    // TODO: Здесь мы превратим блоки в сущности и посадим игрока
                    player.closeInventory();
                    break;
                    
                case 5: // Остановить
                    player.sendMessage(Component.text("Корабль остановлен.", NamedTextColor.RED));
                    // TODO: Возврат сущностей обратно в твердые блоки
                    player.closeInventory();
                    break;
                    
                case 8: // Выход
                    player.closeInventory();
                    break;
            }
        }
    }
}
