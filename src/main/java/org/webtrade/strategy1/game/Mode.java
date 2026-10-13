package org.webtrade.strategy1.game;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The ways to play a world, chosen when it is made:
 * <ul>
 * <li>{@link #CONQUEST}: the realms of the hex map make war on each other and on you; take their land.</li>
 * <li>{@link #SIEGE}: by day you gather, build and fortify; every night the dead come, more and worse each time,
 * and take back the land you can't hold.</li>
 * <li>{@link #TRADE}: the realms don't fight; they race to be the richest, by their trades and markets.</li>
 * <li>{@link #SANDBOX}: only you and the land: build and grow in peace.</li>
 * </ul>
 */
public enum Mode {
    CONQUEST(Items.IRON_SWORD, "⚔", 0xFFD04030, 5, true, false),
    SIEGE(Items.ZOMBIE_HEAD, "☠", 0xFF70B050, 0, false, true),
    TRADE(Items.EMERALD, "⚖", 0xFFE0B020, 5, false, false),
    SANDBOX(Items.OAK_SAPLING, "✿", 0xFF60A0E0, 0, false, false);

    public final Item icon;
    /** Its sign (drawn where items can't be, before a world is loaded) and its colour. */
    public final String glyph;
    public final int tint;
    /** How many AI realms by default. */
    public final int realms;
    /** Do the realms make war? Do the dead come at night? */
    public final boolean war, waves;

    Mode(Item icon, String glyph, int tint, int realms, boolean war, boolean waves) {
        this.icon = icon;
        this.glyph = glyph;
        this.tint = tint;
        this.realms = realms;
        this.war = war;
        this.waves = waves;
    }

    public String key() {
        return "strategy1.mode." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Mode of(int i) {
        Mode[] v = values();
        return v[Math.floorMod(i, v.length)];
    }

    /** What the next world made on this machine is to be (set by the new realm screen; read when its map is laid out). */
    public static final class Pending {
        public static Mode mode;
        public static int realms = -1;
        public static boolean coop = true;
        /** The player chooses where their keep stands (and the map is laid out round it). */
        public static boolean site;

        private Pending() {
        }
    }
}
