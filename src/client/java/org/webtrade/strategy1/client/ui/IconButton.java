package org.webtrade.strategy1.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A button that is a picture (one of the strategist's own {@link Icons}, or an item) in a slot, a count in its corner, lit when the mouse is on it, dimmed when it
 * can't be used, framed in brass when chosen. Its name, cost and what it does are shown by the screen it's on
 * ({@link #info}), in a note by the mouse.
 */
public class IconButton extends Button.Plain {

    /**
     * What the screen shows of it when the mouse is on it: a name, a cost (null: none), a line of what it does, and
     * more lines of what's worth knowing (what it needs, who works there, how strong it is).
     */
    public record Info(Component name, int[] cost, Component line, java.util.List<Component> more) {
        public Info(Component name, int[] cost, Component line) {
            this(name, cost, line, java.util.List.of());
        }
    }

    private final ItemStack icon;
    private final Identifier sprite;
    public final Info info;
    public int count = -1;
    public boolean chosen;

    public IconButton(int x, int y, int size, Item icon, Info info, OnPress onPress) {
        super(x, y, size, size, info == null ? Component.empty() : info.name(), onPress, DEFAULT_NARRATION);
        this.icon = new ItemStack(icon);
        this.sprite = null;
        this.info = info;
    }

    public IconButton(int x, int y, int size, Identifier sprite, Info info, OnPress onPress) {
        super(x, y, size, size, info == null ? Component.empty() : info.name(), onPress, DEFAULT_NARRATION);
        this.icon = ItemStack.EMPTY;
        this.sprite = sprite;
        this.info = info;
    }

    public IconButton count(int n) {
        count = n;
        return this;
    }

    public IconButton chosen(boolean c) {
        chosen = c;
        return this;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        if (chosen) g.fill(x - 1, y - 1, x + w + 1, y + h + 1, Palette.BRASS);
        Ui.blit(g, Ui.SLOT, x, y, x + w, y + h);
        if (sprite != null) {
            int s = Math.min(Icons.SIZE, Math.min(w, h));
            Ui.blit(g, sprite, x + (w - s) / 2, y + (h - s) / 2, x + (w - s) / 2 + s, y + (h - s) / 2 + s);
        } else g.item(icon, x + (w - 16) / 2, y + (h - 16) / 2);
        if (count >= 0) {
            String s = String.valueOf(count);
            var font = Minecraft.getInstance().font;
            g.text(font, s, x + w - 1 - font.width(s), y + h - 8, 0xFFFFFFFF, true);
        }
        if (!active) g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xA0201810);
        else if (isHoveredOrFocused()) g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x40FFFFFF);
    }
}
