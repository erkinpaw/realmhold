package org.webtrade.strategy1.game;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The goods market all the realms trade at: a price in gold for food, wood, stone and iron, that falls as a good is
 * sold and rises as it is bought, and drifts back each turn. Caravans go out from a realm's market to another
 * realm's capital and come back with gold, more the further they went. In the trading mode the realms race to be
 * the richest.
 */
public final class Market {

    /** Prices of a lot of {@link #LOT}, in gold, for the four goods (gold itself is the money). */
    public static final double[] BASE = {4, 4, 5, 12};
    public static final int LOT = 10;

    private Market() {
    }

    private static double[] prices(StrategyData d) {
        if (d.prices == null || d.prices.length != 4) d.prices = BASE.clone();
        return d.prices;
    }

    /** What a lot of a good fetches now (selling), and costs (buying, a little dearer). */
    public static int sellPrice(StrategyData d, Res r) {
        return r == Res.GOLD ? 0 : Math.max(1, (int) Math.round(prices(d)[r.ordinal()]));
    }

    public static int buyPrice(StrategyData d, Res r) {
        return r == Res.GOLD ? 0 : Math.max(2, (int) Math.round(prices(d)[r.ordinal()] * 1.25 + 1));
    }

    public static boolean sell(StrategyData d, Faction f, Res r) {
        if (r == Res.GOLD || f.res[r.ordinal()] < LOT) return false;
        int gold = sellPrice(d, r);
        f.res[r.ordinal()] -= LOT;
        f.res[Res.GOLD.ordinal()] += gold;
        prices(d)[r.ordinal()] = Math.max(BASE[r.ordinal()] * 0.25, prices(d)[r.ordinal()] * 0.96);
        d.touch();
        return true;
    }

    public static boolean buy(StrategyData d, Faction f, Res r) {
        int gold = buyPrice(d, r);
        if (r == Res.GOLD || f.res[Res.GOLD.ordinal()] < gold) return false;
        f.res[Res.GOLD.ordinal()] -= gold;
        f.res[r.ordinal()] += LOT;
        prices(d)[r.ordinal()] = Math.min(BASE[r.ordinal()] * 4, prices(d)[r.ordinal()] * 1.04);
        d.touch();
        return true;
    }

    /** A realm's wealth: its gold, its goods at the market's prices, its land and buildings. */
    public static int wealth(StrategyData d, Faction f) {
        double w = f.res[Res.GOLD.ordinal()];
        for (int i = 0; i < 4; i++) w += f.res[i] * prices(d)[i] / LOT;
        w += d.owned(f.id).size() * 40;
        for (Structure s : d.structures.values()) if (s.owner == f.id && s.built() && !s.type.deposit()) w += 25;
        return (int) w;
    }

    // ------------------------------------------------------------------ caravans

    /** A caravan on the road: whose, to whom, when it is back, with how much gold. */
    public record Caravan(int faction, int to, long back, int gold) {
    }

    /** Sends a caravan from a realm's market to the nearest other realm's capital; null if done, else why not. */
    public static String send(StrategyData d, Faction f, Structure market, long now) {
        if (market.type != StructureType.MARKET || !market.built()) return "strategy1.err.not_built";
        int going = 0;
        for (Caravan c : d.caravans) if (c.faction == f.id) going++;
        int markets = 0;
        for (Structure s : d.structures.values()) if (s.owner == f.id && s.type == StructureType.MARKET && s.built()) markets++;
        if (going >= markets * 2) return "strategy1.err.caravans_busy";
        int[] goods = org.webtrade.strategy1.game.Res.of(20, 20, 0, 0, 0);
        if (!f.canPay(goods)) return "strategy1.err.cost";
        Faction best = null;
        double bestD = Double.MAX_VALUE;
        for (Faction o : d.factions.values()) {
            if (o == f || !o.alive || d.hostile(f.id, o.id)) continue;
            Province cap = d.province(o.capital);
            if (cap == null) continue;
            double dist = cap.hex.distToCenter(market.origin.getX(), market.origin.getZ());
            if (dist < bestD) {
                bestD = dist;
                best = o;
            }
        }
        if (best == null) return "strategy1.err.no_partner";
        f.pay(goods);
        int gold = 15 + (int) (bestD / 25);
        long trip = 20L * 30 + (long) (bestD * 2);
        d.caravans.add(new Caravan(f.id, best.id, now + trip, gold));
        StrategyManager.tell(f, Component.translatable("strategy1.msg.caravan_out", best.name(), (int) (trip / 20), gold).withStyle(ChatFormatting.GOLD));
        d.touch();
        return null;
    }

    /** Once a second: caravans come home; the prices drift back; in the trading mode, the first to the goal wins. */
    public static void second(StrategyData d, long now) {
        for (Iterator<Caravan> it = d.caravans.iterator(); it.hasNext(); ) {
            Caravan c = it.next();
            if (now < c.back) continue;
            it.remove();
            Faction f = d.faction(c.faction), o = d.faction(c.to);
            if (f == null || !f.alive) continue;
            f.res[Res.GOLD.ordinal()] += c.gold;
            if (o != null) o.res[Res.GOLD.ordinal()] += c.gold / 3;
            StrategyManager.tell(f, Component.translatable("strategy1.msg.caravan_back", c.gold).withStyle(ChatFormatting.GOLD));
            d.touch();
        }
        if (now % 1200 == 0) {
            double[] p = prices(d);
            for (int i = 0; i < 4; i++) p[i] += (BASE[i] - p[i]) * 0.15;
        }
        if (d.mode == Mode.TRADE && d.winner < 0 && d.tradeGoal > 0) {
            for (Faction f : d.factions.values()) {
                if (!f.alive || progress(d, f) < d.tradeGoal) continue;
                d.winner = f.id;
                StrategyManager.announce(Component.translatable("strategy1.msg.trade_won", f.name(), d.tradeGoal).withStyle(ChatFormatting.GOLD), true);
                d.touch();
            }
        }
    }

    /** The AI's trading each turn (in every mode): surplus goods to the market, a caravan now and then. */
    static void aiTurn(StrategyData d, Faction f, java.util.Random r) {
        for (int i = 0; i < 4; i++) {
            Res res = Res.values()[i];
            while (f.res[i] > 300 && r.nextFloat() < 0.7F) sell(d, f, res);
        }
        if (d.mode == Mode.TRADE) {
            // a trading realm grows rich by its crafts as well as its land
            f.res[Res.GOLD.ordinal()] += 4 + d.owned(f.id).size() * 2 + r.nextInt(6);
        }
    }

    /** How much richer a realm has grown since the race began (its start measured the first time it's asked). */
    public static int progress(StrategyData d, Faction f) {
        int w = wealth(d, f);
        if (f.startWealth < 0) f.startWealth = w;
        return Math.max(0, w - f.startWealth);
    }

    public static List<int[]> ranking(StrategyData d) {
        List<int[]> out = new ArrayList<>();
        for (Faction f : d.factions.values()) if (f.alive) out.add(new int[]{f.id, progress(d, f)});
        out.sort((a, b) -> b[1] - a[1]);
        return out;
    }
}
