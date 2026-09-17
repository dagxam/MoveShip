package com.dagxam.moveship.core;

import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

// Record - это современный способ создания классов-хранилищ данных в Java
public record ShipBlockData(
        BlockData blockData,      // Тип блока и его поворот (например, ступеньки смотрят на север)
        ItemStack[] inventory,    // Вещи внутри (null, если это обычный блок досок)
        int offsetX,              // Смещение по X от кафедры
        int offsetY,              // Смещение по Y
        int offsetZ               // Смещение по Z
) {}
