package org.webtrade.strategy1.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.network.Payloads;

/**
 * The realm's treasury and the season's clock (top left). Drawn by the strategist's screen in its view, and as a
 * HUD outside it, with a reminder of the key that leads back into it.
 */
public final class StrategyHud {

    private static ItemStack[] icons;

    private StrategyHud() {
    }

    /** The resources' pictures: wheat, a log, cobblestone, an iron ingot, a gold ingot. */
    public static ItemStack icon(int res) {
        if (icons == null) icons = new ItemStack[]{new ItemStack(Items.WHEAT), new ItemStack(Items.OAK_LOG), new ItemStack(Items.COBBLESTONE),
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)};
        return icons[res];
    }

    static void init() {
        HudElementRegistry.addLast(Strategy1.id("strategy"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.gui.hud.isHidden() || Rts.active()) return;
            int y = treasury(mc, g, 4, 4);
            if (y > 4) {
                Component tip = Component.translatable("strategy1.hud.enter_rts", Keys.COMMANDER.getTranslatedKeyMessage());
                g.fill(4, y, 8 + mc.font.width(tip), y + 12, 0x90000000);
                g.text(mc.font, tip, 6, y + 2, 0xFFFFE070, true);
            }
        });
    }

    /** Draws the treasury panel; returns the y under it. */
    public static int treasury(Minecraft mc, GuiGraphicsExtractor g, int x, int y) {
        Payloads.FactionInfo me = ClientState.mine();
        if (me == null || ClientState.map == null) return y;
        Font font = mc.font;
        Payloads.Hud hud = ClientState.hud;
        int w = 5 * 58 + 6;
        g.fill(x, y, x + w, y + 46, 0x90000000);
        g.text(font, ClientState.name(me.id()), x + 4, y + 3, 0xFFFFFFFF, true);
        for (int i = 0; i < 5; i++) {
            int ix = x + 3 + i * 58;
            g.item(icon(i), ix, y + 14);
            g.text(font, String.valueOf(me.res()[i]), ix + 18, y + 19, 0xFFFFFFFF, true);
        }
        String turn = Component.translatable("strategy1.hud.turn", ClientState.map.turn() + 1, hud == null ? 0 : hud.turnLeft()).getString();
        String army = hud == null ? "" : Component.translatable("strategy1.hud.army", hud.army(), hud.armyMax()).getString();
        g.text(font, turn, x + 4, y + 34, 0xFFC8C8C8, true);
        g.text(font, army, x + w - 4 - font.width(army), y + 34, 0xFFFFE0A0, true);
        return y + 48;
    }
}
