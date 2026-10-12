package org.webtrade.strategy1.game;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The kinds of soldier a realm can raise; better ones come with later ages. */
public enum UnitType {
    MILITIA(1, 20, 3, 0.30, false, Res.of(20, 10, 0, 0, 0)),
    ARCHER(1, 18, 2, 0.30, true, Res.of(20, 25, 0, 0, 0)),
    SWORDSMAN(2, 26, 5, 0.30, false, Res.of(30, 5, 0, 15, 0)),
    KNIGHT(3, 36, 7, 0.34, false, Res.of(40, 0, 0, 30, 10)),
    GUARD(4, 44, 9, 0.33, false, Res.of(50, 0, 0, 40, 30));

    public final int age;
    public final double health, damage, speed;
    public final boolean ranged;
    public final int[] cost;

    UnitType(int age, double health, double damage, double speed, boolean ranged, int[] cost) {
        this.age = age;
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
        };
    }

    /** The garrison's soldiers: the best melee kind the realm's age allows (and some archers). */
    public static UnitType garrisonUnit(int age, int index) {
        if (index % 3 == 2) return ARCHER;
        return switch (Math.max(1, Math.min(age, 4))) {
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
