package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Реестр активных кораблей.
 *
 * Корабль идентифицируется пилотом, потому что после перехода на
 * SimpleShips-подобную архитектуру отдельный Boat carrier больше не нужен.
 */
public final class ShipManager {

    private static final Map<UUID, ActiveShip> activeShips =
            new HashMap<>();

    private ShipManager() {
    }

    public static boolean activateShip(
            Player player,
            Location coreLocation,
            Set<Block> shipBlocks
    ) {
        if (player == null
                || coreLocation == null
                || shipBlocks == null
                || shipBlocks.isEmpty()
                || activeShips.containsKey(player.getUniqueId())) {
            return false;
        }

        ActiveShip ship =
                new ActiveShip(
                        shipBlocks,
                        coreLocation,
                        player
                );

        activeShips.put(
                player.getUniqueId(),
                ship
        );

        return true;
    }

    public static ActiveShip getShip(
            Player player
    ) {
        if (player == null) {
            return null;
        }

        return activeShips.get(
                player.getUniqueId()
        );
    }

    public static void stopShip(
            Player player
    ) {
        if (player == null) {
            return;
        }

        ActiveShip ship =
                activeShips.remove(
                        player.getUniqueId()
                );

        if (ship != null) {
            ship.restoreBlocks();
        }
    }

    public static void stopShip(
            UUID uuid
    ) {
        if (uuid == null) {
            return;
        }

        ActiveShip ship =
                activeShips.remove(uuid);

        if (ship != null) {
            ship.restoreBlocks();
        }
    }

    public static void stopAllShips() {
        new HashMap<>(
                activeShips
        ).forEach(
                (uuid, ship) -> {
                    try {
                        ship.restoreBlocks();
                    } catch (Exception exception) {
                        exception.printStackTrace();
                    }
                }
        );

        activeShips.clear();
    }
}
