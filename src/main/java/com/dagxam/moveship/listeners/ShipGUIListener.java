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
import org.bukkit.block.Lectern;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ShipGUIListener implements Listener {

    private static final Map<UUID, Set<Block>> scannedShips = new HashMap<>();
    private static final Map<UUID, Location> scannedCores = new HashMap<>();

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!(event.getInventory().getHolder() instanceof ShipGUI gui)) {
            return;
        }

        event.setCancelled(true);

        if (event.getCurrentItem() == null) {
            return;
        }

        switch (event.getCurrentItem().getType()) {
            case COMPASS -> scan(player, gui);
            case LIME_WOOL -> activate(player);
            case RED_WOOL -> stop(player);
            case BARRIER -> player.closeInventory();
            default -> {
            }
        }
    }

    private void scan(Player player, ShipGUI gui) {
        UUID uuid = player.getUniqueId();
        Location core = gui.getCoreLocation().clone();

        player.closeInventory();

        ShipScanner.ScanResult result =
                ShipScanner.scanShipDetailed(core);

        if (result.blocks().isEmpty()) {
            clearScan(uuid);
            player.sendMessage(Component.text(
                    "Корабль не найден: рядом с кафедрой нет разрешенных блоков.",
                    NamedTextColor.RED
            ));
            return;
        }

        if (result.limitReached()) {
            clearScan(uuid);
            player.sendMessage(Component.text(
                    "Корабль слишком большой. Лимит: "
                            + ShipScanner.MAX_SHIP_SIZE
                            + " блоков. Активация отменена.",
                    NamedTextColor.RED
            ));
            return;
        }

        scannedShips.put(uuid, result.blocks());
        scannedCores.put(uuid, core);

        player.sendMessage(Component.text(
                "Корабль отсканирован: "
                        + result.blocks().size()
                        + " блоков. Нажмите «Активировать».",
                NamedTextColor.AQUA
        ));
    }

    private void activate(Player player) {
        UUID uuid = player.getUniqueId();

        if (ShipManager.getShip(player) != null) {
            player.sendMessage(Component.text(
                    "Корабль уже активирован.",
                    NamedTextColor.YELLOW
            ));
            player.closeInventory();
            return;
        }

        if (!scannedShips.containsKey(uuid)
                || !scannedCores.containsKey(uuid)) {
            player.sendMessage(Component.text(
                    "Сначала отсканируйте корабль.",
                    NamedTextColor.RED
            ));
            player.closeInventory();
            return;
        }

        Location core = scannedCores.get(uuid).clone();

        if (!isController(core)) {
            clearScan(uuid);
            player.sendMessage(Component.text(
                    "Кафедра управления больше не найдена.",
                    NamedTextColor.RED
            ));
            player.closeInventory();
            return;
        }

        /*
         * Повторное сканирование учитывает изменения конструкции между
         * кнопками «Сканировать» и «Активировать».
         */
        ShipScanner.ScanResult fresh =
                ShipScanner.scanShipDetailed(core);

        if (fresh.blocks().isEmpty()) {
            clearScan(uuid);
            player.sendMessage(Component.text(
                    "Повторное сканирование не обнаружило корабль.",
                    NamedTextColor.RED
            ));
            player.closeInventory();
            return;
        }

        if (fresh.limitReached()) {
            clearScan(uuid);
            player.sendMessage(Component.text(
                    "Корабль превышает лимит "
                            + ShipScanner.MAX_SHIP_SIZE
                            + " блоков. Активация отменена.",
                    NamedTextColor.RED
            ));
            player.closeInventory();
            return;
        }

        player.closeInventory();

        boolean activated =
                ShipManager.activateShip(
                        player,
                        core,
                        fresh.blocks()
                );

        if (!activated) {
            player.sendMessage(Component.text(
                    "Не удалось активировать корабль. Проверьте конструкцию и свободное место.",
                    NamedTextColor.RED
            ));
            return;
        }

        clearScan(uuid);

        player.sendMessage(Component.text(
                "Корабль активирован! W/S — движение, A/D — поворот.",
                NamedTextColor.GREEN
        ));
    }

    private void stop(Player player) {
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

    private static boolean isController(Location location) {
        if (location == null
                || location.getWorld() == null
                || location.getBlock().getType() != Material.LECTERN) {
            return false;
        }

        if (!(location.getBlock().getState() instanceof Lectern lectern)) {
            return false;
        }

        return lectern.getPersistentDataContainer().has(
                MoveShipPlugin.CONTROLLER_KEY,
                PersistentDataType.BYTE
        );
    }

    private static void clearScan(UUID uuid) {
        scannedShips.remove(uuid);
        scannedCores.remove(uuid);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Скан сохраняется до активации, но при активации выполняется повторный scan.
    }
}
