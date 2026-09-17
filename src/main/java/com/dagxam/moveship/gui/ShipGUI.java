package com.dagxam.moveship.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ShipGUI implements InventoryHolder {

    private final Inventory inventory;
    private final Location coreLocation; // Координаты нашей кафедры управления

    public ShipGUI(Location coreLocation) {
        this.coreLocation = coreLocation;
        // Создаем инвентарь в 1 строку (9 слотов)
        this.inventory = Bukkit.createInventory(this, 9, Component.text("Управление кораблем", NamedTextColor.DARK_GRAY));
        initializeItems();
    }

    private void initializeItems() {
        // Слот 0: Сканировать (Компас)
        inventory.setItem(0, createGuiItem(Material.COMPASS, "Сканировать", NamedTextColor.AQUA, 
                "Найти все связанные блоки корабля."));
        
        // Слот 3: Активировать (Зеленая шерсть)
        inventory.setItem(3, createGuiItem(Material.LIME_WOOL, "Активировать", NamedTextColor.GREEN, 
                "Перевести корабль в режим движения."));
        
        // Слот 5: Остановить (Красная шерсть)
        inventory.setItem(5, createGuiItem(Material.RED_WOOL, "Остановить", NamedTextColor.RED, 
                "Остановить корабль и зафиксировать блоки."));
        
        // Слот 8: Выход (Барьер)
        inventory.setItem(8, createGuiItem(Material.BARRIER, "Выход", NamedTextColor.DARK_RED, 
                "Закрыть меню."));
    }

    private ItemStack createGuiItem(Material material, String name, NamedTextColor color, String loreText) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Убираем стандартный наклонный шрифт (курсив) у предметов
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text(loreText, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public Location getCoreLocation() {
        return coreLocation;
    }

    // Удобный статический метод для открытия меню
    public static void open(Player player, Location coreLocation) {
        ShipGUI gui = new ShipGUI(coreLocation);
        player.openInventory(gui.getInventory());
    }
}
