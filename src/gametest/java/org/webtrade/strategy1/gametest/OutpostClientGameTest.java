package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.game.Actions;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Province;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.List;

/**
 * Taking land with an outpost: soldiers standing about don't take it; an outpost put up on the free land next door
 * with a soldier in it does.
 */
public class OutpostClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[outpost] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    private static String build(MinecraftServer s, StructureType t, double x, double z) {
        int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        String err = Structures.place(player(s), me(s), t, BlockPos.containing(x, y, z), BlockPos.containing(x, y, z), 0);
        log("build {} at {} {}: {}", t, (int) x, (int) z, err == null ? "ok" : err);
        return err;
    }

    private static List<SoldierEntity> soldiers(MinecraftServer s) {
        return s.overworld().getEntitiesOfClass(SoldierEntity.class, player(s).getBoundingBox().inflate(300),
                e -> e.isAlive() && e.faction() == me(s).id && e.role == UnitEntity.Role.ARMY);
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
            server.waitFor(s -> StrategyData.get(s).generated && me(s) != null, 20 * 30);
            context.waitFor(mc -> Rts.active(), 20 * 20);
            long[] cap = new long[1];
            server.runOnServer(s -> cap[0] = me(s).capital);
            Hex h = Hex.of(cap[0]);
            double cx = h.centerX(), cz = h.centerZ();
            server.waitFor(s -> Structures.has(me(s).id, StructureType.KEEP), 20 * 30);
            // the free land next door (its bandits gone, to see the outpost alone at work)
            long[] next = {Long.MIN_VALUE};
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                double best = Double.MAX_VALUE;
                for (Hex n : h.neighbors()) {
                    for (Hex m : n.neighbors()) {
                        Province q = d.province(m);
                        if (q == null || !q.terrain.land() || q.owner == me(s).id || !d.borders(me(s).id, q.hex)) continue;
                        double dist = q.hex.distToCenter(cx, cz);
                        if (dist < best) {
                            best = dist;
                            next[0] = q.key();
                        }
                    }
                }
                Province q = d.province(next[0]);
                q.garrison = 0;
                log("to take: {} ({}) owner {}", q.name, q.terrain, q.owner);
            });
            Hex t = Hex.of(next[0]);
            server.runOnServer(s -> build(s, StructureType.BARRACKS, cx - 8, cz - 22));
            server.waitFor(s -> Structures.has(me(s).id, StructureType.BARRACKS) && me(s).peasants >= 3, 20 * 60);
            server.runOnServer(s -> {
                Structure b = Structures.nearest(me(s).id, StructureType.BARRACKS, new Vec3(cx, 64, cz));
                for (int i = 0; i < 2; i++) Actions.structure(player(s), new Payloads.StructureAction(b.id, Payloads.StructureAction.MAKE, UnitType.MILITIA.ordinal()));
            });
            server.waitFor(s -> soldiers(s).size() >= 2, 20 * 30);
            // 1. soldiers standing at the centre: nothing
            server.runOnServer(s -> {
                int[] ids = soldiers(s).stream().mapToInt(SoldierEntity::getId).toArray();
                Actions.order(player(s), new Payloads.UnitOrder(ids, Payloads.UnitOrder.MOVE, t.centerX(), 64, t.centerZ(), -1));
            });
            context.runOnClient(mc -> Rts.jump(t.centerX(), t.centerZ()));
            context.waitTicks(20 * 40);
            server.runOnServer(s -> {
                Province q = StrategyData.get(s).province(next[0]);
                log("soldiers at the centre for 40 s: owner {}", q.owner);
                if (q.owner == me(s).id) throw new AssertionError("standing soldiers took the land");
            });
            // 2. an outpost with a soldier in it
            int[] post = {-1};
            server.runOnServer(s -> {
                for (double[] o : new double[][]{{6, 6}, {-6, 6}, {6, -6}, {-6, -6}, {10, 0}, {-10, 0}, {0, 10}, {0, -10}}) {
                    if (build(s, StructureType.OUTPOST, t.centerX() + o[0], t.centerZ() + o[1]) == null) break;
                }
                for (Structure st : StrategyData.get(s).structures.values()) if (st.owner == me(s).id && st.type == StructureType.OUTPOST) post[0] = st.id;
            });
            server.waitFor(s -> post[0] >= 0 && StrategyData.get(s).structures.get(post[0]).built(), 20 * 30);
            server.runOnServer(s -> {
                SoldierEntity one = soldiers(s).getFirst();
                Actions.order(player(s), new Payloads.UnitOrder(new int[]{one.getId()}, Payloads.UnitOrder.POST, 0, 0, 0, post[0]));
            });
            server.waitFor(s -> StrategyData.get(s).structures.get(post[0]).archers >= 1, 20 * 40);
            server.runOnServer(s -> log("a soldier is in the outpost"));
            double[] at = new double[2];
            server.runOnServer(s -> {
                Structure o = StrategyData.get(s).structures.get(post[0]);
                at[0] = o.origin.getX();
                at[1] = o.origin.getZ();
            });
            context.runOnClient(mc -> {
                Rts.jump(at[0], at[1] - 4);
                Rts.distance = 24;
            });
            server.waitFor(s -> StrategyData.get(s).province(next[0]).owner == me(s).id, 20 * 60);
            server.runOnServer(s -> log("the province is ours: {}", StrategyData.get(s).province(next[0]).name));
            context.waitTicks(60);
            context.takeScreenshot("outpost");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
