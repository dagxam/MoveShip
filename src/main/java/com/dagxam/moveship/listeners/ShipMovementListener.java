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
        // 1. Регистрируем слушатель для выхода с корабля (Shift)
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // 2. Регистрируем перехват пакетов WASD через ProtocolLib
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Client.STEER_VEHICLE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                ActiveShip ship = ShipManager.getShip(player);

                // Если игрок не управляет кораблем, пропускаем пакет
                if (ship == null) return;

                // Читаем нажатия кнопок из пакета (WASD)
                float side = event.getPacket().getFloat().read(0);    // A (влево) / D (вправо)
                float forward = event.getPacket().getFloat().read(1); // W (вперед) / S (назад)

                // Пакеты приходят асинхронно! Minecraft API (телепорты, проверка блоков) 
                // можно использовать ТОЛЬКО в основном потоке сервера. 
                // Поэтому мы передаем задачу в Scheduler:
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (forward != 0) {
                        ship.move(forward);
                    }
                    if (side != 0) {
                        ship.rotate(side);
                    }
                });
            }
        });
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Если игрок нажал Shift и слез, останавливаем корабль
        if (ShipManager.getShip(player) != null) {
            ShipManager.stopShip(player);
            player.sendMessage(Component.text("Вы покинули штурвал. Корабль зафиксирован.", NamedTextColor.YELLOW));
        }
    }
}
