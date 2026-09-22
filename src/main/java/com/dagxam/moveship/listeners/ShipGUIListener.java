package com.dagxam.moveship.listeners;

import com.dagxam.moveship.MoveShipPlugin;
import com.dagxam.moveship.core.ShipManager;
import com.dagxam.moveship.core.ShipScanner;
import com.dagxam.moveship.gui.ShipGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.Set;

public class ShipGUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof ShipGUI gui)) return;

        event.setCancelled(true);

        if (event.getCurrentItem() == null) return;

        Material clicked = event.getCurrentItem().getType();

        switch (clicked) {

            /*
             * СКАНИРОВАТЬ
             *
             * Меню больше не закрываем. Игрок сразу видит результат и может
             * нажать "Активировать".
             */
            case COMPASS -> {
                Location core = gui.getCoreLocation();

                Set<Block> found = ShipScanner.scanShip(core);

                if (found == null) {
                    player.sendMessage(Component.text(
                            "Корабль слишком большой для активации. Максимальный размер: "
                                    + ShipScanner.getMaxShipSize()
                                    + " блоков.",
                            NamedTextColor.RED
                    ));
                    return;
                }

                if (found.isEmpty()) {
                    player.sendMessage(Component.text(
                            "Блоки корабля не найдены вокруг кафедры!",
                            NamedTextColor.RED
                    ));
                    return;
                }

                player.sendMessage(Component.text(
                        "Корабль отсканирован: "
                                + found.size()
                                + " блоков. Нажмите «Активировать».",
                        NamedTextColor.AQUA
                ));
            }

            /*
             * АКТИВИРОВАТЬ
             *
             * Ключевое исправление:
             * никогда не используем старый Set<Block> после остановки корабля.
             *
             * При каждом нажатии "Активировать" корабль сканируется заново
             * по текущему состоянию мира. Поэтому достроенная после остановки
             * палуба гарантированно попадет в новую физическую модель.
             */
            case LIME_WOOL -> {
                if (ShipManager.getShip(player) != null) {
                    player.sendMessage(Component.text(
                            "Корабль уже активирован!",
                            NamedTextColor.YELLOW
                    ));
                    player.closeInventory();
                    return;
                }

                Location core = gui.getCoreLocation();

                Set<Block> blocks = ShipScanner.scanShip(core);

                if (blocks == null) {
                    player.sendMessage(Component.text(
                            "Корабль слишком большой для активации. Максимальный размер: "
                                    + ShipScanner.getMaxShipSize()
                                    + " блоков.",
                            NamedTextColor.RED
                    ));
                    player.closeInventory();
                    return;
                }

                if (blocks.isEmpty()) {
                    player.sendMessage(Component.text(
                            "Не удалось найти блоки корабля. Выполните сканирование заново.",
                            NamedTextColor.RED
                    ));
                    player.closeInventory();
                    return;
                }

                player.closeInventory();

                boolean ok = ShipManager.activateShip(
                        player,
                        core,
                        blocks
                );

                if (ok) {
                    player.sendMessage(Component.text(
                            "Корабль активирован! W/S — вперёд/назад по направлению кафедры, A/D — плавный поворот.",
                            NamedTextColor.GREEN
                    ));
                } else {
                    player.sendMessage(Component.text(
                            "Не удалось активировать корабль.",
                            NamedTextColor.RED
                    ));
                }
            }

            case RED_WOOL -> {
                player.closeInventory();

                if (ShipManager.getShip(player) != null) {
                    ShipManager.stopShip(player);

                    player.sendMessage(Component.text(
                            "Корабль зафиксирован.",
                            NamedTextColor.YELLOW
                    ));
                } else {
                    player.sendMessage(Component.text(
                            "Корабль не активирован.",
                            NamedTextColor.GRAY
                    ));
                }
            }

            case BARRIER -> player.closeInventory();

            default -> {
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Ничего специально не очищаем.
        // Активация всё равно всегда выполняет свежий полный скан.
    }
}
