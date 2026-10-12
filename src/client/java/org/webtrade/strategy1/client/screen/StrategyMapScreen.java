package org.webtrade.strategy1.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.webtrade.strategy1.client.ClientState;
import org.webtrade.strategy1.client.Keys;
import org.webtrade.strategy1.client.StrategyHud;
import org.webtrade.strategy1.client.ui.Palette;
import org.webtrade.strategy1.client.ui.Ui;
import org.webtrade.strategy1.client.ui.UiButton;
import org.webtrade.strategy1.client.ui.UiScreen;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Province;
import org.webtrade.strategy1.game.Res;
import org.webtrade.strategy1.game.Rules;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Deposit;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.hex.Terrain;
import org.webtrade.strategy1.network.Payloads;

import java.util.ArrayList;
import java.util.List;

/**
 * The strategic map (M): the hexes of the world in their holders' colours, to drag about and zoom with the wheel;
 * a click picks a province. Beside it the ruler's tabs: the province picked (build, man it, make it the capital),
 * the realm (its age, its income, the treasury from the pack), the army (raise soldiers) and diplomacy (war and peace).
 */
public class StrategyMapScreen extends UiScreen {

    private static final int PANEL = 214;
    private static final int TAB_PROVINCE = 0, TAB_REALM = 1, TAB_ARMY = 2, TAB_DIPLOMACY = 3;
    private static int tab = TAB_PROVINCE;
    private static long selected = Long.MIN_VALUE;
    /** The view: the world point in the middle of the map, and own pixels per block. */
    private static double camX, camZ;
    private static float zoom = -1;

    private int mx0, my0, mx1, my1, px0, px1, py0;
    private int seenRevision = -1;
    private boolean dragging;
    private double dragFrom;

    private record Line(int y, Component text, FormattedCharSequence seq, int[] cost, int color, int swatch) {
    }

    private final List<Line> lines = new ArrayList<>();
    private int cursor;

    public StrategyMapScreen() {
        super(Component.translatable("strategy1.map.title"));
    }

