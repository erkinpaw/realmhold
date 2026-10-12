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
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.game.Actions;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Market;
import org.webtrade.strategy1.game.Mode;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.Arrays;
import java.util.Set;

/** The trading mode: a market, goods sold and bought (the prices moving), caravans out and back, the race. */
public class TradeClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[trade] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        Mode.Pending.mode = Mode.TRADE;
        Mode.Pending.realms = 5;
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            context.getInput().resizeWindow(1600, 900);
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 6000");
            server.waitFor(s -> StrategyData.get(s).generated && me(s) != null, 20 * 30);
            context.waitFor(mc -> Rts.active(), 20 * 20);
            long[] capital = new long[1];
            server.runOnServer(s -> capital[0] = me(s).capital);
            Hex home = Hex.of(capital[0]);
            double cx = home.centerX(), cz = home.centerZ();
            server.waitFor(s -> Structures.has(me(s).id, StructureType.KEEP), 20 * 30);
            server.runOnServer(s -> {
                int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) cx + 8, (int) cz - 20);
                log("market: {}", Structures.place(player(s), me(s), StructureType.MARKET, new BlockPos((int) cx + 8, y, (int) cz - 20), null, 0));
            });
            context.waitTicks(20 * 18);
            int[] id = {-1};
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                Structure m = Structures.nearest(me(s).id, StructureType.MARKET, new Vec3(cx, 64, cz));
                id[0] = m == null ? -1 : m.id;
                log("before: res {} sell food {} buy iron {} progress {}", Arrays.toString(me(s).res), Market.sellPrice(d, org.webtrade.strategy1.game.Res.FOOD),
                        Market.buyPrice(d, org.webtrade.strategy1.game.Res.IRON), Market.progress(d, me(s)));
                for (int i = 0; i < 10; i++) Actions.structure(player(s), new Payloads.StructureAction(id[0], Payloads.StructureAction.SELL, 0));
                for (int i = 0; i < 5; i++) Actions.structure(player(s), new Payloads.StructureAction(id[0], Payloads.StructureAction.BUY, 3));
                Actions.structure(player(s), new Payloads.StructureAction(id[0], Payloads.StructureAction.CARAVAN, 0));
                Actions.structure(player(s), new Payloads.StructureAction(id[0], Payloads.StructureAction.CARAVAN, 0));
                log("after: res {} sell food {} buy iron {} caravans {}", Arrays.toString(me(s).res), Market.sellPrice(d, org.webtrade.strategy1.game.Res.FOOD),
                        Market.buyPrice(d, org.webtrade.strategy1.game.Res.IRON), d.caravans.size());
            });
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), id[0]);
                mc.gui.setScreen(new RtsScreen());
                Rts.jump(cx + 8, cz - 20);
                Rts.distance = 26;
            });
            context.waitTicks(60);
            context.takeScreenshot("trade_market");
            for (int i = 0; i < 5; i++) server.runCommand("strategy turn");
            context.waitTicks(20 * 35);
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                log("later: gold {} caravans {} ranking {}", me(s).res[4], d.caravans.size(),
                        Market.ranking(d).stream().map(r -> r[0] + ":" + r[1]).toList());
            });
            context.takeScreenshot("trade_later");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
        Mode.Pending.mode = null;
    }
}
