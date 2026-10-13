package org.webtrade.strategy1.build;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** The woods: finding a tree to fell, felling it whole (trunk and crown), planting a sapling in its place. */
public final class Trees {

    /** Trees a woodcutter has gone to (their bottom logs), so two don't go to one. */
    private static final Set<Long> TAKEN = new HashSet<>();

    private Trees() {
    }

    private static boolean log(BlockState s) {
        return s.is(BlockTags.LOGS) && !s.getBlock().getDescriptionId().contains("stripped");
    }

    private static boolean soil(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM) || s.is(Blocks.MUD)
                || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.MOSS_BLOCK);
    }

    /** The bottom log of the tree nearest to a point (within r), not taken by another woodcutter; null if none. */
    public static BlockPos nearest(ServerLevel level, BlockPos around, int r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx += 1) {
            for (int dz = -r; dz <= r; dz += 1) {
                int x = around.getX() + dx, z = around.getZ() + dz;
                if (!level.hasChunkAt(p.set(x, 64, z))) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                // the trunk stands on the ground: find its foot just below the top of the trunk column
                for (int y = top - 1; y > top - 14; y--) {
                    p.set(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (!log(s)) {
                        if (!s.isAir() && !s.is(BlockTags.LEAVES)) break;
                        continue;
                    }
                    BlockState below = level.getBlockState(p.below());
                    if (log(below)) continue;
                    if (!soil(below)) break;
                    long k = p.asLong();
                    double d = dx * dx + dz * dz;
                    if (d < bestD && !TAKEN.contains(k)) {
                        bestD = d;
                        best = p.immutable();
                    }
                    break;
                }
            }
        }
        if (best != null) TAKEN.add(best.asLong());
        return best;
    }

    public static void release(BlockPos base) {
        if (base != null) TAKEN.remove(base.asLong());
    }

    /** Fells the whole tree a log belongs to; plants a sapling at its foot. Returns how many logs it gave. */
    public static int fell(ServerLevel level, BlockPos base) {
        release(base);
        BlockState first = level.getBlockState(base);
        if (!log(first)) return 0;
        Block sapling = sapling(first);
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        todo.add(base);
        int logs = 0, n = 0;
        while (!todo.isEmpty() && n < 500) {
            BlockPos p = todo.poll();
            if (!seen.add(p.asLong())) continue;
            BlockState s = level.getBlockState(p);
            boolean isLog = log(s);
            if (!isLog && !s.is(BlockTags.LEAVES)) continue;
            n++;
            if (isLog) {
                logs++;
                // (the trunk breaks with its dust, the crown just goes)
                if (logs <= 3) level.destroyBlock(p, false);
                else level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            int r = isLog ? 1 : 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = isLog ? -1 : -1; dy <= 1; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        if (!isLog && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1) continue;
                        BlockPos q = p.offset(dx, dy, dz);
                        if (q.getY() < base.getY()) continue;
                        if (!seen.contains(q.asLong())) todo.add(q);
                    }
                }
            }
        }
        if (sapling != null && soil(level.getBlockState(base.below())) && level.getBlockState(base).isAir()) {
            level.setBlock(base, sapling.defaultBlockState(), Block.UPDATE_ALL);
        }
        return logs;
    }

    private static Block sapling(BlockState log) {
        String id = log.getBlock().getDescriptionId();
        if (id.contains("spruce")) return Blocks.SPRUCE_SAPLING;
        if (id.contains("birch")) return Blocks.BIRCH_SAPLING;
        if (id.contains("jungle")) return Blocks.JUNGLE_SAPLING;
        if (id.contains("acacia")) return Blocks.ACACIA_SAPLING;
        if (id.contains("dark_oak")) return Blocks.DARK_OAK_SAPLING;
        if (id.contains("pale_oak")) return Blocks.PALE_OAK_SAPLING;
        if (id.contains("cherry")) return Blocks.CHERRY_SAPLING;
        if (id.contains("mangrove")) return Blocks.MANGROVE_PROPAGULE;
        if (id.contains("oak")) return Blocks.OAK_SAPLING;
        return null;
    }

    /** Fells every tree with a log in the box (making room for a building). */
    public static void clear(ServerLevel level, BlockPos min, BlockPos max) {
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockState s = level.getBlockState(p);
            if (!log(s)) continue;
            // down to its foot, then the whole of it
            BlockPos.MutableBlockPos f = p.mutable();
            while (log(level.getBlockState(f.below())) && f.getY() > level.getMinY()) f.move(0, -1, 0);
            fell(level, f.immutable());
            Block sap = level.getBlockState(f).getBlock();
            if (sap != Blocks.AIR && !log(level.getBlockState(f))) level.setBlock(f, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
