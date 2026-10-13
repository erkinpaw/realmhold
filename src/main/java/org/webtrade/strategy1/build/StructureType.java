package org.webtrade.strategy1.build;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.webtrade.strategy1.game.Res;

/**
 * What a realm can build in its provinces. Buildings stand in a box round the point they're put at (local x0..x1,
 * z0..z1, the door at the −z side); walls, paths and bridges run from one point to another. What a building needs
 * to be built first stands in for the ages: stone walls want a quarry, swordsmen a blacksmith.
 */
public enum StructureType {
    KEEP(Category.NONE, null, Res.of(0, 0, 0, 0, 0), 1200, -4, -4, 4, 4, false, 1, 0, 4, Items.BELL),
    HOUSE(Category.ECONOMY, null, Res.of(0, 30, 0, 0, 0), 120, -2, -2, 2, 2, false, 8, 0, 0, Items.OAK_DOOR),
    STOCKPILE(Category.ECONOMY, null, Res.of(0, 20, 0, 0, 0), 120, -3, -3, 3, 3, false, 6, 0, 0, Items.CHEST),
    FARM(Category.ECONOMY, null, Res.of(0, 30, 0, 0, 0), 150, -4, -4, 4, 4, false, 8, 3, 0, Items.WHEAT),
    WOODCUTTER(Category.ECONOMY, null, Res.of(0, 15, 0, 0, 0), 150, -2, -2, 2, 2, false, 8, 2, 0, Items.IRON_AXE),
    QUARRY(Category.ECONOMY, null, Res.of(0, 30, 0, 0, 0), 200, -3, -3, 3, 3, false, 10, 3, 0, Items.STONE_PICKAXE),
    IRON_MINE(Category.ECONOMY, null, Res.of(0, 40, 20, 0, 0), 200, -2, -2, 2, 2, false, 12, 3, 0, Items.IRON_PICKAXE),
    GOLD_MINE(Category.ECONOMY, null, Res.of(0, 40, 30, 10, 0), 200, -2, -2, 2, 2, false, 12, 3, 0, Items.GOLDEN_PICKAXE),
    MARKET(Category.ECONOMY, null, Res.of(0, 60, 20, 0, 0), 220, -3, -3, 3, 3, false, 14, 0, 0, Items.EMERALD),
    BARRACKS(Category.MILITARY, null, Res.of(0, 60, 40, 0, 0), 300, -4, -3, 4, 3, false, 16, 0, 0, Items.IRON_SWORD),
    BLACKSMITH(Category.MILITARY, null, Res.of(0, 40, 30, 10, 0), 220, -3, -3, 3, 3, false, 14, 0, 0, Items.ANVIL),
    WORKSHOP(Category.MILITARY, BLACKSMITH, Res.of(0, 100, 40, 20, 0), 260, -4, -4, 4, 4, false, 18, 0, 0, Items.CROSSBOW),
    /** A post on the land to be taken (next to the realm's own): a soldier in it, and the province comes over. */
    OUTPOST(Category.MILITARY, null, Res.of(0, 40, 10, 0, 0), 160, -2, -2, 2, 2, false, 10, 0, 1, Items.OAK_FENCE),
    PORT(Category.MILITARY, null, Res.of(0, 120, 30, 0, 0), 260, -2, -2, 2, 10, false, 18, 0, 0, Items.OAK_BOAT),
    TOWER_WOOD(Category.DEFENCE, null, Res.of(0, 40, 0, 0, 0), 220, -2, -2, 2, 2, false, 10, 0, 2, Items.SPRUCE_FENCE),
    TOWER_STONE(Category.DEFENCE, QUARRY, Res.of(0, 20, 120, 0, 0), 600, -3, -3, 3, 3, false, 20, 0, 4, Items.STONE_BRICKS),
    WALL_WOOD(Category.DEFENCE, null, Res.of(0, 4, 0, 0, 0), 25, 0, 0, 0, 0, true, 0, 0, 0, Items.OAK_LOG),
    WALL_STONE(Category.DEFENCE, QUARRY, Res.of(0, 0, 8, 0, 0), 70, 0, 0, 0, 0, true, 0, 0, 0, Items.STONE_BRICK_WALL),
    /** A gate cut in a wall (put there from the wall): a portcullis that rises for the realm's own and drops before the enemy. */
    GATE(Category.NONE, null, Res.of(0, 10, 4, 0, 0), 150, 0, 0, 0, 0, true, 0, 0, 0, Items.OAK_FENCE_GATE),
    PATH(Category.ROADS, null, Res.of(0, 0, 1, 0, 0), 0, 0, 0, 0, 0, true, 0, 0, 0, Items.DIRT_PATH),
    BRIDGE(Category.ROADS, null, Res.of(0, 3, 1, 0, 0), 0, 0, 0, 0, 0, true, 0, 0, 0, Items.OAK_PLANKS),
    /** The land's own riches: outcrops of stone, veins of iron and gold. Quarries and mines are put up by them. */
    DEPOSIT_STONE(Category.NONE, null, Res.of(0, 0, 0, 0, 0), 0, -3, -3, 3, 3, false, 0, 0, 0, Items.COBBLESTONE),
    DEPOSIT_IRON(Category.NONE, null, Res.of(0, 0, 0, 0, 0), 0, -3, -3, 3, 3, false, 0, 0, 0, Items.RAW_IRON),
    DEPOSIT_GOLD(Category.NONE, null, Res.of(0, 0, 0, 0, 0), 0, -3, -3, 3, 3, false, 0, 0, 0, Items.RAW_GOLD);

