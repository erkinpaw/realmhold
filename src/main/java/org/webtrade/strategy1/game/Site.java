package org.webtrade.strategy1.game;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * How good a province is for a capital, from the land itself as the world will make it (before any chunk is made):
 * dry and level where the keep goes, not up a mountain, not an island in the water. Lower is better.
 */
final class Site {

    /** Past this, no keep goes there at all. */
    static final double UNFIT = 1e6;

    private Site() {
    }

    static double score(ServerLevel level, StrategyData data, Province p) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = gen.getSeaLevel();
        // where the keep goes (planKeep: a little south of the centre)
        double kx = p.hex.centerX(), kz = p.hex.centerZ() + 7;
        int innerWater = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        // (each look at the land costs the generator its noise: few looks, where they matter)
        for (int dx = -8; dx <= 8; dx += 8) {
            for (int dz = -8; dz <= 8; dz += 8) {
                int h = gen.getBaseHeight((int) kx + dx, (int) kz + dz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                if (h <= sea) innerWater++;
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        if (innerWater > 2) return UNFIT;
        // the land round it: how much is water, how rough, how high
        int water = 0, n = 0;
        double sum = 0, sq = 0;
        for (int dx = -28; dx <= 28; dx += 28) {
            for (int dz = -28; dz <= 28; dz += 28) {
                if (dx == 0 && dz == 0) continue;
                int h = gen.getBaseHeight((int) kx + dx, (int) kz + dz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                if (h <= sea) water++;
                else {
                    n++;
                    sum += h;
                    sq += (double) h * h;
                }
            }
        }
        double mean = n == 0 ? sea : sum / n, std = n == 0 ? 0 : Math.sqrt(Math.max(0, sq / n - mean * mean));
        int land = 0;
        for (var x : p.hex.neighbors()) {
            Province q = data.province(x);
            if (q != null && q.terrain.land()) land++;
        }
        return innerWater * 60 + (max - min) * 3 + water * 8 + std * 1.5 + Math.max(0, mean - (sea + 16)) * 1.5 + (6 - land) * 14
                + (p.terrain == org.webtrade.strategy1.hex.Terrain.MOUNTAINS ? 30 : 0);
    }
}
