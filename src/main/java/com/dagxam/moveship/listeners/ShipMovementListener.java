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
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ShipMovementListener implements Listener {

    private final MoveShipPlugin plugin;

    public ShipMovementListener(MoveShipPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);

        ProtocolManager pm = ProtocolLibrary.getProtocolManager();

        // ProtocolLib 5.3.0 использует STEER_VEHICLE для всех версий MC
        pm.addPacketListener(new PacketAdapter(
                plugin,
                ListenerPriority.HIGHEST,
                PacketType.Play.Client.STEER_VEHICLE
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (ShipManager.getShip(player) == null) return;

                boolean fwd = false, bwd = false, left = false, right = false;

                try {
                    Object handle = event.getPacket().getHandle();

                    // Пробуем найти Input record (Paper 1.20.5+)
                    Object input = findInputObject(handle);

                    if (input != null) {
                        // Input record содержит: forward, backward, left, right
                        fwd   = getBool(input, "forward",  "isForward",  0);
                        bwd   = getBool(input, "backward", "isBackward", 1);
                        left  = getBool(input, "left",     "isLeft",     2);
                        right = getBool(input, "right",    "isRight",    3);
                    } else {
                        // Старый формат: float[] sideway(0), forward(1)
                        Float side = event.getPacket().getFloat().readSafely(0);
                        Float forw = event.getPacket().getFloat().readSafely(1);
                        if (forw != null) { fwd = forw > 0.01f; bwd = forw < -0.01f; }
                        if (side != null) { left = side > 0.01f; right = side < -0.01f; }
                    }

                    // Раскомментируй для диагностики пакетов:
                    // plugin.getLogger().info("fwd=" + fwd + " bwd=" + bwd + " L=" + left + " R=" + right);

                } catch (Exception ex) {
                    plugin.getLogger().warning("Packet parse error: " + ex.getMessage());
                }

                final boolean F = fwd, B = bwd, L = left, R = right;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ActiveShip ship = ShipManager.getShip(player);
                    if (ship != null) ship.setInput(F, B, L, R);
                });
            }
        });
    }

    private static Object findInputObject(Object handle) {
        // Способ 1: метод input()
        try {
            Method m = handle.getClass().getMethod("input");
            return m.invoke(handle);
        } catch (Exception ignored) {}

        // Способ 2: поле "input"
        try {
            Field f = handle.getClass().getDeclaredField("input");
            f.setAccessible(true);
            return f.get(handle);
        } catch (Exception ignored) {}

        // Способ 3: поле тип которого содержит "input" в имени класса
        try {
            for (Field f : handle.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(handle);
                if (val != null && val.getClass().getSimpleName()
                                     .toLowerCase().contains("input")) {
                    return val;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static boolean getBool(Object obj, String name, String getter, int idx) {
        // 1. По имени record-метода
        try {
            Method m = obj.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        // 2. По имени getter
        try {
            Method m = obj.getClass().getDeclaredMethod(getter);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        // 3. По индексу поля
        try {
            Field[] fields = obj.getClass().getDeclaredFields();
            if (idx < fields.length) {
                fields[idx].setAccessible(true);
                Object v = fields[idx].get(obj);
                if (v instanceof Boolean b) return b;
            }
        } catch (Exception ignored) {}

        // 4. toString
        try {
            return obj.toString().contains(name + "=true");
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
