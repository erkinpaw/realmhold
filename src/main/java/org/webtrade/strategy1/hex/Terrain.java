package org.webtrade.strategy1.hex;

import org.webtrade.strategy1.game.Res;

/** What a province mostly is: it decides what the land gives each turn. */
public enum Terrain {
    PLAINS(0x8DB360, Res.of(4, 1, 0, 0, 0)),
    FOREST(0x3F7A32, Res.of(1, 4, 0, 0, 0)),
    MOUNTAINS(0x8C8578, Res.of(0, 0, 3, 2, 0)),
    DESERT(0xD8C27A, Res.of(0, 0, 2, 0, 1)),
    SNOW(0xDDE6EA, Res.of(1, 1, 1, 1, 0)),
    SWAMP(0x5B6E3A, Res.of(2, 2, 0, 0, 0)),
    WATER(0x3C6C93, Res.of(0, 0, 0, 0, 0));

    /** Map colour (RGB). */
    public final int color;
    /** What it gives each turn, before buildings. */
    public final int[] yield;

    Terrain(int color, int[] yield) {
        this.color = color;
        this.yield = yield;
    }

    public boolean land() {
        return this != WATER;
    }

    public String key() {
        return "strategy1.terrain." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Terrain of(int i) {
        Terrain[] v = values();
        return v[Math.floorMod(i, v.length)];
    }
}
