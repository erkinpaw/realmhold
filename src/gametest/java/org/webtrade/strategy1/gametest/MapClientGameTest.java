package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.screen.StrategyMapScreen;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.hex.Hex;

/** The realms map after the camera has gone round the capital: the land as seen, the buildings, the units. */
public class MapClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            context.getInput().resizeWindow(1600, 900);
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 6000");
            server.waitFor(s -> StrategyData.get(s).generated && StrategyData.get(s).factionOf(s.getPlayerList().getPlayers().getFirst().getUUID()) != null, 20 * 30);
            context.waitFor(mc -> Rts.active(), 20 * 20);
            long[] cap = new long[1];
            server.runOnServer(s -> cap[0] = StrategyData.get(s).factionOf(s.getPlayerList().getPlayers().getFirst().getUUID()).capital);
            Hex h = Hex.of(cap[0]);
            double cx = h.centerX(), cz = h.centerZ();
            server.waitFor(s -> Structures.has(StrategyData.get(s).factionOf(s.getPlayerList().getPlayers().getFirst().getUUID()).id, StructureType.KEEP), 20 * 30);
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                var f = StrategyData.get(s).factionOf(p.getUUID());
                for (double[] o : new double[][]{{-24, -18, 2}, {22, 10, 9}, {4, -24, 1}, {11, -24, 1}}) {
                    StructureType t = StructureType.values()[(int) o[2]];
                    int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) (cx + o[0]), (int) (cz + o[1]));
                    Structures.place(p, f, t, BlockPos.containing(cx + o[0], y, cz + o[1]), null, 0);
                }
            });
            for (double[] o : new double[][]{{0, 0}, {120, 0}, {120, 120}, {0, 120}, {-120, 120}, {-120, 0}, {-120, -120}, {0, -120}, {120, -120}, {0, 0}}) {
                context.runOnClient(mc -> Rts.jump(cx + o[0], cz + o[1]));
                context.waitTicks(80);
            }
            context.waitTicks(100);
            context.runOnClient(mc -> mc.gui.setScreen(new StrategyMapScreen(0, cap[0], cx, cz, 0.32F)));
            context.waitTicks(40);
            context.takeScreenshot("map_wide");
            context.runOnClient(mc -> mc.gui.setScreen(new StrategyMapScreen(0, cap[0], cx, cz, 1.2F)));
            context.waitTicks(40);
            context.takeScreenshot("map_close");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
