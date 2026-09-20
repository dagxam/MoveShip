package com.dagxam.moveship.listeners;

import com.dagxam.moveship.MoveShipPlugin;
import com.dagxam.moveship.core.ActiveShip;
import com.dagxam.moveship.core.ShipManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.input.PlayerInput;

public class ShipMovementListener implements Listener {

    public ShipMovementListener(MoveShipPlugin plugin) {
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // Paper 1.21+ — стандартный ивент ввода игрока (без ProtocolLib!)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        ActiveShip ship = ShipManager.getShip(player);
        if (ship == null) return;

        PlayerInput input = event.getInput();

        boolean forward  = input.forward();
        boolean backward = input.backward();
        boolean left     = input.left();
        boolean right    = input.right();

        ship.setInput(forward, backward, left, right);
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
