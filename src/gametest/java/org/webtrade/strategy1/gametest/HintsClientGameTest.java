package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Keys;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.client.screen.StrategyMapScreen;
import org.webtrade.strategy1.game.StrategyData;

/** The notes by the mouse (a building, a soldier, the treasury, the minimap), the minimap moving the camera, M for the map. */
public class HintsClientGameTest implements FabricClientGameTest {

    private static final Logger LOG = LoggerFactory.getLogger("Strategy1");
    private static final int BAR = 58, ICON = 22;

    private static void log(String fmt, Object... args) {
        LOG.info("[hints] " + fmt, args);
    }

    /** The mouse at a point of the screen (in the GUI's own scale). */
    private static void hover(ClientGameTestContext context, double gx, double gy) {
        int scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        context.getInput().setCursorPos(gx * scale, gy * scale);
        context.waitTicks(5);
    }

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
            server.waitFor(s -> Structures.has(StrategyData.get(s).factionOf(s.getPlayerList().getPlayers().getFirst().getUUID()).id, StructureType.KEEP), 20 * 30);
            context.waitTicks(60);
            int w = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth()), h = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
            int x0 = 6 + 5 * (ICON + 2) + 8, iy = h - BAR + 6 + ICON / 2;
            log("gui {}x{}", w, h);

            context.runOnClient(mc -> {
                RtsScreen.showTab(0);
                mc.gui.setScreen(new RtsScreen());
            });
            context.waitTicks(5);
            hover(context, x0 + ICON / 2.0, iy);
            context.takeScreenshot("hint_house");
            hover(context, x0 + 4 * (ICON + 2) + ICON / 2.0, iy);
            context.takeScreenshot("hint_quarry");

            context.runOnClient(mc -> {
                RtsScreen.showTab(4);
                mc.gui.setScreen(new RtsScreen());
            });
            context.waitTicks(5);
            hover(context, x0 + 2 * (ICON + 2) + ICON / 2.0, iy);
            context.takeScreenshot("hint_swordsman");
            hover(context, 6 + ICON / 2.0, iy);
            context.takeScreenshot("hint_tab");
            hover(context, 12, 10);
            context.takeScreenshot("hint_food");

            // the minimap: a note, then a click moves the camera
            int m = Math.max(64, Math.min(112, h / 4));
            hover(context, w - 4 - m / 2.0, h - 4 - m / 2.0);
            context.takeScreenshot("hint_minimap");
            double[] before = {context.computeOnClient(mc -> Rts.focusX), context.computeOnClient(mc -> Rts.focusZ)};
            hover(context, w - 4 - m + 6, h - 4 - m + 6);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            context.waitTicks(60);
            double[] after = {context.computeOnClient(mc -> Rts.focusX), context.computeOnClient(mc -> Rts.focusZ)};
            log("minimap click: camera {} {} -> {} {}", (int) before[0], (int) before[1], (int) after[0], (int) after[1]);
            hover(context, w / 2.0, h / 2.0);
            context.waitTicks(40);
            context.takeScreenshot("hint_after_jump");

            // M: the big map, and M again: back
            context.getInput().pressKey(Keys.MAP);
            context.waitTicks(20);
            String open = context.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
            context.takeScreenshot("hint_map");
            context.getInput().pressKey(Keys.MAP);
            context.waitTicks(20);
            String back = context.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
            log("M: {} then {}", open, back);
            if (!open.equals(StrategyMapScreen.class.getSimpleName()) || !back.equals(RtsScreen.class.getSimpleName())) throw new AssertionError("M doesn't open and close the map");
            if (Math.hypot(after[0] - before[0], after[1] - before[1]) < 50) throw new AssertionError("the minimap didn't move the camera");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
