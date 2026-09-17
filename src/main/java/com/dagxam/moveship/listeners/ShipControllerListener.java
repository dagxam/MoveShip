package com.yourname.moveship.listeners;

import com.yourname.moveship.MoveShipPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class ShipControllerListener implements Listener {

    @EventHandler
    public void onControllerPlace(BlockPlaceEvent event) {
        ItemStack itemInHand = event.getItemInHand();

        // Проверяем, есть ли у предмета мета и наш секретный NBT-тег
        if (itemInHand.getItemMeta() == null) return;
        if (!itemInHand.getItemMeta().getPersistentDataContainer().has(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE)) {
            return;
        }

        Block block = event.getBlockPlaced();

        // Если поставленный блок — Кафедра, переносим в неё данные
        if (block.getState() instanceof Lectern lectern) {
            lectern.getPersistentDataContainer().set(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE, (byte) 1);
            lectern.update(); // Обязательно сохраняем изменения состояния блока

            event.getPlayer().sendMessage(Component.text("Ядро корабля установлено! Нажмите ПКМ для управления.", NamedTextColor.GREEN));
        }
    }

    @EventHandler
    public void onControllerClick(PlayerInteractEvent event) {
        // Игнорируем клики левой кнопкой и клики в воздухе
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        
        // Предотвращаем двойное срабатывание (в Minecraft 2 руки: основная и левая)
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.LECTERN) return;

        // Проверяем наличие нашего тега в блоке
        if (block.getState() instanceof Lectern lectern) {
            if (lectern.getPersistentDataContainer().has(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE)) {
                
                // Отменяем стандартное действие кафедры (чтобы игрок не клал в неё книгу)
                event.setCancelled(true);

                event.getPlayer().sendMessage(Component.text("Открываем меню корабля...", NamedTextColor.YELLOW));
                
                // TODO: Здесь будет вызов метода создания и открытия GUI
                // ShipGUI.openMenu(event.getPlayer(), block.getLocation());
            }
        }
    }
}
