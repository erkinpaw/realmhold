package org.webtrade.strategy1.game;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the strategic game on the server: the seasons (each turn the land yields and the AI kingdoms think), the
 * taking of provinces (stand at a province's centre with nobody left to defend it), the garrisons that come out to
 * fight whoever comes against them, the AI's raiding parties, and what the players are told and shown.
 */
public final class StrategyManager {

    /** The colour of the bandits who hold the free land. */
    public static final int BANDIT_COLOR = 0x555555;
    /** At most this many of a garrison walk out to fight at once. */
    private static final int MUSTER_MAX = 8;
    /** A player hostile to a province's holder this near its centre brings its garrison out. */
    private static final double THREAT_RADIUS = 72;

    private static MinecraftServer server;
    private static StrategyData data;
    private static long ticks;
    private static boolean mapDirty;
    private static long lastSync;

    /** Taking of a province: who, how far along. */
    static final class Capture {
        int by;
        int progress;
        boolean advanced;
    }

    /** A garrison come out to fight. */
    static final class Muster {
        final List<SoldierEntity> soldiers = new ArrayList<>();
        int idle;
        int reinforce;
    }

    /** An AI army marching on a player's province. */
    static final class Raid {
        final int attacker;
        final long target;
        final List<SoldierEntity> soldiers = new ArrayList<>();
        int ticks;

        Raid(int attacker, long target) {
            this.attacker = attacker;
            this.target = target;
        }
    }

    private static final Map<Long, Capture> CAPTURES = new HashMap<>();
    private static final Map<Long, Muster> MUSTERS = new HashMap<>();
    private static final List<Raid> RAIDS = new ArrayList<>();
    private static final Map<UUID, Long> WHERE = new HashMap<>();

    private StrategyManager() {
    }

    public static StrategyData data() {
        return data;
    }

    public static MinecraftServer server() {
        return server;
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            data = StrategyData.get(s);
            ticks = 0;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            server = null;
            data = null;
            CAPTURES.clear();
            MUSTERS.clear();
            RAIDS.clear();
            WHERE.clear();
            org.webtrade.strategy1.build.Structures.clear();
            org.webtrade.strategy1.build.Workforce.clear();
            Waves.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(StrategyManager::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            mapChanged();
            org.webtrade.strategy1.build.Structures.changed();
        });
    }

    public static void mapChanged() {
        mapDirty = true;
    }

    // ------------------------------------------------------------------ the tick

    private static void tick(MinecraftServer s) {
        if (data == null || server != s) return;
        ServerLevel level = s.overworld();
        if (!data.generated) MapGen.generate(level, data);
        if (!data.forts) org.webtrade.strategy1.build.Structures.planForts(data);
        if (!data.deposits) org.webtrade.strategy1.build.Structures.planDeposits(data, level.getSeed());
        ticks++;
        org.webtrade.strategy1.build.Structures.tick(level, ticks);
        if (ticks % 100 == 0) sweepPuppets(level);
        if (ticks % 20 == 0) {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) ensureRealm(p);
            second(level);
            org.webtrade.strategy1.build.Structures.second(level, ticks);
            org.webtrade.strategy1.build.Workforce.second(level);
            if (data.mode.waves) Waves.second(level, data);
        }
        if (ticks % Rules.TURN == 0) {
            Economy.turn(data);
            Ai.turn(data);
            data.turn++;
            data.touch();
        }
        if (mapDirty && ticks - lastSync >= 20) {
            mapDirty = false;
            lastSync = ticks;
            for (ServerPlayer p : s.getPlayerList().getPlayers()) sendMap(p);
        }
    }

    /** One of the night's dead stands at a province's centre: the dead take it, bit by bit, while nobody defends it. */
    static void deadAt(ServerLevel level, Province p) {
        Capture c = CAPTURES.computeIfAbsent(p.key(), k -> new Capture());
        if (c.by != Faction.NEUTRAL) {
            c.by = Faction.NEUTRAL;
            c.progress = 0;
        }
        if (c.advanced) return;
        Vec3 center = new Vec3(p.hex.centerX(), 64, p.hex.centerZ());
        AABB box = new AABB(center, center).inflate(24, 128, 24);
        boolean held = !level.getEntitiesOfClass(org.webtrade.strategy1.entity.UnitEntity.class, box,
                u -> u.isAlive() && u.faction() == p.owner && !(u instanceof org.webtrade.strategy1.entity.PeasantEntity)).isEmpty();
        for (org.webtrade.strategy1.build.Structure s : data.structures.values()) {
            if (s.owner == p.owner && s.province() == p.key() && s.built() && (s.type.tower() || s.type == org.webtrade.strategy1.build.StructureType.KEEP)) held = true;
        }
        if (held) return;
        c.advanced = true;
        c.progress += 2;
        if (c.progress >= Rules.CAPTURE) {
            CAPTURES.remove(p.key());
            lostToTheDead(level, p);
        }
    }

    /** A province falls to the dead: what stood in it is ruined, the land is no one's; a fallen capital is the end. */
    static void lostToTheDead(ServerLevel level, Province p) {
        Faction f = data.faction(p.owner);
        if (f == null) return;
        boolean capital = f.capital == p.key();
        for (org.webtrade.strategy1.build.Structure s : new ArrayList<>(data.structures.values())) {
            if (s.province() == p.key() && !s.type.deposit() && !s.planned && level.hasChunkAt(s.origin)) org.webtrade.strategy1.build.Structures.destroy(level, s);
            else if (s.province() == p.key() && !s.type.deposit()) data.structures.remove(s.id);
        }
        p.owner = Faction.NEUTRAL;
        p.garrison = 2;
        dismissMuster(p.key());
        announce(Component.translatable("strategy1.wave.lost", p.name).withStyle(ChatFormatting.DARK_RED), true);
        if (capital) {
            List<Province> left = data.owned(f.id);
            if (left.isEmpty()) {
                f.alive = false;
                announce(Component.translatable("strategy1.wave.fallen", f.name(), data.wave).withStyle(ChatFormatting.DARK_RED), true);
            } else f.capital = left.get(0).key();
        }
        data.touch();
    }

    /** Ends the turn now (operators). */
    public static void forceTurn() {
        if (data == null) return;
        Economy.turn(data);
        Ai.turn(data);
        data.turn++;
        data.touch();
    }

    /** Display entities of machines' models whose machine is gone (left from before a restart). */
    private static void sweepPuppets(ServerLevel level) {
        List<net.minecraft.world.entity.Entity> gone = new ArrayList<>();
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (!(e instanceof net.minecraft.world.entity.Display) || !e.entityTags().contains(org.webtrade.strategy1.entity.Puppet.TAG)) continue;
            UUID owner = null;
            for (String t : e.entityTags()) {
                if (t.startsWith("s1o:")) {
                    try {
                        owner = UUID.fromString(t.substring(4));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            net.minecraft.world.entity.Entity o = owner == null ? null : level.getEntity(owner);
            boolean mine = o instanceof org.webtrade.strategy1.entity.SiegeEntity sg && sg.owns(e)
                    || o instanceof org.webtrade.strategy1.entity.ShipEntity sh && sh.owns(e);
            if (!mine) gone.add(e);
        }
        for (net.minecraft.world.entity.Entity e : gone) e.discard();
    }

    private static void ensureRealm(ServerPlayer p) {
        Faction mine = data.factionOf(p.getUUID());
        if (mine != null && !mine.boosted) {
            // a stock of everything, to try every building and machine out
            mine.boosted = true;
            mine.gain(Res.of(Rules.TEST_STOCK, Rules.TEST_STOCK, Rules.TEST_STOCK, Rules.TEST_STOCK, Rules.TEST_STOCK));
            p.sendSystemMessage(Component.translatable("strategy1.msg.stock", Rules.TEST_STOCK).withStyle(ChatFormatting.AQUA));
            data.touch();
        }
        if (mine != null) {
            org.webtrade.strategy1.build.Structures.planKeep(data, mine);
            return;
        }
        if (data.coop) {
            for (Faction f : data.factions.values()) {
                if (f.player() && f.alive) {
                    data.players.put(p.getUUID(), f.id);
                    data.touch();
                    p.sendSystemMessage(Component.translatable("strategy1.msg.joined", f.name()).withStyle(ChatFormatting.GOLD));
                    return;
                }
            }
        }
        Faction f = MapGen.foundPlayerRealm(data, p.getUUID(), p.getGameProfile().name(), p.getX(), p.getZ());
        if (f == null) return;
        Province cap = data.province(f.capital);
        p.sendSystemMessage(Component.translatable("strategy1.msg.founded", f.name(), cap.name,
                (int) cap.hex.centerX(), (int) cap.hex.centerZ()).withStyle(ChatFormatting.GOLD));
        p.sendSystemMessage(Component.translatable("strategy1.msg.help").withStyle(ChatFormatting.GRAY));
    }

    /** The players' soldiers in the loaded world (each puts itself here as it ticks). */
    private static final Set<org.webtrade.strategy1.entity.UnitEntity> ARMY = new HashSet<>();

    public static void armyTick(org.webtrade.strategy1.entity.UnitEntity s) {
        ARMY.add(s);
    }

    /** Once a second: the takings, the garrisons, the raids, the HUD. */
    private static void second(ServerLevel level) {
        for (Capture c : CAPTURES.values()) c.advanced = false;
        Set<Long> threatened = new HashSet<>();
        for (ServerPlayer p : level.players()) tellWhere(p, data.province(Hex.at(p.getX(), p.getZ())));
        // the armies in the field: garrisons come out against them, and they take what they stand on
        ARMY.removeIf(s -> s.isRemoved() || !s.isAlive());
        Map<Long, Map<Integer, Integer>> standing = new HashMap<>();
        for (org.webtrade.strategy1.entity.UnitEntity s : ARMY) {
            if (s.level() != level || !(s instanceof SoldierEntity) || s.isPassenger()) continue;
            int f = s.faction();
            Hex at = Hex.at(s.getX(), s.getZ());
            for (int i = -1; i < 6; i++) {
                Province q = data.province(i < 0 ? at : at.neighbor(i));
                if (q != null && q.terrain.land() && q.garrison > 0 && data.hostile(f, q.owner)
                        && q.hex.distToCenter(s.getX(), s.getZ()) < THREAT_RADIUS) threatened.add(q.key());
            }
            Province here = data.province(at);
            if (here != null && here.hex.distToCenter(s.getX(), s.getZ()) <= Rules.CAPTURE_RADIUS) {
                standing.computeIfAbsent(here.key(), k -> new HashMap<>()).merge(f, 1, Integer::sum);
            }
        }
        standing.forEach((key, by) -> by.forEach((f, n) -> armyCapture(level, data.province(key), f, n)));
        for (Raid r : RAIDS) threatened.add(r.target);
        musters(level, threatened);
        raids(level);
        for (Iterator<Map.Entry<Long, Capture>> it = CAPTURES.entrySet().iterator(); it.hasNext(); ) {
            Capture c = it.next().getValue();
            if (!c.advanced && --c.progress <= 0) it.remove();
        }
        for (ServerPlayer p : level.players()) sendHud(p);
    }

    private static void tellWhere(ServerPlayer p, Province here) {
        long key = here == null ? Long.MIN_VALUE : here.key();
        Long was = WHERE.put(p.getUUID(), key);
        if (was != null && was == key || here == null || p.isSpectator()) return;
        Faction owner = data.faction(here.owner);
        MutableComponent who = owner == null || !owner.alive
                ? (here.terrain.land() ? Component.translatable("strategy1.owner.free").withColor(0xB0B0B0) : Component.translatable("strategy1.owner.sea").withColor(0x7FA8D0))
                : owner.name();
        p.sendOverlayMessage(Component.literal(here.name + " · ").append(who).append(" · ")
                .append(Component.translatable(here.terrain.key())));
    }

    // ------------------------------------------------------------------ taking provinces

    /** A realm's soldiers stand in the circle at a province's centre: with nobody left to defend it, it is theirs bit by bit. */
    private static void armyCapture(ServerLevel level, Province here, int f, int soldiers) {
        if (here == null || !here.terrain.land() || here.owner == f || !data.hostile(f, here.owner)) return;
        if (!data.borders(f, here.hex) && !data.owned(f).isEmpty()) return;
        if (contested(level, here, f)) return;
        Capture c = CAPTURES.computeIfAbsent(here.key(), k -> new Capture());
        if (c.by != f) {
            c.by = f;
            c.progress = 0;
        }
        if (c.advanced) return;
        c.advanced = true;
        c.progress += 1 + Math.min(3, soldiers / 2);
        if (c.progress >= Rules.CAPTURE) {
            CAPTURES.remove(here.key());
            capture(here, f, 1);
        }
    }

    /** Is someone still defending the province against the taker (its garrison, or its holder's soldiers or people)? */
    static boolean contested(ServerLevel level, Province p, int taker) {
        Muster m = MUSTERS.get(p.key());
        if (m != null) for (SoldierEntity s : m.soldiers) if (s.isAlive()) return true;
        if (p.garrison > 0 && m == null) return true;
        Vec3 c = new Vec3(p.hex.centerX(), 64, p.hex.centerZ());
        AABB box = new AABB(c, c).inflate(Rules.CONTEST_RADIUS, 256, Rules.CONTEST_RADIUS);
        if (!level.getEntitiesOfClass(SoldierEntity.class, box, s -> s.isAlive() && s.faction() == p.owner && data.hostile(s.faction(), taker)).isEmpty())
            return true;
        for (ServerPlayer pl : level.players()) {
            if (!pl.isAlive() || pl.isSpectator() || pl.isCreative()) continue;
            if (data.factionIdOf(pl.getUUID()) == p.owner && p.hex.distToCenter(pl.getX(), pl.getZ()) < 24) return true;
        }
        return false;
    }

    /** A province changes hands: the taker loots the treasury, the buildings suffer, a fallen capital moves or the realm falls. */
    public static void capture(Province p, int taker, int garrison) {
        Faction loser = data.faction(p.owner), winner = data.faction(taker);
        if (winner == null) return;
        boolean wasCapital = loser != null && loser.capital == p.key();
        int[] loot = new int[Res.COUNT];
        if (loser != null) {
            double share = wasCapital ? 0.35 : 0.12;
            for (int i = 0; i < Res.COUNT; i++) {
                loot[i] = (int) (loser.res[i] * share);
                loser.res[i] -= loot[i];
            }
        } else {
            loot = Res.of(10, 10, 5, 2, 2);
        }
        winner.gain(loot);
        p.owner = taker;
        p.garrison = garrison;
        p.eco = Math.max(0, p.eco - 1);
        p.fort = Math.max(0, p.fort - 1);
        dismissMuster(p.key());
        org.webtrade.strategy1.build.Structures.captured(p.key(), taker);
        MutableComponent what = Component.translatable("strategy1.msg.captured", winner.name(), p.name,
                loser == null ? Component.translatable("strategy1.owner.bandits") : loser.name());
        if (loser != null && loser.alive) {
            List<Province> left = data.owned(loser.id);
            if (left.isEmpty()) {
                loser.alive = false;
                for (Integer e : new ArrayList<>(loser.wars)) makePeace(loser.id, e, false);
                announce(Component.translatable("strategy1.msg.fallen", loser.name()).withStyle(ChatFormatting.DARK_RED), true);
            } else if (wasCapital) {
                Province next = left.get(0);
                for (Province q : left) if (q.eco + q.fort > next.eco + next.fort) next = q;
                loser.capital = next.key();
                announce(Component.translatable("strategy1.msg.capital_fell", loser.name(), p.name, next.name).withStyle(ChatFormatting.RED), true);
            }
        }
        // everyone hears of conquests that touch a player; AI quarrels only go to the log line
        boolean touchesPlayer = winner.player() || loser != null && loser.player();
        announce(what.withStyle(touchesPlayer ? ChatFormatting.YELLOW : ChatFormatting.GRAY), false);
        if (winner.player()) {
            ServerPlayer ruler = server.getPlayerList().getPlayer(winner.ruler);
            if (ruler != null) {
                title(ruler, Component.translatable("strategy1.title.captured").withStyle(ChatFormatting.GOLD), Component.literal(p.name));
                ruler.level().playSound(null, ruler.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.7F, 1F);
                ruler.sendSystemMessage(Component.translatable("strategy1.msg.loot", loot[0], loot[1], loot[2], loot[3], loot[4]).withStyle(ChatFormatting.GREEN));
            }
        }
        if (loser != null && loser.player()) {
            ServerPlayer ruler = server.getPlayerList().getPlayer(loser.ruler);
            if (ruler != null) title(ruler, Component.translatable("strategy1.title.lost").withStyle(ChatFormatting.RED), Component.literal(p.name));
        }
        data.touch();
    }

    // ------------------------------------------------------------------ war and peace

    public static void declareWar(int a, int b) {
        Faction fa = data.faction(a), fb = data.faction(b);
        if (fa == null || fb == null || a == b || fa.wars.contains(b)) return;
        fa.wars.add(b);
        fb.wars.add(a);
        fa.warSince.put(b, data.turn);
        fb.warSince.put(a, data.turn);
        boolean player = fa.player() || fb.player();
        announce(Component.translatable("strategy1.msg.war", fa.name(), fb.name()).withStyle(player ? ChatFormatting.RED : ChatFormatting.GRAY), false);
        if (fb.player()) {
            ServerPlayer ruler = server.getPlayerList().getPlayer(fb.ruler);
            if (ruler != null) {
                title(ruler, Component.translatable("strategy1.title.war").withStyle(ChatFormatting.DARK_RED), fa.name());
                ruler.level().playSound(null, ruler.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1F, 1F);
            }
        }
        data.touch();
    }

    public static void makePeace(int a, int b, boolean tell) {
        Faction fa = data.faction(a), fb = data.faction(b);
        if (fa == null || fb == null) return;
        fa.wars.remove(b);
        fb.wars.remove(a);
        fa.truceUntil.put(b, data.turn + Rules.TRUCE);
        fb.truceUntil.put(a, data.turn + Rules.TRUCE);
        if (tell) {
            boolean player = fa.player() || fb.player();
            announce(Component.translatable("strategy1.msg.peace", fa.name(), fb.name()).withStyle(player ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
        }
        // raids between them are called off
        for (Iterator<Raid> it = RAIDS.iterator(); it.hasNext(); ) {
            Raid r = it.next();
            Province t = data.province(r.target);
            if (t != null && (r.attacker == a && t.owner == b || r.attacker == b && t.owner == a)) {
                retreat(r);
                it.remove();
            }
        }
        data.touch();
    }

    // ------------------------------------------------------------------ garrisons

    private static void musters(ServerLevel level, Set<Long> threatened) {
        for (long key : threatened) {
            if (MUSTERS.containsKey(key)) continue;
            Province p = data.province(key);
            if (p == null || p.garrison <= 0) continue;
            BlockPos c = BlockPos.containing(p.hex.centerX(), 64, p.hex.centerZ());
            if (!level.hasChunkAt(c)) continue;
            Muster m = new Muster();
            MUSTERS.put(key, m);
            int n = Math.min(MUSTER_MAX, p.garrison);
            for (int i = 0; i < n; i++) spawnGuard(level, p, m, i);
        }
        for (Iterator<Map.Entry<Long, Muster>> it = MUSTERS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Muster> e = it.next();
            Muster m = e.getValue();
            m.soldiers.removeIf(s -> s.isRemoved() || !s.isAlive());
            Province p = data.province(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            if (threatened.contains(e.getKey())) m.idle = 0;
            else m.idle++;
            if (m.idle > 30) {
                for (SoldierEntity s : m.soldiers) s.discard();
                it.remove();
                continue;
            }
            // the rest of a large garrison comes out as the first fall
            if (m.soldiers.size() < Math.min(MUSTER_MAX, p.garrison) && ++m.reinforce >= 4) {
                m.reinforce = 0;
                spawnGuard(level, p, m, m.soldiers.size() + level.getRandom().nextInt(3));
            }
        }
    }

    private static void spawnGuard(ServerLevel level, Province p, Muster m, int index) {
        Faction f = data.faction(p.owner);
        int age = f == null ? 1 : org.webtrade.strategy1.build.Structures.tier(f);
        UnitType unit = UnitType.garrisonUnit(age, index);
        if (f == null && unit != UnitType.ARCHER) unit = UnitType.MILITIA;
        double a = level.getRandom().nextDouble() * Math.PI * 2, r = 3 + level.getRandom().nextDouble() * 5;
        Vec3 at = new Vec3(p.hex.centerX() + Math.cos(a) * r, 0, p.hex.centerZ() + Math.sin(a) * r);
        SoldierEntity s = SoldierEntity.spawn(level, unit, p.owner, f == null ? BANDIT_COLOR : f.color, at, SoldierEntity.Role.GARRISON);
        if (s == null) return;
        s.mode = SoldierEntity.Mode.GUARD;
        s.anchor = SoldierEntity.surface(level, p.hex.centerX(), p.hex.centerZ());
        s.province = p.key();
        m.soldiers.add(s);
    }

    private static void dismissMuster(long key) {
        Muster m = MUSTERS.remove(key);
        if (m != null) for (SoldierEntity s : m.soldiers) s.discard();
    }

    /** Is a garrison's or a raid's soldier still part of what it came out for? */
    public static boolean stillWanted(SoldierEntity s) {
        if (data == null) return false;
        if (s.role == SoldierEntity.Role.POST) return org.webtrade.strategy1.build.Structures.posted(s);
        if (s.role == SoldierEntity.Role.GARRISON) {
            Muster m = MUSTERS.get(s.province);
            return m != null && m.soldiers.contains(s);
        }
        for (Raid r : RAIDS) if (r.soldiers.contains(s)) return true;
        return false;
    }

    public static void unitDied(org.webtrade.strategy1.entity.UnitEntity u, DamageSource source) {
        if (data == null) return;
        Faction f = data.faction(u.faction());
        if (u instanceof org.webtrade.strategy1.entity.SiegeEntity) {
            if (f != null && f.siege > 0) f.siege--;
            data.touch();
            return;
        }
        if (u instanceof org.webtrade.strategy1.entity.PeasantEntity) {
            if (f != null && f.peasants > 0) f.peasants--;
            data.touch();
            return;
        }
        if (u instanceof org.webtrade.strategy1.entity.ShipEntity) {
            if (f != null && f.ships > 0) f.ships--;
            data.touch();
            return;
        }
        if (!(u instanceof SoldierEntity s)) return;
        switch (s.role) {
            case GARRISON -> {
                Province p = data.province(s.province);
                if (p != null && p.garrison > 0) {
                    p.garrison--;
                    data.touch();
                }
            }
            case POST -> org.webtrade.strategy1.build.Structures.postDied(s);
            case ARMY -> {
                if (f != null && f.army > 0) {
                    f.army--;
                    data.touch();
                }
                ServerPlayer c = s.commanderPlayer();
                if (c != null) c.sendSystemMessage(Component.translatable("strategy1.msg.soldier_fell", Component.translatable(s.unit().key()))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ structures (for the units)

    public static void strikeStructure(org.webtrade.strategy1.entity.UnitEntity by, int id, float damage) {
        org.webtrade.strategy1.build.Structures.strike(by, id, damage);
    }

    public static Vec3 structurePoint(int id, Vec3 from) {
        return org.webtrade.strategy1.build.Structures.point(id, from);
    }

    public static boolean man(SoldierEntity s, int id) {
        return org.webtrade.strategy1.build.Structures.man(s, id);
    }

    public static Vec3 structureAim(int id, int attacker) {
        return org.webtrade.strategy1.build.Structures.aim(id, attacker);
    }

    public static Vec3 nearestEnemyStructure(int attacker, Vec3 from, double min, double max) {
        return org.webtrade.strategy1.build.Structures.nearestEnemy(attacker, from, min, max);
    }

    public static void impact(ServerLevel level, Vec3 at, int attacker, float damage, float radius) {
        org.webtrade.strategy1.build.Structures.impact(level, at, attacker, damage, radius);
    }

    // ------------------------------------------------------------------ raids

    /**
     * An AI army marches on a player's province, if one of its people is near enough to see it come; false if nobody
     * is (the battle is then fought on paper).
     */
    public static boolean launchRaid(Faction attacker, Province target, int force) {
        ServerLevel level = server.overworld();
        ServerPlayer witness = null;
        for (ServerPlayer p : level.players()) {
            if (data.factionIdOf(p.getUUID()) == target.owner && target.hex.distToCenter(p.getX(), p.getZ()) < 120) witness = p;
        }
        if (witness == null) return false;
        // they come from the attacker's side
        Province from = null;
        for (Hex n : target.hex.neighbors()) {
            Province q = data.province(n);
            if (q != null && q.owner == attacker.id) from = q;
        }
        double dx = 1, dz = 0;
        if (from != null) {
            dx = from.hex.centerX() - target.hex.centerX();
            dz = from.hex.centerZ() - target.hex.centerZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            dx /= d;
            dz /= d;
        }
        double sx = target.hex.centerX() + dx * 38, sz = target.hex.centerZ() + dz * 38;
        if (!level.hasChunkAt(BlockPos.containing(sx, 64, sz))) return false;
        Raid raid = new Raid(attacker.id, target.key());
        int n = Math.min(10, force);
        Vec3 goal = SoldierEntity.surface(level, target.hex.centerX(), target.hex.centerZ());
        for (int i = 0; i < n; i++) {
            Vec3 at = new Vec3(sx + level.getRandom().nextGaussian() * 3, 0, sz + level.getRandom().nextGaussian() * 3);
            SoldierEntity s = SoldierEntity.spawn(level, UnitType.garrisonUnit(attacker.age, i), attacker.id, attacker.color, at, SoldierEntity.Role.RAID);
            if (s == null) continue;
            s.mode = SoldierEntity.Mode.RAID;
            s.anchor = goal;
            s.province = target.key();
            raid.soldiers.add(s);
        }
        RAIDS.add(raid);
        for (ServerPlayer p : level.players()) {
            if (data.factionIdOf(p.getUUID()) != target.owner) continue;
            title(p, Component.translatable("strategy1.title.raid").withStyle(ChatFormatting.DARK_RED), Component.literal(target.name));
            p.sendSystemMessage(Component.translatable("strategy1.msg.raid", attacker.name(), n, target.name, (int) sx, (int) sz)
                    .withStyle(ChatFormatting.RED));
            p.level().playSound(null, p.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1F, 1F);
        }
        return true;
    }

    private static void raids(ServerLevel level) {
        for (Iterator<Raid> it = RAIDS.iterator(); it.hasNext(); ) {
            Raid r = it.next();
            r.soldiers.removeIf(s -> s.isRemoved() || !s.isAlive());
            Province p = data.province(r.target);
            Faction attacker = data.faction(r.attacker);
            if (p == null || attacker == null || !attacker.alive || p.owner == r.attacker || !data.hostile(r.attacker, p.owner)) {
                retreat(r);
                it.remove();
                continue;
            }
            if (r.soldiers.isEmpty()) {
                for (ServerPlayer pl : level.players()) {
                    if (data.factionIdOf(pl.getUUID()) == p.owner) {
                        pl.sendSystemMessage(Component.translatable("strategy1.msg.raid_beaten", attacker.name(), p.name).withStyle(ChatFormatting.GREEN));
                    }
                }
                it.remove();
                continue;
            }
            r.ticks += 20;
            if (r.ticks > 20 * 60 * 5) {
                retreat(r);
                it.remove();
                continue;
            }
            int there = 0;
            for (SoldierEntity s : r.soldiers) if (p.hex.distToCenter(s.getX(), s.getZ()) < 9) there++;
            if (there == 0 || contested(level, p, r.attacker)) continue;
            Capture c = CAPTURES.computeIfAbsent(p.key(), k -> new Capture());
            if (c.by != r.attacker) {
                c.by = r.attacker;
                c.progress = 0;
            }
            c.advanced = true;
            c.progress += Math.min(3, there);
            if (c.progress >= Rules.CAPTURE) {
                CAPTURES.remove(p.key());
                int alive = r.soldiers.size();
                for (SoldierEntity s : r.soldiers) s.discard();
                it.remove();
                capture(p, r.attacker, Math.min(alive, p.garrisonCap(false)));
            }
        }
    }

    /** A raid called off: who is left goes home to the reserve. */
    private static void retreat(Raid r) {
        Faction f = data.faction(r.attacker);
        int alive = 0;
        for (SoldierEntity s : r.soldiers) {
            if (s.isAlive() && !s.isRemoved()) alive++;
            s.discard();
        }
        if (f != null) f.reserve += alive;
        r.soldiers.clear();
    }

    /** Is a raid already on its way to this province? */
    static boolean raided(long province) {
        for (Raid r : RAIDS) if (r.target == province) return true;
        return false;
    }

    // ------------------------------------------------------------------ telling the players

    public static void announce(Component c, boolean all) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(c);
    }

    public static void tell(Faction f, Component c) {
        if (server == null || f == null || !f.player()) return;
        ServerPlayer p = server.getPlayerList().getPlayer(f.ruler);
        if (p != null) p.sendSystemMessage(c);
    }

    public static void title(ServerPlayer p, Component title, Component subtitle) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 15));
        p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    public static void sendMap(ServerPlayer p) {
        if (data == null || !ServerPlayNetworking.canSend(p, Payloads.MapSync.TYPE)) return;
        int me = data.factionIdOf(p.getUUID());
        List<Payloads.FactionInfo> fs = new ArrayList<>();
        Faction mine = data.faction(me);
        for (Faction f : data.factions.values()) {
            int truce = mine == null ? 0 : mine.truceUntil.getOrDefault(f.id, 0);
            fs.add(new Payloads.FactionInfo(f.id, f.nameIndex, f.playerName, f.color, f.age, f.capital, f.player(), f.alive, f.res.clone(),
                    f.reserve, f.army, f.wars.stream().mapToInt(Integer::intValue).toArray(), truce, f.peasants));
        }
        List<Payloads.ProvinceInfo> ps = new ArrayList<>();
        for (Province q : data.provinces.values()) {
            ps.add(new Payloads.ProvinceInfo(q.key(), q.name, q.terrain.ordinal(), q.deposit.ordinal(), q.owner, q.eco, q.fort, q.garrison));
        }
        ServerPlayNetworking.send(p, new Payloads.MapSync(me, data.turn, fs, ps));
    }

    private static void sendHud(ServerPlayer p) {
        if (!ServerPlayNetworking.canSend(p, Payloads.Hud.TYPE)) return;
        Faction f = data.factionOf(p.getUUID());
        int n = CAPTURES.size(), i = 0;
        long[] keys = new long[n];
        int[] by = new int[n], progress = new int[n];
        for (Map.Entry<Long, Capture> e : CAPTURES.entrySet()) {
            keys[i] = e.getKey();
            by[i] = e.getValue().by;
            progress[i++] = e.getValue().progress;
        }
        int turnLeft = (int) ((Rules.TURN - ticks % Rules.TURN) / 20);
        int people = f == null ? 0 : org.webtrade.strategy1.build.Workforce.people(f), room = f == null ? 0 : org.webtrade.strategy1.build.Workforce.room(f.id);
        int idle = f == null ? 0 : org.webtrade.strategy1.build.Workforce.idle(f.id).size();
        ServerPlayNetworking.send(p, new Payloads.Hud(turnLeft, f == null ? 0 : f.army, room, f == null ? 0 : f.peasants, idle, keys, by, progress,
                data.mode.ordinal(), data.wave, Waves.alive(), Waves.clock(server.overworld())));
    }

    // ------------------------------------------------------------------ the strategist's view

    /** The game modes players had before they became the strategist's eye (to give back after). */
    private static final Map<UUID, net.minecraft.world.level.GameType> BEFORE = new HashMap<>();

    /**
     * Into the strategist's view: the player's body is gone (a spectator: unseen, unhurt, through walls), the
     * camera goes where they look from; or back out of it, at the capital.
     */
    public static void setRts(ServerPlayer p, boolean on) {
        if (on) {
            if (p.gameMode() != net.minecraft.world.level.GameType.SPECTATOR) BEFORE.put(p.getUUID(), p.gameMode());
            p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            Faction f = data == null ? null : data.factionOf(p.getUUID());
            Province cap = f == null ? null : data.province(f.capital);
            if (cap != null && cap.hex.distToCenter(p.getX(), p.getZ()) > 400) {
                jump(p, cap.hex.centerX(), cap.hex.centerZ());
            }
        } else {
            net.minecraft.world.level.GameType back = BEFORE.remove(p.getUUID());
            p.setGameMode(back == null ? net.minecraft.world.level.GameType.SURVIVAL : back);
            ServerLevel level = (ServerLevel) p.level();
            Vec3 at = SoldierEntity.surface(level, p.getX(), p.getZ());
            p.teleportTo(level, at.x, at.y + 0.1, at.z, java.util.Set.of(), p.getYRot(), 0, false);
        }
    }

    /** Moves the strategist's eye far at once (over a province picked on the map). */
    public static void jump(ServerPlayer p, double x, double z) {
        if (!p.isSpectator()) return;
        ServerLevel level = (ServerLevel) p.level();
        double y = Math.max(level.getSeaLevel(), SoldierEntity.surface(level, x, z).y) + 24;
        p.teleportTo(level, x, y, z + 18, java.util.Set.of(), 180, 55, false);
    }
}
