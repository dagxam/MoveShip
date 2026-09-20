package com.dagxam.moveship.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
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
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ShipMovementListener implements Listener {

    public ShipMovementListener(MoveShipPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                              PacketType.Play.Client.STEER_VEHICLE) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    Player player = event.getPlayer();
                    if (ShipManager.getShip(player) == null) return;

                    boolean fwd = false, bwd = false, left = false, right = false;

                    try {
                        Object handle = event.getPacket().getHandle();
                        // Paper 1.20.6: ServerboundPlayerInputPacket
                        // содержит поле типа Input (record с полями forward,backward,left,right)
                        Object input = null;

                        // Пробуем получить объект Input разными способами
                        for (Field field : handle.getClass().getDeclaredFields()) {
                            field.setAccessible(true);
                            Object val = field.get(handle);
                            if (val != null && val.getClass().getSimpleName()
                                                  .toLowerCase().contains("input")) {
                                input = val;
                                break;
                            }
                        }

                        if (input != null) {
                            // Paper 1.20.6 Input record: forward, backward, left, right
                            fwd   = getBool(input, "forward",  0);
                            bwd   = getBool(input, "backward", 1);
                            // ВАЖНО: в пакете left/right относительно экрана,
                            // нам нужно: left=A (нос влево), right=D (нос вправо)
                            left  = getBool(input, "left",     2);
                            right = getBool(input, "right",    3);
                        } else {
                            // Legacy (до 1.20.5): float sideway, float forward
                            Float side = event.getPacket().getFloat().readSafely(0);
                            Float forw = event.getPacket().getFloat().readSafely(1);
                            if (forw != null) { fwd = forw > 0.01f; bwd = forw < -0.01f; }
                            if (side != null) { left = side > 0.01f; right = side < -0.01f; }
                        }
                    } catch (Exception ex) {
                        plugin.getLogger().warning("STEER parse error: " + ex.getMessage());
                    }

                    final boolean F = fwd, B = bwd, L = left, R = right;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        ActiveShip ship = ShipManager.getShip(player);
                        if (ship != null) ship.setInput(F, B, L, R);
                    });
                }
            });
    }

    private static boolean getBool(Object obj, String fieldName, int fallbackIndex) {
        // 1) по имени поля/метода record
        try {
            Method m = obj.getClass().getDeclaredMethod(fieldName);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}
        // 2) по индексу поля
        try {
            Field[] fields = obj.getClass().getDeclaredFields();
            if (fallbackIndex < fields.length) {
                fields[fallbackIndex].setAccessible(true);
                Object v = fields[fallbackIndex].get(obj);
                if (v instanceof Boolean b) return b;
            }
        } catch (Exception ignored) {}
        // 3) toString последний шанс
        try {
            return obj.toString().contains(fieldName + "=true");
        } catch (Exception ignored) {}
        return false;
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (ShipManager.getShip(player) != null) {
            ShipManager.stopShip(player);
            player.sendMessage(Component.text(
                "Вы покинули штурвал. Корабль зафиксирован.", NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ShipManager.stopShip(event.getPlayer());
    }
}
