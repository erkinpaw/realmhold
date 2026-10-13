package org.webtrade.strategy1.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.entity.Dyes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How each kind of structure is laid out in blocks, on the land where it is put: the ground under a building made
 * level (filled in below, cleared above), then the building itself, from the ground up; walls and paths follow
 * the land along their line.
 */
final class Blueprints {

    private Blueprints() {
    }

    /** The blocks being laid out: in order, the ones only readying the ground first. */
    private static final class Plan {
        final Map<Long, BlockState> prep = new LinkedHashMap<>();
        final Map<Long, BlockState> body = new LinkedHashMap<>();
        final List<Vec3> spots = new ArrayList<>();
        final List<Long> leaves = new ArrayList<>();
        Vec3 door;

        void put(BlockPos p, BlockState s) {
            body.remove(p.asLong());
            body.put(p.asLong(), s);
        }

        void ready(BlockPos p, BlockState s) {
            prep.put(p.asLong(), s);
        }
    }

    // ------------------------------------------------------------------ the land

    private static boolean soft(BlockState s) {
        return s.isAir() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.FLOWERS)
                || s.is(Blocks.SNOW) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.DEAD_BUSH) || s.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock;
    }

    /** The first block above the real ground at a column (trees and plants don't count). */
    static int ground(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y - 1, z);
        while (p.getY() > level.getMinY() + 1 && soft(level.getBlockState(p)) && level.getFluidState(p).isEmpty()) p.move(0, -1, 0);
        return p.getY() + 1;
    }

    private static boolean water(ServerLevel level, BlockPos p) {
        return level.getFluidState(p).is(FluidTags.WATER);
    }

    // ------------------------------------------------------------------ turning

    private static int[] turn(int x, int z, int rot) {
        return switch (rot & 3) {
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            case 3 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    private static net.minecraft.world.level.block.Rotation rotation(int rot) {
        return switch (rot & 3) {
            case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.world.level.block.Rotation.NONE;
        };
    }

    /** A building's frame: local (x, y, z) from its origin, turned. */
    private record Frame(BlockPos origin, int rot) {
        BlockPos at(int x, int y, int z) {
            int[] t = turn(x, z, rot);
            return origin.offset(t[0], y, t[1]);
        }

        Vec3 spot(double x, double y, double z) {
            int[] t = turn((int) Math.floor(x), (int) Math.floor(z), rot);
            return new Vec3(origin.getX() + t[0] + 0.5, origin.getY() + y, origin.getZ() + t[1] + 0.5);
        }

        BlockState turned(BlockState s) {
            return s.rotate(rotation(rot));
        }
    }

    private static BlockState d(Block b) {
        return b.defaultBlockState();
    }

    private static BlockState slab(Block b) {
        return b.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
    }

    private static BlockState logX(Block b) {
        return b.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
    }

    // ------------------------------------------------------------------ laying out

    /** Lays the structure out on the land. False if it can't stand there (a port without water). */
    static boolean layOut(ServerLevel level, Structure s, int color) {
        Plan plan = new Plan();
        boolean ok = switch (s.type) {
            case WALL_WOOD, WALL_STONE -> wall(level, s, plan);
            case PATH -> path(level, s, plan);
            case BRIDGE -> bridge(level, s, plan);
            case GATE -> gate(level, s, plan);
            case DEPOSIT_STONE, DEPOSIT_IRON, DEPOSIT_GOLD -> deposit(level, s, plan);
            case PORT -> port(level, s, plan);
            default -> building(level, s, plan, color);
        };
        if (!ok) return false;
        Map<BlockState, Integer> index = new HashMap<>();
        List<BlockState> palette = new ArrayList<>();
        int n = plan.prep.size() + plan.body.size();
        long[] pos = new long[n];
        int[] st = new int[n];
        int i = 0;
        for (Map<Long, BlockState> part : List.of(plan.prep, plan.body)) {
            for (Map.Entry<Long, BlockState> e : part.entrySet()) {
                pos[i] = e.getKey();
                st[i++] = index.computeIfAbsent(e.getValue(), k -> {
                    palette.add(k);
                    return palette.size() - 1;
                });
            }
        }
        s.pos = pos;
        s.state = st;
        s.palette = palette;
        s.prep = plan.prep.size();
        s.spots = plan.spots;
        s.door = plan.door;
        if (!plan.leaves.isEmpty()) s.leaves = plan.leaves.stream().mapToLong(Long::longValue).toArray();
        s.planned = false;
        s.laidOut();
        return true;
    }

    /** Levels the ground in a building's box: filled in up to its floor, cleared above it. */
    private static void level(ServerLevel level, Frame f, Plan plan, int x0, int z0, int x1, int z1, int height) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                BlockPos floor = f.at(x, 0, z);
                int g = ground(level, floor.getX(), floor.getZ());
                for (int y = Math.max(g, floor.getY() - 5); y < floor.getY(); y++) plan.ready(new BlockPos(floor.getX(), y, floor.getZ()), d(Blocks.DIRT));
                for (int y = 0; y <= height; y++) {
                    BlockPos p = floor.above(y);
                    if (!level.getBlockState(p).isAir() || !level.getFluidState(p).isEmpty()) plan.ready(p, d(Blocks.AIR));
                }
            }
        }
    }

    private static boolean building(ServerLevel level, Structure s, Plan plan, int color) {
        Frame f = new Frame(s.origin, s.rot);
        StructureType t = s.type;
        int height = switch (t) {
            case TOWER_STONE -> 12;
            case TOWER_WOOD, KEEP -> 9;
            default -> 6;
        };
        // the trees on the ground it takes come down first, whole
        BlockPos c0 = f.at(t.x0 - 1, 0, t.z0 - 1), c1 = f.at(t.x1 + 1, 0, t.z1 + 1);
        Trees.clear(level, new BlockPos(Math.min(c0.getX(), c1.getX()), s.origin.getY() - 1, Math.min(c0.getZ(), c1.getZ())),
                new BlockPos(Math.max(c0.getX(), c1.getX()), s.origin.getY() + height + 6, Math.max(c0.getZ(), c1.getZ())));
        level(level, f, plan, t.x0, t.z0, t.x1, t.z1, height);
        switch (t) {
            case FARM -> farm(f, plan);
            case WOODCUTTER -> woodcutter(f, plan);
            case IRON_MINE -> mine(f, plan, Blocks.IRON_ORE);
            case GOLD_MINE -> mine(f, plan, Blocks.GOLD_ORE);
            case QUARRY -> quarry(f, plan);
            case KEEP -> keep(f, plan, color);
            case HOUSE -> house(f, plan);
            case STOCKPILE -> stockpile(f, plan);
            case MARKET -> market(f, plan, color);
            case BLACKSMITH -> blacksmith(f, plan);
            case BARRACKS -> barracks(f, plan, color);
            case WORKSHOP -> workshop(f, plan);
            case TOWER_WOOD -> towerWood(f, plan);
            case TOWER_STONE -> towerStone(f, plan);
            case OUTPOST -> outpost(f, plan, color);
            default -> {
            }
        }
        return true;
    }

    private static void box(Frame f, Plan plan, int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) plan.put(f.at(x, y, z), f.turned(s));
    }

    /** Walls round the edge of a box (posts at the corners in another block). */
    private static void walls(Frame f, Plan plan, int x0, int z0, int x1, int z1, int y0, int y1, BlockState wall, BlockState post) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edgeX = x == x0 || x == x1, edgeZ = z == z0 || z == z1;
                    if (!edgeX && !edgeZ) continue;
                    plan.put(f.at(x, y, z), f.turned(edgeX && edgeZ ? post : wall));
                }
            }
        }
    }

    private static void farm(Frame f, Plan plan) {
        box(f, plan, -4, -1, -4, 4, -1, 4, d(Blocks.DIRT));
        box(f, plan, -3, -1, -3, 3, -1, 3, d(Blocks.FARMLAND));
        plan.put(f.at(0, -1, 0), d(Blocks.WATER));
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (x == 0 && z == 0) continue;
                plan.put(f.at(x, 0, z), d(Blocks.WHEAT).setValue(CropBlock.AGE, 3 + Math.floorMod(x * 7 + z * 3, 5)));
            }
        }
        walls(f, plan, -4, -4, 4, 4, 0, 0, d(Blocks.OAK_FENCE), d(Blocks.OAK_FENCE));
        plan.put(f.at(0, 0, -4), f.turned(d(Blocks.OAK_FENCE_GATE).setValue(FenceGateBlock.FACING, Direction.NORTH)));
        plan.put(f.at(-4, 1, -4), d(Blocks.HAY_BLOCK));
        plan.put(f.at(4, 1, 4), d(Blocks.COMPOSTER));
        plan.door = f.spot(0, 0, -7);
    }

    private static void roof(Frame f, Plan plan, int x0, int z0, int x1, int z1, int y, Block slab) {
        for (int x = x0 - 1; x <= x1 + 1; x++) for (int z = z0 - 1; z <= z1 + 1; z++) plan.put(f.at(x, y, z), slab(slab));
    }

    private static void woodcutter(Frame f, Plan plan) {
        box(f, plan, -2, -1, -2, 2, -1, 2, d(Blocks.SPRUCE_PLANKS));
        walls(f, plan, -2, -1, 2, 2, 0, 2, d(Blocks.SPRUCE_PLANKS), d(Blocks.SPRUCE_LOG));
        plan.put(f.at(0, 0, -1), d(Blocks.AIR));
        plan.put(f.at(0, 1, -1), d(Blocks.AIR));
        plan.put(f.at(-2, 1, 1), d(Blocks.GLASS_PANE));
        plan.put(f.at(2, 1, 1), d(Blocks.GLASS_PANE));
        roof(f, plan, -2, -1, 2, 2, 3, Blocks.SPRUCE_SLAB);
        plan.put(f.at(1, 0, 1), d(Blocks.CRAFTING_TABLE));
        // the chopping block and the woodpile out front
        plan.put(f.at(-2, 0, -2), d(Blocks.OAK_LOG));
        plan.put(f.at(1, 0, -2), f.turned(logX(Blocks.OAK_LOG)));
        plan.put(f.at(2, 0, -2), f.turned(logX(Blocks.OAK_LOG)));
        plan.put(f.at(2, 1, -2), f.turned(logX(Blocks.OAK_LOG)));
        plan.door = f.spot(0, 0, -4);
    }

    private static void mine(Frame f, Plan plan, Block ore) {
        box(f, plan, -2, -1, -2, 2, -1, 2, d(Blocks.COBBLESTONE));
        box(f, plan, -1, -4, -1, 1, -1, 1, d(Blocks.AIR));
        for (int y = -4; y <= -2; y++) walls(f, plan, -2, -2, 2, 2, y, y, d(Blocks.COBBLESTONE), d(Blocks.COBBLESTONE));
        box(f, plan, -1, -5, -1, 1, -5, 1, d(Blocks.STONE));
        plan.put(f.at(0, -5, 0), d(ore));
        plan.put(f.at(1, -5, 1), d(ore));
        plan.put(f.at(-1, -4, 1), d(ore));
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) box(f, plan, c[0], 0, c[1], c[0], 2, c[1], d(Blocks.OAK_LOG));
        roof(f, plan, -2, -2, 2, 2, 3, Blocks.SPRUCE_SLAB);
        plan.put(f.at(0, 2, 0), d(Blocks.LANTERN).setValue(LanternBlock.HANGING, true));
        plan.put(f.at(2, 0, 0), d(ore));
        plan.put(f.at(-2, 0, 0), d(ore));
        plan.put(f.at(2, 0, 1), d(Blocks.CHEST));
        plan.put(f.at(-2, 0, 1), d(Blocks.RAIL));
        plan.spots.add(f.spot(0, 0, -2));
        plan.door = f.spot(0, 0, -4);
    }

    private static void quarry(Frame f, Plan plan) {
        box(f, plan, -3, -1, -3, 3, -1, 3, d(Blocks.COBBLESTONE));
        // the rock face hewn at the back
        for (int x = -3; x <= 3; x++) {
            int h = 2 + Math.floorMod(x * 5, 3);
            for (int y = 0; y <= h; y++) {
                plan.put(f.at(x, y, 3), d(y % 2 == 0 ? Blocks.STONE : Blocks.ANDESITE));
                if (y < h - 1) plan.put(f.at(x, y, 2), d(Blocks.STONE));
            }
        }
        plan.put(f.at(-3, 0, 1), d(Blocks.COBBLESTONE));
        plan.put(f.at(-3, 1, 1), d(Blocks.COBBLESTONE_SLAB));
        plan.put(f.at(-2, 0, 1), d(Blocks.COBBLESTONE));
        plan.put(f.at(3, 0, -2), d(Blocks.STONECUTTER));
        // a crane to lift the blocks
        for (int y = 0; y <= 4; y++) plan.put(f.at(3, y, 0), d(Blocks.OAK_LOG));
        plan.put(f.at(2, 4, 0), f.turned(logX(Blocks.OAK_LOG)));
        plan.put(f.at(1, 4, 0), f.turned(logX(Blocks.OAK_LOG)));
        plan.put(f.at(1, 3, 0), d(Blocks.IRON_CHAIN));
        plan.spots.add(f.spot(0, 0, 1));
        plan.door = f.spot(0, 0, -5);
    }

    private static void keep(Frame f, Plan plan, int color) {
        box(f, plan, -4, -1, -4, 4, -1, 4, d(Blocks.STONE_BRICKS));
        walls(f, plan, -4, -4, 4, 4, 0, 5, d(Blocks.STONE_BRICKS), d(Blocks.CHISELED_STONE_BRICKS));
        box(f, plan, -3, 5, -3, 3, 5, 3, d(Blocks.STONE_BRICKS));
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) != 4 && Math.abs(z) != 4) continue;
                if (Math.floorMod(x + z, 2) == 0) plan.put(f.at(x, 6, z), d(Blocks.STONE_BRICK_WALL));
            }
        }
        // corner turrets
        for (int[] c : new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) {
            box(f, plan, c[0], 6, c[1], c[0], 7, c[1], d(Blocks.CHISELED_STONE_BRICKS));
            plan.put(f.at(c[0], 8, c[1]), d(Blocks.LANTERN));
        }
        for (int y = 0; y <= 2; y++) plan.put(f.at(0, y, -4), d(Blocks.AIR));
        for (int x : new int[]{-2, 2}) for (int y : new int[]{1, 3}) plan.put(f.at(x, y, -4), d(Blocks.IRON_BARS));
        for (int z : new int[]{-2, 2}) for (int y : new int[]{1, 3}) {
            plan.put(f.at(-4, y, z), d(Blocks.IRON_BARS));
            plan.put(f.at(4, y, z), d(Blocks.IRON_BARS));
        }
        BlockState wool = d(Dyes.wool(color));
        plan.put(f.at(-1, 3, -4), wool);
        plan.put(f.at(0, 3, -4), wool);
        plan.put(f.at(1, 3, -4), wool);
        plan.put(f.at(0, 4, -4), wool);
        // the flag over it
        for (int y = 6; y <= 9; y++) plan.put(f.at(0, y, 0), d(Blocks.OAK_FENCE));
        plan.put(f.at(1, 9, 0), wool);
        plan.put(f.at(2, 9, 0), wool);
        plan.put(f.at(1, 8, 0), wool);
        plan.put(f.at(-3, 0, 3), d(Blocks.CHEST));
        plan.put(f.at(3, 0, 3), d(Blocks.BARREL));
        plan.put(f.at(-3, 0, -3), d(Blocks.LANTERN));
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) plan.spots.add(f.spot(c[0], 6, c[1]));
        plan.door = f.spot(0, 0, -6);
    }

    private static void house(Frame f, Plan plan) {
        box(f, plan, -2, -1, -2, 2, -1, 2, d(Blocks.OAK_PLANKS));
        walls(f, plan, -2, -2, 2, 2, 0, 2, d(Blocks.OAK_PLANKS), d(Blocks.SPRUCE_LOG));
        plan.put(f.at(0, 0, -2), d(Blocks.AIR));
        plan.put(f.at(0, 1, -2), d(Blocks.AIR));
        plan.put(f.at(-2, 1, 0), d(Blocks.GLASS_PANE));
        plan.put(f.at(2, 1, 0), d(Blocks.GLASS_PANE));
        roof(f, plan, -2, -2, 2, 2, 3, Blocks.SPRUCE_SLAB);
        box(f, plan, -1, 3, -1, 1, 3, 1, d(Blocks.SPRUCE_PLANKS));
        plan.put(f.at(0, 4, 0), slab(Blocks.SPRUCE_SLAB));
        plan.put(f.at(1, 4, 1), d(Blocks.COBBLESTONE_WALL));
        plan.put(f.at(1, 0, 1), d(Blocks.CRAFTING_TABLE));
        plan.put(f.at(-1, 0, 1), d(Blocks.BARREL));
        plan.door = f.spot(0, 0, -4);
    }

    private static void stockpile(Frame f, Plan plan) {
        box(f, plan, -3, -1, -3, 3, -1, 3, d(Blocks.COARSE_DIRT));
        walls(f, plan, -3, -3, 3, 3, 0, 0, d(Blocks.OAK_FENCE), d(Blocks.OAK_LOG));
        plan.put(f.at(0, 0, -3), d(Blocks.AIR));
        plan.put(f.at(-1, 0, -3), d(Blocks.AIR));
        plan.put(f.at(-2, 0, 2), d(Blocks.CHEST));
        plan.put(f.at(-1, 0, 2), d(Blocks.BARREL));
        plan.put(f.at(-1, 1, 2), d(Blocks.BARREL));
        plan.put(f.at(1, 0, 2), d(Blocks.HAY_BLOCK));
        plan.put(f.at(2, 0, 2), d(Blocks.HAY_BLOCK));
        plan.put(f.at(2, 1, 2), d(Blocks.HAY_BLOCK));
        for (int z = -1; z <= 1; z++) {
            plan.put(f.at(2, 0, z), f.turned(d(Blocks.OAK_LOG).setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z)));
            plan.put(f.at(-2, 0, z), d(Blocks.COBBLESTONE));
        }
        plan.put(f.at(2, 1, 0), f.turned(d(Blocks.OAK_LOG).setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z)));
        plan.put(f.at(-2, 1, 0), d(Blocks.COBBLESTONE));
        plan.door = f.spot(0, 0, -5);
    }

    private static void market(Frame f, Plan plan, int color) {
        box(f, plan, -3, -1, -3, 3, -1, 3, d(Blocks.SMOOTH_STONE));
        BlockState wool = d(Dyes.wool(color)), white = d(Blocks.WOOL.white());
        for (int sx : new int[]{-2, 2}) {
            for (int[] c : new int[][]{{sx - 1, -2}, {sx + 1, -2}, {sx - 1, 0}, {sx + 1, 0}}) box(f, plan, c[0], 0, c[1], c[0], 1, c[1], d(Blocks.OAK_FENCE));
            for (int x = sx - 1; x <= sx + 1; x++) for (int z = -2; z <= 0; z++) plan.put(f.at(x, 2, z), (x + z) % 2 == 0 ? wool : white);
            plan.put(f.at(sx, 0, -1), d(Blocks.BARREL));
        }
        plan.put(f.at(0, 0, 2), d(Blocks.COMPOSTER));
        plan.put(f.at(-1, 0, 2), d(Blocks.HAY_BLOCK));
        plan.put(f.at(1, 0, 2), d(Blocks.CHEST));
        plan.put(f.at(-3, 0, 2), d(Blocks.LECTERN));
        plan.door = f.spot(0, 0, -5);
    }

    private static void blacksmith(Frame f, Plan plan) {
        box(f, plan, -3, -1, -3, 3, -1, 3, d(Blocks.STONE_BRICKS));
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) box(f, plan, c[0], 0, c[1], c[0], 3, c[1], d(Blocks.DARK_OAK_LOG));
        for (int x = -2; x <= 2; x++) plan.put(f.at(x, 0, 3), d(Blocks.COBBLESTONE));
        for (int z = -2; z <= 2; z++) plan.put(f.at(3, 0, z), d(Blocks.COBBLESTONE));
        roof(f, plan, -3, -3, 3, 3, 4, Blocks.DARK_OAK_SLAB);
        for (int y = 0; y <= 6; y++) plan.put(f.at(-3, y, 3), d(Blocks.COBBLESTONE));
        plan.put(f.at(-2, 0, 2), d(Blocks.BLAST_FURNACE));
        plan.put(f.at(-1, 0, 2), d(Blocks.LAVA_CAULDRON));
        plan.put(f.at(1, 0, 2), d(Blocks.SMITHING_TABLE));
        plan.put(f.at(0, 0, 0), d(Blocks.ANVIL));
        plan.put(f.at(2, 0, -1), d(Blocks.GRINDSTONE));
        plan.put(f.at(2, 0, 1), d(Blocks.FURNACE));
        plan.door = f.spot(0, 0, -5);
    }

    private static void barracks(Frame f, Plan plan, int color) {
        box(f, plan, -4, -1, -3, 4, -1, 3, d(Blocks.STONE_BRICKS));
        walls(f, plan, -4, -3, 4, 3, 0, 2, d(Blocks.COBBLESTONE), d(Blocks.OAK_LOG));
        walls(f, plan, -4, -3, 4, 3, 3, 3, d(Blocks.OAK_PLANKS), d(Blocks.OAK_LOG));
        plan.put(f.at(0, 0, -3), d(Blocks.AIR));
        plan.put(f.at(0, 1, -3), d(Blocks.AIR));
        for (int x : new int[]{-2, 2}) plan.put(f.at(x, 1, -3), d(Blocks.GLASS_PANE));
        plan.put(f.at(-4, 1, 0), d(Blocks.GLASS_PANE));
        plan.put(f.at(4, 1, 0), d(Blocks.GLASS_PANE));
        BlockState wool = d(Dyes.wool(color));
        for (int x = -3; x <= 3; x++) plan.put(f.at(x, 3, -3), wool);
        roof(f, plan, -4, -3, 4, 3, 4, Blocks.DARK_OAK_SLAB);
        plan.put(f.at(-3, 0, 2), d(Blocks.SMITHING_TABLE));
        plan.put(f.at(3, 0, 2), d(Blocks.ANVIL));
        plan.put(f.at(-3, 0, -2), d(Blocks.BARREL));
        plan.put(f.at(3, 0, -2), d(Blocks.TARGET));
        plan.put(f.at(-2, -1 + 1, 2), d(Blocks.HAY_BLOCK));
        plan.door = f.spot(0, 0, -7);
    }

    private static void workshop(Frame f, Plan plan) {
        box(f, plan, -4, -1, -4, 4, -1, 4, d(Blocks.COBBLESTONE));
        for (int[] c : new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}, {-4, 0}, {4, 0}}) box(f, plan, c[0], 0, c[1], c[0], 3, c[1], d(Blocks.OAK_LOG));
        for (int z = -4; z <= 4; z++) {
            plan.put(f.at(-4, 3, z), f.turned(d(Blocks.OAK_LOG).setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z)));
            plan.put(f.at(4, 3, z), f.turned(d(Blocks.OAK_LOG).setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z)));
        }
        roof(f, plan, -4, -4, 4, 4, 4, Blocks.SPRUCE_SLAB);
        plan.put(f.at(-3, 0, 3), d(Blocks.ANVIL));
        plan.put(f.at(-1, 0, 3), d(Blocks.GRINDSTONE));
        plan.put(f.at(1, 0, 3), d(Blocks.SMITHING_TABLE));
        plan.put(f.at(3, 0, 3), d(Blocks.CRAFTING_TABLE));
        for (int x = -2; x <= 2; x++) plan.put(f.at(x, 0, 1), f.turned(logX(Blocks.SPRUCE_LOG)));
        plan.put(f.at(-3, 0, -3), d(Blocks.COBBLESTONE));
        plan.put(f.at(-3, 1, -3), d(Blocks.COBBLESTONE));
        plan.put(f.at(-2, 0, -3), d(Blocks.COBBLESTONE));
        plan.door = f.spot(0, 0, -9);
    }

    /** A little stockade with a lookout and the realm's flag: one soldier keeps it. */
    private static void outpost(Frame f, Plan plan, int color) {
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) box(f, plan, c[0], 0, c[1], c[0], 2, c[1], d(Blocks.SPRUCE_LOG));
        for (int i = -1; i <= 1; i++) {
            plan.put(f.at(i, 0, 2), d(Blocks.OAK_FENCE));
            plan.put(f.at(-2, 0, i), d(Blocks.OAK_FENCE));
            plan.put(f.at(2, 0, i), d(Blocks.OAK_FENCE));
            if (i != 0) plan.put(f.at(i, 0, -2), d(Blocks.OAK_FENCE));
        }
        box(f, plan, -1, 0, -1, 1, 0, 1, d(Blocks.SPRUCE_PLANKS));
        box(f, plan, -1, 1, -1, 1, 1, 1, d(Blocks.SPRUCE_SLAB));
        box(f, plan, 1, 2, 1, 1, 5, 1, d(Blocks.OAK_FENCE));
        BlockState wool = d(Dyes.wool(color));
        plan.put(f.at(0, 5, 1), wool);
        plan.put(f.at(-1, 5, 1), wool);
        plan.put(f.at(0, 4, 1), wool);
        plan.put(f.at(-2, 3, -2), d(Blocks.TORCH));
        plan.put(f.at(2, 3, 2), d(Blocks.TORCH));
        plan.spots.add(f.spot(0, 2, -0.2));
        plan.door = f.spot(0, 0, -4);
    }

    private static void towerWood(Frame f, Plan plan) {
        box(f, plan, -1, 0, -1, 1, 5, 1, d(Blocks.OAK_LOG));
        box(f, plan, -2, 6, -2, 2, 6, 2, d(Blocks.SPRUCE_PLANKS));
        walls(f, plan, -2, -2, 2, 2, 7, 7, d(Blocks.OAK_FENCE), d(Blocks.OAK_FENCE));
        plan.put(f.at(-2, 8, -2), d(Blocks.TORCH));
        plan.put(f.at(2, 8, 2), d(Blocks.TORCH));
        // four bowmen at the corners of the platform (the middle for a ballista)
        for (int[] c : new int[][]{{-1, -1}, {1, 1}, {1, -1}, {-1, 1}}) plan.spots.add(f.spot(c[0], 7, c[1]));
    }

    private static void towerStone(Frame f, Plan plan) {
        walls(f, plan, -2, -2, 2, 2, 0, 9, d(Blocks.STONE_BRICKS), d(Blocks.CHISELED_STONE_BRICKS));
        box(f, plan, -1, 0, -1, 1, 9, 1, d(Blocks.COBBLESTONE));
        box(f, plan, -3, 10, -3, 3, 10, 3, d(Blocks.STONE_BRICKS));
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (Math.abs(x) != 3 && Math.abs(z) != 3) continue;
                if (Math.floorMod(x + z, 2) == 0) plan.put(f.at(x, 11, z), d(Blocks.STONE_BRICK_WALL));
            }
        }
        // eight bowmen round the top, each at his own gap in the parapet (the middle for an engine)
        for (int[] c : new int[][]{{-2, -2}, {2, 2}, {2, -2}, {-2, 2}, {0, -2}, {0, 2}, {-2, 0}, {2, 0}}) plan.spots.add(f.spot(c[0], 11, c[1]));
    }

    // ------------------------------------------------------------------ lines

    /** The columns of a line from a to b (every step of the longer axis). */
    static List<int[]> line(BlockPos a, BlockPos b) {
        List<int[]> out = new ArrayList<>();
        int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ(), n = Math.max(Math.abs(dx), Math.abs(dz));
        for (int i = 0; i <= n; i++) {
            int x = a.getX() + (n == 0 ? 0 : Math.round(dx * i / (float) n)), z = a.getZ() + (n == 0 ? 0 : Math.round(dz * i / (float) n));
            if (out.isEmpty() || out.get(out.size() - 1)[0] != x || out.get(out.size() - 1)[1] != z) out.add(new int[]{x, z});
        }
        return out;
    }

    private static boolean wall(ServerLevel level, Structure s, Plan plan) {
        boolean stone = s.type == StructureType.WALL_STONE;
        int dx = s.end.getX() - s.origin.getX(), dz = s.end.getZ() - s.origin.getZ();
        // the second course of a stone wall: to the side
        int ox = Math.abs(dx) >= Math.abs(dz) ? 0 : 1, oz = Math.abs(dx) >= Math.abs(dz) ? 1 : 0;
        List<int[]> cols = line(s.origin, s.end);
        int height = stone ? 5 : 4;
        int i = 0;
        for (int[] c : cols) {
            for (int k = 0; k < (stone ? 2 : 1); k++) {
                int x = c[0] + ox * k, z = c[1] + oz * k;
                int g = ground(level, x, z);
                for (int y = 0; y <= height + 2; y++) {
                    BlockPos p = new BlockPos(x, g + y, z);
                    if (!level.getBlockState(p).isAir()) plan.ready(p, d(Blocks.AIR));
                }
                for (int y = 0; y < height; y++) {
                    BlockState b = stone ? (y == 0 ? d(Blocks.COBBLESTONE) : d(Blocks.STONE_BRICKS)) : d(Blocks.OAK_LOG);
                    plan.put(new BlockPos(x, g + y, z), b);
                }
                if (stone && k == 1 && i % 2 == 0) plan.put(new BlockPos(x, g + height, z), d(Blocks.STONE_BRICK_WALL));
                if (!stone && i % 2 == 0) plan.put(new BlockPos(x, g + height, z), d(Blocks.OAK_FENCE));
                if (k == 0 && i % 3 == 1) plan.spots.add(new Vec3(x + 0.5, g + height, z + 0.5));
            }
            i++;
        }
        return true;
    }

    /**
     * A gate in its wall: the wall's blocks out of those columns, a lintel over the gap, and the portcullis that hangs
     * in it (raised and dropped as the gate opens and shuts).
     */
    private static boolean gate(ServerLevel level, Structure s, Plan plan) {
        Structure wall = org.webtrade.strategy1.game.StrategyManager.data().structures.get(s.parent);
        if (wall == null) return false;
        boolean stone = wall.type == StructureType.WALL_STONE;
        int height = stone ? 5 : 4;
        int dx = wall.end.getX() - wall.origin.getX(), dz = wall.end.getZ() - wall.origin.getZ();
        int ox = Math.abs(dx) >= Math.abs(dz) ? 0 : 1, oz = Math.abs(dx) >= Math.abs(dz) ? 1 : 0;
        for (int[] c : line(s.origin, s.end)) {
            for (int k = 0; k < (stone ? 2 : 1); k++) {
                int x = c[0] + ox * k, z = c[1] + oz * k;
                // the foot of the wall there
                int base = Integer.MAX_VALUE;
                for (long p : wall.pos) {
                    BlockPos q = BlockPos.of(p);
                    if (q.getX() == x && q.getZ() == z) base = Math.min(base, q.getY());
                }
                if (base == Integer.MAX_VALUE) base = ground(level, x, z);
                for (int y = 0; y < height + 1; y++) plan.ready(new BlockPos(x, base + y, z), d(Blocks.AIR));
                plan.put(new BlockPos(x, base + height - 1, z), stone ? d(Blocks.CHISELED_STONE_BRICKS) : d(Blocks.STRIPPED_SPRUCE_LOG));
                plan.put(new BlockPos(x, base + height, z), stone ? d(Blocks.STONE_BRICK_WALL) : d(Blocks.OAK_FENCE));
                for (int y = 0; y < height - 1; y++) plan.leaves.add(new BlockPos(x, base + y, z).asLong());
            }
        }
        return true;
    }

    /** What a gate's portcullis is made of. */
    public static BlockState leaf(Structure gate) {
        Structure wall = org.webtrade.strategy1.game.StrategyManager.data().structures.get(gate.parent);
        return wall != null && wall.type == StructureType.WALL_STONE ? d(Blocks.IRON_BARS) : d(Blocks.SPRUCE_FENCE);
    }

    private static boolean path(ServerLevel level, Structure s, Plan plan) {
        int dx = s.end.getX() - s.origin.getX(), dz = s.end.getZ() - s.origin.getZ();
        int ox = Math.abs(dx) >= Math.abs(dz) ? 0 : 1, oz = Math.abs(dx) >= Math.abs(dz) ? 1 : 0;
        for (int[] c : line(s.origin, s.end)) {
            for (int k = -1; k <= 1; k++) {
                int x = c[0] + ox * k, z = c[1] + oz * k;
                int g = ground(level, x, z);
                BlockPos top = new BlockPos(x, g - 1, z);
                BlockState under = level.getBlockState(top);
                if (water(level, top)) {
                    // a plank bridge over the water
                    BlockPos w = top;
                    while (water(level, w.above())) w = w.above();
                    plan.put(w, slab(Blocks.SPRUCE_SLAB).setValue(SlabBlock.TYPE, SlabType.TOP));
                    if (Math.abs(k) == 1) plan.put(w.above(), d(Blocks.SPRUCE_FENCE));
                    continue;
                }
                for (int y = 0; y <= 2; y++) {
                    BlockPos p = top.above(1 + y);
                    BlockState b = level.getBlockState(p);
                    if (!b.isAir() && soft(b)) plan.ready(p, d(Blocks.AIR));
                }
                if (under.is(Blocks.GRASS_BLOCK) || under.is(Blocks.DIRT) || under.is(Blocks.COARSE_DIRT) || under.is(Blocks.PODZOL)
                        || under.is(Blocks.MYCELIUM) || under.is(Blocks.ROOTED_DIRT) || under.is(Blocks.SAND) || under.is(Blocks.RED_SAND)
                        || under.is(Blocks.GRAVEL) || under.is(Blocks.SNOW_BLOCK) || under.is(Blocks.MUD)) {
                    plan.put(top, d(Blocks.DIRT_PATH));
                } else if (under.is(BlockTags.BASE_STONE_OVERWORLD)) {
                    plan.put(top, d(Blocks.COBBLESTONE));
                }
            }
        }
        return true;
    }

    /** An outcrop of rock on the land: a mound of stone, with iron or gold showing in it. */
    private static boolean deposit(ServerLevel level, Structure s, Plan plan) {
        Block ore = s.type == StructureType.DEPOSIT_IRON ? Blocks.IRON_ORE : s.type == StructureType.DEPOSIT_GOLD ? Blocks.GOLD_ORE : null;
        int x0 = s.origin.getX(), z0 = s.origin.getZ();
        if (water(level, new BlockPos(x0, ground(level, x0, z0) - 1, z0))) return false;
        long seed = s.origin.asLong() * 31 + s.id;
        java.util.Random r = new java.util.Random(seed);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz) + r.nextDouble() * 0.8;
                if (d > 3.4) continue;
                int g = ground(level, x0 + dx, z0 + dz);
                int h = (int) Math.round(3.2 - d);
                for (int y = -1; y < h; y++) {
                    BlockPos p = new BlockPos(x0 + dx, g + y, z0 + dz);
                    Block b;
                    if (ore != null && r.nextFloat() < 0.35F) b = ore;
                    else {
                        float f = r.nextFloat();
                        b = f < 0.45F ? Blocks.STONE : f < 0.7F ? Blocks.ANDESITE : f < 0.85F ? Blocks.COBBLESTONE : Blocks.MOSSY_COBBLESTONE;
                    }
                    plan.put(p, d(b));
                }
                if (h <= 0 && r.nextFloat() < 0.6F) plan.put(new BlockPos(x0 + dx, g - 1, z0 + dz), d(Blocks.GRAVEL));
                // (snow and grass on it go)
                BlockPos above = new BlockPos(x0 + dx, g + Math.max(0, h), z0 + dz);
                if (!level.getBlockState(above).isAir() && soft(level.getBlockState(above))) plan.ready(above, d(Blocks.AIR));
            }
        }
        return true;
    }

    /** The ground (or the water's surface) at a column: where a bridge's end would rest. */
    private static int rest(ServerLevel level, int x, int z) {
        int g = ground(level, x, z);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, g, z);
        while (water(level, p)) p.move(0, 1, 0);
        return p.getY();
    }

    /** How far a bridge may climb for its length (half a block a step, a block at worst). */
    public static boolean bridgeTooSteep(ServerLevel level, BlockPos a, BlockPos b) {
        int rise = Math.abs(rest(level, a.getX(), a.getZ()) - rest(level, b.getX(), b.getZ()));
        return rise > Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
    }

    private static boolean bridge(ServerLevel level, Structure s, Plan plan) {
        // from the ground at one end to the ground at the other: a slope in half blocks (slabs), not one level
        int deckA = rest(level, s.origin.getX(), s.origin.getZ()), deckB = rest(level, s.end.getX(), s.end.getZ());
        int dx = s.end.getX() - s.origin.getX(), dz = s.end.getZ() - s.origin.getZ();
        int ox = Math.abs(dx) >= Math.abs(dz) ? 0 : 1, oz = Math.abs(dx) >= Math.abs(dz) ? 1 : 0;
        List<int[]> cols = line(s.origin, s.end);
        int i = 0;
        for (int[] c : cols) {
            boolean end = i == 0 || i == cols.size() - 1;
            double t = cols.size() <= 1 ? 0 : i / (double) (cols.size() - 1);
            int h2 = (int) Math.round((deckA + (deckB - deckA) * t) * 2);
            int deck = Math.floorDiv(h2, 2);
            boolean half = (h2 & 1) == 1;
            // three to walk on, a rail each side
            for (int k = -2; k <= 2; k++) {
                int x = c[0] + ox * k, z = c[1] + oz * k;
                for (int y = 0; y <= 3; y++) {
                    BlockPos p = new BlockPos(x, deck + y, z);
                    if (!level.getBlockState(p).isAir() && !water(level, p)) plan.ready(p, d(Blocks.AIR));
                }
                plan.put(new BlockPos(x, deck - 1, z), d(Blocks.SPRUCE_PLANKS));
                if (half) plan.put(new BlockPos(x, deck, z), d(Blocks.SPRUCE_SLAB));
                if (Math.abs(k) == 2 && !end) plan.put(new BlockPos(x, half ? deck + 1 : deck, z), d(Blocks.SPRUCE_FENCE));
                // the piers, every few blocks, down to the bottom
                if (Math.abs(k) == 2 && i % 4 == 0) {
                    for (int y = deck - 2; y > deck - 24; y--) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState b = level.getBlockState(p);
                        if (!b.isAir() && !water(level, p) && !soft(b)) break;
                        plan.put(p, d(Blocks.STRIPPED_SPRUCE_LOG));
                    }
                    if (!end) plan.put(new BlockPos(x, half ? deck + 2 : deck + 1, z), d(Blocks.LANTERN));
                }
            }
            i++;
        }
        return true;
    }

    private static boolean port(ServerLevel level, Structure s, Plan plan) {
        Frame probe = new Frame(s.origin, s.rot);
        // the water's surface out in front
        int wet = 0, surface = Integer.MIN_VALUE;
        for (int z = 3; z <= 10; z++) {
            BlockPos p = probe.at(0, 0, z);
            int g = level.getHeight(Heightmap.Types.WORLD_SURFACE, p.getX(), p.getZ()) - 1;
            BlockPos top = new BlockPos(p.getX(), g, p.getZ());
            if (water(level, top) || level.getBlockState(top).is(Blocks.ICE)) {
                wet++;
                surface = Math.max(surface, g);
            }
        }
        if (wet < 5) return false;
        // (ice in front of it is broken, a channel for the ships)
        for (int x = -4; x <= 4; x++) {
            for (int z = 0; z <= 18; z++) {
                BlockPos p = probe.at(x, 0, z);
                BlockPos top = new BlockPos(p.getX(), surface, p.getZ());
                if (level.getBlockState(top).is(Blocks.ICE)) plan.ready(top, d(Blocks.WATER));
            }
        }
        BlockPos deck0 = new BlockPos(s.origin.getX(), surface + 1, s.origin.getZ());
        Frame f = new Frame(deck0, s.rot);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 10; z++) {
                BlockPos p = f.at(x, 0, z);
                for (int y = 0; y <= 4; y++) {
                    BlockPos q = p.above(y);
                    if (!level.getBlockState(q).isAir() && !water(level, q)) plan.ready(q, d(Blocks.AIR));
                }
                plan.put(p.below(), d(Blocks.SPRUCE_PLANKS));
                boolean post = (x == -2 || x == 2) && z % 4 == 0;
                if (post) {
                    for (int y = 2; y <= 7; y++) {
                        BlockPos q = p.below(y);
                        if (!water(level, q) && !level.getBlockState(q).isAir()) break;
                        plan.put(q, d(Blocks.OAK_LOG));
                    }
                }
                if ((x == -2 || x == 2) && z >= 0) plan.put(p, d(Blocks.OAK_FENCE));
            }
        }
        plan.put(f.at(-2, 1, 10), d(Blocks.LANTERN));
        plan.put(f.at(2, 1, 10), d(Blocks.LANTERN));
        plan.put(f.at(-1, 0, -1), d(Blocks.BARREL));
        plan.put(f.at(1, 0, -1), d(Blocks.CHEST));
        plan.put(f.at(1, 0, -2), d(Blocks.BARREL));
        // a crane at the end
        for (int y = 0; y <= 3; y++) plan.put(f.at(2, y, 9), d(Blocks.OAK_LOG));
        plan.put(f.at(1, 3, 9), f.turned(logX(Blocks.OAK_LOG)));
        plan.put(f.at(0, 3, 9), f.turned(logX(Blocks.OAK_LOG)));
        plan.put(f.at(0, 2, 9), d(Blocks.IRON_CHAIN));
        plan.door = f.spot(0, -1, 14);
        return true;
    }
}
