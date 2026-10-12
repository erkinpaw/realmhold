package org.webtrade.strategy1.game;

/** The numbers of the game: turn length, costs, limits. */
public final class Rules {

    /** One turn (a season): the land yields, the AI thinks. Ticks. */
    public static final int TURN = 1200;
    /** Capture points needed to take a province (one a second, more with soldiers about). */
    public static final int CAPTURE = 30;
    /** How near the centre of a province one must stand to take it, blocks. */
    public static final double CAPTURE_RADIUS = 12;
    /** Hostile soldiers this near the centre keep it from being taken. */
    public static final double CONTEST_RADIUS = 36;
    /** No AI makes war on a player before this turn. */
    public static final int PLAYER_GRACE = 12;
    /** Turns of truce after a peace. */
    public static final int TRUCE = 5;

    /** A player's realm gets this much of everything once (to try every building and machine out). */
    public static final int TEST_STOCK = 20000;
    public static final int MAX_SIEGE = 6, MAX_SHIPS = 6;
    public static final int[] CATAPULT_COST = Res.of(0, 120, 30, 20, 0), TREBUCHET_COST = Res.of(0, 200, 60, 50, 20);
    public static final int[] TRANSPORT_COST = Res.of(0, 150, 0, 0, 0), WARSHIP_COST = Res.of(0, 250, 20, 60, 20);

    private Rules() {
    }

    public static int[] ecoCost(int level) {
        return Res.of(0, 30 * (level + 1), 20 * (level + 1), 0, 5 * level);
    }

    public static int[] fortCost(int level) {
        return Res.of(0, 15 * (level + 1), 40 * (level + 1), 10 * level, 0);
    }

    public static final int[] GARRISON_COST = Res.of(15, 0, 0, 5, 0);
    /** An AI's counted soldier. */
    public static final int[] RESERVE_COST = Res.of(18, 4, 0, 4, 0);

    /** The cost of going on from {@code age} to the next. */
    public static int[] ageCost(int age) {
        return switch (age) {
            case 1 -> Res.of(150, 150, 100, 0, 0);
            case 2 -> Res.of(300, 200, 250, 120, 40);
            default -> Res.of(500, 300, 400, 250, 150);
        };
    }

    /** The most buildings of a kind a province can have in an age. */
    public static int maxBuilding(int age) {
        return Math.min(3, age);
    }

    /** How many soldiers a player may lead in an age. */
    public static int maxArmy(int age) {
        return 2 + 4 * age;
    }

    /** The most troops an AI keeps in reserve in an age. */
    public static int maxReserve(int age) {
        return 6 + 4 * age;
    }
}
