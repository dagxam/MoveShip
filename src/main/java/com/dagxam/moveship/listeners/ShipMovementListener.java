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

                float forwardVal = 0;
                float sideVal = 0;

                // Безопасная проверка, чтобы избежать ошибок в консоли для новых версий
                if (event.getPacket().getFloat().size() >= 2) {
                    sideVal = event.getPacket().getFloat().read(0);
                    forwardVal = event.getPacket().getFloat().read(1);
                } else {
                    boolean fwd = false, bwd = false, l = false, r = false;
                    for (Object obj : event.getPacket().getModifier().getValues()) {
                        if (obj != null) {
                            String str = obj.toString();
                            if (str.contains("forward=true")) fwd = true;
                            if (str.contains("backward=true")) bwd = true;
                            if (str.contains("left=true")) l = true;
                            if (str.contains("right=true")) r = true;
                        }
                    }
                    forwardVal = (fwd ? 1.0f : 0.0f) + (bwd ? -1.0f : 0.0f);
                    sideVal = (l ? 1.0f : 0.0f) + (r ? -1.0f : 0.0f);
                }

                final float finalForward = forwardVal;
                final float finalSide = sideVal;

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