    /** Opens on a tab, a province picked, the view on a point at a zoom (tests). */
    public StrategyMapScreen(int tab, long selected, double x, double z, float zoom) {
        this();
        StrategyMapScreen.tab = tab;
        StrategyMapScreen.selected = selected;
        camX = x;
        camZ = z;
        StrategyMapScreen.zoom = zoom;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void layout() {
        int m = width < 400 ? 4 : 8;
        int fx0 = m, fy0 = m, fx1 = width - m, fy1 = height - m;
        mx0 = fx0 + Ui.BORDER + 2;
        my0 = fy0 + Ui.TITLE + 2;
        my1 = fy1 - Ui.BORDER - 2;
        px1 = fx1 - Ui.BORDER - 4;
        px0 = px1 - PANEL;
        mx1 = px0 - 8;
        if (selected == Long.MIN_VALUE && minecraft.player != null) {
            selected = Hex.at(minecraft.player.getX(), minecraft.player.getZ()).key();
        }
        if (zoom < 0 && minecraft.player != null) {
            camX = minecraft.player.getX();
            camZ = minecraft.player.getZ();
            zoom = 0.35F;
        }
        seenRevision = ClientState.revision;
        // the tabs
        String[] tabs = {"strategy1.tab.province", "strategy1.tab.realm", "strategy1.tab.army", "strategy1.tab.diplomacy"};
        int tw = PANEL / 4;
        for (int i = 0; i < 4; i++) {
            int t = i;
            addRenderableWidget(UiButton.make(Component.translatable(tabs[i]), b -> {
                tab = t;
                rebuildWidgets();
            }).bounds(px0 + i * tw, my0, tw - 1, 18).style(tab == i ? UiButton.Style.TAB_OPEN : UiButton.Style.TAB).build());
        }
        py0 = my0 + 24;
        scaleButtons(px1, fy0 + 6);
        lines.clear();
        cursor = py0;
        if (ClientState.map == null) {
            text(Component.translatable("strategy1.map.waiting"), Palette.TEXT_MUTED);
            return;
        }
        switch (tab) {
            case TAB_PROVINCE -> provinceTab();
            case TAB_REALM -> realmTab();
            case TAB_ARMY -> armyTab();
            default -> diplomacyTab();
        }
    }

    private void text(Component c, int color) {
        lines.add(new Line(cursor, c, null, null, color, -1));
        cursor += 11;
    }

    private void swatch(Component c, int rgb) {
        lines.add(new Line(cursor, c, null, null, Palette.TEXT, rgb));
        cursor += 11;
    }

    private void para(Component c, int color) {
        for (FormattedCharSequence s : font.split(c, PANEL - 8)) {
            lines.add(new Line(cursor, null, s, null, color, -1));
            cursor += 10;
        }
        cursor += 2;
    }

    private void cost(int[] c) {
        lines.add(new Line(cursor, null, null, c, 0, -1));
        cursor += 11;
    }

    private void gap(int h) {
        cursor += h;
    }

    private UiButton button(Component label, boolean active, Component tip, Runnable run) {
        UiButton b = addRenderableWidget(UiButton.make(label, x -> run.run()).bounds(px0 + 2, cursor, PANEL - 4, 16).build());
        b.active = active;
        if (tip != null) b.setTooltip(Tooltip.create(tip));
        cursor += 18;
        return b;
    }

    private static void act(int action, long province, int arg) {
        if (ClientPlayNetworking.canSend(Payloads.Action.TYPE)) ClientPlayNetworking.send(new Payloads.Action(action, province, arg));
    }

    private static Province asProvince(Payloads.ProvinceInfo p) {
        Province q = new Province(Hex.of(p.key()), p.name(), Terrain.of(p.terrain()), Deposit.of(p.deposit()));
        q.owner = p.owner();
        q.eco = p.eco();
        q.fort = p.fort();
        q.garrison = p.garrison();
        return q;
    }

    private static boolean canPay(Payloads.FactionInfo f, int[] cost) {
        if (f == null) return false;
        for (int i = 0; i < Res.COUNT; i++) if (f.res()[i] < cost[i]) return false;
        return true;
    }

    private void provinceTab() {
        Payloads.ProvinceInfo p = ClientState.PROVINCES.get(selected);
        Payloads.FactionInfo me = ClientState.mine();
        if (p == null) {
            para(Component.translatable("strategy1.map.pick"), Palette.TEXT_MUTED);
            return;
        }
        Terrain t = Terrain.of(p.terrain());
        boolean capital = ClientState.isCapital(p);
        text(Component.literal(p.name()).withStyle(s -> s.withBold(true)), Palette.INK);
        if (!t.land()) {
            text(Component.translatable("strategy1.owner.sea"), Palette.TEXT_MUTED);
            para(Component.translatable("strategy1.map.sea_info"), Palette.TEXT_MUTED);
            return;
        }
        int color = ClientState.color(p.owner());
        swatch(Component.translatable("strategy1.map.owner").append(ClientState.name(p.owner())), color < 0 ? 0x999999 : color);
        if (capital) text(Component.translatable("strategy1.map.capital"), Palette.BRASS_DARK);
        text(Component.translatable("strategy1.map.terrain", Component.translatable(t.key())), Palette.TEXT);
        Deposit d = Deposit.of(p.deposit());
        if (d != Deposit.NONE) text(Component.translatable("strategy1.map.deposit", Component.translatable(d.key()), d.bonus,
                Component.translatable(d.res.key())), 0xFF7A4A10);
        if (org.webtrade.strategy1.client.Rts.active()) {
            Hex at = Hex.of(p.key());
            button(Component.translatable("strategy1.btn.look"), true, null, () -> {
                org.webtrade.strategy1.client.Rts.jump(at.centerX(), at.centerZ());
                onClose();
            });
        }
        Province q = asProvince(p);
        text(Component.translatable("strategy1.map.yield"), Palette.TEXT);
        lines.add(new Line(cursor, null, null, q.yield(capital), 1, -1));
        cursor += 11;
        text(Component.translatable("strategy1.map.garrison", p.garrison(), q.garrisonCap(capital)), Palette.TEXT);
        gap(4);
        if (me != null && p.owner() == me.id()) {
            button(Component.translatable("strategy1.btn.garrison"), p.garrison() < q.garrisonCap(capital) && canPay(me, Rules.GARRISON_COST),
                    Component.translatable("strategy1.tip.garrison"), () -> act(Payloads.Action.GARRISON, selected, 0));
            cost(Rules.GARRISON_COST);
            if (!capital) {
                int[] c = Res.of(0, 50, 50, 0, 20);
                button(Component.translatable("strategy1.btn.capital"), canPay(me, c), null, () -> act(Payloads.Action.CAPITAL, selected, 0));
                cost(c);
            }
        } else if (me != null) {
            boolean war = ClientState.atWar(me.id(), p.owner());
            boolean border = false;
            for (Hex n : Hex.of(p.key()).neighbors()) {
                Payloads.ProvinceInfo x = ClientState.province(n);
                if (x != null && x.owner() == me.id()) border = true;
            }
            para(Component.translatable(war ? (border ? "strategy1.map.take_how" : "strategy1.map.take_border") : "strategy1.map.take_peace"),
                    war ? Palette.TEXT : Palette.TEXT_MUTED);
            if (!war && p.owner() != Faction.NEUTRAL) {
                Payloads.FactionInfo o = ClientState.FACTIONS.get(p.owner());
                if (o != null) warButton(me, o);
            }
        }
    }

    private void warButton(Payloads.FactionInfo me, Payloads.FactionInfo o) {
        boolean war = ClientState.atWar(me.id(), o.id());
        int truce = o.truceUntil() - ClientState.map.turn();
        if (war) {
            button(Component.translatable("strategy1.btn.peace"), true, Component.translatable("strategy1.tip.peace"),
                    () -> act(Payloads.Action.PEACE, 0, o.id()));
        } else {
            button(truce > 0 ? Component.translatable("strategy1.btn.truce", truce) : Component.translatable("strategy1.btn.war"), truce <= 0,
                    Component.translatable("strategy1.tip.war"), () -> act(Payloads.Action.WAR, 0, o.id()));
        }
    }

    private void realmTab() {
        Payloads.FactionInfo me = ClientState.mine();
        if (me == null) return;
        int owned = 0, garrison = 0;
        int[] income = new int[Res.COUNT];
        for (Payloads.ProvinceInfo p : ClientState.PROVINCES.values()) {
            if (p.owner() != me.id()) continue;
            owned++;
            garrison += p.garrison();
            int[] y = asProvince(p).yield(me.capital() == p.key());
            for (int i = 0; i < Res.COUNT; i++) income[i] += y[i];
        }
        swatch(ClientState.name(me.id()).withStyle(s -> s.withBold(true)), me.color());
        text(Component.translatable("strategy1.map.provinces", owned), Palette.TEXT);
        Payloads.ProvinceInfo cap = ClientState.PROVINCES.get(me.capital());
        if (cap != null && owned > 0) text(Component.translatable("strategy1.map.capital_is", cap.name()), Palette.TEXT);
        gap(3);
        text(Component.translatable("strategy1.map.treasury"), Palette.INK);
        lines.add(new Line(cursor, null, null, me.res(), 2, -1));
        cursor += 20;
        text(Component.translatable("strategy1.map.income"), Palette.TEXT);
        lines.add(new Line(cursor, null, null, income, 1, -1));
        cursor += 11;
        int upkeep = garrison + me.army();
        text(Component.translatable("strategy1.map.upkeep", upkeep, income[0] - upkeep), income[0] - upkeep < 0 ? Palette.BAD : Palette.TEXT);
        gap(4);
        if (owned == 0) {
            para(Component.translatable("strategy1.map.no_land"), Palette.BAD);
            button(Component.translatable("strategy1.btn.found"), true, null, () -> act(Payloads.Action.FOUND, 0, 0));
            return;
        }
        button(Component.translatable("strategy1.btn.donate"), true, Component.translatable("strategy1.tip.donate"),
                () -> act(Payloads.Action.DONATE, 0, 0));
        gap(4);
        para(Component.translatable("strategy1.map.realm_help"), Palette.TEXT_MUTED);
    }

    private void armyTab() {
        Payloads.FactionInfo me = ClientState.mine();
        if (me == null) return;
        text(Component.translatable("strategy1.map.army2", me.army(), me.peasants()), Palette.INK);
        para(Component.translatable("strategy1.map.orders"), Palette.TEXT_MUTED);
        if (me.army() > 0) button(Component.translatable("strategy1.btn.dismiss"), true, Component.translatable("strategy1.tip.dismiss"),
                () -> act(Payloads.Action.DISMISS, 0, 0));
    }

    private void diplomacyTab() {
        Payloads.FactionInfo me = ClientState.mine();
        if (me == null) return;
        para(Component.translatable("strategy1.map.diplomacy_help"), Palette.TEXT_MUTED);
        for (Payloads.FactionInfo f : ClientState.FACTIONS.values()) {
            if (f.id() == me.id() || !f.alive()) continue;
            int owned = 0;
            for (Payloads.ProvinceInfo p : ClientState.PROVINCES.values()) if (p.owner() == f.id()) owned++;
            boolean war = ClientState.atWar(me.id(), f.id());
            swatch(ClientState.name(f.id()), f.color());
            text(Component.translatable("strategy1.map.realm_line2", owned,
                    Component.translatable(war ? "strategy1.map.at_war" : "strategy1.map.at_peace")), war ? Palette.BAD : Palette.GOOD);
            warButton(me, f);
            gap(3);
        }
    }

    // ------------------------------------------------------------------ the view

    private double sx(double wx) {
        return (mx0 + mx1) / 2.0 + (wx - camX) * zoom;
    }

    private double sy(double wz) {
        return (my0 + my1) / 2.0 + (wz - camZ) * zoom;
    }

    private double wx(double x) {
        return camX + (x - (mx0 + mx1) / 2.0) / zoom;
    }

    private double wz(double y) {
        return camZ + (y - (my0 + my1) / 2.0) / zoom;
    }

    private boolean inMap(double x, double y) {
        return x >= mx0 && x < mx1 && y >= my0 && y < my1;
    }

    @Override
    public void tick() {
        if (ClientState.revision != seenRevision) rebuildWidgets();
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int m = width < 400 ? 4 : 8;
        Ui.window(g, font, m, m, width - m, height - m, title);
        Ui.inset(g, mx0 - 2, my0 - 2, mx1 + 2, my1 + 2);
        g.enableScissor(mx0, my0, mx1, my1);
        g.fill(mx0, my0, mx1, my1, 0xFFE4D2A6);
        Identifier tex = MapTexture.get();
        if (tex != null) {
            g.pose().pushMatrix();
            g.pose().translate((float) sx(MapTexture.originX), (float) sy(MapTexture.originZ));
            g.pose().scale(zoom * MapTexture.BPP, zoom * MapTexture.BPP);
            g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0, 0, MapTexture.width, MapTexture.height, MapTexture.width, MapTexture.height);
            g.pose().popMatrix();
        }
        marks(g);
        things(g);
        Long hovered = inMap(mouseX, mouseY) ? Hex.at(wx(mouseX), wz(mouseY)).key() : null;
        if (hovered != null && ClientState.PROVINCES.containsKey(hovered)) outline(g, Hex.of(hovered), 0x90FFFFFF);
        if (ClientState.PROVINCES.containsKey(selected)) outline(g, Hex.of(selected), 0xFFFFF4A0);
        player(g);
        g.disableScissor();
        // the hovered province's name by the mouse
        if (hovered != null) {
            Payloads.ProvinceInfo p = ClientState.PROVINCES.get(hovered);
            if (p != null) {
                Component c = Component.literal(p.name() + " — ").append(Terrain.of(p.terrain()).land() ? ClientState.name(p.owner())
                        : Component.translatable("strategy1.owner.sea"));
                int w = font.width(c) + 8;
                int x = Math.min(mouseX + 10, mx1 - w), y = Math.max(my0, mouseY - 16);
                g.fill(x, y, x + w, y + 13, 0xD0201810);
                g.text(font, c, x + 4, y + 3, 0xFFFFFFFF, true);
            }
        }
        panel(g);
        widgets(g, mouseX, mouseY, partialTick);
    }

