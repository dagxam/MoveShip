package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ShipManager {

    private static final Map<UUID, ActiveShip> activeShips = new HashMap<>();

    private ShipManager() {
    }

    public static boolean activateShip(
            Player player,
            Location coreLocation,
            Set<Block> shipBlocks
    ) {
        if (player == null
                || coreLocation == null
                || coreLocation.getWorld() == null
                || shipBlocks == null
                || shipBlocks.isEmpty()) {
            return false;
        }

        if (activeShips.containsKey(player.getUniqueId())) {
            return false;
        }

        try {
            ActiveShip ship =
                    new ActiveShip(
                            shipBlocks,
                            coreLocation,
                            player
                    );

            activeShips.put(player.getUniqueId(), ship);
            return true;
        } catch (RuntimeException ex) {
            player.getServer().getLogger().warning(
                    "Не удалось активировать корабль игрока "
                            + player.getName()
                            + ": "
                            + ex.getMessage()
            );
            return false;
        }
    }

    public static ActiveShip getShip(Player player) {
        if (player == null) {
            return null;
        }

        return activeShips.get(player.getUniqueId());
    }

    public static void stopShip(Player player) {
        if (player == null) {
            return;
        }

        ActiveShip ship =
                activeShips.remove(player.getUniqueId());

        if (ship != null) {
            ship.restoreBlocks();
        }
    }

    public static void stopShip(UUID uuid) {
        if (uuid == null) {
            return;
        }

        ActiveShip ship = activeShips.remove(uuid);

        if (ship != null) {
            ship.restoreBlocks();
        }
    }

    public static void stopAllShips() {
        Map<UUID, ActiveShip> snapshot =
                new HashMap<>(activeShips);

        activeShips.clear();

        for (ActiveShip ship : snapshot.values()) {
            try {
                ship.restoreBlocks();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}