    public enum Category {
        NONE(Items.BARRIER), ECONOMY(Items.WHEAT), MILITARY(Items.IRON_SWORD), DEFENCE(Items.STONE_BRICK_WALL), ROADS(Items.DIRT_PATH);

        public final Item icon;

        Category(Item icon) {
            this.icon = icon;
        }

        public String key() {
            return "strategy1.cat." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public final Category category;
    /** What must already stand (built) in the realm before this can be put up; null: nothing. */
    public final StructureType requires;
    /** The cost (for walls, paths and bridges: per block of their length). */
    public final int[] cost;
    /** Its strength (walls: per block of length); 0: can't be broken. */
    public final int hp;
    public final int x0, z0, x1, z1;
    /** Runs from point to point (walls, paths, bridges). */
    public final boolean line;
    /** Seconds to build (lines: by their length). */
    public final int seconds;
    /** Peasants working there. */
    public final int workers;
    /** Archers it holds (walls: by their length). */
    public final int posts;
    public final Item icon;

    StructureType(Category category, StructureType requires, int[] cost, int hp, int x0, int z0, int x1, int z1, boolean line, int seconds,
                  int workers, int posts, Item icon) {
        this.category = category;
        this.requires = requires;
        this.cost = cost;
        this.hp = hp;
        this.x0 = x0;
        this.z0 = z0;
        this.x1 = x1;
        this.z1 = z1;
        this.line = line;
        this.seconds = seconds;
        this.workers = workers;
        this.posts = posts;
        this.icon = icon;
    }

    public boolean wall() {
        return this == WALL_WOOD || this == WALL_STONE;
    }

    public boolean tower() {
        return this == TOWER_WOOD || this == TOWER_STONE;
    }

    /** Can archers stand on it? */
    public boolean manned() {
        return wall() || tower() || this == KEEP || this == OUTPOST;
    }

    public boolean deposit() {
        return this == DEPOSIT_STONE || this == DEPOSIT_IRON || this == DEPOSIT_GOLD;
    }

    /** Where its new units (and carriers) come out: the spot this far in front of it (−z), the port's out over the water (+z). */
    public int doorZ() {
        return switch (this) {
            case FARM, BARRACKS -> -7;
            case KEEP -> -6;
            case WOODCUTTER, IRON_MINE, GOLD_MINE, HOUSE, OUTPOST -> -4;
            case QUARRY, STOCKPILE, MARKET, BLACKSMITH -> -5;
            case WORKSHOP -> -9;
            case PORT -> 14;
            default -> 0;
        };
    }

    /** Does it send out units (and so has a gathering point)? */
    public boolean musters() {
        return this == BARRACKS || this == WORKSHOP || this == PORT;
    }

    /** The deposit a quarry or mine must stand by (null: none). */
    public StructureType needsDeposit() {
        return switch (this) {
            case QUARRY -> DEPOSIT_STONE;
            case IRON_MINE -> DEPOSIT_IRON;
            case GOLD_MINE -> DEPOSIT_GOLD;
            default -> null;
        };
    }

    /** Where carriers bring what they gathered. */
    public boolean store() {
        return this == KEEP || this == STOCKPILE;
    }

    public String key() {
        return "strategy1.build." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The longest a wall, path or bridge may be at once. */
    public int maxLength() {
        return this == PATH ? 96 : this == BRIDGE ? 48 : 40;
    }

    /** The cost of a line of this length. */
    public int[] cost(int length) {
        if (!line) return cost.clone();
        int[] c = new int[Res.COUNT];
        for (int i = 0; i < Res.COUNT; i++) c[i] = this == PATH ? (cost[i] * length + 2) / 3 : cost[i] * length;
        return c;
    }

    public static StructureType of(int i) {
        StructureType[] v = values();
        return v[Math.floorMod(i, v.length)];
    }
}