    private void marks(GuiGraphicsExtractor g) {
        for (Payloads.ProvinceInfo p : ClientState.PROVINCES.values()) {
            Hex h = Hex.of(p.key());
            int x = (int) sx(h.centerX()), y = (int) sy(h.centerZ());
            if (x < mx0 - 40 || x > mx1 + 40 || y < my0 - 40 || y > my1 + 40) continue;
            if (!Terrain.of(p.terrain()).land()) continue;
            float hexPx = Hex.SIZE * zoom;
            if (ClientState.isCapital(p)) crown(g, x - 5, y - (hexPx > 22 ? 14 : 4));
            Deposit d = Deposit.of(p.deposit());
            if (d != Deposit.NONE && hexPx > 14) {
                g.pose().pushMatrix();
                float s = Math.min(1F, hexPx / 30F);
                g.pose().translate(x - 8 * s, y - 2 * s);
                g.pose().scale(s, s);
                g.item(depositIcon(d), 0, 0);
                g.pose().popMatrix();
            }
            if (hexPx > 26) {
                String n = Ui.fit(font, p.name(), (int) (hexPx * 1.6));
                Ui.centered(g, font, n, x, y + 10, 0xFF20160C);
                if (p.garrison() > 0) {
                    String gs = "⚔" + p.garrison() + (p.fort() > 0 ? " ▲" + p.fort() : "");
                    Ui.centered(g, font, gs, x, y + 20, 0xFF4A1A10);
                }
            }
        }
    }

