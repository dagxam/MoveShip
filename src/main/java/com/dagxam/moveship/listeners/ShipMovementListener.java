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

        // Paper 1.21.4 использует PLAYER_INPUT вместо STEER_VEHICLE
        PacketType inputPacket;
        try {
            // Пробуем новый тип пакета (1.21+)
            inputPacket = PacketType.Play.Client.PLAYER_INPUT;
        } catch (Exception e) {
            // Fallback для старых версий
            inputPacket = PacketType.Play.Client.STEER_VEHICLE;
        }

        final PacketType finalPacket = inputPacket;

        pm.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST, finalPacket) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (ShipManager.getShip(player) == null) return;

                boolean fwd = false, bwd = false, left = false, right = false;

                try {
                    Object handle = event.getPacket().getHandle();

                    // Paper 1.21.4: ServerboundPlayerInputPacket
                    // Поля: xxa (strafe), zza (forward), jumping, shiftKeyDown
                    // ИЛИ Input record с forward/backward/left/right

                    // Сначала пробуем найти Input record
                    Object input = findInputObject(handle);

                    if (input != null) {
                        // Input record (1.20.5+)
                        fwd   = getBool(input, "forward",  "isForward",  0);
                        bwd   = getBool(input, "backward", "isBackward", 1);
                        left  = getBool(input, "left",     "isLeft",     2);
                        right = getBool(input, "right",    "isRight",    3);
                    } else {
                        // Прямые float поля (legacy или 1.21.4 без Input record)
                        // zza = forward/backward, xxa = left/right (strafe)
                        Float zza = getFloatField(handle, "zza", 1);
                        Float xxa = getFloatField(handle, "xxa", 0);

                        if (zza != null) { fwd = zza > 0.01f; bwd = zza < -0.01f; }
                        if (xxa != null) {
                            // xxa > 0 = вправо (D), xxa < 0 = влево (A)
                            left  = xxa < -0.01f;
                            right = xxa > 0.01f;
                        }
                    }

                    // Диагностика (раскомментируй если нужно проверить пакеты)
                    // plugin.getLogger().info("Input: fwd=" + fwd + " bwd=" + bwd + " L=" + left + " R=" + right);

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

    // Ищем объект типа Input внутри пакета
    private static Object findInputObject(Object handle) {
        // Способ 1: метод input()
        try {
            Method m = handle.getClass().getMethod("input");
            return m.invoke(handle);
        } catch (Exception ignored) {}

        // Способ 2: поле с именем "input"
        try {
            Field f = handle.getClass().getDeclaredField("input");
            f.setAccessible(true);
            return f.get(handle);
        } catch (Exception ignored) {}

        // Способ 3: ищем поле тип которого содержит "input" в названии класса
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

    // Читаем boolean из объекта Input по имени поля или индексу
    private static boolean getBool(Object obj, String name, String getter, int idx) {
        // 1. По имени record-метода
        try {
            Method m = obj.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        // 2. По имени getter-метода
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

        // 4. toString как последний шанс
        try {
            return obj.toString().contains(name + "=true");
        } catch (Exception ignored) {}

        return false;
    }

    // Читаем float поле из пакета напрямую
    private static Float getFloatField(Object handle, String name, int fallbackIdx) {
        try {
            Field f = handle.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(handle);
            if (v instanceof Float fl) return fl;
        } catch (Exception ignored) {}

        try {
            Field[] fields = handle.getClass().getDeclaredFields();
            // Ищем float поля
            int floatCount = 0;
            for (Field f : fields) {
                if (f.getType() == float.class) {
                    if (floatCount == fallbackIdx) {
                        f.setAccessible(true);
                        return f.getFloat(handle);
                    }
                    floatCount++;
                }
            }
        } catch (Exception ignored) {}

        // ProtocolLib fallback
        try {
            return PacketType.Play.Client.STEER_VEHICLE.equals(
                PacketType.Play.Client.STEER_VEHICLE)
                ? null : null;
        } catch (Exception ignored) {}

        return null;
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

    // КРИТИЧНО: паркуем корабль если игрок вышел с сервера
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ShipManager.stopShip(event.getPlayer());
    }
}
