package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import org.lwjgl.glfw.GLFW;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.client.ClientState;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.MapGen;
import org.webtrade.strategy1.game.Mode;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.hex.Hex;

/**
 * A world made from the realm menu: nothing is laid out until the player puts their keep down with a click; then the
 * keep stands there, its province's hex round it, and the AI realms far off.
 */
public class SiteClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[site] " + fmt, args);
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(s.getPlayerList().getPlayers().getFirst().getUUID());
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        Mode.Pending.mode = Mode.CONQUEST;
        Mode.Pending.realms = 5;
        Mode.Pending.site = true;
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            context.getInput().resizeWindow(1600, 900);
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.runOnClient(mc -> {
                mc.options.renderDistance().set(10);
                mc.options.simulationDistance().set(10);
                mc.options.broadcastOptions();
            });
            server.runCommand("time set 6000");
            context.waitFor(mc -> Rts.active() && ClientState.choosingSite(), 20 * 20);
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                log("waiting for the keep's place: generated {}, provinces {}, realms {}", d.generated, d.provinces.size(), d.factions.size());
                if (d.generated || !d.provinces.isEmpty() || me(s) != null) throw new AssertionError("laid out before the keep's place was chosen");
            });
            // a dry place a little off the spawn, looked at from above
            double[] at = new double[2];
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst();
                for (int r = 30; r < 200 && at[0] == 0; r += 10) {
                    for (int a = 0; a < 360; a += 30) {
                        double x = Math.floor(p.getX() + Math.cos(Math.toRadians(a)) * r) + 0.5, z = Math.floor(p.getZ() + Math.sin(Math.toRadians(a)) * r) + 0.5;
                        if (MapGen.dry(s.overworld(), x, z) && MapGen.dry(s.overworld(), x + 6, z + 6) && MapGen.dry(s.overworld(), x - 6, z - 6)) {
                            at[0] = x;
                            at[1] = z;
                            break;
                        }
                    }
                }
                log("the place: {} {}", (int) at[0], (int) at[1]);
            });
            context.runOnClient(mc -> {
                Rts.jump(at[0], at[1]);
                Rts.distance = 70;
                Rts.yaw = 180;
            });
            context.waitTicks(60);
            // the mouse on the middle of the view (where the camera looks), the keep's outline and its hex there
            int scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
            int gw = context.computeOnClient(mc -> mc.gui.screen().width), gh = context.computeOnClient(mc -> mc.gui.screen().height);
            context.getInput().setCursorPos(gw / 2.0 * scale, gh / 2.0 * scale);
            context.waitTicks(10);
            context.takeScreenshot("site_choose");
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            server.waitFor(s -> me(s) != null, 20 * 30);
            context.waitFor(mc -> !ClientState.choosingSite() && ClientState.mine() != null, 20 * 10);
            server.waitFor(s -> {
                for (Structure st : StrategyData.get(s).structures.values()) if (st.owner == me(s).id && st.type == StructureType.KEEP) return true;
                return false;
            }, 20 * 10);
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                Faction f = me(s);
                Structure keep = null;
                for (Structure st : d.structures.values()) if (st.owner == f.id && st.type == StructureType.KEEP) keep = st;
                Hex cap = Hex.of(f.capital);
                double off = Math.hypot(keep.origin.getX() + 0.5 - at[0], keep.origin.getZ() + 0.5 - at[1]);
                double fromCentre = cap.distToCenter(keep.origin.getX() + 0.5, keep.origin.getZ() + 0.5);
                int nearestAi = Integer.MAX_VALUE;
                for (Faction o : d.factions.values()) if (o != f) nearestAi = Math.min(nearestAi, Hex.of(o.capital).distance(cap));
                log("keep at {} ({} from the place clicked); capital hex {} {}, the keep {} from its centre, the hex holding it {}; {} provinces, {} realms, the nearest AI capital {} hexes off; the clock {}",
                        keep.origin.toShortString(), String.format("%.1f", off), cap.q(), cap.r(), String.format("%.1f", fromCentre),
                        Hex.at(keep.origin.getX() + 0.5, keep.origin.getZ() + 0.5).equals(cap), d.provinces.size(), d.factions.size(), nearestAi,
                        s.overworld().getOverworldClockTime());
                if (off > 12) throw new AssertionError("the keep isn't where it was put");
                if (fromCentre > 12 || !Hex.at(keep.origin.getX() + 0.5, keep.origin.getZ() + 0.5).equals(cap)) throw new AssertionError("the hex isn't round the keep");
                if (d.factions.size() < 2 || nearestAi < 6) throw new AssertionError("the AI realms");
            });
            // the clock goes again
            long[] t0 = new long[1];
            server.runOnServer(s -> t0[0] = s.overworld().getOverworldClockTime());
            context.waitTicks(40);
            server.runOnServer(s -> {
                long dt = s.overworld().getOverworldClockTime() - t0[0];
                log("the clock ran {} in 40 ticks", dt);
                if (dt <= 0) throw new AssertionError("the clock is still held");
            });
            context.runOnClient(mc -> {
                Rts.distance = 110;
                Rts.jump(at[0], at[1] - 7);
            });
            context.waitTicks(20 * 8);
            context.takeScreenshot("site_founded");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        } finally {
            Mode.Pending.mode = null;
            Mode.Pending.site = false;
        }
    }
}
