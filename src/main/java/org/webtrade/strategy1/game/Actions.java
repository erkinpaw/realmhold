package org.webtrade.strategy1.game;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.entity.ShipEntity;
import org.webtrade.strategy1.entity.SiegeEntity;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import java.util.ArrayList;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What a ruler decides on the map (build, raise, war and peace, the treasury) and the orders to their soldiers. */
public final class Actions {

    /** Peace offers between players' realms: "a>b" offered. */
    private static final Set<Long> OFFERS = new HashSet<>();

    private Actions() {
    }

    private static void no(ServerPlayer p, String key, Object... args) {
        p.sendSystemMessage(Component.translatable(key, args).withStyle(ChatFormatting.RED));
    }

    private static void ok(ServerPlayer p, Component c) {
        p.sendSystemMessage(c.copy().withStyle(ChatFormatting.GREEN));
        p.level().playSound(null, p.blockPosition(), SoundEvents.VILLAGER_WORK_TOOLSMITH, SoundSource.PLAYERS, 0.6F, 1.1F);
    }

    public static void handle(ServerPlayer player, Payloads.Action a) {
        StrategyData data = StrategyManager.data();
        if (data == null) return;
        Faction f = data.factionOf(player.getUUID());
        if (f == null) return;
        if (!f.alive && a.action() != Payloads.Action.FOUND) {
            no(player, "strategy1.err.fallen");
            return;
        }
        Province p = data.province(a.province());
        switch (a.action()) {
            case Payloads.Action.ECO, Payloads.Action.FORT, Payloads.Action.AGE -> {
                return;
            }
            case -100 -> {
                if (!own(player, f, p)) return;
                if (p.eco >= Rules.maxBuilding(f.age)) {
                    no(player, "strategy1.err.max_level");
                    return;
                }
                if (!pay(player, f, Rules.ecoCost(p.eco))) return;
                p.eco++;
                ok(player, Component.translatable("strategy1.ok.eco", p.name, p.eco));
            }
            case Payloads.Action.GARRISON -> {
                if (!own(player, f, p)) return;
                if (p.garrison >= p.garrisonCap(f.capital == p.key())) {
                    no(player, "strategy1.err.garrison_full");
                    return;
                }
                if (!pay(player, f, Rules.GARRISON_COST)) return;
                p.garrison++;
                ok(player, Component.translatable("strategy1.ok.garrison", p.name, p.garrison));
            }
            case Payloads.Action.RECRUIT -> recruit(player, data, f, p, UnitType.of(a.arg()));
            case Payloads.Action.WAR -> {
                Faction o = data.faction(a.arg());
                if (o == null || !o.alive || o == f || f.wars.contains(o.id)) return;
                if (f.truceUntil.getOrDefault(o.id, -1) > data.turn) {
                    no(player, "strategy1.err.truce", f.truceUntil.get(o.id) - data.turn);
                    return;
                }
                StrategyManager.declareWar(f.id, o.id);
            }
            case Payloads.Action.PEACE -> peace(player, data, f, data.faction(a.arg()));
            case Payloads.Action.DONATE -> donate(player, f);
            case Payloads.Action.FOUND -> {
                if (f.alive && !data.owned(f.id).isEmpty()) return;
                Faction n = MapGen.foundPlayerRealm(StrategyManager.server().overworld(), data, player.getUUID(), player.getGameProfile().name(), player.getX(), player.getZ());
                if (n == null) no(player, "strategy1.err.no_land");
                else ok(player, Component.translatable("strategy1.ok.founded", data.province(n.capital).name));
            }
            case Payloads.Action.CAPITAL -> {
                if (!own(player, f, p) || f.capital == p.key()) return;
                if (!pay(player, f, Res.of(0, 50, 50, 0, 20))) return;
                f.capital = p.key();
                ok(player, Component.translatable("strategy1.ok.capital", p.name));
            }
            case Payloads.Action.DISMISS -> {
                List<SoldierEntity> mine = soldiers(player);
                for (SoldierEntity s : mine) {
                    s.discard();
                    f.army = Math.max(0, f.army - 1);
                }
                ok(player, Component.translatable("strategy1.ok.dismissed", mine.size()));
            }
            default -> {
                return;
            }
        }
        data.touch();
        StrategyManager.sendMap(player);
    }

    private static boolean own(ServerPlayer player, Faction f, Province p) {
        if (p == null || p.owner != f.id) {
            no(player, "strategy1.err.not_yours");
            return false;
        }
        return true;
    }

