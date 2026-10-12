package org.webtrade.strategy1.game;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.hex.Deposit;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.hex.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Lays out the strategic map from the world generator (its biomes, read without loading the land): a field of hexes
 * round the spawn, each a province with its terrain and perhaps a rich deposit; the AI kingdoms with their capitals;
 * and a player's realm when they first come.
 */
public final class MapGen {

    /** Hexes of the map round the spawn. */
    public static final int RADIUS = 14;
    public static final int AI_REALMS = 5;
    private static final int[] AI_COLORS = {0xD63CA0, 0xC8322B, 0xE0B020, 0x2E9E4A, 0x8A3CC8, 0x3B6FD8};
    private static final int[] PLAYER_COLORS = {0xF08A24, 0x22C3C3, 0xFF5FA8, 0x8BC34A, 0x6D4C41, 0x00897B};

    private MapGen() {
    }

    public static void generate(ServerLevel level, StrategyData data) {
        // the way the world was chosen to be played (on this machine's new realm screen)
        if (Mode.Pending.mode != null) {
            data.mode = Mode.Pending.mode;
            data.realms = Mode.Pending.realms >= 0 ? Mode.Pending.realms : data.mode.realms;
            data.coop = Mode.Pending.coop;
            Mode.Pending.mode = null;
            Mode.Pending.realms = -1;
        }
        BlockPos spawn = level.getRespawnData().pos();
        Hex origin = Hex.at(spawn.getX(), spawn.getZ());
        data.origin = origin.key();
        RandomSource random = RandomSource.create(level.getSeed() ^ 0x5EED_CAFEL);
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = gen.getSeaLevel();
        for (int dq = -RADIUS; dq <= RADIUS; dq++) {
            for (int dr = Math.max(-RADIUS, -dq - RADIUS); dr <= Math.min(RADIUS, -dq + RADIUS); dr++) {
                Hex h = new Hex(origin.q() + dq, origin.r() + dr);
                data.provinces.put(h.key(), make(h, gen, rs, sea, random));
            }
        }
        placeRealms(data, origin, random);
        data.generated = true;
        data.touch();
        Strategy1.LOGGER.info("Strategic map laid out: {} provinces, {} realms", data.provinces.size(), data.factions.size());
    }

    private static Province make(Hex h, ChunkGenerator gen, RandomState rs, int sea, RandomSource random) {
        int[] votes = new int[Terrain.values().length];
        int water = 0;
        for (int i = -1; i < 6; i++) {
            double x = h.centerX(), z = h.centerZ();
            if (i >= 0) {
                double[] c = h.corner(i);
                x += (c[0] - x) * 0.6;
                z += (c[1] - z) * 0.6;
            }
            Holder<Biome> b = gen.getBiomeSource().getNoiseBiome((int) x >> 2, sea >> 2, (int) z >> 2, rs.sampler());
            Terrain t = classify(b);
            if (t == null) continue;
            if (t == Terrain.WATER) water++;
            votes[t.ordinal()] += i < 0 ? 2 : 1;
        }
        Terrain terrain;
        if (water >= 4) terrain = Terrain.WATER;
        else {
            terrain = Terrain.PLAINS;
            int best = -1;
            for (Terrain t : Terrain.values()) {
                if (t == Terrain.WATER) continue;
                if (votes[t.ordinal()] > best) {
                    best = votes[t.ordinal()];
                    terrain = t;
                }
            }
        }
        Deposit deposit = Deposit.NONE;
        if (terrain.land() && random.nextFloat() < 0.13F) deposit = depositFor(terrain, random);
        Province p = new Province(h, terrain.land() ? Names.province(random) : Names.sea(random), terrain, deposit);
        if (terrain.land()) p.garrison = random.nextInt(3) + (deposit != Deposit.NONE ? 1 : 0);
        return p;
    }

