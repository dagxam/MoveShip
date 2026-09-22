package com.dagxam.moveship.listeners;

import com.dagxam.moveship.MoveShipPlugin;
import com.dagxam.moveship.core.ShipManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Обработчики жизненного цикла управления кораблем.
 *
 * W/A/S/D не обрабатываются через событие изменения input.
 * ActiveShip каждый тик получает актуальное состояние через
 * Player#getCurrentInput(), что корректно сохраняет удержание клавиши.
 */
public final class ShipMovementListener implements Listener {

    public ShipMovementListener(MoveShipPlugin plugin) {
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
