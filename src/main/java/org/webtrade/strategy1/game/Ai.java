package org.webtrade.strategy1.game;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.webtrade.strategy1.hex.Deposit;
import org.webtrade.strategy1.hex.Hex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The AI kingdoms' turn: go on to a new age, raise troops, man the borders, build, take free land, make war on a
 * weaker neighbour (more readily over rich deposits), march on the enemy, and make peace when a war drags on.
 */
final class Ai {

    private static final Random RANDOM = new Random();

    private Ai() {
    }

    static void turn(StrategyData data) {
        for (Faction f : new ArrayList<>(data.factions.values())) {
            if (!f.alive || f.player()) continue;
            if (data.owned(f.id).isEmpty()) {
                f.alive = false;
                continue;
            }
            develop(data, f);
            expand(data, f);
            diplomacy(data, f);
            attack(data, f);
        }
    }

    // ------------------------------------------------------------------ at home

    private static void develop(StrategyData data, Faction f) {
        if (f.age < Faction.MAX_AGE && f.canPay(Rules.ageCost(f.age))) {
            f.pay(Rules.ageCost(f.age));
            f.age++;
            StrategyManager.announce(Component.translatable("strategy1.msg.age_ai", f.name(), Component.translatable("strategy1.age." + f.age))
                    .withStyle(ChatFormatting.GRAY), true);
        }
        // troops, keeping something back for building
        for (int i = 0; i < 2; i++) {
            if (f.reserve >= Rules.maxReserve(f.age) || !f.canPay(Rules.RESERVE_COST) || f.res[Res.FOOD.ordinal()] < 40) break;
            f.pay(Rules.RESERVE_COST);
            f.reserve++;
        }
        List<Province> own = data.owned(f.id);
        // man the weakest border province
        Province weakest = null;
        for (Province p : own) {
            boolean border = threatened(data, f, p);
            if (!border || p.garrison >= p.garrisonCap(f.capital == p.key())) continue;
            if (weakest == null || p.garrison < weakest.garrison) weakest = p;
        }
        if (weakest != null && f.reserve > 2) {
            f.reserve--;
            weakest.garrison++;
        }
        int max = Rules.maxBuilding(f.age);
        // walls where the enemy is, else farms and mines where the land is best
        for (Province p : own) {
            if (p.fort < max && threatened(data, f, p) && (f.wars.size() > 0 || p.deposit != Deposit.NONE) && f.canPay(Rules.fortCost(p.fort))) {
                f.pay(Rules.fortCost(p.fort));
                p.fort++;
                return;
            }
        }
        Province best = null;
        int bestYield = -1;
        for (Province p : own) {
            if (p.eco >= max) continue;
            int y = 0;
            for (int v : p.yield(false)) y += v;
            if (y > bestYield) {
                bestYield = y;
                best = p;
            }
        }
        if (best != null && f.canPay(Rules.ecoCost(best.eco))) {
            f.pay(Rules.ecoCost(best.eco));
            best.eco++;
        }
    }