    /** What stands and moves on the land: buildings and deposits as their pictures, units as dots, the dead in red. */
    private void things(GuiGraphicsExtractor g) {
        float scale = Math.max(0.35F, Math.min(1F, zoom * 1.4F));
        for (Payloads.StructureInfo s : ClientState.STRUCTURES.values()) {
            org.webtrade.strategy1.build.StructureType t = org.webtrade.strategy1.build.StructureType.of(s.type());
            if (t.line) {
                if (zoom < 0.5F) continue;
                int col = t.wall() ? 0xFF404040 : 0xFFB08850;
                int x0 = (int) sx(s.box()[0]), z0 = (int) sy(s.box()[2]), x1 = (int) sx(s.box()[3]), z1 = (int) sy(s.box()[5]);
                g.fill(Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1) + 1, Math.max(z0, z1) + 1, col);
                continue;
            }
            if (zoom < 0.2F && !t.deposit() && t != org.webtrade.strategy1.build.StructureType.KEEP) continue;
            if (t == org.webtrade.strategy1.build.StructureType.DEPOSIT_STONE && zoom < 0.7F) continue;
            if (t.deposit() && zoom < 0.3F) continue;
            double cx = (s.box()[0] + s.box()[3]) / 2.0, cz = (s.box()[2] + s.box()[5]) / 2.0;
            int x = (int) sx(cx), y = (int) sy(cz);
            if (x < mx0 - 10 || x > mx1 + 10 || y < my0 - 10 || y > my1 + 10) continue;
            g.pose().pushMatrix();
            g.pose().translate(x - 8 * scale, y - 8 * scale);
            g.pose().scale(scale, scale);
            int col = ClientState.color(s.owner());
            if (!t.deposit()) g.fill(-1, -1, 17, 17, 0xFF000000 | (col < 0 ? 0x666666 : col));
            var sprite = org.webtrade.strategy1.client.ui.Icons.building(t);
            if (sprite != null) org.webtrade.strategy1.client.ui.Ui.blit(g, sprite, -2, -2, 18, 18);
            else g.item(new ItemStack(t.icon), 0, 0);
            g.pose().popMatrix();
        }
        if (minecraft.level == null) return;
        for (net.minecraft.world.entity.Entity e : minecraft.level.entitiesForRendering()) {
            int col;
            if (e instanceof org.webtrade.strategy1.entity.UnitEntity u && u.isAlive()) col = 0xFF000000 | u.color();
            else if (e instanceof net.minecraft.world.entity.monster.Enemy && e.isAlive()) col = 0xFFE02020;
            else continue;
            int x = (int) sx(e.getX()), y = (int) sy(e.getZ());
            if (x < mx0 || x > mx1 || y < my0 || y > my1) continue;
            g.fill(x - 2, y - 2, x + 2, y + 2, 0xFF000000);
            g.fill(x - 1, y - 1, x + 1, y + 1, col);
        }
        if (org.webtrade.strategy1.client.Rts.active()) {
            // where the camera looks
            int x = (int) sx(org.webtrade.strategy1.client.Rts.focusX), y = (int) sy(org.webtrade.strategy1.client.Rts.focusZ), r = Math.max(4, (int) (30 * zoom));
            g.outline(x - r, y - r, 2 * r, 2 * r, 0xFFFFFFFF);
        }
    }

    private static ItemStack depositIcon(Deposit d) {
        return switch (d) {
            case FERTILE -> new ItemStack(Items.WHEAT);
            case OLD_FOREST -> new ItemStack(Items.OAK_SAPLING);
            case QUARRY -> new ItemStack(Items.COBBLESTONE);
            case IRON_VEIN -> new ItemStack(Items.RAW_IRON);
            case GOLD_VEIN -> new ItemStack(Items.RAW_GOLD);
            default -> ItemStack.EMPTY;
        };
    }

    private static final String[] CROWN = {"o.o.o", "ooooo", "ooooo"};

    private static void crown(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x - 1, y - 1, x + 11, y + 7, 0xFF3A2A10);
        for (int j = 0; j < CROWN.length; j++) {
            for (int i = 0; i < CROWN[j].length(); i++) {
                if (CROWN[j].charAt(i) == 'o') g.fill(x + i * 2, y + j * 2, x + i * 2 + 2, y + j * 2 + 2, 0xFFF2C830);
            }
        }
    }

    private void outline(GuiGraphicsExtractor g, Hex h, int color) {
        for (int e = 0; e < 6; e++) {
            double[] a = h.corner(e), b = h.corner(e + 1);
            double ax = sx(a[0]), ay = sy(a[1]), bx = sx(b[0]), by = sy(b[1]);
            int n = (int) Math.max(2, Math.hypot(bx - ax, by - ay));
            for (int i = 0; i <= n; i++) {
                int x = (int) (ax + (bx - ax) * i / n), y = (int) (ay + (by - ay) * i / n);
                g.fill(x - 1, y - 1, x + 1, y + 1, color);
            }
        }
    }

    private void player(GuiGraphicsExtractor g) {
        if (minecraft.player == null) return;
        int x = (int) sx(minecraft.player.getX()), y = (int) sy(minecraft.player.getZ());
        float yaw = minecraft.player.getYRot();
        double a = Math.toRadians(yaw);
        // an arrow the way one looks
        for (int i = 0; i < 7; i++) {
            int px = (int) (x - Math.sin(a) * i), py = (int) (y + Math.cos(a) * i);
            g.fill(px - 1, py - 1, px + 2, py + 2, 0xFF000000);
        }
        g.fill(x - 3, y - 3, x + 4, y + 4, 0xFF000000);
        g.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
    }

    private void panel(GuiGraphicsExtractor g) {
        Ui.inset(g, px0 - 2, py0 - 4, px1 + 2, my1 + 2);
        Payloads.FactionInfo me = ClientState.mine();
        for (Line l : lines) {
            int x = px0 + 4;
            if (l.swatch >= 0) {
                g.fill(x, l.y + 1, x + 8, l.y + 9, 0xFF000000);
                g.fill(x + 1, l.y + 2, x + 7, l.y + 8, 0xFF000000 | l.swatch);
                x += 12;
            }
            if (l.text != null) g.text(font, Ui.fit(font, l.text.getString(), px1 - x - 2), x, l.y + 1, l.color, false);
            else if (l.seq != null) g.text(font, l.seq, x, l.y, l.color, false);
            else if (l.cost != null) amounts(g, x, l.y, l.cost, l.color == 0 ? me : null, l.color == 2);
        }
    }

    /** A row of amounts: a coloured square and the number for each resource there is any of (red when short). */
    private void amounts(GuiGraphicsExtractor g, int x, int y, int[] amounts, Payloads.FactionInfo payer, boolean big) {
        if (big) {
            for (int i = 0; i < Res.COUNT; i++) {
                int ix = x + i * 41;
                g.item(StrategyHud.icon(i), ix, y);
                g.text(font, String.valueOf(amounts[i]), ix + 17, y + 5, Palette.INK, false);
            }
            return;
        }
        int cx = x;
        boolean any = false;
        for (int i = 0; i < Res.COUNT; i++) {
            if (amounts[i] == 0) continue;
            any = true;
            Res r = Res.values()[i];
            g.fill(cx, y + 1, cx + 7, y + 8, 0xFF2A1D12);
            g.fill(cx + 1, y + 2, cx + 6, y + 7, r.color);
            String s = String.valueOf(amounts[i]);
            boolean short_ = payer != null && payer.res()[i] < amounts[i];
            g.text(font, s, cx + 9, y + 1, short_ ? Palette.BAD : Palette.INK_SOFT, false);
            cx += 12 + font.width(s) + 4;
        }
        if (!any) g.text(font, "—", cx, y + 1, Palette.TEXT_MUTED, false);
    }

    // ------------------------------------------------------------------ the mouse and keys

    @Override
    protected boolean uiClick(MouseButtonEvent event, boolean doubleClick) {
        if (super.uiClick(event, doubleClick)) return true;
        if (inMap(event.x(), event.y()) && event.button() == 0) {
            dragging = true;
            dragFrom = 0;
            return true;
        }
        return false;
    }

    @Override
    protected boolean uiDrag(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            camX -= dx / zoom;
            camZ -= dy / zoom;
            dragFrom += Math.abs(dx) + Math.abs(dy);
            return true;
        }
        return super.uiDrag(event, dx, dy);
    }

    private long lastClick;

    @Override
    protected boolean uiRelease(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            if (dragFrom < 4 && inMap(event.x(), event.y())) {
                long now = System.currentTimeMillis();
                // a double click: the camera goes there
                if (now - lastClick < 350 && org.webtrade.strategy1.client.Rts.active()) {
                    org.webtrade.strategy1.client.Rts.jump(wx(event.x()), wz(event.y()));
                    onClose();
                    return true;
                }
                lastClick = now;
                long k = Hex.at(wx(event.x()), wz(event.y())).key();
                if (ClientState.PROVINCES.containsKey(k)) {
                    selected = k;
                    tab = TAB_PROVINCE;
                    rebuildWidgets();
                }
            }
            return true;
        }
        return super.uiRelease(event);
    }

    @Override
    protected boolean uiScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!inMap(mouseX, mouseY)) return super.uiScroll(mouseX, mouseY, scrollX, scrollY);
        double wx = wx(mouseX), wz = wz(mouseY);
        zoom = Mth.clamp(zoom * (scrollY > 0 ? 1.2F : 1 / 1.2F), 0.08F, 3F);
        // (the point under the mouse stays put)
        camX = wx - (mouseX - (mx0 + mx1) / 2.0) / zoom;
        camZ = wz - (mouseY - (my0 + my1) / 2.0) / zoom;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (Keys.MAP.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
