package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.entity.PeasantEntity;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.hex.Hex;

/** An AI capital seen up close: its keep, walls, towers and town, and its folk at work. */
public class TownClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            context.getInput().resizeWindow(1600, 900);
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set 6000");
            server.waitFor(s -> StrategyData.get(s).generated, 20 * 30);
            context.waitFor(mc -> Rts.active(), 20 * 20);
            double[] at = new double[3];
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                Faction me = d.factionOf(s.getPlayerList().getPlayers().getFirst().getUUID());
                double best = Double.MAX_VALUE;
                for (Faction f : d.factions.values()) {
                    if (f.player()) continue;
                    double dist = Hex.of(f.capital).distance(Hex.of(me.capital));
                    if (dist < best) {
                        best = dist;
                        at[0] = Hex.of(f.capital).centerX();
                        at[1] = Hex.of(f.capital).centerZ();
                        at[2] = f.id;
                    }
                }
            });
            context.runOnClient(mc -> {
                Rts.jump(at[0], at[1] + 6);
                Rts.distance = 60;
            });
            context.waitTicks(20 * 25);
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                StringBuilder b = new StringBuilder();
                for (Structure st : d.structures.values()) if (st.owner == (int) at[2] && !st.planned) b.append(st.type).append(st.built() ? "" : "*").append(' ');
                long folk = s.overworld().getEntitiesOfClass(PeasantEntity.class, s.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(200),
                        p -> p.faction() == (int) at[2]).size();
                Strategy1.LOGGER.info("[town] realm {} has: {} folk {}", (int) at[2], b, folk);
            });
            context.takeScreenshot("town_wide");
            context.runOnClient(mc -> Rts.distance = 30);
            context.waitTicks(40);
            context.takeScreenshot("town_close");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
