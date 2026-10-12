package org.webtrade.strategy1.client.ui;

import net.minecraft.resources.Identifier;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.game.UnitType;

import java.util.Locale;

/**
 * The strategist's own pictures (drawn by tools/icons.py, 20x20): every building, soldier, engine, ship, order and
 * tab has one, so a tower looks like a tower and a catapult like a catapult. The land's riches keep the game's ores.
 */
public final class Icons {

    public static final int SIZE = 20;

    public static final Identifier PEASANT = of("peasant"), CATAPULT = of("catapult"), TREBUCHET = of("trebuchet"), BALLISTA = of("ballista"),
            ENGINE_CATAPULT = of("engine_catapult"), BRAZIER = of("brazier"), TRANSPORT = of("transport"), WARSHIP = of("warship"),
            HOLD = of("hold"), UNMAN = of("unman"), DEMOLISH = of("demolish"), MAP = of("map"), LEAVE = of("leave"), CARAVAN = of("caravan"),
            TAB_ECONOMY = of("tab_economy"), TAB_MILITARY = of("tab_military"), TAB_DEFENCE = of("tower_stone"), TAB_ROADS = of("path"),
            TAB_ARMY = of("tab_army");

    private Icons() {
    }

    public static Identifier of(String name) {
        return Strategy1.id("icon/" + name);
    }

    /** A building's picture; null for the land's riches (they keep their ore). */
    public static Identifier building(StructureType t) {
        return t.deposit() ? null : of(t.name().toLowerCase(Locale.ROOT));
    }

    public static Identifier unit(UnitType u) {
        return of(u.name().toLowerCase(Locale.ROOT));
    }

    /** A tower's engine (1 ballista, 2 catapult, 3 brazier). */
    public static Identifier engine(int e) {
        return switch (e) {
            case 1 -> BALLISTA;
            case 2 -> ENGINE_CATAPULT;
            default -> BRAZIER;
        };
    }
}
