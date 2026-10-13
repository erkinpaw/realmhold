package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.build.Workforce;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.entity.PeasantEntity;
import org.webtrade.strategy1.game.Actions;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.Arrays;
import java.util.Set;

/**
 * The realm's economy, Stronghold-style, in a real world: the keep at the capital, peasants coming to it, the
 * workplaces put up (woodcutter by the woods, farm, quarry, mine, stockpile, houses, blacksmith, barracks), the
 * peasants sent to work and carrying their goods home, soldiers raised from them, a bridge over the water; the
 * icon panels. Screenshots in build/run/clientGameTest/screenshots.
 */
public class EconomyClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[eco] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    private static void look(ClientGameTestContext context, double x, double z, float distance, float yaw) {
        context.runOnClient(mc -> {
            Rts.jump(x, z);
            Rts.distance = distance;
            Rts.yaw = yaw;
        });
        context.waitTicks(60);
    }

    private static String build(MinecraftServer s, StructureType t, double x, double z, double x2, double z2, int rot) {
        int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        String err = Structures.place(player(s), me(s), t, BlockPos.containing(x, y, z), BlockPos.containing(x2, y, z2), rot);
        log("build {} at {} {}: {}", t, (int) x, (int) z, err == null ? "ok" : err);
        return err;
    }

    private static boolean water(ServerLevel l, int x, int z) {
        int y = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos p = new BlockPos(x, y, z);
        return l.getFluidState(p).is(FluidTags.WATER) || l.getBlockState(p).is(net.minecraft.world.level.block.Blocks.ICE);
    }

    private static void state(MinecraftServer s, String when) {
        Faction f = me(s);
        int working = 0, idle = 0;
        for (PeasantEntity p : s.overworld().getEntitiesOfClass(PeasantEntity.class, player(s).getBoundingBox().inflate(300), e -> e.faction() == f.id)) {
            if (p.idle()) idle++;
            else working++;
        }
        for (PeasantEntity p : s.overworld().getEntitiesOfClass(PeasantEntity.class, player(s).getBoundingBox().inflate(300), e -> e.faction() == f.id && e.workplace() != null && e.workplace().type == StructureType.WOODCUTTER))
            log("woodcutter: {}", p.describe());
        log("{}: delivered {} res {} peasants {} (working {}, idle {}) army {} room {} people {}", when, Arrays.toString(Workforce.DELIVERED), Arrays.toString(f.res), f.peasants, working, idle, f.army,
                Workforce.room(f.id), Workforce.people(f));
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            context.getInput().resizeWindow(1600, 900);
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.runOnClient(mc -> {
                mc.options.renderDistance().set(10);
                mc.options.simulationDistance().set(10);
                mc.options.broadcastOptions();
            });
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 6000");
            server.runCommand("weather clear");
            server.waitFor(s -> StrategyData.get(s).generated && me(s) != null, 20 * 30);
            context.waitFor(mc -> Rts.active(), 20 * 20);
            long[] capital = new long[1];
            server.runOnServer(s -> capital[0] = me(s).capital);
            Hex home = Hex.of(capital[0]);
            double cx = home.centerX(), cz = home.centerZ();
            look(context, cx, cz, 60, 200);
            server.waitFor(s -> Structures.has(me(s).id, StructureType.KEEP), 20 * 30);
            server.runOnServer(s -> {
                int[] n = new int[3];
                for (Structure st : StrategyData.get(s).structures.values()) {
                    if (st.type == StructureType.DEPOSIT_STONE) n[0]++;
                    if (st.type == StructureType.DEPOSIT_IRON) n[1]++;
                    if (st.type == StructureType.DEPOSIT_GOLD) n[2]++;
                }
                log("deposits in the world: stone {}, iron {}, gold {}", n[0], n[1], n[2]);
            });
            context.waitTicks(20 * 15);
            server.runOnServer(s -> state(s, "keep built"));
            look(context, cx, cz + 7, 30, 200);
            context.takeScreenshot("eco_keep");

            // the workplaces
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                // the woodcutter's hut by the nearest woods
                BlockPos tree = null;
                double best = Double.MAX_VALUE;
                for (int dx = -90; dx <= 90; dx += 2) {
                    for (int dz = -90; dz <= 90; dz += 2) {
                        int x = (int) cx + dx, z = (int) cz + dz;
                        int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                        if (l.getBlockState(new BlockPos(x, y, z)).is(BlockTags.LOGS) && Math.abs(dx) + Math.abs(dz) > 20 && dx * dx + dz * dz < best) {
                            best = dx * dx + dz * dz;
                            tree = new BlockPos(x, y, z);
                        }
                    }
                }
                log("nearest tree {}", tree);
                if (tree != null) {
                    double ux = cx + (tree.getX() - cx) * 0.75, uz = cz + (tree.getZ() - cz) * 0.75;
                    if (build(s, StructureType.WOODCUTTER, ux, uz, 0, 0, 0) != null) build(s, StructureType.WOODCUTTER, ux + 4, uz + 4, 0, 0, 0);
                }
                build(s, StructureType.FARM, cx - 24, cz - 18, 0, 0, 0);
                // the quarry and the mine by their deposits
                for (StructureType[] k : new StructureType[][]{{StructureType.QUARRY, StructureType.DEPOSIT_STONE}, {StructureType.IRON_MINE, StructureType.DEPOSIT_IRON}}) {
                    Structure dep = Structures.depositNear(k[1], new Vec3(cx, 64, cz), 400);
                    log("deposit {}: {}", k[1], dep == null ? "none" : dep.origin.toShortString() + " planned " + dep.planned);
                    if (dep == null) continue;
                    deposits.add(dep.origin);
                    boolean ok = false;
                    for (int[] o : new int[][]{{0, 0}, {8, 0}, {-8, 0}, {0, 8}, {0, -8}, {7, 7}, {-7, -7}, {7, -7}, {-7, 7}}) {
                        if (!ok) ok = build(s, k[0], dep.origin.getX() + o[0], dep.origin.getZ() + o[1], 0, 0, 0) == null;
                    }
                }
                build(s, StructureType.STOCKPILE, cx - 10, cz - 22, 0, 0, 0);
                build(s, StructureType.HOUSE, cx + 4, cz - 24, 0, 0, 0);
                build(s, StructureType.HOUSE, cx + 11, cz - 24, 0, 0, 0);
                build(s, StructureType.BARRACKS, cx + 22, cz + 10, 0, 0, 0);
                build(s, StructureType.MARKET, cx + 8, cz + 24, 0, 0, 0);
                // a bridge over the water by the capital
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
                outer:
                for (int r0 = 6; r0 < 80; r0 += 3) {
                    for (int[] d : dirs) {
                        int sx = (int) cx + d[0] * r0, sz = (int) cz + d[1] * r0;
                        if (water(l, sx, sz) || !water(l, sx + d[0], sz + d[1])) continue;
                        for (int k = 4; k < 46; k++) {
                            int ex = sx + d[0] * k, ez = sz + d[1] * k;
                            if (!water(l, ex, ez)) {
                                if (k > 5 && build(s, StructureType.BRIDGE, sx, sz, ex, ez, 0) == null) break outer;
                                break;
                            }
                        }
                    }
                }
            });
            context.waitTicks(20 * 25);
            server.runOnServer(s -> {
                build(s, StructureType.BLACKSMITH, cx + 22, cz - 8, 0, 0, 0);
                for (Structure st : StrategyData.get(s).structures.values()) {
                    if (st.owner == me(s).id) log("structure #{} {} built {} ({} / {})", st.id, st.type, st.built(), st.progress, st.pos.length);
                }
                state(s, "after building");
            });
            look(context, cx - 10, cz - 4, 70, 160);
            context.takeScreenshot("eco_overview");
            context.waitTicks(20 * 40);
            server.runOnServer(s -> state(s, "after a minute of work"));
            // the woodcutter at work
            server.runOnServer(s -> {
                Structure hut = Structures.nearest(me(s).id, StructureType.WOODCUTTER, new Vec3(cx, 64, cz));
                if (hut != null) {
                    look[0] = hut.center().x;
                    look[1] = hut.center().z;
                }
            });
            look(context, look[0], look[1], 26, 200);
            context.takeScreenshot("eco_woodcutter");
            for (int i = 0; i < deposits.size(); i++) {
                look(context, deposits.get(i).getX(), deposits.get(i).getZ(), 24, 200);
                context.takeScreenshot("eco_deposit" + i);
            }

            // soldiers from the peasants
            server.runOnServer(s -> {
                Structure barracks = Structures.nearest(me(s).id, StructureType.BARRACKS, new Vec3(cx, 64, cz));
                if (barracks == null) return;
                for (UnitType u : new UnitType[]{UnitType.MILITIA, UnitType.ARCHER, UnitType.SWORDSMAN})
                    Actions.structure(player(s), new Payloads.StructureAction(barracks.id, Payloads.StructureAction.MAKE, u.ordinal()));
                state(s, "after raising soldiers");
            });
            // the panels
            for (int t = 0; t < 5; t++) {
                int tt = t;
                context.runOnClient(mc -> {
                    RtsScreen.choose(Set.of(), -1);
                    RtsScreen.showTab(tt);
                    mc.gui.setScreen(new RtsScreen());
                });
                context.waitTicks(10);
                context.takeScreenshot("eco_tab" + t);
            }
            server.runOnServer(s -> {
                Structure barracks = Structures.nearest(me(s).id, StructureType.BARRACKS, new Vec3(cx, 64, cz));
                if (barracks != null) chosen[0] = barracks.id;
            });
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), chosen[0]);
                mc.gui.setScreen(new RtsScreen());
            });
            look(context, cx + 22, cz + 10, 30, 200);
            context.takeScreenshot("eco_barracks_chosen");
            server.runOnServer(s -> {
                for (Structure st : StrategyData.get(s).structures.values()) {
                    if (st.owner == me(s).id && st.type == StructureType.BRIDGE) {
                        look[0] = st.center().x;
                        look[1] = st.center().z;
                    }
                }
            });
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), -1);
                mc.gui.setScreen(new RtsScreen());
            });
            look(context, look[0], look[1], 30, 200);
            context.takeScreenshot("eco_bridge");
            server.runOnServer(s -> state(s, "end"));
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }

    private static final double[] look = new double[2];
    private static final java.util.List<BlockPos> deposits = new java.util.ArrayList<>();
    private static final int[] chosen = {-1};
}
