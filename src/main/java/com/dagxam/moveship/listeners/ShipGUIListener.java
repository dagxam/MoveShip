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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ShipGUIListener implements Listener {

    // Храним результат сканирования пока игрок не нажал Активировать
    private static final Map<UUID, Set<Block>> scannedShips = new HashMap<>();
    private static final Map<UUID, Location>   scannedCores = new HashMap<>();

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof ShipGUI gui)) return;

        // Отменяем любое перемещение предметов в GUI
        event.setCancelled(true);

        if (event.getCurrentItem() == null) return;
        Material clicked = event.getCurrentItem().getType();

        switch (clicked) {

            // ── СКАНИРОВАТЬ (слот 0) ──────────────────────────────────────
            case COMPASS -> {
                Location core = gui.getCoreLocation();
                player.closeInventory();

                Set<Block> found = ShipScanner.scanShip(core);

                if (found == null) {
                    player.sendMessage(Component.text(
                        "Корабль слишком большой для активации. Максимальный размер: "
                                + ShipScanner.getMaxShipSize()
                                + " блоков.",
                        NamedTextColor.RED));
                    return;
                }

                if (found.isEmpty()) {
                    player.sendMessage(Component.text(
                        "Блоки корабля не найдены вокруг кафедры!", NamedTextColor.RED));
                    return;
                }

                scannedShips.put(player.getUniqueId(), found);
                scannedCores.put(player.getUniqueId(), core);

                player.sendMessage(Component.text(
                    "Корабль отсканирован: " + found.size() + " блоков. Нажмите «Активировать».",
                    NamedTextColor.AQUA));
            }

            // ── АКТИВИРОВАТЬ (слот 3) ─────────────────────────────────────
            case LIME_WOOL -> {
                UUID uuid = player.getUniqueId();

                // Если уже плывёт — игнорируем
                if (ShipManager.getShip(player) != null) {
                    player.sendMessage(Component.text(
                        "Корабль уже активирован!", NamedTextColor.YELLOW));
                    player.closeInventory();
                    return;
                }

                Set<Block> blocks = scannedShips.get(uuid);
                Location   core   = scannedCores.get(uuid);

                if (blocks == null || core == null) {
                    player.sendMessage(Component.text(
                        "Сначала отсканируйте корабль!", NamedTextColor.RED));
                    player.closeInventory();
                    return;
                }

                player.closeInventory();

                boolean ok = ShipManager.activateShip(player, core, blocks);
                if (ok) {
                    scannedShips.remove(uuid);
                    scannedCores.remove(uuid);
                    player.sendMessage(Component.text(
                        "Корабль активирован! W/S — вперёд/назад по направлению кафедры, A/D — плавный поворот.",
                        NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text(
                        "Не удалось активировать корабль.", NamedTextColor.RED));
                }
            }

            // ── ОСТАНОВИТЬ (слот 5) ───────────────────────────────────────
            case RED_WOOL -> {
                player.closeInventory();
                if (ShipManager.getShip(player) != null) {
                    ShipManager.stopShip(player);
                    player.sendMessage(Component.text(
                        "Корабль зафиксирован.", NamedTextColor.YELLOW));
                } else {
                    player.sendMessage(Component.text(
                        "Корабль не активирован.", NamedTextColor.GRAY));
                }
            }

            // ── ВЫХОД (слот 8) ────────────────────────────────────────────
            case BARRIER -> player.closeInventory();

            default -> { /* другие слоты — игнорируем */ }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Ничего не делаем при закрытии — данные сканирования храним до активации
    }
}
