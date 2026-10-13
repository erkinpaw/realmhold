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
import org.webtrade.strategy1.build.Workforce;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.entity.PeasantEntity;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.hex.Hex;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * The peasants at work: a farm takes three, a woodcutter two; those left over help where a building goes up (and it
 * goes up faster); the season's tally counts what they carried home.
 */
public class WorkersClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[workers] " + fmt, args);
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

    /** Who does what: idle, builders (and how many at their site), each workplace's hands. */
    private static Map<String, Integer> jobs(MinecraftServer s) {
        Faction f = me(s);
        Map<String, Integer> out = new TreeMap<>();
        for (PeasantEntity p : s.overworld().getEntitiesOfClass(PeasantEntity.class, player(s).getBoundingBox().inflate(400), e -> e.faction() == f.id)) {
            String k = p.builder ? (p.atSite ? "builder@site" : "builder") : p.workplace() == null ? "idle" : p.workplace().type.name();
            out.merge(k, 1, Integer::sum);
        }
        return out;
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
            // folk come first: a house for more of them
            server.runOnServer(s -> {
                for (int[] o : new int[][]{{4, -24}, {12, -20}, {-14, 20}, {14, 18}, {20, 0}, {-20, 4}}) if (build(s, StructureType.HOUSE, cx + o[0], cz + o[1]) == null) break;
            });
            server.waitFor(s -> me(s).peasants >= 9, 20 * 90);
            server.runOnServer(s -> log("before: {}", jobs(s)));
            // the workplaces, and a big building to help with
            server.runOnServer(s -> {
                build(s, StructureType.FARM, cx - 24, cz - 18);
                for (int[] o : new int[][]{{-22, 10}, {22, 12}, {-12, 24}, {14, -26}, {26, -6}}) if (build(s, StructureType.WOODCUTTER, cx + o[0], cz + o[1]) == null) break;
                for (int[] o : new int[][]{{22, 10}, {-8, -22}, {24, -12}, {-26, 6}, {6, 28}}) if (build(s, StructureType.BARRACKS, cx + o[0], cz + o[1]) == null) break;
            });
            int[] peakSite = new int[1];
            long[] barracksAt = new long[2];
            for (int i = 0; i < 12; i++) {
                context.waitTicks(20 * 3);
                server.runOnServer(s -> {
                    Map<String, Integer> j = jobs(s);
                    peakSite[0] = Math.max(peakSite[0], j.getOrDefault("builder@site", 0) + j.getOrDefault("builder", 0));
                    Structure b = Structures.nearest(me(s).id, StructureType.BARRACKS, new Vec3(cx, 64, cz));
                    if (b != null && barracksAt[1] == 0) barracksAt[1] = s.overworld().getGameTime();
                    log("jobs {}", j);
                });
            }
            context.runOnClient(mc -> {
                Rts.jump(cx - 6, cz - 4);
                Rts.distance = 46;
                Rts.yaw = 200;
            });
            context.waitTicks(20 * 40);
            context.takeScreenshot("workers");
            server.runOnServer(s -> {
                Map<String, Integer> j = jobs(s);
                log("after: {} delivered {} brought this season {} peak builders at a site {}", j, Arrays.toString(Workforce.DELIVERED),
                        Arrays.toString(me(s).brought), peakSite[0]);
                if (j.getOrDefault("FARM", 0) != 3) throw new AssertionError("the farm should have 3 hands: " + j);
                if (j.getOrDefault("WOODCUTTER", 0) != 2) throw new AssertionError("the woodcutter should have 2 hands: " + j);
            });
            // (sent to help; small buildings may be up before they get there)
            if (peakSite[0] == 0) throw new AssertionError("nobody was sent to help build");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
