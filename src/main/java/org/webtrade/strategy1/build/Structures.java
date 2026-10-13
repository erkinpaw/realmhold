package org.webtrade.strategy1.build;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Province;
import org.webtrade.strategy1.game.Rules;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.StrategyManager;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The realms' buildings, towers, walls and paths in the world: putting them up (block by block, as builders would),
 * their strength (siege stones and blows break them, and holes show), the archers standing on towers and walls,
 * the AI capitals' forts, and what the players are told of them.
 */
public final class Structures {

    /** The archers standing on each structure now (by its id). */
    private static final Map<Integer, List<SoldierEntity>> POSTS = new HashMap<>();
    private static boolean dirty = true;
    private static long lastSync;
    private static final RandomSource RANDOM = RandomSource.create();

    private Structures() {
    }

    public static void clear() {
        POSTS.clear();
        AIMED.clear();
        dirty = true;
        lastSync = -10;
    }

    public static void changed() {
        dirty = true;
    }

    private static StrategyData data() {
        return StrategyManager.data();
    }

    // ------------------------------------------------------------------ putting up

    /** Puts up a structure for a player's realm; null if done, else the reason (a translation key). */
    public static String place(ServerPlayer player, Faction f, StructureType type, BlockPos a, BlockPos b, int rot) {
        StrategyData d = data();
        ServerLevel level = StrategyManager.server().overworld();
        if (type == StructureType.KEEP && nearest(f.id, StructureType.KEEP, Vec3.ZERO) != null) return "strategy1.err.build_overlap";
        if (type.requires != null && nearest(f.id, type.requires, Vec3.ZERO) == null) return "strategy1.err.build_needs";
        if (type.deposit() || type == StructureType.KEEP && nearest(f.id, StructureType.KEEP, Vec3.ZERO) != null) return "strategy1.err.build_overlap";
        // a quarry or mine goes right on its deposit (as the Cossacks had it), one to a deposit
        Structure dep = null;
        if (type.needsDeposit() != null) {
            dep = depositNear(type.needsDeposit(), Vec3.atCenterOf(a), 6);
            if (dep == null) return "strategy1.err.build_deposit";
            for (Structure o : d.structures.values()) if (o.parent == dep.id && o.type == type) return "strategy1.err.deposit_taken";
            a = new BlockPos(dep.origin.getX(), a.getY(), dep.origin.getZ());
        }
        if (!level.hasChunkAt(a) || type.line && !level.hasChunkAt(b)) return "strategy1.err.recruit_here";
        // a wall begun (or ended) at a tower, the keep or another wall runs from its side
        if (type.wall()) {
            a = snap(d, a, b);
            b = snap(d, b, a);
        }
        if (type == StructureType.BRIDGE && Blueprints.bridgeTooSteep(level, a, b)) return "strategy1.err.bridge_steep";
        Structure s = new Structure(d.nextStructure, type, f.id, a, type.line ? b : a, rot & 3);
        if (dep != null) s.parent = dep.id;
        if (type.line) {
            int len = s.length();
            if (len < 2) return "strategy1.err.build_short";
            if (len > type.maxLength()) return "strategy1.err.build_long";
        }
        if (!type.line) s.origin = new BlockPos(a.getX(), Blueprints.ground(level, a.getX(), a.getZ()), a.getZ());
        if (type == StructureType.OUTPOST) {
            String why = outpostLand(d, f.id, s);
            if (why != null) return why;
        } else if (!ownLand(d, f.id, s)) return "strategy1.err.build_land";
        if (type != StructureType.PATH) {
            // (over the ground, whatever the height: no farm on the keep's roof)
            AABB box = flat(s.box().deflate(0.1));
            for (Structure o : d.structures.values()) {
                if (o.type == StructureType.PATH || o.planned || o == dep) continue;
                // (a wall may lean on a tower, the keep or another wall at its ends)
                if (type.wall() && (o.type.tower() || o.type == StructureType.KEEP || o.type.wall() || o.type == StructureType.GATE)) continue;
                if (flat(o.box()).intersects(box)) return "strategy1.err.build_overlap";
            }
        }
        int[] cost = type.cost(s.length());
        if (!f.canPay(cost)) return "strategy1.err.cost";
        if (!Blueprints.layOut(level, s, f.color)) return "strategy1.err.build_water";
        f.pay(cost);
        s.maxHp = type.hp * (type.line ? s.length() : 1);
        s.hp = Math.max(1, s.maxHp / 10);
        d.nextStructure++;
        d.structures.put(s.id, s);
        d.touch();
        dirty = true;
        return null;
    }

    /** What the client is told besides: the door, the gathering point, what's left to dig, the gate. */
    private static int[] more(Structure s) {
        int n = Payloads.StructureInfo.NONE;
        Vec3 door = s.door;
        return new int[]{door == null ? n : (int) Math.floor(door.x), door == null ? n : (int) Math.floor(door.y), door == null ? n : (int) Math.floor(door.z),
                s.rally == null ? n : (int) Math.floor(s.rally.x), s.rally == null ? n : (int) Math.floor(s.rally.z),
                s.type.deposit() ? left(s) : s.type.needsDeposit() != null ? leftAt(s) : -1,
                s.type == StructureType.GATE ? (s.held ? 1 : 0) : -1, s.open ? 1 : 0};
    }

    // ------------------------------------------------------------------ gates

