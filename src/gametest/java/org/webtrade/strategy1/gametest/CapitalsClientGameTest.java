package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Province;
import org.webtrade.strategy1.game.StrategyData;

/** Where the capitals go on a few worlds: on level, dry ground, not up a mountain or out on an island. */
public class CapitalsClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[capitals] " + fmt, args);
    }

    /** The ground in a ring round a point: its lowest and highest, and how much of it is water. */
    private static String ground(ServerLevel l, int x, int z) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, water = 0, n = 0;
        for (int a = 0; a < 360; a += 20) {
            for (int r = 12; r <= 28; r += 8) {
                int px = x + (int) (Math.cos(Math.toRadians(a)) * r), pz = z + (int) (Math.sin(Math.toRadians(a)) * r);
                if (!l.hasChunkAt(new BlockPos(px, 64, pz))) continue;
                int y = l.getHeight(Heightmap.Types.OCEAN_FLOOR, px, pz);
                int top = l.getHeight(Heightmap.Types.WORLD_SURFACE, px, pz) - 1;
                n++;
                if (l.getFluidState(new BlockPos(px, top, pz)).is(FluidTags.WATER)) water++;
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        return "heights " + min + ".." + max + " water " + water + "/" + n;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        int shot = 0;
        for (String seed : new String[]{"4242", "777", "31337", "9001"}) {
            try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed(seed)).create()) {
                context.getInput().resizeWindow(1600, 900);
                TestServerContext server = sp.getServer();
                sp.getConnection().waitForChunksRender();
                server.runCommand("gamerule advance_time false");
                server.runCommand("time set 6000");
                server.waitFor(s -> StrategyData.get(s).generated && StrategyData.get(s).factionOf(s.getPlayerList().getPlayers().getFirst().getUUID()) != null, 20 * 30);
                context.waitFor(mc -> Rts.active(), 20 * 20);
                server.waitFor(s -> {
                    Faction f = StrategyData.get(s).factionOf(s.getPlayerList().getPlayers().getFirst().getUUID());
                    for (Structure st : StrategyData.get(s).structures.values()) if (st.owner == f.id && st.type == StructureType.KEEP && st.built()) return true;
                    return false;
                }, 20 * 40);
                context.waitTicks(40);
                server.runOnServer(s -> {
                    StrategyData d = StrategyData.get(s);
                    for (Faction f : d.factions.values()) {
                        Province cap = d.province(f.capital);
                        Structure keep = null;
                        for (Structure st : d.structures.values()) if (st.owner == f.id && st.type == StructureType.KEEP) keep = st;
                        String g = keep != null && !keep.planned ? ground(s.overworld(), keep.origin.getX(), keep.origin.getZ()) : "not laid out";
                        log("seed {} {} {}: capital {} ({}) keep {} {}", seed, f.player() ? "PLAYER" : "ai", f.id, cap.name, cap.terrain,
                                keep == null ? "-" : keep.origin.toShortString(), g);
                    }
                });
                context.takeScreenshot("capital_" + seed);
                shot++;
                context.runOnClient(mc -> Rts.exit(mc));
                context.waitTicks(10);
            }
        }
    }
}