    /** Does an enemy (or free land) lie next to it? */
    private static boolean threatened(StrategyData data, Faction f, Province p) {
        for (Hex n : p.hex.neighbors()) {
            Province q = data.province(n);
            if (q != null && q.terrain.land() && q.owner != f.id && (q.neutral() || f.wars.contains(q.owner))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ free land

    private static void expand(StrategyData data, Faction f) {
        if (f.reserve < 3 || RANDOM.nextFloat() > 0.6F) return;
        Province best = null;
        double bestScore = -1;
        for (Province q : frontier(data, f)) {
            if (!q.neutral()) continue;
            double score = (q.deposit != Deposit.NONE ? 6 : 0) - q.garrison * 1.5 + RANDOM.nextDouble() * 2;
            for (int v : q.yield(false)) score += v * 0.5;
            if (score > bestScore) {
                bestScore = score;
                best = q;
            }
        }
        if (best == null) return;
        int force = Math.min(f.reserve, best.garrison + 3);
        if (force * (0.8 + RANDOM.nextDouble() * 0.5) > best.defense()) {
            f.reserve -= Math.min(f.reserve, (int) Math.ceil(best.garrison * 0.7) + 1);
            StrategyManager.capture(best, f.id, 1);
        } else {
            f.reserve -= Math.min(f.reserve, force / 2);
        }
    }

    /** The provinces next to the realm's land that are not its own. */
    private static List<Province> frontier(StrategyData data, Faction f) {
        Set<Long> seen = new HashSet<>();
        List<Province> out = new ArrayList<>();
        for (Province p : data.provinces.values()) {
            if (p.owner != f.id) continue;
            for (Hex n : p.hex.neighbors()) {
                Province q = data.province(n);
                if (q != null && q.terrain.land() && q.owner != f.id && seen.add(q.key())) out.add(q);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ war and peace

    private static void diplomacy(StrategyData data, Faction f) {
        // long wars end
        for (Integer e : new ArrayList<>(f.wars)) {
            Faction enemy = data.faction(e);
            if (enemy == null || !enemy.alive) {
                f.wars.remove(e);
                continue;
            }
            if (enemy.player()) continue;
            int since = f.warSince.getOrDefault(e, data.turn);
            if (data.turn - since > 8 && RANDOM.nextFloat() < 0.15F) {
                StrategyManager.makePeace(f.id, e, true);
                return;
            }
        }
        if (f.wars.size() >= 2) return;
        double mine = data.strength(f);
        // neighbours, and how many rich provinces of theirs touch our land
        Set<Integer> neighbours = new HashSet<>();
        int[] rich = new int[data.nextFaction + 1];
        for (Province q : frontier(data, f)) {
            if (q.neutral()) continue;
            neighbours.add(q.owner);
            if (q.deposit != Deposit.NONE && q.owner < rich.length) rich[q.owner]++;
        }
        for (int n : neighbours) {
            Faction o = data.faction(n);
            if (o == null || !o.alive || f.wars.contains(n) || f.truceUntil.getOrDefault(n, -1) > data.turn) continue;
            if (o.player() && data.turn < Rules.PLAYER_GRACE) continue;
            double ratio = mine / Math.max(1, data.strength(o));
            if (ratio < 1.15) continue;
            double chance = 0.05 + 0.06 * Math.min(3, rich[n]) + (ratio > 1.6 ? 0.05 : 0);
            if (o.player()) chance *= 0.6;
            if (RANDOM.nextDouble() < chance) {
                StrategyManager.declareWar(f.id, n);
                return;
            }
        }
    }

    private static void attack(StrategyData data, Faction f) {
        if (f.wars.isEmpty() || f.reserve < 3) return;
        Province best = null;
        double bestScore = -1e9;
        for (Province q : frontier(data, f)) {
            if (q.neutral() || !f.wars.contains(q.owner)) continue;
            Faction o = data.faction(q.owner);
            if (o == null) continue;
            if (o.player() && (data.turn - f.lastRaid < 3 || StrategyManager.raided(q.key()))) continue;
            double score = (q.deposit != Deposit.NONE ? 5 : 0) - q.defense() * 1.2 + (data.isCapital(q) ? 3 : 0) + RANDOM.nextDouble() * 2;
            if (score > bestScore) {
                bestScore = score;
                best = q;
            }
        }
        if (best == null) return;
        Faction defender = data.faction(best.owner);
        if (defender.player()) {
            f.lastRaid = data.turn;
            int force = Math.min(f.reserve, 4 + 2 * f.age);
            if (StrategyManager.launchRaid(f, best, force)) {
                f.reserve -= Math.min(force, 10);
                return;
            }
        }
        int force = (int) Math.ceil(f.reserve * 0.6);
        double atk = force * (0.8 + RANDOM.nextDouble() * 0.5) * (1 + 0.15 * (f.age - 1));
        double def = best.defense() * (1 + 0.15 * (defender.age - 1));
        if (atk > def) {
            f.reserve -= Math.min(f.reserve, (int) Math.ceil(force / 2.0));
            StrategyManager.capture(best, f.id, Math.max(1, force / 3));
        } else {
            f.reserve -= Math.min(f.reserve, (int) Math.ceil(force * 0.6));
            best.garrison = Math.max(0, best.garrison - Math.max(1, force / 3));
            if (defender.player()) {
                StrategyManager.tell(defender, Component.translatable("strategy1.msg.held", f.name(), best.name).withStyle(ChatFormatting.GREEN));
            }
            data.touch();
        }
    }
}
