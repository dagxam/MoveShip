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
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Управление активным кораблем.
 *
 * Как и в проверенной архитектуре SimpleShips, управление W/A/S/D
 * поступает через нативный Paper PlayerInputEvent.
 *
 * Направление взгляда игрока здесь не перехватывается:
 * yaw/pitch принадлежат игроку, а yaw штурвала после активации заморожен.
 */
public final class ShipMovementListener implements Listener {

    public ShipMovementListener(MoveShipPlugin plugin) {
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        ActiveShip ship = ShipManager.getShip(player);

        if (ship == null) {
            return;
        }

        Input input = event.getInput();

        ship.setInput(
                input.isForward(),
                input.isBackward(),
                input.isLeft(),
                input.isRight()
        );
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (ShipManager.getShip(player) == null) {
            return;
        }

        ShipManager.stopShip(player);

        player.sendMessage(
                Component.text(
                        "Вы покинули штурвал. Корабль зафиксирован.",
                        NamedTextColor.YELLOW
                )
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ShipManager.stopShip(event.getPlayer());
    }
}
