package org.webtrade.strategy1.build;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.entity.PeasantEntity;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Province;
import org.webtrade.strategy1.game.Res;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.StrategyManager;
import org.webtrade.strategy1.hex.Deposit;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.hex.Terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A realm's people: peasants come to the keep while there is room for them (the keep and every house give room)
 * and food to feed them; idle ones are sent to the workplaces short of hands, the rest to help where something is
 * being built; the fields grow. When there's no work for many, the realm is told.
 */
public final class Workforce {

    /** The peasants in the loaded world (each puts itself here as it ticks). */
    private static final Set<PeasantEntity> PEASANTS = new HashSet<>();
    private static final Map<Integer, Integer> ARRIVAL = new HashMap<>();
    /** Builders at each building site now (structure id → how many). */
    private static final Map<Integer, Integer> SITE = new HashMap<>();
    /** Seconds since a realm was last told its folk stand idle. */
    private static final Map<Integer, Integer> TOLD = new HashMap<>();
    private static final int BUILDERS = 3;
    /** Everything the peasants have carried home since the server started, by resource (for the tests and the log). */
    public static final int[] DELIVERED = new int[Res.COUNT];

    private Workforce() {
    }

    public static void clear() {
        PEASANTS.clear();
        ARRIVAL.clear();
        SITE.clear();
        TOLD.clear();
    }

    public static void tick(PeasantEntity p) {
        PEASANTS.add(p);
    }

    /** Room for people: the keep's and the houses'. */
    public static int room(int faction) {
        int n = 0;
        for (Structure s : StrategyManager.data().structures.values()) {
            if (s.owner != faction || !s.built()) continue;
            if (s.type == StructureType.KEEP) n += 12;
            if (s.type == StructureType.HOUSE) n += 8;
        }
        return n;
    }

    /** The realm's people now: peasants, soldiers, archers on the walls. */
    public static int people(Faction f) {
        int posted = 0;
        for (Structure s : StrategyManager.data().structures.values()) if (s.owner == f.id) posted += s.archers;
        return f.peasants + f.army + posted;
    }

    /** How many lend a hand at a building going up. */
    public static int builders(int structure) {
        return SITE.getOrDefault(structure, 0);
    }

    public static List<PeasantEntity> idle(int faction) {
        List<PeasantEntity> out = new ArrayList<>();
        for (PeasantEntity p : PEASANTS) if (p.isAlive() && !p.isRemoved() && p.faction() == faction && p.idle()) out.add(p);
        return out;
    }

