package com.dagxam.moveship.listeners;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
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

import java.lang.reflect.Method;

public class ShipMovementListener implements Listener {

    public ShipMovementListener(MoveShipPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        protocolManager.addPacketListener(new PacketAdapter(
                plugin,
                ListenerPriority.HIGHEST,
                PacketType.Play.Client.STEER_VEHICLE
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                ActiveShip ship = ShipManager.getShip(player);
                if (ship == null) return;

                boolean fwd = false;
                boolean bwd = false;
                boolean left = false;
                boolean right = false;

                try {
                    Object handle = event.getPacket().getHandle();
                    Object input = null;

                    try {
                        Method inputMethod = handle.getClass().getMethod("input");
                        input = inputMethod.invoke(handle);
                    } catch (NoSuchMethodException ignored) {
                        try {
                            var field = handle.getClass().getDeclaredField("input");
                            field.setAccessible(true);
                            input = field.get(handle);
                        } catch (Exception ignored2) {
                            StructureModifier<Object> mod = event.getPacket().getModifier();
                            if (mod.size() > 0) {
                                input = mod.read(0);
                            }
                        }
                    }

                    if (input != null) {
                        fwd = readBool(input, "forward", "getForward", 0);
                        bwd = readBool(input, "backward", "getBackward", 1);
                        left = readBool(input, "left", "getLeft", 2);
                        right = readBool(input, "right", "getRight", 3);
                    } else {
                        Float sideVal = event.getPacket().getFloat().readSafely(0);
                        Float forwardVal = event.getPacket().getFloat().readSafely(1);
                        if (forwardVal != null) {
                            fwd = forwardVal > 0.01f;
                            bwd = forwardVal < -0.01f;
                        }
                        if (sideVal != null) {
                            left = sideVal > 0.01f;
                            right = sideVal < -0.01f;
                        }
                    }
                } catch (Exception ex) {
                    try {
                        Float sideVal = event.getPacket().getFloat().readSafely(0);
                        Float forwardVal = event.getPacket().getFloat().readSafely(1);
                        if (forwardVal != null) {
                            fwd = forwardVal > 0.01f;
                            bwd = forwardVal < -0.01f;
                        }
                        if (sideVal != null) {
                            left = sideVal > 0.01f;
                            right = sideVal < -0.01f;
                        }
                    } catch (Exception ignored) {
                    }
                }

                final boolean f = fwd;
                final boolean b = bwd;
                final boolean l = left;
                final boolean r = right;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    ActiveShip active = ShipManager.getShip(player);
                    if (active != null) {
                        active.setInput(f, b, l, r);
                    }
                });
            }
        });
    }

    private static boolean readBool(Object input, String recordName, String getterName, int recordIndex) {
        try {
            Method m = input.getClass().getMethod(recordName);
            Object val = m.invoke(input);
            if (val instanceof Boolean bool) return bool;
        } catch (Exception ignored) {
        }
        try {
            Method m = input.getClass().getMethod(getterName);
            Object val = m.invoke(input);
            if (val instanceof Boolean bool) return bool;
        } catch (Exception ignored) {
        }
        try {
            var fields = input.getClass().getDeclaredFields();
            if (recordIndex < fields.length) {
                fields[recordIndex].setAccessible(true);
                Object val = fields[recordIndex].get(input);
                if (val instanceof Boolean bool) return bool;
            }
        } catch (Exception ignored) {
        }
        try {
            String s = input.toString().toLowerCase();
            return s.contains(recordName.toLowerCase() + "=true");
        } catch (Exception ignored) {
        }
        return false;
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
