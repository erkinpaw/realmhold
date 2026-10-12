package org.webtrade.strategy1.hex;

import org.webtrade.strategy1.game.Res;

/** A rich spot some provinces have: much more of one resource. What realms go to war over. */
public enum Deposit {
    NONE(null, 0),
    FERTILE(Res.FOOD, 5),
    OLD_FOREST(Res.WOOD, 5),
    QUARRY(Res.STONE, 5),
    IRON_VEIN(Res.IRON, 4),
    GOLD_VEIN(Res.GOLD, 3);

    public final Res res;
    public final int bonus;

    Deposit(Res res, int bonus) {
        this.res = res;
        this.bonus = bonus;
    }

    public String key() {
        return "strategy1.deposit." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Deposit of(int i) {
        Deposit[] v = values();
        return v[Math.floorMod(i, v.length)];
    }
}