    private static boolean pay(ServerPlayer player, Faction f, int[] cost) {
        if (!f.canPay(cost)) {
            no(player, "strategy1.err.cost", cost[0], cost[1], cost[2], cost[3], cost[4]);
            return false;
        }
        f.pay(cost);
        return true;
    }

    /** Raises a soldier at the centre of a province of the realm's (the one under the strategist's eye, else the capital). */
    /**
     * Raises a soldier: at the barracks given (or the realm's barracks in the province under the strategist's eye,
     * else at that province's centre, else at the capital).
     */
    private static void recruit(ServerPlayer player, StrategyData data, Faction f, Province at, UnitType unit) {
        recruitAt(player, data, f, at, unit, null);
    }

    private static void recruitAt(ServerPlayer player, StrategyData data, Faction f, Province at, UnitType unit, Structure barracks) {
        if (!Structures.has(f.id, unit.requires)) {
            no(player, "strategy1.err.build_needs_one", Component.translatable(unit.requires.key()));
            return;
        }
        ServerLevel level = StrategyManager.server().overworld();
        if (at == null || at.owner != f.id) at = data.province(f.capital);
        if (at == null || at.owner != f.id) return;
        Vec3 spot;
        if (barracks == null) {
            barracks = Structures.nearest(f.id, StructureType.BARRACKS, new Vec3(at.hex.centerX(), 64, at.hex.centerZ()));
            if (barracks != null && barracks.province() != at.key()) barracks = null;
        }
        if (barracks != null) {
            Vec3 door = Structures.door(barracks);
            spot = new Vec3(door.x + Math.random() - 0.5, 0, door.z + Math.random() - 0.5);
        } else {
            double a = Math.random() * Math.PI * 2;
            spot = new Vec3(at.hex.centerX() + Math.cos(a) * 4, 0, at.hex.centerZ() + Math.sin(a) * 4);
        }
        if (!level.hasChunkAt(BlockPos.containing(spot.x, 64, spot.z))) {
            no(player, "strategy1.err.recruit_here");
            return;
        }
        // a soldier is one of the realm's peasants, armed
        org.webtrade.strategy1.entity.PeasantEntity recruit = org.webtrade.strategy1.build.Workforce.draft(f.id, spot);
        if (recruit == null) {
            no(player, "strategy1.err.no_peasants");
            return;
        }
        if (!pay(player, f, unit.cost)) return;
        recruit.discard();
        f.peasants = Math.max(0, f.peasants - 1);
        SoldierEntity s = SoldierEntity.spawn(level, unit, f.id, f.color, spot, SoldierEntity.Role.ARMY);
        if (s == null) return;
        s.commander = player.getUUID();
        s.mode = SoldierEntity.Mode.HOLD;
        f.army++;
        ok(player, Component.translatable("strategy1.ok.recruit", Component.translatable(unit.key()), f.army, f.peasants));
    }

    // ------------------------------------------------------------------ building

    public static void build(ServerPlayer player, Payloads.Build b) {
        StrategyData data = StrategyManager.data();
        Faction f = data == null ? null : data.factionOf(player.getUUID());
        if (f == null || !f.alive) return;
        StructureType type = StructureType.of(b.kind());
        String err = Structures.place(player, f, type, new BlockPos(b.x(), b.y(), b.z()), new BlockPos(b.x2(), b.y(), b.z2()), b.rot());
        if (err != null) {
            if (err.equals("strategy1.err.cost")) {
                int len = (int) Math.round(Math.hypot(b.x2() - b.x(), b.z2() - b.z())) + 1;
                int[] c = type.cost(len);
                no(player, err, c[0], c[1], c[2], c[3], c[4]);
            } else no(player, err, type.requires == null ? Component.empty() : Component.translatable(type.requires.key()));
            return;
        }
        ok(player, Component.translatable("strategy1.ok.building", Component.translatable(type.key())));
        StrategyManager.sendMap(player);
    }

