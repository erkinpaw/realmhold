package org.webtrade.strategy1.entity;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A ship's way over the water (in the spirit of the harbours mod's): A* over the open water of the loaded world,
 * eight ways, dearer close to the shore (so she keeps to open water but will take a narrow channel if she must),
 * then pulled tight into long straight legs.
 */
public final class WaterPath {

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final int MAX_NODES = 60_000;

    private WaterPath() {
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Open water at a column (not under ice, loaded). */
    static boolean open(ServerLevel level, int x, int z, double y) {
        if (!level.hasChunkAt(new BlockPos(x, 64, z))) return false;
        return !Double.isNaN(ShipEntity.waterTop(level, x + 0.5, y + 2, z + 0.5));
    }

    /** How much room round a column: 2 all round, 1 next to the shore, 0 not water. */
    private static int room(ServerLevel level, int x, int z, double y) {
        if (!open(level, x, z, y)) return 0;
        for (int i = 0; i < 8; i++) if (!open(level, x + DX[i], z + DZ[i], y)) return 1;
        return 2;
    }

    /** Room for a ship to lie in: 3 open water two blocks all round and deep, else as {@link #room}. */
    private static int berth(ServerLevel level, int x, int z, double y) {
        int r = room(level, x, z, y);
        if (r < 2) return r;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (!open(level, x + dx, z + dz, y)) return 2;
        // (deep enough that a hull doesn't sit in the bottom)
        BlockPos below = BlockPos.containing(x + 0.5, y - 2, z + 0.5);
        return level.getFluidState(below).is(net.minecraft.tags.FluidTags.WATER) ? 3 : 2;
    }

    /** The open water nearest a point (within r), roomy if it can be; null if none. */
    public static Vec3 nearestWater(ServerLevel level, Vec3 to, double y, int r) {
        Vec3 best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = (int) Math.floor(to.x) + dx, z = (int) Math.floor(to.z) + dz;
                int room = berth(level, x, z, y);
                if (room == 0) continue;
                double d = dx * dx + dz * dz + (room == 1 ? 400 : room == 2 ? 60 : 0);
                if (d < bestD) {
                    bestD = d;
                    best = new Vec3(x + 0.5, y, z + 0.5);
                }
            }
        }
        return best;
    }

    private record Node(long key, double f) {
    }

    /** The way from a point to another over the water: its legs' ends, the last one the goal; null if there's none. */
    public static List<Vec3> find(ServerLevel level, Vec3 from, Vec3 to, double y) {
        int sx = (int) Math.floor(from.x), sz = (int) Math.floor(from.z), gx = (int) Math.floor(to.x), gz = (int) Math.floor(to.z);
        if (!open(level, gx, gz, y)) return null;
        int margin = 48;
        int minX = Math.min(sx, gx) - margin, maxX = Math.max(sx, gx) + margin, minZ = Math.min(sz, gz) - margin, maxZ = Math.max(sz, gz) + margin;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Long2DoubleOpenHashMap g = new Long2DoubleOpenHashMap();
        Long2LongOpenHashMap came = new Long2LongOpenHashMap();
        LongOpenHashSet closed = new LongOpenHashSet();
        long start = key(sx, sz), goal = key(gx, gz);
        g.put(start, 0);
        open.add(new Node(start, h(sx, sz, gx, gz)));
        int n = 0;
        boolean found = false;
        while (!open.isEmpty() && n < MAX_NODES) {
            Node cur = open.poll();
            if (!closed.add(cur.key)) continue;
            if (cur.key == goal) {
                found = true;
                break;
            }
            n++;
            int x = (int) (cur.key >> 32), z = (int) cur.key;
            double gc = g.get(cur.key);
            for (int i = 0; i < 8; i++) {
                int nx = x + DX[i], nz = z + DZ[i];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                long nk = key(nx, nz);
                if (closed.contains(nk)) continue;
                int room = room(level, nx, nz, y);
                if (room == 0) continue;
                // (no cutting a corner past the land)
                if (i >= 4 && (!WaterPath.open(level, x + DX[i], z, y) || !WaterPath.open(level, x, z + DZ[i], y))) continue;
                double step = (i >= 4 ? 1.4142 : 1) * (room == 1 ? 4 : 1);
                double ng = gc + step;
                if (ng < g.getOrDefault(nk, Double.MAX_VALUE)) {
                    g.put(nk, ng);
                    came.put(nk, cur.key);
                    open.add(new Node(nk, ng + 1.1 * h(nx, nz, gx, gz)));
                }
            }
        }
        if (!found) return null;
        List<long[]> raw = new ArrayList<>();
        for (long k = goal; ; k = came.get(k)) {
            raw.add(new long[]{k >> 32, (int) k});
            if (k == start || !came.containsKey(k)) break;
        }
        Collections.reverse(raw);
        // pulled tight: from each corner, as far on as the water lets her go straight
        List<Vec3> legs = new ArrayList<>();
        int i = 0;
        while (i < raw.size() - 1) {
            int j = Math.min(raw.size() - 1, i + 48);
            while (j > i + 1 && !clear(level, raw.get(i), raw.get(j), y)) j--;
            legs.add(new Vec3(raw.get(j)[0] + 0.5, y, raw.get(j)[1] + 0.5));
            i = j;
        }
        return legs;
    }

    private static double h(int x, int z, int gx, int gz) {
        int dx = Math.abs(x - gx), dz = Math.abs(z - gz);
        return Math.max(dx, dz) + 0.4142 * Math.min(dx, dz);
    }

    /** Is the straight way between two cells open water, with room either side? */
    private static boolean clear(ServerLevel level, long[] a, long[] b, double y) {
        double dx = b[0] - a[0], dz = b[1] - a[1];
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) * 2);
        for (int s = 0; s <= steps; s++) {
            double x = a[0] + 0.5 + dx * s / steps, z = a[1] + 0.5 + dz * s / steps;
            if (room(level, (int) Math.floor(x), (int) Math.floor(z), y) < 2 && s > 1 && s < steps - 1) return false;
            if (!open(level, (int) Math.floor(x), (int) Math.floor(z), y)) return false;
        }
        return true;
    }
}
