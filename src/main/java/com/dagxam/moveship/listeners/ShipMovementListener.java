package com.dagxam.moveship.listeners;

import com.dagxam.moveship.MoveShipPlugin;
import com.dagxam.moveship.core.ActiveShip;
import com.dagxam.moveship.core.ShipManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Input;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class ShipMovementListener implements Listener {

    public ShipMovementListener(MoveShipPlugin plugin) {
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        ActiveShip ship = ShipManager.getShip(player);
        if (ship == null) return;

        Input input = event.getInput();

        ship.setInput(
            input.isForward(),
            input.isBackward(),
            input.isLeft(),
            input.isRight()
        );
    }

    /**
     * Во время управления кораблем игрок не может физически сместиться
     * с точки штурвала. yaw/pitch из события сохраняются, поэтому мышь
     * продолжает свободно вращать голову.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        ActiveShip ship = ShipManager.getShip(event.getPlayer());
        if (ship == null) return;
        ship.constrainPilotMove(event);
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (ShipManager.getShip(player) != null) {
            ShipManager.stopShip(player);
            player.sendMessage(Component.text(
                "Вы покинули штурвал. Корабль зафиксирован.",
                NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ShipManager.stopShip(event.getPlayer());
    }
}
