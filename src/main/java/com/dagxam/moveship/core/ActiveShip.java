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

    private final List<ShipBlockData> originalBlocks  = new ArrayList<>();
    private final List<BlockDisplay>  displayEntities = new ArrayList<>();

    private static final double MAX_FWD   = 0.40;
    private static final double MAX_BACK  = 0.15;
    private static final double ACCEL     = 0.025;
    private static final double DECEL     = 0.88;

    private static final float TURN_MAX   = 3.0f;
    private static final float TURN_ACCEL = 0.4f;
    private static final float TURN_DECEL = 0.78f;

    private boolean kFwd, kBack, kLeft, kRight;

    private Location anchorCenter;
    private float    shipYaw;
    private final float initialYaw;

    private double currentSpeed = 0.0;
    private float  currentTurn  = 0.0f;

    private final BukkitTask task;
    private final Set<Block> submergedWake = new HashSet<>();
    private record BP(int x, int y, int z) {}

    private final double activationBlockY;

    // ФИКС 1: Храним последнюю применённую Transformation чтобы не пересчитывать без поворота
    private float lastAppliedDelta = Float.MAX_VALUE;

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

        Block blockUnder = pilot.getLocation().getBlock().getRelative(BlockFace.DOWN);
        this.activationBlockY = blockUnder.getY() + 1.0;

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

        // ФИКС 2: Сохраняем инвентарь через getInventory() а не rawInventory()
        // чтобы потом корректно восстановить
        for (Block block : blocks) {
            Location  bc     = block.getLocation().add(0.5, 0.0, 0.5);
            Vector    offset = bc.toVector().subtract(anchorCenter.toVector());
            BlockData bd     = block.getBlockData().clone();

            ItemStack[] savedItems = null;
            BlockState  snapshot;

            if (block.getState() instanceof Container cnt) {
                // Используем getInventory() — работает для всех контейнеров
                Inventory inv = cnt.getInventory();
                savedItems = deepCopy(inv);
                inv.clear();
                cnt.update(true, false);
                snapshot = block.getState(true);
            } else {
                snapshot = block.getState(true);
            }

            originalBlocks.add(new ShipBlockData(offset.clone(), bd, snapshot, savedItems));
            block.setType(Material.AIR, false);

            // ФИКС 1: BlockDisplay спавним в anchorCenter, смещение — через Transformation
            // Это значит позиция entity НЕ МЕНЯЕТСЯ при движении без поворота
            // Двигаем только seatEntity, блоки получают позицию через Transformation offset
            final Vector finalOffset = offset.clone();
            BlockDisplay disp = anchorCenter.getWorld().spawn(anchorCenter, BlockDisplay.class, e -> {
                e.setBlock(bd);
                e.setPersistent(false);
                // teleportDuration = 2 тика = клиент интерполирует плавно
                e.setTeleportDuration(2);
                e.setInterpolationDuration(2);
                e.setInterpolationDelay(0);
                // Начальный оффсет блока от якоря
                e.setTransformation(new Transformation(
                        new Vector3f(
                            (float) finalOffset.getX() - 0.5f,
                            (float) finalOffset.getY(),
                            (float) finalOffset.getZ() - 0.5f
                        ),
                        new Quaternionf(),
                        new Vector3f(1f, 1f, 1f),
                        new Quaternionf()
                ));
            });
            displayEntities.add(disp);
        }

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

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void setInput(boolean forward, boolean backward, boolean left, boolean right) {
        this.kFwd   = forward;
        this.kBack  = backward;
        this.kLeft  = left;
        this.kRight = right;
    }

    private void tick() {
        if (!pilot.isOnline() || !seatEntity.isValid()) {
            task.cancel();
            return;
        }

        if (kFwd && !kBack) {
            currentSpeed = Math.min(MAX_FWD, currentSpeed + ACCEL);
        } else if (kBack && !kFwd) {
            currentSpeed = Math.max(-MAX_BACK, currentSpeed - ACCEL);
        } else {
            currentSpeed *= DECEL;
            if (Math.abs(currentSpeed) < 0.001) currentSpeed = 0.0;
        }

        if (kLeft && !kRight) {
            currentTurn = Math.max(-TURN_MAX, currentTurn - TURN_ACCEL);
        } else if (kRight && !kLeft) {
            currentTurn = Math.min(TURN_MAX,  currentTurn + TURN_ACCEL);
        } else {
            currentTurn *= TURN_DECEL;
            if (Math.abs(currentTurn) < 0.02f) currentTurn = 0f;
        }

        boolean moved = false;
        boolean rotated = false;

        if (currentTurn != 0f) {
            float nextYaw = norm(shipYaw + currentTurn);
            if (canMoveTo(anchorCenter, nextYaw)) {
                shipYaw = nextYaw;
                moved = true;
                rotated = true;
            } else {
                currentTurn  = 0f;
                currentSpeed *= 0.5;
            }
        }

        if (Math.abs(currentSpeed) > 0.001) {
            Vector   dir  = yawDir(shipYaw).multiply(currentSpeed);
            Location next = anchorCenter.clone().add(dir);
            if (canMoveTo(next, shipYaw)) {
                anchorCenter = next;
                moved = true;
            } else {
                currentSpeed = 0.0;
            }
        }

        if (!moved) return;

        float delta = shipYaw - initialYaw;
        double rad  = Math.toRadians(delta);
        double cos  = Math.cos(rad);
        double sin  = Math.sin(rad);

        // ФИКС 1: КЛЮЧЕВОЕ ИЗМЕНЕНИЕ
        // При движении БЕЗ поворота — телепортируем все DisplayEntity напрямую
        // При повороте — обновляем Transformation (меняет визуальный угол блока)
        // Никогда не делаем оба действия одновременно!

        if (rotated) {
            // Поворот: обновляем Transformation у каждого Display
            // Display остаётся на месте, меняется только его визуальный угол и оффсет
            Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(-delta));

            for (int i = 0; i < displayEntities.size(); i++) {
                BlockDisplay disp = displayEntities.get(i);
                if (!disp.isValid()) continue;

                Vector off = originalBlocks.get(i).getRelativeOffset();

                // Повёрнутый оффсет блока
                double nx = off.getX() * cos - off.getZ() * sin;
                double nz = off.getX() * sin + off.getZ() * cos;

                Vector3f translation = new Vector3f(
                        (float) nx - 0.5f,
                        (float) off.getY(),
                        (float) nz - 0.5f
                );

                // Телепортируем Display на новую мировую позицию (якорь + повёрнутый оффсет)
                Location dispLoc = anchorCenter.clone().add(nx, off.getY(), nz);
                dispLoc.setYaw(0f);
                dispLoc.setPitch(0f);
                disp.teleport(dispLoc);

                // Применяем поворот через Transformation
                Transformation tf = new Transformation(
                        new Vector3f(-0.5f, 0f, -0.5f),
                        rot,
                        new Vector3f(1f, 1f, 1f),
                        new Quaternionf()
                );
                disp.setInterpolationDelay(0);
                disp.setInterpolationDuration(2);
                disp.setTransformation(tf);
            }

            lastAppliedDelta = delta;

        } else {
            // Движение БЕЗ поворота: только teleport, БЕЗ setTransformation
            // teleportDuration=2 даёт клиенту 2 тика на плавную интерполяцию
            for (int i = 0; i < displayEntities.size(); i++) {
                BlockDisplay disp = displayEntities.get(i);
                if (!disp.isValid()) continue;

                Vector off = originalBlocks.get(i).getRelativeOffset();
                double nx  = off.getX() * cos - off.getZ() * sin;
                double nz  = off.getX() * sin + off.getZ() * cos;

                Location target = anchorCenter.clone().add(nx, off.getY(), nz);
                target.setYaw(0f);
                target.setPitch(0f);

                // ТОЛЬКО teleport — без setTransformation
                disp.teleport(target);
            }
        }

        // Кресло
        Location seatTarget = anchorCenter.clone();
        seatTarget.setY(activationBlockY - 0.6);
        seatTarget.setYaw(shipYaw);
        seatTarget.setPitch(0f);
        seatEntity.teleport(seatTarget);

        fillWater(cos, sin);
    }

    private boolean canMoveTo(Location target, float yaw) {
        float  delta = yaw - initialYaw;
        double rad   = Math.toRadians(delta);
        double cos   = Math.cos(rad);
        double sin   = Math.sin(rad);

        for (ShipBlockData d : originalBlocks) {
            Vector off = d.getRelativeOffset();
            double nx  = off.getX() * cos - off.getZ() * sin;
            double nz  = off.getX() * sin + off.getZ() * cos;
            Block  b   = target.clone().add(nx, off.getY() + 0.5, nz).getBlock();
            if (b.getType().isSolid()) return false;
        }
        return true;
    }

    private void fillWater(double cos, double sin) {
        if (submergedWake.isEmpty()) return;
        Set<BP> occupied = new HashSet<>();
        for (ShipBlockData d : originalBlocks) {
            Vector off = d.getRelativeOffset();
            double nx  = off.getX() * cos - off.getZ() * sin;
            double nz  = off.getX() * sin + off.getZ() * cos;
            int    bx  = (int) Math.floor(anchorCenter.getX() + nx);
            int    by  = (int) Math.floor(anchorCenter.getY() + off.getY() + 0.5);
            int    bz  = (int) Math.floor(anchorCenter.getZ() + nz);
            occupied.add(new BP(bx, by, bz));
        }
        submergedWake.removeIf(b -> {
            if (!occupied.contains(new BP(b.getX(), b.getY(), b.getZ()))) {
                b.setType(Material.WATER, true);
                return true;
            }
            return false;
        });
    }

    public void restoreBlocks() {
        if (task != null) task.cancel();

        if (seatEntity.isValid()) seatEntity.eject();
        if (pilot.isInsideVehicle()) pilot.leaveVehicle();

        for (Block b : submergedWake) b.setType(Material.WATER, true);
        submergedWake.clear();

        Location grid = new Location(
                anchorCenter.getWorld(),
                Math.floor(anchorCenter.getX()) + 0.5,
                Math.floor(anchorCenter.getY()),
                Math.floor(anchorCenter.getZ()) + 0.5);

        float  delta = shipYaw - initialYaw;
        int    snap  = Math.floorMod(Math.round(delta / 90f) * 90, 360);
        int    rots  = snap / 90;
        double csr   = Math.round(Math.cos(Math.toRadians(snap)));
        double snr   = Math.round(Math.sin(Math.toRadians(snap)));

        record PB(Block block, BlockData bd, BlockState snap2, ItemStack[] items) {}
        List<PB> pass1 = new ArrayList<>(),
                 pass2 = new ArrayList<>(),
                 pass3 = new ArrayList<>();

        for (ShipBlockData d : originalBlocks) {
            Vector off = d.getRelativeOffset();
            int dx = (int) Math.round(off.getX() * csr - off.getZ() * snr);
            int dz = (int) Math.round(off.getX() * snr + off.getZ() * csr);
            int dy = (int) Math.round(off.getY());

            Block     nb = grid.clone().add(dx, dy, dz).getBlock();
            BlockData bd = d.getBlockData().clone();
            rotateData(bd, rots, snap);

            if (nb.getType() == Material.WATER && bd instanceof Waterlogged wl)
                wl.setWaterlogged(true);

            PB pb = new PB(nb, bd, d.getStateSnapshot(), d.getItems());
            if (d.getItems() != null || isTile(d.getStateSnapshot())) pass3.add(pb);
            if (isPass2(bd)) pass2.add(pb);
            else             pass1.add(pb);
        }

        for (PB pb : pass1) {
            boolean phys = pb.bd() instanceof MultipleFacing || pb.bd() instanceof Wall;
            pb.block().setType(pb.bd().getMaterial(), false);
            pb.block().setBlockData(pb.bd(), phys);
        }
        for (PB pb : pass2) {
            pb.block().setType(pb.bd().getMaterial(), false);
            pb.block().setBlockData(pb.bd(), false);
        }

        // ФИКС 2: Контейнеры — применяем сразу и через 2 тика
        // 2 тика вместо 1 — Paper 26.3 медленнее инициализирует TileEntity
        for (PB pb : pass3) applyContainer(pb.block(), pb.snap2(), pb.items());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (PB pb : pass3) applyContainer(pb.block(), pb.snap2(), pb.items());
        }, 2L);

        double safeY = findSafeDeckY(pilot.getLocation(), grid, csr, snr);
        Location land = pilot.getLocation().clone();
        land.setY(safeY);
        pilot.teleport(land);

        displayEntities.forEach(d -> { if (d.isValid()) d.remove(); });
        displayEntities.clear();
        if (seatEntity.isValid()) seatEntity.remove();
    }

    private double findSafeDeckY(Location pilotLoc, Location grid, double cos, double sin) {
        int px  = pilotLoc.getBlockX();
        int pz  = pilotLoc.getBlockZ();
        int top = Integer.MIN_VALUE;

        for (ShipBlockData d : originalBlocks) {
            Vector off = d.getRelativeOffset();
            int dx = (int) Math.round(off.getX() * cos - off.getZ() * sin);
            int dz = (int) Math.round(off.getX() * sin + off.getZ() * cos);
            int bx = grid.getBlockX() + dx;
            int by = grid.getBlockY() + (int) Math.round(off.getY());
            int bz = grid.getBlockZ() + dz;

            if (bx == px && bz == pz
                    && d.getBlockData().getMaterial().isSolid()
                    && by > top) {
                top = by;
            }
        }
        return top != Integer.MIN_VALUE ? top + 1.0 : Math.floor(pilotLoc.getY()) + 1.0;
    }

    private static float norm(float y) {
        y %= 360f;
        return y < 0 ? y + 360f : y;
    }

    private static Vector yawDir(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0.0, Math.cos(r));
    }

    private void rotateData(BlockData bd, int rots, int snap) {
        if (bd instanceof Directional d) {
            BlockFace f = d.getFacing();
            for (int i = 0; i < rots; i++) f = cw(f);
            if (d.getFaces().contains(f)) d.setFacing(f);
        } else if (bd instanceof Orientable o) {
            if (rots % 2 != 0) {
                if (o.getAxis() == Axis.X) o.setAxis(Axis.Z);
                else if (o.getAxis() == Axis.Z) o.setAxis(Axis.X);
            }
        } else if (bd instanceof Rotatable r) {
            int idx = ROT16.indexOf(r.getRotation());
            if (idx >= 0) {
                int steps = Math.round((snap % 360) / 22.5f);
                r.setRotation(ROT16.get(Math.floorMod(idx + steps, 16)));
            }
        } else if (bd instanceof MultipleFacing mf) {
            Set<BlockFace> cur = new HashSet<>(mf.getFaces());
            mf.getAllowedFaces().forEach(f -> mf.setFace(f, false));
            for (BlockFace f : cur) {
                BlockFace rf = f;
                for (int i = 0; i < rots; i++) rf = cw(rf);
                if (mf.getAllowedFaces().contains(rf)) mf.setFace(rf, true);
            }
        } else if (bd instanceof Wall w) {
            Map<BlockFace, Wall.Height> h = new HashMap<>();
            for (BlockFace f : new BlockFace[]{
                    BlockFace.NORTH, BlockFace.EAST,
                    BlockFace.SOUTH, BlockFace.WEST})
                h.put(f, w.getHeight(f));
            h.forEach((f, ht) -> {
                BlockFace rf = f;
                for (int i = 0; i < rots; i++) rf = cw(rf);
                w.setHeight(rf, ht);
            });
        }
        if (bd instanceof org.bukkit.block.data.type.Chest c) {
            if (snap == 180
                    && c.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
                c.setType(c.getType() == org.bukkit.block.data.type.Chest.Type.LEFT
                        ? org.bukkit.block.data.type.Chest.Type.RIGHT
                        : org.bukkit.block.data.type.Chest.Type.LEFT);
            }
        }
    }

    private static BlockFace cw(BlockFace f) {
        return switch (f) {
            case NORTH      -> BlockFace.EAST;
            case EAST       -> BlockFace.SOUTH;
            case SOUTH      -> BlockFace.WEST;
            case WEST       -> BlockFace.NORTH;
            case NORTH_EAST -> BlockFace.SOUTH_EAST;
            case SOUTH_EAST -> BlockFace.SOUTH_WEST;
            case SOUTH_WEST -> BlockFace.NORTH_WEST;
            case NORTH_WEST -> BlockFace.NORTH_EAST;
            default         -> f;
        };
    }

    private boolean isPass2(BlockData bd) {
        if (bd instanceof Bisected b && b.getHalf() == Bisected.Half.TOP) return true;
        if (bd instanceof FaceAttachable) return true;
        if (bd instanceof Lantern) return true;
        Material m = bd.getMaterial();
        return m == Material.LANTERN         || m == Material.SOUL_LANTERN
            || m == Material.CHAIN           || m == Material.END_ROD
            || m == Material.TORCH           || m == Material.SOUL_TORCH
            || m == Material.WALL_TORCH      || m == Material.SOUL_WALL_TORCH
            || m == Material.REDSTONE_TORCH  || m == Material.REDSTONE_WALL_TORCH
            || m.name().endsWith("_BUTTON")
            || m.name().endsWith("_SIGN")
            || m.name().endsWith("_HANGING_SIGN")
            || m.name().endsWith("_WALL_SIGN")
            || m.name().endsWith("_BANNER")
            || m.name().endsWith("_WALL_BANNER");
    }

    private boolean isTile(BlockState s) {
        return s instanceof Container
            || s instanceof Furnace
            || s instanceof org.bukkit.block.Lectern;
    }

    // ФИКС 2: applyContainer использует getInventory() вместо rawInventory()
    private void applyContainer(Block block, BlockState snap, ItemStack[] items) {
        if (items != null) {
            // Получаем свежее состояние блока после его установки
            BlockState fresh = block.getState();
            if (fresh instanceof Container c) {
                // Используем getInventory() — единственный надёжный способ
                // для всех типов контейнеров в Paper 26.3
                Inventory inv = c.getInventory();
                ItemStack[] fill = new ItemStack[inv.getSize()];
                for (int i = 0; i < Math.min(items.length, fill.length); i++) {
                    if (items[i] != null) fill[i] = items[i].clone();
                }
                inv.setContents(fill);
                // force=true обязательно для записи в NBT чанка
                c.update(true, false);
            }
        }
        if (snap instanceof org.bukkit.block.Lectern old
                && old.getPersistentDataContainer()
                      .has(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE)
                && block.getState() instanceof org.bukkit.block.Lectern nl) {
            nl.getPersistentDataContainer()
              .set(MoveShipPlugin.CONTROLLER_KEY, PersistentDataType.BYTE, (byte) 1);
            nl.update(true, false);
        }
    }

    private static ItemStack[] deepCopy(Inventory inv) {
        ItemStack[] a = new ItemStack[inv.getSize()];
        for (int i = 0; i < a.length; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && !s.getType().isAir()) a[i] = s.clone();
        }
        return a;
    }

    public Player getPilot() { return pilot; }
}