    public static void structure(ServerPlayer player, Payloads.StructureAction a) {
        StrategyData data = StrategyManager.data();
        Faction f = data == null ? null : data.factionOf(player.getUUID());
        if (f == null || !f.alive) return;
        Structure st = data.structures.get(a.id());
        if (st == null || st.owner != f.id) return;
        ServerLevel level = StrategyManager.server().overworld();
        switch (a.action()) {
            case Payloads.StructureAction.UNMAN -> {
                Structures.unman(f, st.id);
            }
            case Payloads.StructureAction.DEMOLISH -> Structures.destroy(level, st);
            case Payloads.StructureAction.AIM -> {
                if (!Structures.aim(level, st.id, a.arg())) no(player, "strategy1.err.cant_aim");
            }
            case Payloads.StructureAction.SELL -> {
                if (st.type != StructureType.MARKET || !st.built() || !Market.sell(data, f, Res.of(a.arg()))) no(player, "strategy1.err.cant_trade");
            }
            case Payloads.StructureAction.BUY -> {
                if (st.type != StructureType.MARKET || !st.built() || !Market.buy(data, f, Res.of(a.arg()))) no(player, "strategy1.err.cant_trade");
            }
            case Payloads.StructureAction.CARAVAN -> {
                String err = Market.send(data, f, st, level.getGameTime());
                if (err != null) no(player, err, 20, 20, 0, 0, 0);
            }
            case Payloads.StructureAction.UPGRADE -> {
                if (!Structures.upgrade(f, st.id, a.arg())) {
                    int[] c = Structures.engineCost(a.arg());
                    no(player, "strategy1.err.cost", c[0], c[1], c[2], c[3], c[4]);
                } else ok(player, Component.translatable("strategy1.ok.engine", Component.translatable("strategy1.engine." + a.arg())));
            }
            case Payloads.StructureAction.MAKE -> {
                if (!st.built()) {
                    no(player, "strategy1.err.not_built");
                    return;
                }
                make(player, data, f, st, a.arg(), level);
            }
            default -> {
            }
        }
        data.touch();
        StrategyManager.sendMap(player);
    }

    private static void make(ServerPlayer player, StrategyData data, Faction f, Structure st, int what, ServerLevel level) {
        Vec3 door = Structures.door(st);
        if (!level.hasChunkAt(BlockPos.containing(door))) {
            no(player, "strategy1.err.recruit_here");
            return;
        }
        switch (st.type) {
            case BARRACKS -> {
                if (what >= 0 && what < UnitType.values().length) recruitAt(player, data, f, data.province(st.province()), UnitType.of(what), st);
            }
            case WORKSHOP -> {
                boolean treb = what == Payloads.StructureAction.TREBUCHET;
                if (f.siege >= Rules.MAX_SIEGE) {
                    no(player, "strategy1.err.siege_full", Rules.MAX_SIEGE);
                    return;
                }
                if (!pay(player, f, treb ? Rules.TREBUCHET_COST : Rules.CATAPULT_COST)) return;
                SiegeEntity.Kind kind = treb ? SiegeEntity.Kind.TREBUCHET : SiegeEntity.Kind.CATAPULT;
                if (SiegeEntity.spawn(level, kind, f.id, f.color, door, player.getUUID()) != null) {
                    f.siege++;
                    ok(player, Component.translatable("strategy1.ok.made", Component.translatable(kind.key())));
                }
            }
            case PORT -> {
                boolean war = what == Payloads.StructureAction.WARSHIP;
                if (f.ships >= Rules.MAX_SHIPS) {
                    no(player, "strategy1.err.ships_full", Rules.MAX_SHIPS);
                    return;
                }
                if (!pay(player, f, war ? Rules.WARSHIP_COST : Rules.TRANSPORT_COST)) return;
                ShipEntity.Kind kind = war ? ShipEntity.Kind.WARSHIP : ShipEntity.Kind.TRANSPORT;
                float yaw = (st.rot & 3) * 90F;
                if (ShipEntity.spawn(level, kind, f.id, f.color, door, yaw, player.getUUID()) != null) {
                    f.ships++;
                    ok(player, Component.translatable("strategy1.ok.made", Component.translatable(kind.key())));
                } else {
                    f.gain(war ? Rules.WARSHIP_COST : Rules.TRANSPORT_COST);
                    no(player, "strategy1.err.no_water");
                }
            }
            default -> {
            }
        }
    }

