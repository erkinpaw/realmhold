package org.webtrade.strategy1.game;

import net.minecraft.nbt.CompoundTag;
import org.webtrade.strategy1.hex.Deposit;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.hex.Terrain;

/** One hex of the strategic map: its land, who holds it, what was built on it, who guards it. */
public final class Province {

    public final Hex hex;
    public final String name;
    public final Terrain terrain;
    public final Deposit deposit;
    /** The realm holding it; {@link Faction#NEUTRAL} for free land (bandits guard it). */
    public int owner = Faction.NEUTRAL;
    /** Farms, mills and workshops (0..3): more of what the land gives. */
    public int eco;
    /** Walls and towers (0..3): the garrison fights harder, and more of it fits. */
    public int fort;
    /** Soldiers kept here (counted, not walking about until a fight comes). */
    public int garrison;

    public Province(Hex hex, String name, Terrain terrain, Deposit deposit) {
        this.hex = hex;
        this.name = name;
        this.terrain = terrain;
        this.deposit = deposit;
    }

    public long key() {
        return hex.key();
    }

    public boolean neutral() {
        return owner == Faction.NEUTRAL;
    }

    /** What it gives its holder each turn. */
    public int[] yield(boolean capital) {
        int[] out = terrain.yield.clone();
        if (deposit != Deposit.NONE) out[deposit.res.ordinal()] += deposit.bonus;
        for (int i = 0; i < out.length; i++) out[i] = (int) Math.round(out[i] * (1 + 0.5 * eco));
        if (capital) {
            out[Res.FOOD.ordinal()] += 3;
            out[Res.WOOD.ordinal()] += 2;
            out[Res.STONE.ordinal()] += 2;
            out[Res.GOLD.ordinal()] += 1;
        }
        return out;
    }

    /** How many soldiers may be kept here. */
    public int garrisonCap(boolean capital) {
        return 2 + 2 * fort + (capital ? 2 : 0);
    }

    /** How hard it is to take from afar (abstract battles). */
    public double defense() {
        return garrison * (1 + 0.5 * fort) + fort * 2 + 0.5;
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("q", hex.q());
        t.putInt("r", hex.r());
        t.putString("name", name);
        t.putInt("terrain", terrain.ordinal());
        t.putInt("deposit", deposit.ordinal());
        t.putInt("owner", owner);
        t.putInt("eco", eco);
        t.putInt("fort", fort);
        t.putInt("garrison", garrison);
        return t;
    }

    static Province load(CompoundTag t) {
        Province p = new Province(new Hex(t.getIntOr("q", 0), t.getIntOr("r", 0)), t.getStringOr("name", "?"),
                Terrain.of(t.getIntOr("terrain", 0)), Deposit.of(t.getIntOr("deposit", 0)));
        p.owner = t.getIntOr("owner", Faction.NEUTRAL);
        p.eco = t.getIntOr("eco", 0);
        p.fort = t.getIntOr("fort", 0);
        p.garrison = t.getIntOr("garrison", 0);
        return p;
    }
}
