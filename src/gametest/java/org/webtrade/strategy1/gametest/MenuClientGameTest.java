package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.client.screen.NewRealmScreen;
import org.webtrade.strategy1.client.screen.RealmTitleScreen;
import org.webtrade.strategy1.game.Mode;

/** The game's front and its new realm screen, as screenshots. */
public class MenuClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1600, 900);
        context.runOnClient(mc -> mc.gui.setScreen(new RealmTitleScreen()));
        context.waitTicks(20);
        context.takeScreenshot("menu_title");
        context.runOnClient(mc -> mc.gui.setScreen(new NewRealmScreen(new RealmTitleScreen())));
        context.waitTicks(20);
        context.takeScreenshot("menu_new_realm");
        Strategy1.LOGGER.info("[menu] pending mode before: {}", Mode.Pending.mode);
        context.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
        context.waitTicks(5);
    }
}