    private static void peace(ServerPlayer player, StrategyData data, Faction f, Faction o) {
        if (o == null || !f.wars.contains(o.id)) return;
        if (o.player()) {
            long mine = ((long) f.id << 32) | o.id, theirs = ((long) o.id << 32) | f.id;
            if (OFFERS.remove(theirs)) {
                StrategyManager.makePeace(f.id, o.id, true);
            } else {
                OFFERS.add(mine);
                ok(player, Component.translatable("strategy1.ok.offer_sent", o.name()));
                StrategyManager.tell(o, Component.translatable("strategy1.msg.offer", f.name()).withStyle(ChatFormatting.AQUA));
            }
            return;
        }
        int since = f.warSince.getOrDefault(o.id, data.turn);
        if (data.turn - since < 3) {
            no(player, "strategy1.err.peace_early", Component.empty().append(o.name()));
            return;
        }
        if (data.turn - o.refusedAt.getOrDefault(f.id, -100) < 2) {
            no(player, "strategy1.err.peace_wait", Component.empty().append(o.name()));
            return;
        }
        double ratio = data.strength(f) / Math.max(1, data.strength(o));
        double chance = Math.max(0.1, Math.min(0.9, 0.25 + (ratio - 1) * 0.5));
        if (Math.random() < chance) {
            StrategyManager.makePeace(f.id, o.id, true);
        } else {
            o.refusedAt.put(f.id, data.turn);
            no(player, "strategy1.err.peace_refused", Component.empty().append(o.name()));
        }
    }

