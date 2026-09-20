package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ShipManager {

    // Используем UUID вместо Player для предотвращения утечек памяти
    private static final Map<UUID, ActiveShip> activeShips = new HashMap<>();

    public static boolean activateShip(Player player, Location coreLocation, Set<Block> shipBlocks) {
        if (player == null || activeShips.containsKey(player.getUniqueId())) {
            return false;
        }

        ActiveShip ship = new ActiveShip(shipBlocks, coreLocation, player);
        activeShips.put(player.getUniqueId(), ship);
        return true;
    }

    public static ActiveShip getShip(Player player) {
        if (player == null) return null;
        return activeShips.get(player.getUniqueId());
    }

    public static void stopShip(Player player) {
        if (player == null) return;
        ActiveShip ship = activeShips.remove(player.getUniqueId());
        if (ship != null) {
            ship.restoreBlocks();
        }
    }

    // Вызывать при отключении игрока (Quit / Kick / Death)
    public static void stopShip(UUID playerId) {
        ActiveShip ship = activeShips.remove(playerId);
        if (ship != null) {
            ship.restoreBlocks();
        }
    }

    // КРИТИЧНО: Безопасное сохранение всех кораблей при /reload или остановке сервера (onDisable)
    public static void stopAllShips() {
        for (ActiveShip ship : new HashMap<>(activeShips).values()) {
            if (ship != null) {
                try {
                    ship.restoreBlocks();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        activeShips.clear();
    }
}
