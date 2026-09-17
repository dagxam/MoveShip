package com.dagxam.moveship.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.dagxam.moveship.MoveShipPlugin;
import com.dagxam.moveship.core.ActiveShip;
import com.dagxam.moveship.core.ShipManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;

public class ShipMovementListener implements Listener {

    public ShipMovementListener(MoveShipPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.STEER_VEHICLE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                ActiveShip ship = ShipManager.getShip(player);

                if (ship == null) return;

                float sideVal = 0;
                float forwardVal = 0;

                try {
                    // Старый формат
                    sideVal = event.getPacket().getFloat().read(0);
                    forwardVal = event.getPacket().getFloat().read(1);
                } catch (Exception e) {
                    // Новый формат (1.20.6+)
                    Object inputObj = event.getPacket().getModifier().read(0);
                    if (inputObj != null) {
                        String inputStr = inputObj.toString();
                        
                        boolean forward = inputStr.contains("forward=true");
                        boolean backward = inputStr.contains("backward=true");
                        boolean left = inputStr.contains("left=true");
                        boolean right = inputStr.contains("right=true");

                        forwardVal = (forward ? 1.0f : 0.0f) + (backward ? -1.0f : 0.0f);
                        sideVal = (left ? 1.0f : 0.0f) + (right ? -1.0f : 0.0f);
                    }
                }

                final float finalForward = forwardVal;
                final float finalSide = sideVal;

                // Передаем текущее состояние кнопок (зажато или отпущено) в ядро корабля
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ship.setInput(finalForward, finalSide);
                });
            }
        });
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (ShipManager.getShip(player) != null) {
            ShipManager.stopShip(player);
            player.sendMessage(Component.text("Вы покинули штурвал. Корабль зафиксирован.", NamedTextColor.YELLOW));
        }
    }
}