    /** The terrain a biome counts as (null: a river, which counts as nothing). */
    private static Terrain classify(Holder<Biome> b) {
        String id = b.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        if (b.is(BiomeTags.IS_RIVER)) return null;
        if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN)) return Terrain.WATER;
        if (id.contains("desert") || b.is(BiomeTags.IS_BADLANDS)) return Terrain.DESERT;
        if (id.contains("snowy") || id.contains("frozen") || id.contains("ice") || id.contains("grove")) return Terrain.SNOW;
        if (b.is(BiomeTags.IS_MOUNTAIN) || b.is(BiomeTags.IS_HILL) || id.contains("peaks") || id.contains("slopes")
                || id.contains("windswept") || id.contains("stony")) return Terrain.MOUNTAINS;
        if (id.contains("swamp") || id.contains("mangrove")) return Terrain.SWAMP;
        if (b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA) || b.is(BiomeTags.IS_JUNGLE) || id.contains("forest")) return Terrain.FOREST;
        return Terrain.PLAINS;
    }

    private static Deposit depositFor(Terrain t, RandomSource r) {
        return switch (t) {
            case MOUNTAINS -> r.nextBoolean() ? Deposit.IRON_VEIN : r.nextBoolean() ? Deposit.QUARRY : Deposit.GOLD_VEIN;
            case DESERT -> r.nextFloat() < 0.6F ? Deposit.GOLD_VEIN : Deposit.QUARRY;
            case FOREST -> r.nextFloat() < 0.7F ? Deposit.OLD_FOREST : Deposit.IRON_VEIN;
            case SNOW -> r.nextBoolean() ? Deposit.IRON_VEIN : Deposit.QUARRY;
            case SWAMP -> r.nextBoolean() ? Deposit.FERTILE : Deposit.OLD_FOREST;
            default -> r.nextFloat() < 0.6F ? Deposit.FERTILE : Deposit.values()[1 + r.nextInt(5)];
        };
    }

    private static void placeRealms(StrategyData data, Hex origin, RandomSource random) {
        List<Province> candidates = new ArrayList<>();
        for (Province p : data.provinces.values()) {
            int d = p.hex.distance(origin);
            if (p.terrain.land() && d >= 6 && d <= RADIUS - 2 && landAround(data, p.hex) >= 4) candidates.add(p);
        }
        // (shuffled, then good land first)
        java.util.Collections.shuffle(candidates, new java.util.Random(random.nextLong()));
        candidates.sort(Comparator.comparingInt(p -> -landAround(data, p.hex)));
        List<Province> capitals = new ArrayList<>();
        for (Province p : candidates) {
            if (capitals.size() >= data.realms) break;
            boolean far = true;
            for (Province c : capitals) if (c.hex.distance(p.hex) < 6) far = false;
            if (far) capitals.add(p);
        }
        for (int i = 0; i < capitals.size(); i++) {
            Faction f = new Faction(data.nextFaction++, i, null, null, AI_COLORS[i % AI_COLORS.length]);
            f.gain(Res.of(80, 80, 60, 20, 10));
            f.reserve = 4;
            data.factions.put(f.id, f);
            settle(data, f, capitals.get(i), 3);
        }
    }

    private static int landAround(StrategyData data, Hex h) {
        int n = 0;
        for (Hex x : h.neighbors()) {
            Province p = data.province(x);
            if (p != null && p.terrain.land()) n++;
        }
        return n;
    }

    /** Gives a realm its capital and the free land round it. */
    private static void settle(StrategyData data, Faction f, Province capital, int capitalGarrison) {
        f.capital = capital.key();
        capital.owner = f.id;
        capital.garrison = capitalGarrison;
        capital.eco = 1;
        for (Hex n : capital.hex.neighbors()) {
            Province p = data.province(n);
            if (p != null && p.terrain.land() && p.neutral()) {
                p.owner = f.id;
                p.garrison = 1;
            }
        }
    }

    /** A player's own realm, round the free land nearest to where they stand. */
    public static Faction foundPlayerRealm(StrategyData data, UUID player, String name, double x, double z) {
        Hex at = Hex.at(x, z);
        Province best = null;
        double bestScore = Double.MAX_VALUE;
        for (Province p : data.provinces.values()) {
            if (!p.terrain.land() || !p.neutral()) continue;
            boolean nearEnemy = false;
            for (Hex n : p.hex.neighbors()) {
                Province q = data.province(n);
                if (q != null && !q.neutral()) nearEnemy = true;
            }
            if (nearEnemy) continue;
            double score = p.hex.distance(at) * 10 - landAround(data, p.hex);
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (best == null) return null;
        Faction existing = data.factionOf(player);
        Faction f;
        if (existing != null) {
            f = existing;
            f.alive = true;
        } else {
            int n = 0;
            for (Faction o : data.factions.values()) if (o.player()) n++;
            f = new Faction(data.nextFaction++, -1, name, player, PLAYER_COLORS[n % PLAYER_COLORS.length]);
            data.factions.put(f.id, f);
            data.players.put(player, f.id);
        }
        f.gain(Res.of(60, 60, 40, 10, 5));
        settle(data, f, best, 2);
        data.touch();
        return f;
    }
}
