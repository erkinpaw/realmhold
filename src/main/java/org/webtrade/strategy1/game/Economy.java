package org.webtrade.strategy1.game;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Random;

/** A turn's economy: the land yields to its holders, the soldiers eat, the bandits come back to empty land. */
final class Economy {

    private static final Random RANDOM = new Random();

    private Economy() {
    }

    /**
     * What the realm gets each turn without lifting a hand: an AI kingdom (counted, not walking about) its lands'
     * yield; a player's realm the land's taxes, in gold (the rest its peasants gather and carry home).
     */
    static int[] income(StrategyData data, Faction f) {
        int[] in = new int[Res.COUNT];
        for (Province p : data.provinces.values()) {
            if (p.owner != f.id) continue;
            if (f.player()) {
                in[Res.GOLD.ordinal()] += 1;
                continue;
            }
            int[] y = p.yield(f.capital == p.key());
            for (int i = 0; i < Res.COUNT; i++) in[i] += y[i];
        }
        return in;
    }

    static int upkeep(StrategyData data, Faction f) {
        int n = f.reserve + f.army + f.peasants;
        for (Province p : data.provinces.values()) if (p.owner == f.id) n += p.garrison;
        return n;
    }

    static void turn(StrategyData data) {
        for (Faction f : data.factions.values()) {
            if (!f.alive) continue;
            int[] in = income(data, f);
            int eat = upkeep(data, f);
            f.gain(in);
            f.res[Res.FOOD.ordinal()] -= eat;
            boolean hungry = f.res[Res.FOOD.ordinal()] < 0;
            if (hungry) {
                f.res[Res.FOOD.ordinal()] = 0;
                // hunger: soldiers desert, the reserve first, then a garrison
                if (f.reserve > 0) f.reserve--;
                else {
                    List<Province> own = data.owned(f.id);
                    own.removeIf(p -> p.garrison == 0);
                    if (!own.isEmpty()) own.get(RANDOM.nextInt(own.size())).garrison--;
                }
            }
            if (f.player()) {
                StrategyManager.tell(f, Component.translatable("strategy1.msg.season", data.turn + 1, in[0] - eat, in[1], in[2], in[3], in[4])
                        .withStyle(ChatFormatting.DARK_AQUA));
                if (hungry) StrategyManager.tell(f, Component.translatable("strategy1.msg.hunger").withStyle(ChatFormatting.RED));
            }
        }
        // the bandits come back to land nobody holds
        if (data.turn % 6 == 5) {
            for (Province p : data.provinces.values()) {
                if (p.neutral() && p.terrain.land() && p.garrison < 3 && RANDOM.nextFloat() < 0.3F) p.garrison++;
            }
        }
    }
}
