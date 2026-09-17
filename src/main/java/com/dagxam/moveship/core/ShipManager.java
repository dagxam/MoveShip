package com.dagxam.moveship.core;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ShipManager {

    private static final Map<Player, ActiveShip> activeShips = new HashMap<>();

    public static boolean activateShip(Player player, Location coreLocation, Set<Block> shipBlocks) {
        if (activeShips.containsKey(player)) {
            return false; // Игрок уже управляет кораблем
        }
        
        ActiveShip ship = new ActiveShip(shipBlocks, coreLocation, player);
        activeShips.put(player, ship);
        return true;
    }

    public static ActiveShip getShip(Player player) {
        return activeShips.get(player);
    }

    public static void removeShip(Player player) {
        activeShips.remove(player);
    }
}
