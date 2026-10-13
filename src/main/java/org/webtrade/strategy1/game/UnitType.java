package org.webtrade.strategy1.game;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.webtrade.strategy1.build.StructureType;

/**
 * The kinds of soldier a realm can raise (each from one of its peasants): the barracks trains militia and archers;
 * with a blacksmith's forge, swordsmen, knights and guardsmen too.
 */
public enum UnitType {
    MILITIA(StructureType.BARRACKS, 20, 3, 0.30, false, Res.of(10, 10, 0, 0, 0)),
    ARCHER(StructureType.BARRACKS, 18, 2, 0.30, true, Res.of(10, 25, 0, 0, 0)),
    SWORDSMAN(StructureType.BLACKSMITH, 26, 5, 0.30, false, Res.of(15, 5, 0, 15, 0)),
    KNIGHT(StructureType.BLACKSMITH, 36, 7, 0.34, false, Res.of(20, 0, 0, 30, 10)),
    GUARD(StructureType.BLACKSMITH, 44, 9, 0.33, false, Res.of(25, 0, 0, 40, 30)),
    /** Doesn't fight: heals the realm's own round him, half a heart every few seconds. */
    HEALER(StructureType.BARRACKS, 16, 1, 0.31, false, Res.of(20, 5, 0, 0, 15));

    /** A healer's reach (blocks) and how often he heals (ticks), and how much (half a heart). */
    public static final double HEAL_RADIUS = 6;
    public static final int HEAL_EVERY = 60;
    public static final float HEAL = 1F;

    /** The building the realm must have to raise them. */
    public final StructureType requires;
    public final double health, damage, speed;
    public final boolean ranged;
    public final int[] cost;

    UnitType(StructureType requires, double health, double damage, double speed, boolean ranged, int[] cost) {
        this.requires = requires;
        this.health = health;
        this.damage = damage;
        this.speed = speed;
        this.ranged = ranged;
        this.cost = cost;
    }

    public String key() {
        return "strategy1.unit." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public Item weapon() {
        return switch (this) {
            case MILITIA -> Items.STONE_SWORD;
            case ARCHER -> Items.BOW;
            case SWORDSMAN -> Items.IRON_SWORD;
            case KNIGHT -> Items.DIAMOND_SWORD;
            case GUARD -> Items.NETHERITE_SWORD;
            case HEALER -> Items.GOLDEN_APPLE;
        };
    }

    public boolean heals() {
        return this == HEALER;
    }

    /** A garrison's soldiers: the best melee kind the realm's might allows (and some archers). */
    public static UnitType garrisonUnit(int tier, int index) {
        if (index % 3 == 2) return ARCHER;
        return switch (Math.max(1, Math.min(tier, 4))) {
            case 1 -> MILITIA;
            case 2 -> SWORDSMAN;
            case 3 -> KNIGHT;
            default -> GUARD;
        };
    }

    public static UnitType of(int i) {
        UnitType[] v = values();
        return v[Math.floorMod(i, v.length)];
    }
}
