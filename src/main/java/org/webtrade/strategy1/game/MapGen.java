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

    /** The way a new world was chosen to be played (on this machine's new realm screen), taken in once. */
    public static void adopt(StrategyData data) {
        if (Mode.Pending.mode == null) return;
        data.mode = Mode.Pending.mode;
        data.realms = Mode.Pending.realms >= 0 ? Mode.Pending.realms : data.mode.realms;
        data.coop = Mode.Pending.coop;
        data.siteWanted = Mode.Pending.site;
        Mode.Pending.mode = null;
        Mode.Pending.realms = -1;
        Mode.Pending.site = false;
        data.touch();
    }

    /** The map round the world's spawn. */
    public static void generate(ServerLevel level, StrategyData data) {
        BlockPos spawn = level.getRespawnData().pos();
        generate(level, data, Hex.at(spawn.getX(), spawn.getZ()));
    }

    /** The map round a hex. */
    public static void generate(ServerLevel level, StrategyData data, Hex origin) {
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
        long t0 = System.currentTimeMillis();
        placeRealms(level, data, origin, random);
        data.generated = true;
        data.touch();
        Strategy1.LOGGER.info("Strategic map laid out: {} provinces, {} realms (capitals chosen in {} ms)", data.provinces.size(), data.factions.size(),
                System.currentTimeMillis() - t0);
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

    private static void placeRealms(ServerLevel level, StrategyData data, Hex origin, RandomSource random) {
        List<Province> candidates = new ArrayList<>();
        for (Province p : data.provinces.values()) {
            int d = p.hex.distance(origin);
            if (p.terrain.land() && d >= 6 && d <= RADIUS - 2 && landAround(data, p.hex) >= 4) candidates.add(p);
        }
        // (shuffled, then the best ground first: level, dry, not up a mountain nor out on an island)
        java.util.Collections.shuffle(candidates, new java.util.Random(random.nextLong()));
        // (only the likeliest are looked at closely: the land with the most land round it)
        candidates.sort(Comparator.comparingInt(p -> -landAround(data, p.hex)));
        if (candidates.size() > Math.max(30, data.realms * 8)) candidates = new ArrayList<>(candidates.subList(0, Math.max(30, data.realms * 8)));
        java.util.Map<Province, Double> score = new java.util.HashMap<>();
        for (Province p : candidates) score.put(p, Site.score(level, data, p));
        candidates.removeIf(p -> score.get(p) >= Site.UNFIT);
        candidates.sort(Comparator.comparingDouble(score::get));
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

    /** Where a keep may stand: dry land (as the world will make it, if it isn't made there yet). */
    public static boolean dry(ServerLevel level, double x, double z) {
        BlockPos at = BlockPos.containing(x, 0, z);
        if (level.hasChunkAt(at)) {
            BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at);
            return level.getFluidState(top.below()).isEmpty() && level.getFluidState(top).isEmpty();
        }
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        return gen.getBaseHeight(at.getX(), at.getZ(), net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState())
                >= gen.getBaseHeight(at.getX(), at.getZ(), net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
    }

    /**
     * The world's first player chose where their keep stands: the grid laid so that their capital's hex is centred
     * just north of it (the keep stands a little south of its province's centre), the map laid out round it, the AI
     * realms far enough off, and their realm founded there.
     */
    public static Faction foundAtSite(ServerLevel level, StrategyData data, UUID player, String name, double x, double z) {
        data.gridX = x;
        data.gridZ = z - org.webtrade.strategy1.build.Structures.KEEP_SOUTH;
        Hex.setOrigin(data.gridX, data.gridZ);
        data.provinces.clear();
        data.factions.clear();
        data.players.clear();
        data.structures.clear();
        data.nextFaction = 1;
        data.nextStructure = 1;
        data.forts = data.towns = data.deposits = false;
        data.siteWanted = false;
        Hex home = new Hex(0, 0);
        generate(level, data, home);
        Province p = data.province(home);
        // (whatever the map made of it, it is land now, and the realm's)
        if (!p.terrain.land()) {
            Province land = new Province(home, Names.province(RandomSource.create(level.getSeed())), Terrain.PLAINS, Deposit.NONE);
            data.provinces.put(home.key(), land);
            p = land;
        }
        p.owner = Faction.NEUTRAL;
        return found(data, player, name, p);
    }

    /** A player's own realm, round the free land nearest to where they stand. */
    public static Faction foundPlayerRealm(ServerLevel level, StrategyData data, UUID player, String name, double x, double z) {
        Hex at = Hex.at(x, z);
        Province best = null;
        double bestScore = Double.MAX_VALUE;
        List<Province> near = new ArrayList<>();
        for (Province p : data.provinces.values()) if (p.terrain.land() && p.neutral() && p.hex.distance(at) <= 4) near.add(p);
        near.sort(Comparator.comparingInt(p -> p.hex.distance(at)));
        // (the nearest few looked at closely: each look at the land costs the generator)
        int looked = 0;
        for (Province p : near) {
            if (p.hex.distance(at) * 6 >= bestScore || looked >= 24) break;
            boolean nearEnemy = false;
            for (Hex n : p.hex.neighbors()) {
                Province q = data.province(n);
                if (q != null && !q.neutral()) nearEnemy = true;
            }
            if (nearEnemy) continue;
            // near where the world put us, on good ground for a keep
            looked++;
            double site = Site.score(level, data, p);
            if (site >= Site.UNFIT) continue;
            double score = p.hex.distance(at) * 6 + site;
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (best == null) {
            // (nowhere good near: the nearest free land then)
            for (Province p : data.provinces.values()) {
                if (!p.terrain.land() || !p.neutral()) continue;
                double score = p.hex.distance(at) * 10 - landAround(data, p.hex);
                if (score < bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        if (best == null) return null;
        return found(data, player, name, best);
    }

    /** A player's realm founded (or come back) with its capital in a province. */
    private static Faction found(StrategyData data, UUID player, String name, Province best) {
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
