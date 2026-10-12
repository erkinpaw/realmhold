package org.webtrade.strategy1.game;

/** The five resources a realm lives and fights on. */
public enum Res {
    FOOD(0xFFE0B040), WOOD(0xFF8A5A2B), STONE(0xFF9A9A9A), IRON(0xFFD8D8D8), GOLD(0xFFF2D020);

    public static final int COUNT = 5;
    public final int color;

    Res(int color) {
        this.color = color;
    }

    public String key() {
        return "strategy1.res." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /** A cost or a yield: amounts in the order of the resources. */
    public static Res of(int i) {
        return values()[Math.floorMod(i, COUNT)];
    }

    public static int[] of(int food, int wood, int stone, int iron, int gold) {
        return new int[]{food, wood, stone, iron, gold};
    }
}
