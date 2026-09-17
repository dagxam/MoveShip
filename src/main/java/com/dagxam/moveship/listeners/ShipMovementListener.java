package com.dagxam.moveship.listeners;

import com.dagxam.moveship.core.ActiveShip;
import com.dagxam.moveship.core.ShipManager;
import com.destroystokyo.paper.event.player.PlayerSteerVehicleEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;

public class ShipMovementListener implements Listener {

    @EventHandler
    public void onVehicleSteer(PlayerSteerVehicleEvent event) {
        Player player = event.getPlayer();
        ActiveShip ship = ShipManager.getShip(player);

        // Если игрок не управляет кораблем, игнорируем
        if (ship == null) return;

        // Получаем параметры нажатий
        float forward = event.getForward(); // W (положительное) или S (отрицательное)
        float side = event.getSide();       // A (положительное) или D (отрицательное)

        // Движение вперед/назад
        if (forward != 0) {
            ship.move(forward);
        }

        // Поворот (пока что поворачивает только "нос" якоря, блоки просто едут за ним)
        if (side != 0) {
            ship.rotate(side);
        }
        
        // ВАЖНО: Если вы хотите, чтобы корабль летел вверх на Пробел (event.isJump()), 
        // это можно добавить позже.
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Если игрок нажал Shift и слез с сущности, проверяем, управлял ли он кораблем
        if (ShipManager.getShip(player) != null) {
            
            // Останавливаем корабль (превращаем в твердые блоки)
            ShipManager.stopShip(player);
            
            player.sendMessage(Component.text("Вы покинули штурвал. Корабль зафиксирован.", NamedTextColor.YELLOW));
        }
    }
}
