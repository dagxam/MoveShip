package com.dagxam.moveship;

import com.dagxam.moveship.listeners.ShipControllerListener;
import com.dagxam.moveship.listeners.ShipGUIListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public class MoveShipPlugin extends JavaPlugin {

    public static NamespacedKey CONTROLLER_KEY;

    @Override
    public void onEnable() {
        CONTROLLER_KEY = new NamespacedKey(this, "ship_controller");

        registerShipControllerRecipe();

        getServer().getPluginManager().registerEvents(new ShipControllerListener(), this);
        getServer().getPluginManager().registerEvents(new ShipGUIListener(), this);

        getLogger().info("MoveShip плагин успешно запущен!");
    }

    @Override
    public void onDisable() {
        getLogger().info("MoveShip плагин отключен.");
    }

    private void registerShipControllerRecipe() {
        ItemStack controllerItem = new ItemStack(Material.LECTERN);
        ItemMeta meta = controllerItem.getItemMeta();
        
        if (meta != null) {
            meta.displayName(Component.text("Кафедра управления", NamedTextColor.GOLD));
            meta.getPersistentDataContainer().set(CONTROLLER_KEY, PersistentDataType.BYTE, (byte) 1);
            controllerItem.setItemMeta(meta);
        }

        NamespacedKey recipeKey = new NamespacedKey(this, "ship_controller_recipe");
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, controllerItem);
        
        recipe.shape("PPP", "LLL");
        recipe.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
        recipe.setIngredient('L', new RecipeChoice.MaterialChoice(Tag.LOGS));

        Bukkit.addRecipe(recipe);
    }
}
