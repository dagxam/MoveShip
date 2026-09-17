package com.dagxam.moveship;

import com.dagxam.moveship.listeners.ShipControllerListener;
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

    // Уникальный ключ NBT для идентификации кафедры корабля
    public static NamespacedKey CONTROLLER_KEY;

    @Override
    public void onEnable() {
        // Инициализация уникального ключа (пространство имен плагина)
        CONTROLLER_KEY = new NamespacedKey(this, "ship_controller");

        // Регистрируем рецепт крафта
        registerShipControllerRecipe();

        // Регистрируем слушатель событий (блоки и клики)
        getServer().getPluginManager().registerEvents(new ShipControllerListener(), this);

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
            // Paper использует Adventure API для цвета и текста
            meta.displayName(Component.text("Кафедра управления", NamedTextColor.GOLD));
            
            // Сохраняем NBT тег прямо в предмет
            meta.getPersistentDataContainer().set(CONTROLLER_KEY, PersistentDataType.BYTE, (byte) 1);
            controllerItem.setItemMeta(meta);
        }

        // Создаем рецепт
        NamespacedKey recipeKey = new NamespacedKey(this, "ship_controller_recipe");
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, controllerItem);
        
        // Матрица 3x2: P - любые доски, L - любые бревна
        recipe.shape("PPP", "LLL");
        recipe.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
        recipe.setIngredient('L', new RecipeChoice.MaterialChoice(Tag.LOGS));

        Bukkit.addRecipe(recipe);
    }
}
