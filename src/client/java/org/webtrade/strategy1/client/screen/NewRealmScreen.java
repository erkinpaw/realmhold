package org.webtrade.strategy1.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.item.ItemStack;
import org.webtrade.strategy1.client.ui.Palette;
import org.webtrade.strategy1.client.ui.Ui;
import org.webtrade.strategy1.client.ui.UiButton;
import org.webtrade.strategy1.game.Mode;

import java.util.OptionalLong;

/**
 * A new realm: the way to play it (four cards: conquest, the siege of the dead, trading realms, building in
 * peace), its name and seed, how many AI realms share the land, and whether friends who join share the realm.
 */
public class NewRealmScreen extends Screen {

    private final Screen parent;
    private static Mode mode = Mode.CONQUEST;
    private static int realms = Mode.CONQUEST.realms;
    private static boolean coop = true;
    private EditBox name, seed;
    private int cardsY, cardW, cardH, cardsX;

    public NewRealmScreen(Screen parent) {
        super(Component.translatable("strategy1.menu.new"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int n = Mode.values().length;
        cardW = Math.min(150, (width - 40) / n - 8);
        cardH = 112;
        cardsX = width / 2 - (n * (cardW + 8) - 8) / 2;
        cardsY = 40;
        int y = cardsY + cardH + 14, w = 220, x = width / 2 - w / 2;
        String oldName = name == null ? Component.translatable("strategy1.menu.world_name").getString() : name.getValue();
        String oldSeed = seed == null ? "" : seed.getValue();
        name = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.translatable("strategy1.menu.world_name")));
        name.setValue(oldName);
        seed = addRenderableWidget(new EditBox(font, x, y + 22, w, 18, Component.translatable("strategy1.menu.seed")));
        seed.setValue(oldSeed);
        seed.setHint(Component.translatable("strategy1.menu.seed_hint"));
        int oy = y + 48;
        addRenderableWidget(UiButton.make(Component.literal("-"), b -> {
            realms = Math.max(0, realms - 1);
            rebuildWidgets();
        }).bounds(x, oy, 20, 20).build());
        addRenderableWidget(UiButton.make(Component.literal("+"), b -> {
            realms = Math.min(6, realms + 1);
            rebuildWidgets();
        }).bounds(x + w - 20, oy, 20, 20).build());
        addRenderableWidget(UiButton.make(Component.translatable(coop ? "strategy1.menu.coop_on" : "strategy1.menu.coop_off"), b -> {
            coop = !coop;
            rebuildWidgets();
        }).bounds(x, oy + 24, w, 20).build());
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.menu.create"), b -> create()).bounds(width / 2 + 4, height - 30, 140, 22).build());
        addRenderableWidget(UiButton.make(Component.translatable("gui.back"), b -> onClose()).bounds(width / 2 - 144, height - 30, 140, 22).build());
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int i = 0; i < Mode.values().length; i++) {
            int x = cardsX + i * (cardW + 8);
            if (event.x() >= x && event.x() < x + cardW && event.y() >= cardsY && event.y() < cardsY + cardH) {
                mode = Mode.values()[i];
                realms = mode.realms;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void create() {
        Mode.Pending.mode = mode;
        Mode.Pending.realms = realms;
        Mode.Pending.coop = coop;
        String shown = name.getValue().isBlank() ? "Realm" : name.getValue().trim();
        String folder = shown.replaceAll("[^\\p{L}\\p{N} _-]", "_");
        while (minecraft.getLevelSource().levelExists(folder)) folder += "_";
        OptionalLong s = WorldOptions.parseSeed(seed.getValue());
        WorldOptions options = new WorldOptions(s.isPresent() ? s.getAsLong() : WorldOptions.randomSeed(), true, false);
        LevelSettings settings = new LevelSettings(shown, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true,
                WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(folder, settings, options, WorldPresets::createNormalWorldDimensions, this);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        extractPanorama(g, partialTick);
        g.fill(0, 0, width, height, 0x90100A05);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Ui.centered(g, font, title, width / 2, 14, Palette.BRASS);
        for (int i = 0; i < Mode.values().length; i++) {
            Mode m = Mode.values()[i];
            int x = cardsX + i * (cardW + 8), y = cardsY;
            boolean on = m == mode, hover = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;
            g.fill(x - 2, y - 2, x + cardW + 2, y + cardH + 2, on ? Palette.BRASS : hover ? 0xFF8C6F33 : 0xFF2A1D12);
            Ui.blit(g, Ui.PARCHMENT, x, y, x + cardW, y + cardH);
            g.pose().pushMatrix();
            g.pose().translate(x + cardW / 2F - font.width(m.glyph) * 1.5F, y + 10);
            g.pose().scale(3F, 3F);
            g.text(font, m.glyph, 0, 0, m.tint, true);
            g.pose().popMatrix();
            Ui.centered(g, font, Component.translatable(m.key()), x + cardW / 2, y + 46, Palette.INK);
            Ui.wrap(g, font, Component.translatable(m.key() + ".info"), x + 6, y + 60, cardW - 12, Palette.TEXT_MUTED);
        }
        int y = cardsY + cardH + 14 + 48;
        Ui.centered(g, font, Component.translatable("strategy1.menu.realms", realms), width / 2, y + 6, Palette.TEXT_LIGHT);
    }
}
