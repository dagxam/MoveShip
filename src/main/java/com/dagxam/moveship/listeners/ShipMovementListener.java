package com.dagxam.moveship.listeners;

import com.dagxam.moveship.MoveShipPlugin;
import com.dagxam.moveship.core.ActiveShip;
import com.dagxam.moveship.core.ShipManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Input;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

public class ShipMovementListener implements Listener {

    public ShipMovementListener(MoveShipPlugin plugin) {
    }

    /**
     * Сохраняем получение input-события Paper для совместимости с текущим
     * слоем управления. Фактическое движение теперь выполняет сама Boat.
     */
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

    /**
     * Настоящий источник движения активного корабля.
     *
     * Boat получает штатное движение Minecraft, а наш ActiveShip
     * превращает новую позицию Boat в новую позицию построенного корабля
     * после проверки его полной collision-модели.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Boat boat)) {
            return;
        }

        ActiveShip ship = ShipManager.getShip(boat);

        if (ship == null) {
            return;
        }

        ship.processCarrierMove(
                event.getFrom(),
                event.getTo()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPilotLook(PlayerMoveEvent event) {
        ActiveShip ship = ShipManager.getShip(event.getPlayer());

        if (ship == null || event.getTo() == null) {
            return;
        }

        /*
         * Запоминаем только yaw/pitch мыши.
         * Положение игрока и курс Boat здесь не смешиваются.
         */
        ship.setPilotView(
                event.getTo().getYaw(),
                event.getTo().getPitch()
        );
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (ShipManager.getShip(player) != null) {
            ShipManager.stopShip(player);

            player.sendMessage(
                    Component.text(
                            "Вы покинули штурвал. Корабль зафиксирован.",
                            NamedTextColor.YELLOW
                    )
            );
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ShipManager.stopShip(event.getPlayer());
    }
}
