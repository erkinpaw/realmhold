package org.webtrade.strategy1.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.webtrade.strategy1.client.ui.Palette;
import org.webtrade.strategy1.client.ui.Ui;
import org.webtrade.strategy1.client.ui.UiButton;
import org.webtrade.strategy1.game.Mode;

/**
 * The game's own front: the world's panorama behind, the name in large brass letters, and the ways in — a new
 * realm (its mode chosen), carrying on with one, playing with friends over the network, the settings.
 */
public class RealmTitleScreen extends Screen {

    /** The game's own title screen asked for (once, from the corner button). */
    public static boolean vanilla;

    public RealmTitleScreen() {
        super(Component.literal("Realmhold"));
    }

    @Override
    protected void init() {
        int w = 200, x = width / 2 - w / 2, y = height / 2 - 20;
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.menu.new"), b -> minecraft.gui.setScreen(new NewRealmScreen(this)))
                .bounds(x, y, w, 22).build());
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.menu.load"), b -> minecraft.gui.setScreen(new SelectWorldScreen(this)))
                .bounds(x, y + 26, w, 20).build());
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.menu.multiplayer"), b -> minecraft.gui.setScreen(new JoinMultiplayerScreen(this)))
                .bounds(x, y + 50, w, 20).build());
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.menu.options"), b -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options, false)))
                .bounds(x, y + 74, w / 2 - 2, 20).build());
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.menu.quit"), b -> minecraft.stop())
                .bounds(x + w / 2 + 2, y + 74, w / 2 - 2, 20).build());
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.menu.vanilla"), b -> {
            vanilla = true;
            minecraft.gui.setScreen(new TitleScreen());
        }).bounds(4, height - 18, 90, 14).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        extractPanorama(g, partialTick);
        g.fill(0, 0, width, height, 0x60100A05);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // the name, large
        String name = "REALMHOLD";
        float k = Math.min(5F, width / 2.4F / font.width(name));
        int ty = height / 2 - 100;
        g.pose().pushMatrix();
        g.pose().translate(width / 2F - font.width(name) * k / 2F, ty);
        g.pose().scale(k, k);
        g.text(font, name, 1, 1, 0xFF2A1D12, false);
        g.text(font, name, 0, 0, Palette.BRASS, false);
        g.pose().popMatrix();
        Ui.centered(g, font, Component.translatable("strategy1.menu.tagline"), width / 2, ty + (int) (10 * k) + 2, Palette.TEXT_LIGHT);
        // the four ways to play, in pictures, under the name
        int n = Mode.values().length, step = 26, x0 = width / 2 - (n * step) / 2;
        for (int i = 0; i < n; i++) {
            Mode m = Mode.values()[i];
            g.pose().pushMatrix();
            g.pose().translate(x0 + i * step + 6, ty + (int) (10 * k) + 16);
            g.pose().scale(2F, 2F);
            g.text(font, m.glyph, 0, 0, m.tint, true);
            g.pose().popMatrix();
        }
        g.text(font, "Realmhold · Fabric 26.2", width - font.width("Realmhold · Fabric 26.2") - 4, height - 12, 0xA0FFFFFF, true);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