    /** Once a second: arrivals, hands sent to work, the fields growing. */
    public static void second(ServerLevel level) {
        StrategyData d = StrategyManager.data();
        PEASANTS.removeIf(p -> p.isRemoved() || !p.isAlive());
        SITE.clear();
        for (PeasantEntity p : PEASANTS) if (p.builder && p.atSite) SITE.merge(p.job, 1, Integer::sum);
        for (Faction f : d.factions.values()) {
            if (!f.alive) continue;
            Structure keep = null;
            for (Structure s : d.structures.values()) if (s.owner == f.id && s.type == StructureType.KEEP && s.built()) keep = s;
            if (keep == null) continue;
            Vec3 door = Structures.door(keep);
            // someone new comes, now and then, while there's room and food
            int wait = ARRIVAL.merge(f.id, 1, Integer::sum);
            // (an AI town's folk: a few, to be seen at work; they don't count in its abstract numbers)
            int townsfolk = 0;
            if (!f.player()) for (PeasantEntity p : PEASANTS) if (p.faction() == f.id) townsfolk++;
            boolean room = f.player() ? people(f) < room(f.id) : townsfolk < 8;
            if (wait >= 6 && room && f.res[Res.FOOD.ordinal()] > 0 && level.hasChunkAt(BlockPos.containing(door))) {
                ARRIVAL.put(f.id, 0);
                PeasantEntity p = PeasantEntity.spawn(level, f, door.add(level.getRandom().nextGaussian(), 0, level.getRandom().nextGaussian()));
                if (p != null && f.player()) {
                    f.peasants++;
                    d.touch();
                }
            }
            // hands to where they're wanted
            Map<Integer, Integer> hands = new HashMap<>(), building = new HashMap<>();
            for (PeasantEntity p : PEASANTS) {
                if (p.faction() != f.id || p.job < 0) continue;
                (p.builder ? building : hands).merge(p.job, 1, Integer::sum);
            }
            // (real work first: a builder is taken off the site for it)
            List<PeasantEntity> free = idle(f.id);
            for (Structure s : d.structures.values()) {
                if (free.isEmpty()) break;
                if (s.owner != f.id || !s.built() || s.type.workers == 0 || Structures.exhausted(s)) continue;
                int want = s.type.workers - hands.getOrDefault(s.id, 0);
                for (int i = 0; i < want && !free.isEmpty(); i++) {
                    PeasantEntity best = null;
                    double bestD = Double.MAX_VALUE;
                    for (PeasantEntity p : free) {
                        double dist = p.position().distanceToSqr(s.center());
                        if (dist < bestD) {
                            bestD = dist;
                            best = p;
                        }
                    }
                    free.remove(best);
                    best.assign(s.id);
                    hands.merge(s.id, 1, Integer::sum);
                }
            }
            // the rest lend a hand where the realm builds
            free.removeIf(p -> p.builder);
            if (f.player()) {
                for (Structure s : d.structures.values()) {
                    if (free.isEmpty()) break;
                    if (s.owner != f.id || s.planned || s.built() || s.type.deposit() || s.type == StructureType.PATH) continue;
                    int want = BUILDERS - building.getOrDefault(s.id, 0);
                    for (int i = 0; i < want && !free.isEmpty(); i++) {
                        PeasantEntity best = null;
                        double bestD = Double.MAX_VALUE;
                        for (PeasantEntity p : free) {
                            double dist = p.position().distanceToSqr(s.center());
                            if (dist < bestD) {
                                bestD = dist;
                                best = p;
                            }
                        }
                        free.remove(best);
                        best.build(s.id);
                    }
                }
                // many with nothing to do: the realm is told, now and then
                int told = TOLD.merge(f.id, 1, Integer::sum);
                if (free.size() >= 4 && told >= 90) {
                    TOLD.put(f.id, 0);
                    StrategyManager.tell(f, net.minecraft.network.chat.Component.translatable("strategy1.msg.idle_folk", free.size())
                            .withStyle(net.minecraft.ChatFormatting.YELLOW));
                }
            }
        }
        // the fields grow
        RandomSource r = level.getRandom();
        for (Structure s : d.structures.values()) {
            if (s.type != StructureType.FARM || !s.built()) continue;
            for (int k = 0; k < 4; k++) {
                BlockPos p = crop(level, s, r, false);
                if (p == null || !level.hasChunkAt(p)) continue;
                BlockState st = level.getBlockState(p);
                if (st.getBlock() instanceof CropBlock && st.getValue(CropBlock.AGE) < 7) level.setBlock(p, st.setValue(CropBlock.AGE, st.getValue(CropBlock.AGE) + 1), 2);
            }
        }
    }

    /** A ripe (or any) crop of a farm's field. */
    public static BlockPos crop(ServerLevel level, Structure farm, RandomSource r) {
        return crop(level, farm, r, true);
    }

    private static BlockPos crop(ServerLevel level, Structure farm, RandomSource r, boolean ripe) {
        List<BlockPos> crops = new ArrayList<>();
        for (int i = farm.prep; i < farm.pos.length; i++) {
            if (farm.palette.get(farm.state[i]).getBlock() instanceof CropBlock) crops.add(BlockPos.of(farm.pos[i]));
        }
        if (crops.isEmpty()) return null;
        if (ripe) {
            for (int k = 0; k < 12; k++) {
                BlockPos p = crops.get(r.nextInt(crops.size()));
                BlockState s = level.getBlockState(p);
                if (s.getBlock() instanceof CropBlock && s.getValue(CropBlock.AGE) >= 6) return p;
            }
            return null;
        }
        return crops.get(r.nextInt(crops.size()));
    }

    /** Is the workplace on rich ground for what it gives (a deposit, or fitting land)? */
    public static boolean richness(Structure s, Res res) {
        Province p = StrategyManager.data().province(Hex.at(s.origin.getX(), s.origin.getZ()));
        if (p == null) return false;
        if (p.deposit != Deposit.NONE && p.deposit.res == res) return true;
        return res == Res.STONE && p.terrain == Terrain.MOUNTAINS;
    }

    /** A peasant to be made a soldier: the idle one nearest a point (taken out of the world), or null. */
    public static PeasantEntity draft(int faction, Vec3 near) {
        PeasantEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (PeasantEntity p : idle(faction)) {
            double d = p.position().distanceToSqr(near);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
