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

                boolean fwd = false, bwd = false, l = false, r = false;

                try {
                    // Парсинг нового формата пакета (1.20.6+ Record Input)
                    Object inputObj = event.getPacket().getModifier().read(0);
                    if (inputObj != null) {
                        String inputStr = inputObj.toString();
                        fwd = inputStr.contains("forward=true");
                        bwd = inputStr.contains("backward=true");
                        l = inputStr.contains("left=true");
                        r = inputStr.contains("right=true");
                    }
                } catch (Exception ignored) {
                    // Старый формат (до 1.20.4)
                    float sideVal = event.getPacket().getFloat().readSafely(0);
                    float forwardVal = event.getPacket().getFloat().readSafely(1);
                    fwd = forwardVal > 0;
                    bwd = forwardVal < 0;
                    l = sideVal > 0;
                    r = sideVal < 0;
                }

                final boolean finalFwd = fwd;
                final boolean finalBwd = bwd;
                final boolean finalL = l;
                final boolean finalR = r;

                // Немедленно передаем состояние кнопок в ядро корабля
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ship.setInput(finalFwd, finalBwd, finalL, finalR);
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
