package com.dagxam.moveship.core;

import com.dagxam.moveship.MoveShipPlugin;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Wall;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ActiveShip {

    private final Player pilot;
    private final MoveShipPlugin plugin;
    private final ArmorStand seatEntity;

    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay>  displayEntities = new ArrayList<>();

    // ── физика как у лодки ──────────────────────────────────────────────────
    private static final double MAX_FWD    = 0.40;
    private static final double MAX_BACK   = 0.15;
    private static final double ACCEL      = 0.025;  // плавный разгон
    private static final double DECEL      = 0.88;   // инерция (трение воды)

    private static final float TURN_MAX    = 3.0f;
    private static final float TURN_ACCEL  = 0.4f;
    private static final float TURN_DECEL  = 0.78f;

    // ── ввод (обновляется из PlayerInputEvent каждый тик) ───────────────────
    private boolean kFwd, kBack, kLeft, kRight;

    // ── состояние ───────────────────────────────────────────────────────────
    private Location anchorCenter;
    private float    shipYaw;
    private final float initialYaw;

    private double currentSpeed = 0.0;
    private float  currentTurn  = 0.0f;

    private final BukkitTask task;
    private final Set<Block> submergedWake = new HashSet<>();
    private record BP(int x, int y, int z) {}

    // Высота блока на котором стоял игрок при активации
    private final double activationBlockY;

    private static final List<BlockFace> ROT16 = List.of(
            BlockFace.NORTH,           BlockFace.NORTH_NORTH_EAST,
            BlockFace.NORTH_EAST,      BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST,            BlockFace.EAST_SOUTH_EAST,
            BlockFace.SOUTH_EAST,      BlockFace.SOUTH_SOUTH_EAST,
            BlockFace.SOUTH,           BlockFace.SOUTH_SOUTH_WEST,
            BlockFace.SOUTH_WEST,      BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST,            BlockFace.WEST_NORTH_WEST,
            BlockFace.NORTH_WEST,      BlockFace.NORTH_NORTH_WEST
    );

    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot  = pilot;
        this.plugin = JavaPlugin.getPlugin(MoveShipPlugin.class);

        this.anchorCenter = anchorLocation.getBlock().getLocation().add(0.5, 0.0, 0.5);
        this.anchorCenter.setWorld(anchorLocation.getWorld());

        this.shipYaw    = pilot.getLocation().getYaw();
        this.initialYaw = this.shipYaw;

        // Запоминаем Y блока под ногами игрока при активации
        // Это нужно чтобы кресло было ровно на палубе
        Block blockUnderPilot = pilot.getLocation().getBlock().getRelative(BlockFace.DOWN);
        this.activationBlockY = blockUnderPilot.getY() + 1.0;

        // ── Ватерлиния ──────────────────────────────────────────────────────
        int seaLevel = Integer.MIN_VALUE;
        for (Block b : blocks) {
            for (BlockFace f : new BlockFace[]{
                    BlockFace.NORTH, BlockFace.SOUTH,
                    BlockFace.EAST,  BlockFace.WEST,
                    BlockFace.DOWN}) {
                Block nb = b.getRelative(f);
                if (!blocks.contains(nb)) {
                    Material m = nb.getType();
                    if (m == Material.WATER || m == Material.SEAGRASS
                            || m == Material.KELP || m == Material.TALL_SEAGRASS
                            || m == Material.BUBBLE_COLUMN) {
                        if (nb.getY() > seaLevel) seaLevel = nb.getY();
                    }
                }
            }
        }
        if (seaLevel != Integer.MIN_VALUE) {
            for (Block b : blocks)
                if (b.getY() <= seaLevel) submergedWake.add(b);
        }

        // ── Сохраняем и удаляем физические блоки, спавним Display ──────────
        for (Block block : blocks) {
            Location bc     = block.getLocation().add(0.5, 0.0, 0.5);
            Vector   offset = bc.toVector().subtract(anchorCenter.toVector());
            BlockData bd    = block.getBlockData().clone();

            ItemStack[] savedItems = null;
            BlockState  snapshot;

            if (block.getState() instanceof Container cnt) {
                Inventory inv = rawInventory(cnt);
                savedItems = deepCopy(inv);
                inv.clear();
                cnt.update(true, false);
                snapshot = block.getState(true);
            } else {
                snapshot = block.getState(true);
            }

            originalBlocks.add(new ShipBlockData(offset.clone(), bd, snapshot, savedItems));
            block.setType(Material.AIR, false);

            BlockDisplay disp = bc.getWorld().spawn(bc, BlockDisplay.class, e -> {
                e.setBlock(bd);
                e.setPersistent(false);
                // teleportDuration=1 = клиент интерполирует 60 FPS между тиками
                e.setTeleportDuration(1);
                e.setInterpolationDuration(0);
                e.setInterpolationDelay(0);
                // Центрируем блок (BlockDisplay спавнится в углу)
                e.setTransformation(new Transformation(
                        new Vector3f(-0.5f, 0f, -0.5f),
                        new Quaternionf(),
                        new Vector3f(1f, 1f, 1f),
                        new Quaternionf()
                ));
            });
            displayEntities.add(disp);
        }

        // ── Кресло пилота ────────────────────────────────────────────────────
        // Спавним ПОСЛЕ удаления блоков
        // Y = activationBlockY - 0.6 чтобы игрок сидел РОВНО на палубе
        Location seatLoc = anchorCenter.clone();
        seatLoc.setY(activationBlockY - 0.6);
        seatLoc.setYaw(shipYaw);
        seatLoc.setPitch(0f);

        seatEntity = seatLoc.getWorld().spawn(seatLoc, ArmorStand.class, e -> {
            e.setInvisible(true);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setMarker(true);
            e.setSmall(true);
            e.setBasePlate(false);
            e.setPersistent(false);
        });
        seatEntity.addPassenger(pilot);

        // ── Такт движка каждый тик ───────────────────────────────────────────
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ── Ввод из PlayerInputEvent (Paper 1.21+) ───────────────────────────────
    public void setInput(boolean forward, boolean backward, boolean left, boolean right) {
        this.kFwd   = forward;
        this.kBack  = backward;
        this.kLeft  = left;
        this.kRight = right;
    }

    // ── Тик движка ──────────────────────────────────────────────────────────
    private void tick() {
        if (!pilot.isOnline() || !seatEntity.isValid()) {
            task.cancel();
            return;
        }

        // ── Скорость (плавная инерция как у лодки) ──
        if (kFwd && !kBack) {
            currentSpeed = Math.min(MAX_FWD, currentSpeed + ACCEL);
        } else if (kBack && !kFwd) {
            currentSpeed = Math.max(-MAX_BACK, currentSpeed - ACCEL);
        } else {
            // Плавное торможение — инерция воды
            currentSpeed *= DECEL;
            if (Math.abs(currentSpeed) < 0.001) currentSpeed = 0.0;
        }

        // ── Поворот (A = влево, D = вправо) ──
        if (kLeft && !kRight) {
            currentTurn = Math.max(-TURN_MAX, currentTurn - TURN_ACCEL);
        } else if (kRight && !kLeft) {
            currentTurn = Math.min(TURN_MAX,  currentTurn + TURN_ACCEL);
        } else {
            currentTurn *= TURN_DECEL;
            if (Math.abs(currentTurn) < 0.02f) currentTurn = 0f;
        }

        boolean moved = false;

        // ── Применяем поворот ──
        if (currentTurn != 0f) {
            float nextYaw = norm(shipYaw + currentTurn);
            if (canMoveTo(anchorCenter, nextYaw)) {
                shipYaw = nextYaw;
                moved = true;
            } else {
                currentTurn = 0f;
                currentSpeed *= 0.5;
            }
        }

        // ── Применяем движение ──
        if (Math.abs(currentSpeed) > 0.001) {
            Vector dir  = yawDir(shipYaw).multiply(currentSpeed);
            Location next = anchorCenter.clone().add(dir);
            if (canMoveTo(next, shipYaw)) {
                anchorCenter = next;
                moved = true;
            } else {
                currentSpeed = 0.0;
            }
        }

        if (!moved) return;

        // ── Обновляем позиции всех BlockDisplay ──
        float  delta = shipYaw - initialYaw;
        double rad   = Math.toRadians(delta);
        double cos   = Math.cos(rad);
        double sin   = Math.sin(rad);

        // Quaternion поворота для Transformation
        Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(-delta));
        Vector3f    tr  = new Vector3f(-0.5f, 0f, -0.5f);
        rot.transform(tr);
        Transformation tf = new Transformation(tr, rot,
                new Vector3f(1f, 1f, 1f), new Quaternionf());

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay disp = displayEntities.get(i);
            if (!disp.isValid()) continue;

            Vector off = originalBlocks.get(i).getRelativeOffset();
            double nx  = off.getX() * cos - off.getZ() * sin;
            double nz  = off.getX() * sin + off.getZ() * cos;

            Location target = anchorCenter.clone().add(nx, off.getY(), nz);
            target.setYaw(0f);
            target.setPitch(0f);

            // teleportDuration=1 → плавная интерполяция клиентом
            disp.teleport(target);

            // Обновляем поворот через 
