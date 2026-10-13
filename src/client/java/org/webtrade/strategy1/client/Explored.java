package org.webtrade.strategy1.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * What the strategist has seen of the land: the colour of its top every {@link #CELL} blocks (as the game's own maps
 * colour it, lighter on the rises and darker in the hollows), gathered from the chunks loaded round the camera. The
 * realms map draws the land from it; what was never seen stays a sketch of the provinces.
 */
public final class Explored {

    public static final int CELL = 4;
    private static final Long2IntOpenHashMap COLOURS = new Long2IntOpenHashMap();
    private static final Long2LongOpenHashMap SEEN = new Long2LongOpenHashMap();
    /** Goes up as more land is seen (the map redraws its picture then, not too often). */
    public static int revision;
    /** The cells seen anew (or changed) since the map last looked: it redraws just those. */
    public static final it.unimi.dsi.fastutil.longs.LongArrayList CHANGED = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static long tick;

    private Explored() {
    }

    public static void clear() {
        COLOURS.clear();
        SEEN.clear();
        CHANGED.clear();
        revision++;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** The land's colour at a cell (ARGB), or 0 if it was never seen. */
    public static int colour(int cellX, int cellZ) {
        return COLOURS.getOrDefault(key(cellX, cellZ), 0);
    }

    static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || ++tick % 10 != 0) return;
        int px = (int) Math.floor((Rts.active() ? Rts.focusX : mc.player.getX())) >> 4, pz = (int) Math.floor((Rts.active() ? Rts.focusZ : mc.player.getZ())) >> 4;
        int r = Math.min(10, mc.options.renderDistance().get());
        int done = 0;
        boolean changed = false;
        for (int dx = -r; dx <= r && done < 12; dx++) {
            for (int dz = -r; dz <= r && done < 12; dz++) {
                int cx = px + dx, cz = pz + dz;
                long k = key(cx, cz);
                if (tick - SEEN.getOrDefault(k, -100000L) < 600) continue;
                if (!mc.level.hasChunk(cx, cz)) continue;
                SEEN.put(k, tick);
                sample(mc, cx, cz);
                done++;
                changed = true;
            }
        }
        if (changed) revision++;
    }

    private static void sample(Minecraft mc, int cx, int cz) {
        var level = mc.level;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int ox = 0; ox < 16; ox += CELL) {
            for (int oz = 0; oz < 16; oz += CELL) {
                int x = cx * 16 + ox + CELL / 2, z = cz * 16 + oz + CELL / 2;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                p.set(x, y, z);
                MapColor c = level.getBlockState(p).getMapColor(level, p);
                if (c == MapColor.NONE) continue;
                // relief: lit where the land rises to the north, shaded where it falls
                int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - CELL) - 1;
                MapColor.Brightness b = y > north ? MapColor.Brightness.HIGH : y < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                if (!level.getFluidState(p).isEmpty()) b = (y + x / 3 + z / 5) % 2 == 0 ? MapColor.Brightness.NORMAL : MapColor.Brightness.HIGH;
                long k = key(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL));
                int col = c.calculateARGBColor(b) | 0xFF000000;
                if (COLOURS.put(k, col) != col && CHANGED.size() < 200_000) CHANGED.add(k);
            }
        }
    }
}
