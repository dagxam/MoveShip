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

    // Кресло пилота — отдельная стойка
    private final ArmorStand seatEntity;

    private final List<ShipBlockData> originalBlocks = new ArrayList<>();
    private final List<BlockDisplay> displayEntities = new ArrayList<>();

    // Мировые координаты каждого display в «нулевом» состоянии (до поворотов)
    // Пересчитываются в tick() и передаются напрямую в teleport()
    private final List<Location> displayWorldLocations = new ArrayList<>();

    // ── физика ──────────────────────────────────────────────────────────────
    private static final double MAX_FWD   = 0.35;
    private static final double MAX_BACK  = 0.15;
    private static final double ACCEL     = 0.03;
    private static final double FRICTION  = 0.90;   // ≈ лодка в воде

    private static final float  TURN_MAX   = 2.5f;
    private static final float  TURN_ACCEL = 0.45f;
    private static final float  TURN_FRIC  = 0.72f;

    // ── ввод ────────────────────────────────────────────────────────────────
    // Grace: сколько тиков держать кнопку «нажатой» после последнего пакета
    private static final int GRACE = 6;
    private volatile boolean kFwd, kBack, kLeft, kRight;
    private int gFwd, gBack, gLeft, gRight;

    // ── состояние ───────────────────────────────────────────────────────────
    private Location anchorCenter;       // центр корабля в мире
    private float    shipYaw;            // текущий курс (градусы, MC-система)
    private final float initialYaw;

    private double currentSpeed = 0.0;
    private float  currentTurn  = 0.0f;

    private final BukkitTask task;
    private final Set<Block>   submergedWake   = new HashSet<>();
    private record BP(int x, int y, int z) {}

    // 16 направлений для Rotatable (таблички, головы, флаги)
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

    // ════════════════════════════════════════════════════════════════════════
    public ActiveShip(Set<Block> blocks, Location anchorLocation, Player pilot) {
        this.pilot  = pilot;
        this.plugin = JavaPlugin.getPlugin(MoveShipPlugin.class);

        // Центр привязки — центр блока кафедры
        this.anchorCenter = anchorLocation.getBlock().getLocation()
                                          .add(0.5, 0.0, 0.5);
        this.anchorCenter.setWorld(anchorLocation.getWorld());

        this.shipYaw    = pilot.getLocation().getYaw();
        this.initialYaw = this.shipYaw;

        // ── 1. Ватерлиния ──────────────────────────────────────────────────
        int seaLevel = Integer.MIN_VALUE;
        for (Block b : blocks) {
            for (BlockFace f : new BlockFace[]{
                    BlockFace.NORTH, BlockFace.SOUTH,
                    BlockFace.EAST,  BlockFace.WEST, BlockFace.DOWN}) {
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

        // ── 2. Сохраняем блоки + инвентари; удаляем физику; спавним Display ─
        for (Block block : blocks) {

            // Центр блока и его смещение от якоря
            Location bc = block.getLocation().add(0.5, 0.0, 0.5);
            Vector offset = bc.toVector().subtract(anchorCenter.toVector());

            BlockData bd = block.getBlockData().clone();

            // Сохраняем инвентарь ПЕРЕД clear()
            ItemStack[]  savedItems = null;
            BlockState   snapshot;
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

            // Удаляем физический блок
            block.setType(Material.AIR, false);

            // Спавним BlockDisplay на МИРОВЫХ координатах блока
            BlockDisplay disp = bc.getWorld().spawn(bc, BlockDisplay.class, e -> {
                e.setBlock(bd);
                e.setPersistent(false);
                // teleportDuration=1 → клиент интерполирует на 60 FPS
                e.setTeleportDuration(1);
                e.setInterpolationDuration(0);
                e.setInterpolationDelay(0);
                // Центрируем модель блока (origin у BlockDisplay — угол блока)
                e.setTransformation(new Transformation(
                        new Vector3f(-0.5f, 0f, -0.5f),
                        new Quaternionf(),
                        new Vector3f(1f, 1f, 1f),
                        new Quaternionf()
                ));
            });

            displayEntities.add(disp);
            displayWorldLocations.add(bc.clone());
        }

        // ── 3. Кресло пилота ────────────────────────────────────────────────
        // Спавним ПОСЛЕ удаления блоков → нет коллизии
        Location seatLoc = pilot.getLocation().clone();
        // Опускаем ArmorStand на 0.6 ниже ног: игрок окажется ровно на палубе
        seatLoc.setY(seatLoc.getY() - 0.6);
        seatLoc.setYaw(shipYaw);
        seatLoc.setPitch(0f);

        seatEntity = seatLoc.getWorld().spawn(seatLoc, ArmorStand.class, e -> {
            e.setInvisible(true);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setMarker(true);    // без хитбокса → камера не скачет
            e.setSmall(true);
            e.setBasePlate(false);
            e.setPersistent(false);
        });
        seatEntity.addPassenger(pilot);

        // ── 4. Двигательный цикл — каждый тик (50 мс) ──────────────────────
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Ввод — вызывается из ShipMovementListener (main thread)
    // ════════════════════════════════════════════════════════════════════════
    public void setInput(boolean forward, boolean backward, boolean left, boolean right) {
        if (forward)  { kFwd  = true; gFwd  = GRACE; }
        if (backward) { kBack = true; gBack = GRACE; }
        if (left)     { kLeft = true; gLeft = GRACE; }
        if (right)    { kRight= true; gRight= GRACE; }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Тик движка
    // ════════════════════════════════════════════════════════════════════════
    private void tick() {
        if (!pilot.isOnline() || !seatEntity.isValid()) {
            task.cancel();
            return;
        }

        // ── grace-таймеры ──
        if (gFwd  > 0) { gFwd--;  } else kFwd  = false;
        if (gBack > 0) { gBack--; } else kBack = false;
        if (gLeft > 0) { gLeft--; } else kLeft = false;
        if (gRight> 0) { gRight--;} else kRight= false;

        // ── скорость (вперёд / назад) ──
        if (kFwd && !kBack) {
            currentSpeed = Math.min(MAX_FWD,  currentSpeed + ACCEL);
        } else if (kBack && !kFwd) {
            currentSpeed = Math.max(-MAX_BACK, currentSpeed - ACCEL);
        } else {
            currentSpeed *= FRICTION;
            if (Math.abs(currentSpeed) < 0.002) currentSpeed = 0.0;
        }

        // ── поворот A = нос налево (yaw−), D = нос направо (yaw+) ──
        if (kLeft && !kRight) {
            currentTurn = Math.max(-TURN_MAX, currentTurn - TURN_ACCEL);
        } else if (kRight && !kLeft) {
            currentTurn = Math.min(TURN_MAX,  currentTurn + TURN_ACCEL);
        } else {
            currentTurn *= TURN_FRIC;
            if (Math.abs(currentTurn) < 0.03f) currentTurn = 0f;
        }

        boolean moved = false;

        // ── применяем поворот ──
        if (currentTurn != 0f) {
            float nextYaw = norm(shipYaw + currentTurn);
            if (canMoveTo(anchorCenter, nextYaw)) {
                shipYaw = nextYaw;
                moved = true;
            } else {
                currentTurn = 0f;
            }
        }

        // ── применяем сдвиг ──
        if (currentSpeed != 0.0) {
            Vector dir = yawDir(shipYaw).multiply(currentSpeed);
            Location next = anchorCenter.clone().add(dir);
            if (canMoveTo(next, shipYaw)) {
                anchorCenter = next;
                moved = true;
            } else {
                currentSpeed = 0.0;
            }
        }

        if (!moved) return;

        // ── пересчёт мировых позиций Display ──
        float delta = shipYaw - initialYaw;
        double rad = Math.toRadians(delta);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        for (int i = 0; i < displayEntities.size(); i++) {
            BlockDisplay disp = displayEntities.get(i);
            if (!disp.isValid()) continue;

            Vector off = originalBlocks.get(i).getRelativeOffset();
            double nx = off.getX() * cos - off.getZ() * sin;
            double nz = off.getX() * sin + off.getZ() * cos;

            Location target = anchorCenter.clone().add(nx, off.getY(), nz);
            // Yaw у BlockDisplay ставим в 0 — ротация корабля через Transformation
            target.setYaw(0f);
            target.setPitch(0f);

            // teleportDuration=1 даёт клиенту полный тик (50 мс) на интерполяцию
            disp.teleport(target);
        }

        // Если корабль повернулся — поворачиваем Transformation каждого Display
        if (currentTurn != 0f) {
            Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(-delta));
            Vector3f    tr  = new Vector3f(-0.5f, 0f, -0.5f);
            rot.transform(tr);
            Transformation tf = new Transformation(tr, rot,
                    new Vector3f(1f, 1f, 1f), new Quaternionf());
            for (BlockDisplay disp : displayEntities) {
                if (disp.isValid()) {
                    disp.setInterpolationDelay(0);
                    disp.setInterpolationDuration(1);
                    disp.setTransformation(tf);
                }
            }
        }

        // ── двигаем кресло ──
        Location seatTarget = seatLocation(cos, sin);
        seatEntity.teleport(seatTarget);

        fillWater(cos, sin);
    }

    // ════════════════════════════════════════════════════════════════════════
    private Location seatLocation(double cos, double sin) {
        // Кресло — сразу над центром якоря (пилот «стоит» на палубе)
        // Небольшое смещение Y = 0.0 даёт ноги на уровне палубы
        Location loc = anchorCenter.clone().add(0, 0.0, 0);
        loc.setYaw(shipYaw);
        loc.setPitch(0f);
        return loc;
    }

    private boolean canMoveTo(Location target, float yaw) {
        float delta = yaw - initialYaw;
        double rad  = Math.toRadians(delta);
        double cos  = Math.cos(rad);
        double sin  = Math.sin(rad);

        for (ShipBlockData d : originalBlocks) {
            Vector off = d.getRelativeOffset();
            double nx = off.getX() * cos - off.getZ() * sin;
            double nz = off.getX() * sin + off.getZ() * cos;
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
            double nx = off.getX() * cos - off.getZ() * sin;
            double nz = off.getX() * sin + off.getZ() * cos;
            int bx = (int) Math.floor(anchorCenter.getX() + nx);
            int by = (int) Math.floor(anchorCenter.getY() + off.getY() + 0.5);
            int bz = (int) Math.floor(anchorCenter.getZ() + nz);
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

    // ════════════════════════════════════════════════════════════════════════
    // Парковка
    // ════════════════════════════════════════════════════════════════════════
    public void restoreBlocks() {
        if (task != null) task.cancel();

        // Снимаем игрока с кресла
        if (seatEntity.isValid()) seatEntity.eject();
        if (pilot.isInsideVehicle()) pilot.leaveVehicle();

        // Восстанавливаем воду
        for (Block b : submergedWake) b.setType(Material.WATER, true);
        submergedWake.clear();

        // Привязка к сетке блоков
        Location grid = new Location(
                anchorCenter.getWorld(),
                Math.floor(anchorCenter.getX()) + 0.5,
                Math.floor(anchorCenter.getY()),
                Math.floor(anchorCenter.getZ()) + 0.5);

        float delta = shipYaw - initialYaw;
        int   snap  = Math.floorMod(Math.round(delta / 90f) * 90, 360);
        int   rots  = snap / 90;

        double csr = Math.round(Math.cos(Math.toRadians(snap)));
        double snr = Math.round(Math.sin(Math.toRadians(snap)));

        record PB(Block block, BlockData bd, BlockState snap2, ItemStack[] items) {}
        List<PB> pass1 = new ArrayList<>(), pass2 = new ArrayList<>(), pass3 = new ArrayList<>();

        for (ShipBlockData d : originalBlocks) {
            Vector off = d.getRelativeOffset();
            int dx = (int) Math.round(off.getX() * csr - off.getZ() * snr);
            int dz = (int) Math.round(off.getX() * snr + off.getZ() * csr);
            int dy = (int) Math.round(off.getY());

            Block   nb = grid.clone().add(dx, dy, dz).getBlock();
            BlockData bd = d.getBlockData().clone();

            rotateData(bd, rots, snap);

            if (nb.getType() == Material.WATER && bd instanceof Waterlogged wl)
                wl.setWaterlogged(true);

            PB pb = new PB(nb, bd, d.getStateSnapshot(), d.getItems());

            if (d.getItems() != null || isTile(d.getStateSnapshot()))
                pass3.add(pb);

            if (isPass2(bd)) pass2.add(pb);
            else             pass1.add(pb);
        }

        // Проход 1: несущие блоки
        for (PB pb : pass1) {
            boolean phys = pb.bd() instanceof MultipleFacing || pb.bd() instanceof Wall;
            pb.block().setType(pb.bd().getMaterial(), false);
            pb.block().setBlockData(pb.bd(), phys);
        }
        // Проход 2: фонари, двери (top), кнопки, таблички
        for (PB pb : pass2) {
            pb.block().setType(pb.bd().getMaterial(), false);
            pb.block().setBlockData(pb.bd(), false);
        }
        // Проход 3: контейнеры (сразу и через тик — Paper иногда медленно создаёт TileEntity)
        for (PB pb : pass3) applyContainer(pb.block(), pb.snap2(), pb.items());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (PB pb : pass3) applyContainer(pb.block(), pb.snap2(), pb.items());
        }, 1L);

        // Ставим игрока на палубу
        double safeY = safeDeckY(pilot.getLocation(), grid, csr, snr);
        Location land = pilot.getLocation().clone();
        land.setY(safeY);
        pilot.teleport(land);

        // Удаляем Display
        displayEntities.forEach(d -> { if (d.isValid()) d.remove(); });
        displayEntities.clear();
        if (seatEntity.isValid()) seatEntity.remove();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Вспомогательные методы
    // ════════════════════════════════════════════════════════════════════════
    private static float norm(float y) {
        y %= 360f;
        return y < 0 ? y + 360f : y;
    }

    /** MC-система: yaw=0 → юг (+Z), yaw=90 → запад (-X) */
    private static Vector yawDir(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    private double safeDeckY(Location pilot, Location grid, double cos, double sin) {
        int px = pilot.getBlockX(), pz = pilot.getBlockZ();
        int top = Integer.MIN_VALUE;
        for (ShipBlockData d : originalBlocks) {
            Vector off = d.getRelativeOffset();
            int dx = (int) Math.round(off.getX() * cos - off.getZ() * sin);
            int dz = (int) Math.round(off.getX() * sin + off.getZ() * cos);
            int bx = grid.getBlockX() + dx;
            int by = grid.getBlockY() + (int) Math.round(off.getY());
            int bz = grid.getBlockZ() + dz;
            if (bx == px && bz == pz && d.getBlockData().getMaterial().isSolid() && by > top)
                top = by;
        }
        return top != Integer.MIN_VALUE ? top + 1.0 : Math.floor(pilot.getY()) + 1.0;
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
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST})
                h.put(f, w.getHeight(f));
            h.forEach((f, ht) -> {
                BlockFace rf = f;
                for (int i = 0; i < rots; i++) rf = cw(rf);
                w.setHeight(rf, ht);
            });
        }
        if (bd instanceof org.bukkit.block.data.type.Chest c) {
            if (snap == 180 && c.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE)
                c.setType(c.getType() == org.bukkit.block.data.type.Chest.Type.LEFT
                        ? org.bukkit.block.data.type.Chest.Type.RIGHT
                        : org.bukkit.block.data.type.Chest.Type.LEFT);
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
        return m == Material.LANTERN || m == Material.SOUL_LANTERN
                || m == Material.CHAIN  || m == Material.END_ROD
                || m == Material.TORCH  || m == Material.SOUL_TORCH
                || m == Material.WALL_TORCH || m == Material.SOUL_WALL_TORCH
                || m == Material.REDSTONE_TORCH || m == Material.REDSTONE_WALL_TORCH
                || m.name().endsWith("_BUTTON")
                || m.name().endsWith("_SIGN") || m.name().endsWith("_HANGING_SIGN")
                || m.name().endsWith("_WALL_SIGN")
                || m.name().endsWith("_BANNER") || m.name().endsWith("_WALL_BANNER");
    }

    private boolean isTile(BlockState s) {
        return s instanceof Container || s instanceof Furnace
                || s instanceof org.bukkit.block.Lectern;
    }

    private void applyContainer(Block block, BlockState snap, ItemStack[] items) {
        if (items != null && block.getState() instanceof Container c) {
            Inventory inv = rawInventory(c);
            ItemStack[] fill = new ItemStack[inv.getSize()];
            for (int i = 0; i < Math.min(items.length, fill.length); i++)
                if (items[i] != null) fill[i] = items[i].clone();
            inv.setContents(fill);
            c.update(true, false);
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

    private static Inventory rawInventory(Container c) {
        return c instanceof org.bukkit.block.Chest ch
                ? ch.getBlockInventory() : c.getInventory();
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
