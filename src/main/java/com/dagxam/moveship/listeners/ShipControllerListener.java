package com.dagxam.moveship.listeners;

import com.dagxam.moveship.MoveShipPlugin;
import com.dagxam.moveship.gui.ShipGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class ShipControllerListener implements Listener {

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onControllerPlace(BlockPlaceEvent event) {
        ItemStack itemInHand = event.getItemInHand();

        if (itemInHand.getItemMeta() == null) {
            return;
        }

        if (!itemInHand.getItemMeta()
                .getPersistentDataContainer()
                .has(
                        MoveShipPlugin.CONTROLLER_KEY,
                        PersistentDataType.BYTE
                )) {
            return;
        }

        Block block = event.getBlockPlaced();

        if (block.getState() instanceof Lectern lectern) {
            lectern.getPersistentDataContainer().set(
                    MoveShipPlugin.CONTROLLER_KEY,
                    PersistentDataType.BYTE,
                    (byte) 1
            );

            lectern.update(true, false);

            event.getPlayer().sendMessage(Component.text(
                    "Ядро корабля установлено! Нажмите ПКМ для управления.",
                    NamedTextColor.GREEN
            ));
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onControllerClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();

        if (block == null || block.getType() != Material.LECTERN) {
            return;
        }

        if (!(block.getState() instanceof Lectern lectern)) {
            return;
        }

        if (!lectern.getPersistentDataContainer().has(
                MoveShipPlugin.CONTROLLER_KEY,
                PersistentDataType.BYTE
        )) {
            return;
        }

        event.setCancelled(true);
        ShipGUI.open(event.getPlayer(), block.getLocation());
    }
}