    /** Cuts a gate in a realm's wall where it was clicked (not at its very ends); null if done, else why not. */
    public static String gate(Faction f, int wallId, int dx, int dz) {
        StrategyData d = data();
        Structure wall = d.structures.get(wallId);
        if (wall == null || wall.owner != f.id || !wall.type.wall() || !wall.built()) return "strategy1.err.not_built";
        List<int[]> cols = Blueprints.line(wall.origin, wall.end);
        // (the click as the client gives it: from the corner of the wall's box)
        int x = (int) Math.floor(wall.box().minX) + dx, z = (int) Math.floor(wall.box().minZ) + dz, best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < cols.size(); i++) {
            double dist = Math.hypot(cols.get(i)[0] - x, cols.get(i)[1] - z);
            if (dist < bestD) {
                bestD = dist;
                best = i;
            }
        }
        if (best < 0 || bestD > 3) return "strategy1.err.gate_here";
        best = Math.clamp(best, 1, cols.size() - 2);
        if (cols.size() < 5) return "strategy1.err.gate_here";
        int[] a = cols.get(best - 1), b = cols.get(best + 1);
        for (Structure o : d.structures.values()) {
            if (o.type == StructureType.GATE && o.parent == wall.id && Math.hypot(o.origin.getX() - a[0], o.origin.getZ() - a[1]) < 4) return "strategy1.err.gate_there";
        }
        int[] cost = StructureType.GATE.cost(3);
        if (!f.canPay(cost)) return "strategy1.err.cost";
        ServerLevel level = StrategyManager.server().overworld();
        Structure g = new Structure(d.nextStructure, StructureType.GATE, f.id, new BlockPos(a[0], wall.origin.getY(), a[1]), new BlockPos(b[0], wall.origin.getY(), b[1]), 0);
        g.parent = wall.id;
        if (!Blueprints.layOut(level, g, f.color)) return "strategy1.err.gate_here";
        f.pay(cost);
        g.maxHp = StructureType.GATE.hp * 3 * (wall.type == StructureType.WALL_STONE ? 2 : 1);
        g.hp = Math.max(1, g.maxHp / 10);
        d.nextStructure++;
        d.structures.put(g.id, g);
        d.touch();
        dirty = true;
        return null;
    }

    /** A gate held open by hand, or let be (it opens for its own then, and shuts before the enemy). */
    public static void holdGate(Faction f, int id) {
        Structure g = data().structures.get(id);
        if (g == null || g.owner != f.id || g.type != StructureType.GATE) return;
        g.held = !g.held;
        data().touch();
        dirty = true;
    }

    /** Four times a second: each gate opens if its own are near and no enemy is, or if held open; shuts otherwise. */
    private static void gates(ServerLevel level, long ticks) {
        StrategyData d = data();
        for (Structure g : d.structures.values()) {
            if (g.type != StructureType.GATE || !g.built() || g.leaves.length == 0 || !level.hasChunkAt(g.origin)) continue;
            Vec3 c = g.center();
            boolean friend = false, foe = false;
            for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    new AABB(c, c).inflate(9, 6, 9), e -> e.isAlive())) {
                double dist = Math.hypot(e.getX() - c.x, e.getZ() - c.z);
                if (enemyOf(d, g.owner, e)) {
                    if (dist < 9) foe = true;
                } else if (e instanceof UnitEntity u && dist < 6 && !d.hostile(g.owner, u.faction())) friend = true;
            }
            boolean open = g.held || friend && !foe;
            // (laid again now and then, in case something was knocked out of it)
            if (open == g.open && g.leavesLaid && ticks % 100 != 0) continue;
            boolean changed = open != g.open || !g.leavesLaid;
            g.leavesLaid = true;
            g.open = open;
            BlockState leaf = Blueprints.leaf(g);
            for (long p : g.leaves) {
                BlockPos q = BlockPos.of(p);
                BlockState is = level.getBlockState(q);
                if (open && is.getBlock() == leaf.getBlock()) level.setBlock(q, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                else if (!open && (is.isAir() || is.canBeReplaced())) level.setBlock(q, leaf, Block.UPDATE_ALL);
            }
            if (changed) {
                level.playSound(null, BlockPos.containing(c), open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1F, 0.7F);
                dirty = true;
            }
        }
    }

    // ------------------------------------------------------------------ what's in the ground

    /** What's left in a deposit (reckoned the first time it's asked: a rich province's holds half again as much). */
    public static int left(Structure dep) {
        if (dep.left < 0) {
            Province p = data().province(dep.province());
            boolean rich = p != null && p.deposit != org.webtrade.strategy1.hex.Deposit.NONE;
            dep.left = (int) (dep.type.riches() * (rich ? 1.5 : 1.0));
        }
        return dep.left;
    }

    /** What a quarry or mine's deposit has left (−1 if it has none). */
    public static int leftAt(Structure mine) {
        Structure dep = data().structures.get(mine.parent);
        return dep == null || !dep.type.deposit() ? -1 : left(dep);
    }

    /** A load dug at a quarry or mine: as much as the deposit has left of it, and that much less in it. */
    public static int dig(Structure mine, int load) {
        Structure dep = data().structures.get(mine.parent);
        if (dep == null || !dep.type.deposit()) return load;
        int got = Math.min(load, left(dep));
        dep.left -= got;
        if (dep.left <= 0 && got > 0) {
            StrategyManager.tell(data().faction(mine.owner), Component.translatable("strategy1.msg.mine_empty", Component.translatable(mine.type.key()))
                    .withStyle(ChatFormatting.YELLOW));
        }
        dirty = true;
        return got;
    }

    /** A quarry or mine whose deposit is dug out (its workers go). */
    public static boolean exhausted(Structure s) {
        return s.type.needsDeposit() != null && leftAt(s) == 0;
    }

    /** A box from the bottom of the world to the top: what stands over the same ground. */
    private static AABB flat(AABB b) {
        return new AABB(b.minX, -2048, b.minZ, b.maxX, 2048, b.maxZ);
    }

    /**
     * A wall's end put inside a tower, the keep or another wall: moved out to its side, towards the wall's other end,
     * so the wall runs from it.
     */
    public static BlockPos snap(StrategyData d, BlockPos end, BlockPos other) {
        for (Structure o : d.structures.values()) {
            if (o.planned || !(o.type.tower() || o.type == StructureType.KEEP || o.type.wall())) continue;
            AABB b = flat(o.box());
            if (!b.contains(end.getX() + 0.5, 0, end.getZ() + 0.5)) continue;
            double x = end.getX() + 0.5, z = end.getZ() + 0.5, tx = other.getX() + 0.5, tz = other.getZ() + 0.5;
            double len = Math.hypot(tx - x, tz - z);
            if (len < 1) return end;
            for (double step = 0; step <= len; step += 0.5) {
                double px = x + (tx - x) * step / len, pz = z + (tz - z) * step / len;
                if (!b.contains(px, 0, pz)) return BlockPos.containing(px, end.getY(), pz);
            }
            return end;
        }
        return end;
    }

    /** An outpost goes on land the realm doesn't hold yet, next to its own, of someone it may take it from; null if fine. */
    private static String outpostLand(StrategyData d, int faction, Structure s) {
        AABB box = s.box();
        Province q = null;
        for (double[] p : new double[][]{{box.minX, box.minZ}, {box.maxX, box.minZ}, {box.minX, box.maxZ}, {box.maxX, box.maxZ}}) {
            Province at = d.province(Hex.at(p[0], p[1]));
            if (at == null || q != null && at != q) return "strategy1.err.build_outpost";
            q = at;
        }
        if (q == null || !q.terrain.land() || q.owner == faction || !d.borders(faction, q.hex) || !d.hostile(faction, q.owner)) return "strategy1.err.build_outpost";
        for (Structure o : d.structures.values()) if (o.owner == faction && o.type == StructureType.OUTPOST && o.province() == q.key()) return "strategy1.err.outpost_there";
        return null;
    }

    /** Are the structure's corners (or both ends of a line) on the realm's land? */
    private static boolean ownLand(StrategyData d, int faction, Structure s) {
        List<double[]> pts = new ArrayList<>();
        if (s.type.line) {
            pts.add(new double[]{s.origin.getX() + 0.5, s.origin.getZ() + 0.5});
            pts.add(new double[]{s.end.getX() + 0.5, s.end.getZ() + 0.5});
        } else {
            AABB box = s.box();
            pts.add(new double[]{box.minX, box.minZ});
            pts.add(new double[]{box.maxX, box.minZ});
            pts.add(new double[]{box.minX, box.maxZ});
            pts.add(new double[]{box.maxX, box.maxZ});
        }
        for (double[] p : pts) {
            Province q = d.province(Hex.at(p[0], p[1]));
            if (q == null || q.owner != faction) return false;
        }
        return true;
    }

    /** Each tick: the builders lay their blocks; AI forts are laid out when someone comes to see them. */
    public static void tick(ServerLevel level, long ticks) {
        StrategyData d = data();
        if (d == null) return;
        if (ticks % 5 == 0) gates(level, ticks);
        for (Structure s : new ArrayList<>(d.structures.values())) {
            if (s.planned) {
                if (ticks % 20 == 0) layOutPlanned(level, s);
                continue;
            }
            if (s.built()) continue;
            Faction f = d.faction(s.owner);
            int body = s.pos.length - s.prep;
            int perTick;
            if (f != null && !f.player() || s.instant) perTick = s.pos.length;
            else {
                double seconds = s.type.line ? s.length() * (s.type == StructureType.PATH ? 0.08 : s.type == StructureType.WALL_STONE ? 0.5 : 0.25)
                        : s.type.seconds;
                perTick = Math.max(1, (int) Math.ceil(s.pos.length / Math.max(1, seconds * 20)));
                // peasants lending a hand: it goes up faster with each
                perTick *= 1 + Workforce.builders(s.id);
            }
            for (int i = 0; i < perTick && s.progress < s.pos.length; i++) {
                BlockPos p = BlockPos.of(s.pos[s.progress]);
                if (!level.hasChunkAt(p)) break;
                level.setBlock(p, s.palette.get(s.state[s.progress]), Block.UPDATE_ALL);
                if (s.progress >= s.prep) s.hp = Math.min(s.maxHp, s.hp + Math.max(1, s.maxHp * 9 / 10 / Math.max(1, body)));
                s.progress++;
                if (i == 0 && ticks % 4 == 0 && f != null && f.player()) {
                    level.sendParticles(ParticleTypes.CLOUD, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 1, 0.2, 0.2, 0.2, 0);
                    level.playSound(null, p, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.4F, 0.9F + RANDOM.nextFloat() * 0.2F);
                }
            }
            if (s.built()) {
                s.hp = s.maxHp;
                d.setDirty();
                StrategyManager.tell(f, Component.translatable("strategy1.msg.built", Component.translatable(s.type.key())).withStyle(ChatFormatting.GREEN));
            }
            if (ticks % 10 == 0) dirty = true;
        }
    }

    private static void layOutPlanned(ServerLevel level, Structure s) {
        boolean seen = false;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(s.origin.getX(), p.getY(), s.origin.getZ()) < 170 * 170) seen = true;
        }
        if (!seen || !level.hasChunkAt(s.origin) || !level.hasChunkAt(s.end)) return;
        Faction f = data().faction(s.owner);
        if (!s.type.line) s.origin = new BlockPos(s.origin.getX(), Blueprints.ground(level, s.origin.getX(), s.origin.getZ()), s.origin.getZ());
        if (!Blueprints.layOut(level, s, f == null ? 0x888888 : f.color)) {
            data().structures.remove(s.id);
            return;
        }
        // (a deposit that came out on top of something put there before goes)
        if (s.type.deposit()) {
            AABB box = s.box().deflate(0.1);
            for (Structure o : data().structures.values()) {
                if (o != s && !o.planned && !o.type.deposit() && o.box().intersects(box)) {
                    data().structures.remove(s.id);
                    return;
                }
            }
        }
        s.maxHp = s.type.hp * (s.type.line ? s.length() : 1);
        s.hp = s.maxHp;
        s.instant = true;
        dirty = true;
    }

    // ------------------------------------------------------------------ breaking

    /** A stone lands (or a blow falls) at a point: enemy structures round it take the damage. */
    public static void impact(ServerLevel level, Vec3 at, int attacker, float damage, float radius) {
        StrategyData d = data();
        for (Structure s : new ArrayList<>(d.structures.values())) {
            if (s.planned || s.maxHp <= 0 || !d.hostile(attacker, s.owner)) continue;
            if (!s.box().inflate(radius).contains(at)) continue;
            breakBlocks(level, s, at, 2 + RANDOM.nextInt(3));
            damage(level, s, damage);
        }
    }

    /** A soldier's blow at a structure (by its id). */
    public static void strike(UnitEntity by, int id, float damage) {
        strike(by, by.faction(), id, damage);
    }

    /** A blow of one of the night's dead. */
    public static void strike(net.minecraft.world.entity.Mob by, int id, float damage) {
        strike(by, Faction.NEUTRAL, id, damage);
    }

    /** A structure of anyone hostile to the attacker that the box touches (walls first), or null. */
    public static Structure touching(AABB box, int attacker) {
        StrategyData d = data();
        Structure best = null;
        for (Structure s : d.structures.values()) {
            if (s.planned || s.maxHp <= 0 || s.type.deposit() || s.progress == 0 || !d.hostile(attacker, s.owner)) continue;
            if (!s.box().intersects(box)) continue;
            if (best == null || s.type.wall()) best = s;
        }
        return best;
    }

    private static void strike(net.minecraft.world.entity.Entity by, int attacker, int id, float damage) {
        StrategyData d = data();
        Structure s = d.structures.get(id);
        if (s == null || s.maxHp <= 0 || !d.hostile(attacker, s.owner) || !(by.level() instanceof ServerLevel level)) return;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()), by.getX(), by.getY() + 1, by.getZ(), 6, 0.4, 0.4, 0.4, 0.1);
        if (RANDOM.nextFloat() < 0.15F) breakBlocks(level, s, by.position(), 1);
        damage(level, s, damage);
    }

    private static void breakBlocks(ServerLevel level, Structure s, Vec3 near, int n) {
        List<BlockPos> close = new ArrayList<>();
        for (int i = s.prep; i < s.progress; i++) {
            BlockPos p = BlockPos.of(s.pos[i]);
            if (p.distToCenterSqr(near) < 9 && !level.getBlockState(p).isAir()) close.add(p);
        }
        for (int i = 0; i < n && !close.isEmpty(); i++) {
            BlockPos p = close.remove(RANDOM.nextInt(close.size()));
            level.destroyBlock(p, false);
        }
    }

    private static void damage(ServerLevel level, Structure s, float amount) {
        s.hp -= (int) Math.ceil(amount);
        dirty = true;
        if (s.hp <= 0) destroy(level, s);
    }

    /** It falls: its blocks come down, its archers jump off. */
    public static void destroy(ServerLevel level, Structure s) {
        StrategyData d = data();
        if (s.type == StructureType.GATE) {
            BlockState leaf = Blueprints.leaf(s);
            for (long p : s.leaves) if (level.getBlockState(BlockPos.of(p)).getBlock() == leaf.getBlock()) level.setBlock(BlockPos.of(p), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (s.type.wall()) {
            for (Structure g : new ArrayList<>(d.structures.values())) if (g.type == StructureType.GATE && g.parent == s.id) destroy(level, g);
        }
        for (int i = s.prep; i < s.progress; i++) {
            BlockPos p = BlockPos.of(s.pos[i]);
            BlockState want = s.palette.get(s.state[i]);
            BlockState is = level.getBlockState(p);
            if (!want.isAir() && is.getBlock() == want.getBlock()) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Vec3 c = s.center();
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x, c.y, c.z, 20, 2, 1, 2, 0.02);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 2F, 0.7F);
        dismiss(s.id);
        Faction owner = d.faction(s.owner);
        if (owner != null && owner.player() && s.archers > 0) {
            // a player's archers jump down and fight on
            for (int i = 0; i < s.archers; i++) {
                SoldierEntity a = SoldierEntity.spawn(level, UnitType.ARCHER, owner.id, owner.color, new Vec3(c.x + RANDOM.nextGaussian() * 2, 0, c.z + RANDOM.nextGaussian() * 2),
                        UnitEntity.Role.ARMY);
                if (a != null) {
                    a.commander = owner.ruler;
                    owner.army++;
                }
            }
        }
        d.structures.remove(s.id);
        StrategyManager.announce(Component.translatable("strategy1.msg.destroyed", Component.translatable(s.type.key()),
                owner == null ? Component.literal("?") : owner.name()).withStyle(ChatFormatting.GOLD), false);
        d.touch();
        dirty = true;
    }

    // ------------------------------------------------------------------ archers on towers and walls

    /** A soldier sent to man a structure gets up there (an archer, with room). */
    public static boolean man(SoldierEntity s, int id) {
        StrategyData d = data();
        Structure st = d.structures.get(id);
        Faction f = d.faction(s.faction());
        // (an outpost takes any soldier; the rest want bowmen)
        if (st == null || f == null || st.owner != s.faction() || !st.built() || !st.type.manned() || !s.unit().ranged && st.type != StructureType.OUTPOST) return false;
        if (st.archers >= Math.min(st.posts(), st.spots.size())) return false;
        st.archers++;
        if (s.role == UnitEntity.Role.ARMY) f.army = Math.max(0, f.army - 1);
        s.discard();
        d.touch();
        dirty = true;
        return true;
    }

    /** An archer comes down from a player's structure. */
    public static boolean unman(Faction f, int id) {
        StrategyData d = data();
        Structure st = d.structures.get(id);
        if (st == null || st.owner != f.id || st.archers <= 0) return false;
        ServerLevel level = StrategyManager.server().overworld();
        List<SoldierEntity> on = POSTS.get(id);
        if (on != null && !on.isEmpty()) on.remove(on.size() - 1).discard();
        st.archers--;
        Vec3 c = st.center();
        AABB b = st.box();
        Vec3 at = new Vec3(b.minX - 1.5, 0, c.z);
        SoldierEntity a = SoldierEntity.spawn(level, UnitType.ARCHER, f.id, f.color, at, UnitEntity.Role.ARMY);
        if (a != null) {
            a.commander = f.ruler;
            f.army++;
        }
        d.touch();
        dirty = true;
        return true;
    }

    private static void dismiss(int id) {
        List<SoldierEntity> on = POSTS.remove(id);
        if (on != null) for (SoldierEntity s : on) s.discard();
    }

    public static boolean posted(SoldierEntity s) {
        List<SoldierEntity> on = POSTS.get(s.structure);
        return on != null && on.contains(s);
    }

    /** An archer on a structure fell: one fewer up there. */
    public static void postDied(SoldierEntity s) {
        Structure st = data().structures.get(s.structure);
        List<SoldierEntity> on = POSTS.get(s.structure);
        if (on != null) on.remove(s);
        if (st != null && st.archers > 0) {
            st.archers--;
            data().touch();
            dirty = true;
        }
    }

    /** Once a second: archers stand on the structures someone is near enough to see; the players hear of the structures. */
    public static void second(ServerLevel level, long ticks) {
        StrategyData d = data();
        for (Iterator<Map.Entry<Integer, List<SoldierEntity>>> it = POSTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, List<SoldierEntity>> e = it.next();
            e.getValue().removeIf(s -> s.isRemoved() || !s.isAlive());
            if (!d.structures.containsKey(e.getKey())) {
                for (SoldierEntity s : e.getValue()) s.discard();
                it.remove();
            }
        }
        for (Structure s : d.structures.values()) {
            if (!s.built() || !s.type.manned()) continue;
            int want = Math.min(s.archers, s.spots.size());
            Vec3 c = s.center();
            boolean seen = false;
            for (ServerPlayer p : level.players()) if (p.distanceToSqr(c.x, p.getY(), c.z) < 150 * 150) seen = true;
            if (!seen || !level.hasChunkAt(BlockPos.containing(c))) want = 0;
            List<SoldierEntity> on = POSTS.computeIfAbsent(s.id, k -> new ArrayList<>());
            while (on.size() > want) on.remove(on.size() - 1).discard();
            Faction f = d.faction(s.owner);
            while (on.size() < want) {
                Vec3 at = s.spots.get(on.size());
                SoldierEntity a = SoldierEntity.spawn(level, UnitType.ARCHER, s.owner, f == null ? 0x888888 : f.color, at, UnitEntity.Role.POST);
                if (a == null) break;
                a.mode = SoldierEntity.Mode.POSTED;
                a.anchor = at;
                a.structure = s.id;
                a.snapTo(at.x, at.y, at.z, RANDOM.nextFloat() * 360, 0);
                on.add(a);
            }
        }
        engines(level);
        if (dirty && ticks - lastSync >= 10) {
            dirty = false;
            lastSync = ticks;
            for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) send(p);
        }
    }

    public static void send(ServerPlayer p) {
        StrategyData d = data();
        if (d == null || !ServerPlayNetworking.canSend(p, Payloads.StructureSync.TYPE)) return;
        List<Payloads.StructureInfo> out = new ArrayList<>();
        for (Structure s : d.structures.values()) {
            if (s.planned) continue;
            AABB b = s.box();
            int pct = s.pos.length == 0 ? 100 : s.progress * 100 / s.pos.length;
            out.add(new Payloads.StructureInfo(s.id, s.type.ordinal(), s.owner, new int[]{(int) b.minX, (int) b.minY, (int) b.minZ, (int) b.maxX, (int) b.maxY,
                    (int) b.maxZ}, Math.max(0, s.hp), s.maxHp, pct, s.archers, Math.min(s.posts(), s.spots.size()), s.engine, more(s)));
        }
        ServerPlayNetworking.send(p, new Payloads.StructureSync(out));
    }

    // ------------------------------------------------------------------ the towers' engines

    private static final Map<Integer, Integer> RELOAD = new HashMap<>();
    /** What each tower was told to shoot (structure id → entity id). */
    private static final Map<Integer, Integer> AIMED = new HashMap<>();

    /** A tower told to shoot one of them: its engine and its archers turn on it. */
    public static boolean aim(ServerLevel level, int id, int entity) {
        Structure s = data().structures.get(id);
        if (s == null || !s.type.manned() || !(level.getEntity(entity) instanceof net.minecraft.world.entity.LivingEntity e) || !enemyOf(data(), s.owner, e)) return false;
        AIMED.put(id, entity);
        List<SoldierEntity> on = POSTS.get(id);
        if (on != null) for (SoldierEntity a : on) if (a.isAlive()) a.setTarget(e);
        level.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY() + e.getBbHeight() + 0.5, e.getZ(), 12, 0.3, 0.2, 0.3, 0.1);
        return true;
    }

    /** The cost of putting an engine on a tower: 1 ballista, 2 catapult, 3 fire. */
    public static int[] engineCost(int engine) {
        return switch (engine) {
            case 1 -> org.webtrade.strategy1.game.Res.of(0, 60, 0, 20, 0);
            case 2 -> org.webtrade.strategy1.game.Res.of(0, 40, 80, 10, 0);
            default -> org.webtrade.strategy1.game.Res.of(0, 20, 20, 40, 20);
        };
    }

    /** Puts an engine on a tower (its blocks go on the top). */
    public static boolean upgrade(Faction f, int id, int engine) {
        Structure s = data().structures.get(id);
        if (s == null || s.owner != f.id || !s.type.tower() || !s.built() || engine < 1 || engine > 3) return false;
        int[] cost = engineCost(engine);
        if (!f.canPay(cost)) return false;
        f.pay(cost);
        s.engine = engine;
        ServerLevel level = StrategyManager.server().overworld();
        int top = s.type == StructureType.TOWER_STONE ? 11 : 7;
        BlockPos c = s.origin.above(top);
        List<long[]> add = new ArrayList<>();
        java.util.function.BiConsumer<BlockPos, BlockState> put = (p, st) -> {
            level.setBlock(p, st, Block.UPDATE_ALL);
            add.add(new long[]{p.asLong(), 0});
            int idx = s.palette.indexOf(st);
            if (idx < 0) {
                s.palette.add(st);
                idx = s.palette.size() - 1;
            }
            add.get(add.size() - 1)[1] = idx;
        };
        switch (engine) {
            case 1 -> {
                put.accept(c, Blocks.DISPENSER.defaultBlockState().setValue(net.minecraft.world.level.block.DispenserBlock.FACING, net.minecraft.core.Direction.UP));
                put.accept(c.above(), Blocks.OAK_FENCE.defaultBlockState());
            }
            case 2 -> {
                put.accept(c, Blocks.OAK_LOG.defaultBlockState());
                put.accept(c.above(), Blocks.COBBLESTONE_WALL.defaultBlockState());
            }
            default -> {
                put.accept(c, Blocks.CAMPFIRE.defaultBlockState());
            }
        }
        int n0 = s.pos.length;
        s.pos = java.util.Arrays.copyOf(s.pos, n0 + add.size());
        s.state = java.util.Arrays.copyOf(s.state, n0 + add.size());
        for (int i = 0; i < add.size(); i++) {
            s.pos[n0 + i] = add.get(i)[0];
            s.state[n0 + i] = (int) add.get(i)[1];
        }
        s.progress = s.pos.length;
        s.laidOut();
        data().touch();
        dirty = true;
        return true;
    }

    /** Once a second: each tower's engine fires at the enemy nearest it in its reach. */
    private static void engines(ServerLevel level) {
        StrategyData d = data();
        for (Structure s : d.structures.values()) {
            if (s.engine == 0 || !s.built() || !level.hasChunkAt(s.origin)) continue;
            int r = RELOAD.merge(s.id, -1, Integer::sum);
            if (r > 0) continue;
            int top = s.type == StructureType.TOWER_STONE ? 12 : 8;
            Vec3 from = Vec3.atCenterOf(s.origin.above(top));
            double reach = s.engine == 1 ? 40 : s.engine == 2 ? 44 : 14;
            net.minecraft.world.entity.LivingEntity target = null;
            double best = Double.MAX_VALUE;
            // the one the strategist pointed at, while it's alive and in reach
            Integer aimed = AIMED.get(s.id);
            if (aimed != null) {
                if (level.getEntity(aimed) instanceof net.minecraft.world.entity.LivingEntity e && e.isAlive() && e.position().distanceTo(from) <= reach + 8) {
                    target = e;
                    best = -1;
                } else AIMED.remove(s.id);
            }
            for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    new AABB(from, from).inflate(reach, 30, reach), e -> e.isAlive() && enemyOf(d, s.owner, e))) {
                double dist = e.position().distanceTo(from);
                if (best >= 0 && dist < best && dist <= reach && (s.engine != 2 || dist > 8)) {
                    best = dist;
                    target = e;
                }
            }
            if (target == null) continue;
            // (out from the engine, clear of the tower's own top)
            Vec3 toward = target.position().subtract(from);
            from = from.add(new Vec3(toward.x, 0, toward.z).normalize().scale(2.2)).add(0, 1.2, 0);
            switch (s.engine) {
                case 1 -> {
                    // the ballista: a heavy bolt, straight and hard
                    RELOAD.put(s.id, 2);
                    net.minecraft.world.entity.projectile.arrow.Arrow bolt = new net.minecraft.world.entity.projectile.arrow.Arrow(level, from.x, from.y, from.z,
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
                    bolt.setBaseDamage(7);
                    bolt.addTag(org.webtrade.strategy1.entity.ShieldZombie.BOLT);
                    bolt.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.DISALLOWED;
                    Vec3 aim = target.getEyePosition().subtract(0, 0.5, 0).subtract(from);
                    bolt.shoot(aim.x, aim.y + aim.horizontalDistance() * 0.05, aim.z, 3.2F, 0.5F);
                    level.addFreshEntity(bolt);
                    level.playSound(null, s.origin.above(top), SoundEvents.CROSSBOW_SHOOT, SoundSource.BLOCKS, 1.2F, 0.6F);
                }
                case 2 -> {
                    RELOAD.put(s.id, 5);
                    org.webtrade.strategy1.entity.BoulderEntity.fire(level, null, from, target.position(), 40, 10, 3F);
                }
                default -> {
                    // the fire: burning pitch over whoever comes near
                    RELOAD.put(s.id, 2);
                    for (int i = 0; i <= 10; i++) {
                        Vec3 p = from.lerp(target.position().add(0, 1, 0), i / 10.0);
                        level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.01);
                    }
                    for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                            target.getBoundingBox().inflate(2.5), e -> enemyOf(d, s.owner, e))) {
                        e.igniteForSeconds(5);
                        e.hurtServer(level, level.damageSources().inFire(), 4);
                    }
                    level.playSound(null, s.origin.above(top), SoundEvents.BLAZE_SHOOT, SoundSource.BLOCKS, 0.8F, 0.8F);
                }
            }
        }
    }

    /** Is this one an enemy of a realm's (enemy soldiers, monsters)? */
    private static boolean enemyOf(StrategyData d, int faction, net.minecraft.world.entity.LivingEntity e) {
        if (e instanceof UnitEntity u) return d.hostile(faction, u.faction());
        return e instanceof net.minecraft.world.entity.monster.Enemy;
    }

    // ------------------------------------------------------------------ what the soldiers and engines need to know

    /** The middle of an enemy structure to throw at (null if it's gone or not an enemy's). */
    public static Vec3 aim(int id, int attacker) {
        StrategyData d = data();
        Structure s = d.structures.get(id);
        if (s == null || s.planned || s.maxHp <= 0 || !d.hostile(attacker, s.owner)) return null;
        return s.center();
    }

    /** The nearest enemy structure in range (towers first), its middle. */
    public static Vec3 nearestEnemy(int attacker, Vec3 from, double min, double max) {
        StrategyData d = data();
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (Structure s : d.structures.values()) {
            if (s.planned || s.maxHp <= 0 || s.progress == 0 || !d.hostile(attacker, s.owner) || s.owner == Faction.NEUTRAL) continue;
            Vec3 c = s.center();
            double dist = Math.hypot(c.x - from.x, c.z - from.z);
            if (dist < min || dist > max) continue;
            double score = dist - (s.type.tower() ? 30 : s.type.wall() ? 15 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** The point on the ground beside a structure nearest to someone (to walk to it). */
    public static Vec3 point(int id, Vec3 from) {
        Structure s = data().structures.get(id);
        if (s == null || s.planned) return null;
        AABB b = s.box();
        double x = Math.max(b.minX - 0.8, Math.min(b.maxX + 0.8, from.x)), z = Math.max(b.minZ - 0.8, Math.min(b.maxZ + 0.8, from.z));
        if (x > b.minX && x < b.maxX && z > b.minZ && z < b.maxZ) {
            // (inside: out to the nearest side)
            double[] dd = {x - b.minX, b.maxX - x, z - b.minZ, b.maxZ - z};
            int k = 0;
            for (int i = 1; i < 4; i++) if (dd[i] < dd[k]) k = i;
            if (k == 0) x = b.minX - 0.8;
            else if (k == 1) x = b.maxX + 0.8;
            else if (k == 2) z = b.minZ - 0.8;
            else z = b.maxZ + 0.8;
        }
        ServerLevel level = StrategyManager.server().overworld();
        return SoldierEntity.surface(level, x, z);
    }

    /** Where what a barracks, workshop or port makes comes out. */
    public static Vec3 door(Structure s) {
        return s.door != null ? s.door : s.center();
    }

    /** The realm's built structures of a kind, nearest a point first. */
    public static Structure nearest(int faction, StructureType type, Vec3 to) {
        Structure best = null;
        double bestD = Double.MAX_VALUE;
        for (Structure s : data().structures.values()) {
            if (s.owner != faction || s.type != type || !s.built()) continue;
            double dist = s.center().distanceToSqr(to);
            if (dist < bestD) {
                bestD = dist;
                best = s;
            }
        }
        return best;
    }

    /** The keep or stockpile nearest a point, where carriers bring what they gathered. */
    public static Structure nearestStore(int faction, Vec3 to) {
        Structure best = null;
        double bestD = Double.MAX_VALUE;
        for (Structure s : data().structures.values()) {
            if (s.owner != faction || !s.type.store() || !s.built()) continue;
            double dist = s.center().distanceToSqr(to);
            if (dist < bestD) {
                bestD = dist;
                best = s;
            }
        }
        return best;
    }

    /** Has the realm a built structure of this kind? */
    public static boolean has(int faction, StructureType type) {
        return nearest(faction, type, Vec3.ZERO) != null;
    }

    /** The realm's might in arms: what its garrisons are made of (forge and gold make better ones). */
    public static int tier(Faction f) {
        if (!f.player()) return f.age;
        return has(f.id, StructureType.BLACKSMITH) ? 2 : 1;
    }

    /** A keep at a realm's capital (built at once when its land is first seen). */
    public static void planKeep(StrategyData d, Faction f) {
        org.webtrade.strategy1.game.Province cap = d.province(f.capital);
        if (cap == null) return;
        for (Structure s : d.structures.values()) if (s.owner == f.id && s.type == StructureType.KEEP) return;
        Structure k = new Structure(d.nextStructure++, StructureType.KEEP, f.id, BlockPos.containing(cap.hex.centerX(), 64, cap.hex.centerZ() + 7), null, 0);
        k.planned = true;
        k.archers = 4;
        d.structures.put(k.id, k);
        d.touch();
    }

    /** The deposit of a kind nearest a point (within r), or null. */
    public static Structure depositNear(StructureType kind, Vec3 at, double r) {
        Structure best = null;
        double bestD = r * r;
        for (Structure s : data().structures.values()) {
            if (s.type != kind) continue;
            double d = s.origin.distToCenterSqr(at.x, s.origin.getY(), at.z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /**
     * The riches of the land, laid out on it as soon as anyone comes near: an outcrop of stone in every province, a
     * vein of iron in the mountains and where the province has one, a vein of gold in the deserts and the gold
     * provinces.
     */
    public static void planDeposits(StrategyData d, long seed) {
        java.util.Random r = new java.util.Random(seed ^ 0xDE9051L);
        for (org.webtrade.strategy1.game.Province p : d.provinces.values()) {
            if (!p.terrain.land()) continue;
            List<StructureType> kinds = new ArrayList<>();
            kinds.add(StructureType.DEPOSIT_STONE);
            if (p.deposit == org.webtrade.strategy1.hex.Deposit.QUARRY) kinds.add(StructureType.DEPOSIT_STONE);
            if (p.deposit == org.webtrade.strategy1.hex.Deposit.IRON_VEIN || p.terrain == org.webtrade.strategy1.hex.Terrain.MOUNTAINS
                    || p.terrain == org.webtrade.strategy1.hex.Terrain.SNOW && r.nextBoolean()) kinds.add(StructureType.DEPOSIT_IRON);
            if (p.deposit == org.webtrade.strategy1.hex.Deposit.GOLD_VEIN || p.terrain == org.webtrade.strategy1.hex.Terrain.DESERT && r.nextBoolean()
                    || p.terrain == org.webtrade.strategy1.hex.Terrain.MOUNTAINS && r.nextFloat() < 0.25F)
                kinds.add(StructureType.DEPOSIT_GOLD);
            for (StructureType k : kinds) {
                double a = r.nextDouble() * Math.PI * 2, dist = 18 + r.nextDouble() * 18;
                BlockPos at = BlockPos.containing(p.hex.centerX() + Math.cos(a) * dist, 64, p.hex.centerZ() + Math.sin(a) * dist);
                Structure s = new Structure(d.nextStructure++, k, Faction.NEUTRAL, at, null, 0);
                s.planned = true;
                d.structures.put(s.id, s);
            }
        }
        d.deposits = true;
        d.touch();
    }

    /** A province taken: what stands in it is the taker's now (with nobody on its walls). */
    public static void captured(long province, int taker) {
        for (Structure s : data().structures.values()) {
            if (s.province() != province || s.type.deposit()) continue;
            s.owner = taker;
            s.archers = 0;
            dismiss(s.id);
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ the AI's forts

    /** Every AI capital gets a town round its keep: houses, a farm, barracks (and a market, trading). */
    public static void planTowns(StrategyData d) {
        for (Faction f : d.factions.values()) {
            if (f.player() || !f.alive) continue;
            Province cap = d.province(f.capital);
            if (cap == null) continue;
            // the town round the keep, inside its walls
            List<Object[]> town = new ArrayList<>(List.of(
                    new Object[]{StructureType.HOUSE, -8, -6}, new Object[]{StructureType.HOUSE, 8, -6},
                    new Object[]{StructureType.HOUSE, -7, 17}, new Object[]{StructureType.HOUSE, 7, 17},
                    new Object[]{StructureType.FARM, -15, -8}, new Object[]{StructureType.BARRACKS, 16, -8}));
            if (d.mode == org.webtrade.strategy1.game.Mode.TRADE) town.add(new Object[]{StructureType.MARKET, 0, 20});
            for (Object[] o : town) {
                Structure b = new Structure(d.nextStructure++, (StructureType) o[0], f.id,
                        BlockPos.containing(cap.hex.centerX() + (int) o[1], 64, cap.hex.centerZ() + (int) o[2]), null, 0);
                b.planned = true;
                d.structures.put(b.id, b);
            }
        }
        d.towns = true;
        d.touch();
    }

    /** Every AI capital gets towers and a ring of walls with gates (put up when its land is first seen). */
    public static void planForts(StrategyData d) {
        for (Faction f : d.factions.values()) {
            if (f.player() || !f.alive) continue;
            Province cap = d.province(f.capital);
            if (cap == null) continue;
            planKeep(d, f);
            boolean stone = f.age >= 2;
            double cx = cap.hex.centerX(), cz = cap.hex.centerZ();
            for (int i = 0; i < 3; i++) {
                double a = Math.toRadians(30 + i * 120);
                BlockPos p = BlockPos.containing(cx + Math.cos(a) * 15, 64, cz + Math.sin(a) * 15);
                Structure s = new Structure(d.nextStructure++, stone ? StructureType.TOWER_STONE : StructureType.TOWER_WOOD, f.id, p, p, 0);
                s.planned = true;
                s.archers = stone ? 4 : 2;
                d.structures.put(s.id, s);
            }
            double r = 25;
            for (int i = 0; i < 6; i++) {
                double a0 = Math.toRadians(i * 60), a1 = Math.toRadians(i * 60 + 60);
                double x0 = cx + Math.cos(a0) * r, z0 = cz + Math.sin(a0) * r, x1 = cx + Math.cos(a1) * r, z1 = cz + Math.sin(a1) * r;
                // two lengths of wall a side, a gate between them
                for (double[] seg : new double[][]{{0.04, 0.38}, {0.62, 0.96}}) {
                    BlockPos p = BlockPos.containing(x0 + (x1 - x0) * seg[0], 64, z0 + (z1 - z0) * seg[0]);
                    BlockPos q = BlockPos.containing(x0 + (x1 - x0) * seg[1], 64, z0 + (z1 - z0) * seg[1]);
                    Structure s = new Structure(d.nextStructure++, stone ? StructureType.WALL_STONE : StructureType.WALL_WOOD, f.id, p, q, 0);
                    s.planned = true;
                    s.archers = 1;
                    d.structures.put(s.id, s);
                }
            }
        }
        d.forts = true;
        d.touch();
    }
}