    /** Puts the goods in the player's pack into the treasury. */
    private static void donate(ServerPlayer player, Faction f) {
        int[] got = new int[Res.COUNT];
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            int[] v = value(s);
            if (v == null) continue;
            got[v[0]] += v[1] * s.getCount();
            inv.setItem(i, ItemStack.EMPTY);
        }
        int sum = 0;
        for (int g : got) sum += g;
        if (sum == 0) {
            no(player, "strategy1.err.nothing_to_give");
            return;
        }
        f.gain(got);
        ok(player, Component.translatable("strategy1.ok.donated", got[0], got[1], got[2], got[3], got[4]));
    }

    /** What an item is worth to the treasury: {resource, amount}, or null. */
    private static int[] value(ItemStack s) {
        if (s.is(ItemTags.LOGS)) return new int[]{Res.WOOD.ordinal(), 2};
        if (s.is(ItemTags.PLANKS)) return new int[]{Res.WOOD.ordinal(), 1};
        if (s.is(Items.WHEAT) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT) || s.is(Items.APPLE)
                || s.is(Items.SWEET_BERRIES) || s.is(Items.MELON_SLICE)) return new int[]{Res.FOOD.ordinal(), 1};
        if (s.is(Items.BREAD) || s.is(Items.BAKED_POTATO) || s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_CHICKEN)
                || s.is(Items.COOKED_MUTTON) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON)) return new int[]{Res.FOOD.ordinal(), 3};
        if (s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.CHICKEN) || s.is(Items.MUTTON) || s.is(Items.COD) || s.is(Items.SALMON))
            return new int[]{Res.FOOD.ordinal(), 2};
        if (s.is(Items.HAY_BLOCK)) return new int[]{Res.FOOD.ordinal(), 9};
        if (s.is(Items.COBBLESTONE) || s.is(Items.STONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.DEEPSLATE) || s.is(Items.ANDESITE)
                || s.is(Items.DIORITE) || s.is(Items.GRANITE) || s.is(Items.TUFF)) return new int[]{Res.STONE.ordinal(), 1};
        if (s.is(Items.STONE_BRICKS)) return new int[]{Res.STONE.ordinal(), 2};
        if (s.is(Items.RAW_IRON)) return new int[]{Res.IRON.ordinal(), 2};
        if (s.is(Items.IRON_INGOT)) return new int[]{Res.IRON.ordinal(), 3};
        if (s.is(Items.IRON_BLOCK)) return new int[]{Res.IRON.ordinal(), 27};
        if (s.is(Items.RAW_GOLD)) return new int[]{Res.GOLD.ordinal(), 2};
        if (s.is(Items.GOLD_INGOT)) return new int[]{Res.GOLD.ordinal(), 3};
        if (s.is(Items.GOLD_BLOCK)) return new int[]{Res.GOLD.ordinal(), 27};
        if (s.is(Items.EMERALD)) return new int[]{Res.GOLD.ordinal(), 5};
        if (s.is(Items.DIAMOND)) return new int[]{Res.GOLD.ordinal(), 10};
        return null;
    }

    // ------------------------------------------------------------------ orders

    private static List<SoldierEntity> soldiers(ServerPlayer player) {
        return player.level().getEntitiesOfClass(SoldierEntity.class, player.getBoundingBox().inflate(256, 128, 256),
                s -> s.isAlive() && s.role == SoldierEntity.Role.ARMY && player.getUUID().equals(s.commander));
    }

    /**
     * Orders to the units the strategist chose. Soldiers: go, attack, hold, break a building, board a ship, man a
     * tower or a wall (archers). Siege engines: go, attack a unit or a building. Ships: sail (onto the land: to the
     * shore there, and put the soldiers ashore), attack (warships).
     */
    public static void order(ServerPlayer player, Payloads.UnitOrder o) {
        List<UnitEntity> mine = new ArrayList<>();
        for (int id : o.units()) {
            if (player.level().getEntity(id) instanceof UnitEntity u && u.isAlive() && u.role == UnitEntity.Role.ARMY
                    && u.faction() == StrategyManager.data().factionIdOf(player.getUUID())) mine.add(u);
        }
        if (mine.isEmpty()) return;
        ServerLevel level = (ServerLevel) player.level();
        Vec3 to = new Vec3(o.x(), o.y(), o.z());
        Entity target = o.target() >= 0 && (o.order() == Payloads.UnitOrder.ATTACK || o.order() == Payloads.UnitOrder.BOARD) ? level.getEntity(o.target()) : null;
        int n = 0;
        for (UnitEntity u : mine) {
            int i = n++;
            // (engines and ships side by side, not on one spot)
            Vec3 spread = to.add((i % 3 - 1) * 4.5, 0, (i / 3 % 3 - 1) * 4.5);
            if (u instanceof SoldierEntity s) {
                switch (o.order()) {
                    case Payloads.UnitOrder.HOLD -> s.order(SoldierEntity.Mode.HOLD, s.position());
                    case Payloads.UnitOrder.ATTACK -> {
                        if (target instanceof LivingEntity le && s.hostileTo(le)) {
                            s.order(SoldierEntity.Mode.MOVE, le.position());
                            s.setTarget(le);
                        }
                    }
                    case Payloads.UnitOrder.ATTACK_STRUCTURE -> {
                        s.order(SoldierEntity.Mode.BREAK, null);
                        s.structure = o.target();
                    }
                    case Payloads.UnitOrder.BOARD -> {
                        s.order(SoldierEntity.Mode.BOARD, null);
                        s.ship = o.target();
                    }
                    case Payloads.UnitOrder.POST -> {
                        Structure at = StrategyManager.data().structures.get(o.target());
                        if (s.unit().ranged || at != null && at.type == StructureType.OUTPOST) {
                            s.order(SoldierEntity.Mode.POST, null);
                            s.structure = o.target();
                        } else s.order(SoldierEntity.Mode.MOVE, to);
                    }
                    default -> s.order(SoldierEntity.Mode.MOVE, to);
                }
            } else if (u instanceof SiegeEntity g) {
                switch (o.order()) {
                    case Payloads.UnitOrder.HOLD -> g.order(SiegeEntity.Mode.HOLD, g.position(), -1, -1);
                    case Payloads.UnitOrder.ATTACK -> g.order(SiegeEntity.Mode.ATTACK, null, -1, o.target());
                    case Payloads.UnitOrder.ATTACK_STRUCTURE -> g.order(SiegeEntity.Mode.ATTACK, null, o.target(), -1);
                    default -> g.order(SiegeEntity.Mode.MOVE, spread, -1, -1);
                }
            } else if (u instanceof ShipEntity sh) {
                switch (o.order()) {
                    case Payloads.UnitOrder.HOLD -> sh.order(ShipEntity.Mode.HOLD, sh.position(), -1, -1);
                    case Payloads.UnitOrder.ATTACK -> sh.order(ShipEntity.Mode.ATTACK, null, -1, o.target());
                    case Payloads.UnitOrder.ATTACK_STRUCTURE -> sh.order(ShipEntity.Mode.ATTACK, null, o.target(), -1);
                    default -> {
                        boolean water = level.getFluidState(BlockPos.containing(to.x, to.y - 0.5, to.z)).is(FluidTags.WATER)
                                || level.getFluidState(BlockPos.containing(to.x, to.y, to.z)).is(FluidTags.WATER);
                        sh.order(water ? ShipEntity.Mode.MOVE : ShipEntity.Mode.UNLOAD, water ? spread : to, -1, -1);
                    }
                }
            }
        }
    }
}
