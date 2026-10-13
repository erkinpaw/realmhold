package org.webtrade.strategy1.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.client.ClientState;
import org.webtrade.strategy1.client.Keys;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.StrategyHud;
import org.webtrade.strategy1.client.ui.IconButton;
import org.webtrade.strategy1.client.ui.Icons;
import org.webtrade.strategy1.client.ui.Palette;
import org.webtrade.strategy1.client.ui.Ui;
import org.webtrade.strategy1.entity.PeasantEntity;
import org.webtrade.strategy1.entity.ShipEntity;
import org.webtrade.strategy1.entity.SiegeEntity;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.game.Res;
import org.webtrade.strategy1.game.Rules;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.hex.Terrain;
import org.webtrade.strategy1.network.Payloads;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The strategist's screen, over the world seen from the camera; the mouse is free. Everything is in pictures: the
 * treasury and the people along the top; at the bottom the tabs (farming and trades, the army's buildings, defences,
 * roads, raising soldiers), what they hold, the chosen units or building and their orders, a box telling what is
 * chosen, and in the corner the minimap. A note by the mouse tells what any picture is: its name, its cost in
 * pictures, what it does and what it needs.
 * <ul>
 * <li>Left click or drag a box: choose units (Shift adds); click a building: choose it.</li>
 * <li>Right click: the chosen units go there; on an enemy: attack; on an enemy building: break it; on your ship:
 * board her; on your tower or wall: archers man it. A ship sent onto the land puts her soldiers ashore there.</li>
 * </ul>
 */
public class RtsScreen extends Screen {

    private static final int BAR = 58, TOP = 22, ICON = 22;
    private int INFO = 196;

    private enum Tab {
        ECONOMY(StructureType.Category.ECONOMY, Icons.TAB_ECONOMY), MILITARY(StructureType.Category.MILITARY, Icons.TAB_MILITARY),
        DEFENCE(StructureType.Category.DEFENCE, Icons.TAB_DEFENCE), ROADS(StructureType.Category.ROADS, Icons.TAB_ROADS), ARMY(null, Icons.TAB_ARMY);

        final StructureType.Category category;
        final Identifier icon;

        Tab(StructureType.Category category, Identifier icon) {
            this.category = category;
            this.icon = icon;
        }

        String key() {
            return "strategy1.tab2." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The chosen units (entity ids) and the chosen structure; kept while the screen comes and goes. */
    private static final Set<Integer> SELECTED = new HashSet<>();
    private static int structure = -1;
    private static Tab tab = Tab.ECONOMY;
    /** Being placed: what, which way (quarter turns), the first end of a line. */
    private static StructureType placing;
    private static int turn;
    private static BlockPos lineStart;

    private double pressX, pressY;
    private boolean boxing;
    /** Where the chosen structure was clicked (a wall: where a gate would go). */
    private static BlockPos clicked;
    /** The enemy last pointed out to a tower (marked over its head). */
    private static int aimed = -1;
    private int seenRevision = -1, seenStructures = -1, seenSelection = -1;
    /** The pictures of the top bar, where they are, to tell what they are when the mouse is on them. */
    private record Stat(int x0, int x1, Component name, Component line) {
    }

    private final List<Stat> stats = new ArrayList<>();
    /** Blocks from the minimap's middle to its edge. */
    private static final double MINI_R = 160;
    private boolean miniDrag;
    private double sentX = Double.NaN, sentZ;
    private static final DustParticleOptions GO = new DustParticleOptions(0xFF60FF60, 1.6F);
    private static final DustParticleOptions HIT = new DustParticleOptions(0xFFFF3030, 1.8F);

    public RtsScreen() {
        super(Component.translatable("strategy1.rts.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Does the camera move when the mouse is at the screen's edge (not while a box is being drawn)? */
    public boolean edgeScroll() {
        return !boxing;
    }

    /** Chooses every soldier of the player's in the loaded world, and sends them to a point (tests). */
    public static int sendAll(Minecraft mc, Vec3 to) {
        SELECTED.clear();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof SoldierEntity s && s.isAlive() && s.faction() == ClientState.me() && !s.isPassenger()) SELECTED.add(s.getId());
        }
        if (to != null && !SELECTED.isEmpty()) {
            ClientPlayNetworking.send(new Payloads.UnitOrder(SELECTED.stream().mapToInt(Integer::intValue).toArray(), Payloads.UnitOrder.MOVE,
                    to.x, to.y, to.z, -1));
        }
        return SELECTED.size();
    }

    /** Chooses units and a structure (tests). */
    public static void choose(Set<Integer> units, int structureId) {
        SELECTED.clear();
        SELECTED.addAll(units);
        structure = structureId;
    }

    public static void showBuild(boolean on) {
        tab = on ? Tab.ECONOMY : Tab.ARMY;
    }

    public static void showTab(int i) {
        tab = Tab.values()[Math.floorMod(i, Tab.values().length)];
    }

    // ------------------------------------------------------------------ the pictures

    private static Identifier unitIcon(UnitType u) {
        return Icons.unit(u);
    }

    private static Identifier icon(Entity e) {
        if (e instanceof SoldierEntity s) return unitIcon(s.unit());
        if (e instanceof SiegeEntity g) return g.kind() == SiegeEntity.Kind.TREBUCHET ? Icons.TREBUCHET : Icons.CATAPULT;
        if (e instanceof ShipEntity sh) return sh.kind() == ShipEntity.Kind.WARSHIP ? Icons.WARSHIP : Icons.TRANSPORT;
        return Icons.PEASANT;
    }

    private static String kindKey(Entity e) {
        if (e instanceof SoldierEntity s) return s.unit().key();
        if (e instanceof SiegeEntity g) return g.kind().key();
        if (e instanceof ShipEntity sh) return sh.kind().key();
        if (e instanceof PeasantEntity) return "strategy1.unit.peasant";
        return "?";
    }

    // ------------------------------------------------------------------ layout

    private int sig() {
        int h = ClientState.STRUCTURES.size() * 7 + SELECTED.hashCode() * 31;
        Payloads.StructureInfo s = ClientState.STRUCTURES.get(structure);
        if (s != null) h = h * 31 + (s.built() >= 100 ? 7 : 0) + s.archers() * 131 + s.owner() * 1009 + s.gate() * 7919;
        for (Payloads.StructureInfo i : ClientState.STRUCTURES.values()) if (i.owner() == ClientState.me() && i.built() >= 100) h += i.type() * 17 + 1;
        Payloads.Hud hud = ClientState.hud;
        if (hud != null) h += (hud.idle() > 0 ? 3 : 0);
        return h;
    }

    private int x0() {
        return 6 + Tab.values().length * (ICON + 2) + 8;
    }

    @Override
    protected void init() {
        INFO = Math.min(170, width / 4);
        seenRevision = ClientState.revision;
        seenStructures = sig();
        int y = height - BAR + 8;
        // the tabs
        int x = 6;
        for (Tab t : Tab.values()) {
            addRenderableWidget(new IconButton(x, y, ICON, t.icon, new IconButton.Info(Component.translatable(t.key()), null,
                    Component.translatable(t.key() + ".info"), List.of(Component.translatable("strategy1.tip.tab_key").withStyle(ChatFormatting.GRAY))), b -> {
                tab = t;
                placing = null;
                lineStart = null;
                structure = -1;
                rebuildWidgets();
            }).chosen(tab == t && structure < 0 && SELECTED.isEmpty()));
            x += ICON + 2;
        }
        // the corner: the map, the way out
        addRenderableWidget(new IconButton(width - 2 * (ICON + 2) - 2, 1, ICON - 2, Icons.MAP,
                new IconButton.Info(Component.translatable("strategy1.rts.map"), null, Component.translatable("strategy1.tip.map")), b -> openMap()));
        addRenderableWidget(new IconButton(width - (ICON + 2) - 2, 1, ICON - 2, Icons.LEAVE,
                new IconButton.Info(Component.translatable("strategy1.rts.leave"), null, Component.translatable("strategy1.rts.leave_tip")), b -> Rts.exit(minecraft)));
        var me = ClientState.mine();
        Payloads.StructureInfo st = ClientState.STRUCTURES.get(structure);
        if (st != null && me != null && st.owner() == me.id()) structurePanel(st, me);
        else if (!SELECTED.isEmpty()) unitPanel();
        else if (tab == Tab.ARMY) recruitPanel(me, -1);
        else buildPanel(me);
    }

    private static boolean canPay(Payloads.FactionInfo f, int[] cost) {
        if (f == null) return false;
        for (int i = 0; i < cost.length; i++) if (f.res()[i] < cost[i]) return false;
        return true;
    }

    private static boolean have(StructureType t) {
        if (t == null) return true;
        for (Payloads.StructureInfo s : ClientState.STRUCTURES.values()) if (s.owner() == ClientState.me() && s.type() == t.ordinal() && s.built() >= 100) return true;
        return false;
    }

    /** How many pictures fit in a row between the tabs and the box on the right. */
    private int perRow() {
        return Math.max(4, (width - INFO - mini() - 18 - x0()) / (ICON + 2));
    }

    private IconButton add(int i, Item icon, IconButton.Info info, boolean active, Runnable run) {
        int row = i / perRow(), col = i % perRow();
        IconButton b = addRenderableWidget(new IconButton(x0() + col * (ICON + 2), height - BAR + 6 + row * (ICON + 3), ICON, icon, info, x -> run.run()));
        b.active = active;
        return b;
    }

    private IconButton add(int i, Identifier icon, IconButton.Info info, boolean active, Runnable run) {
        int row = i / perRow(), col = i % perRow();
        IconButton b = addRenderableWidget(new IconButton(x0() + col * (ICON + 2), height - BAR + 6 + row * (ICON + 3), ICON, icon, info, x -> run.run()));
        b.active = active;
        return b;
    }

    private void buildPanel(Payloads.FactionInfo me) {
        int i = 0;
        for (StructureType t : StructureType.values()) {
            if (t.category != tab.category) continue;
            boolean ok = me != null && have(t.requires);
            add(i++, Icons.building(t), new IconButton.Info(Component.translatable(t.key()), t.cost, Component.translatable(t.key() + ".info"), about(t)), ok, () -> {
                placing = t;
                lineStart = null;
                structure = -1;
                rebuildWidgets();
            }).chosen(placing == t);
        }
    }

    /** What's worth knowing of a building before it's put up: who works there, how strong, what it needs. */
    private static List<Component> about(StructureType t) {
        List<Component> out = new ArrayList<>();
        if (t.workers > 0) out.add(Component.translatable("strategy1.tip.workers", t.workers));
        if (t.posts > 0) out.add(Component.translatable("strategy1.tip.posts", t.posts));
        else if (t.wall()) out.add(Component.translatable("strategy1.tip.posts_wall"));
        if (t.hp > 0) out.add(Component.translatable(t.line ? "strategy1.tip.hp_block" : "strategy1.tip.hp", t.hp));
        if (t.line) out.add(Component.translatable("strategy1.tip.per_block", t.maxLength()));
        if (t.requires != null) {
            out.add(Component.translatable("strategy1.rts.needs", Component.translatable(t.requires.key()))
                    .withStyle(have(t.requires) ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        if (t.needsDeposit() != null) out.add(Component.translatable("strategy1.rts.by_deposit", Component.translatable(t.needsDeposit().key())).withStyle(ChatFormatting.YELLOW));
        out.add(Component.translatable(t.line ? "strategy1.rts.help_line" : "strategy1.rts.help_place").withStyle(ChatFormatting.GRAY));
        return out;
    }

    /** Raise soldiers (at the chosen barracks, or wherever the camera is). */
    private void recruitPanel(Payloads.FactionInfo me, int barracks) {
        Payloads.Hud hud = ClientState.hud;
        boolean hands = hud != null && hud.idle() > 0;
        int i = 0;
        for (UnitType u : UnitType.values()) {
            boolean built = have(u.requires);
            List<Component> more = new ArrayList<>();
            more.add(Component.translatable("strategy1.tip.unit_stats", (int) u.health, (int) u.damage,
                    Component.translatable(u.ranged ? "strategy1.unit.ranged" : "strategy1.unit.melee")));
            more.add(Component.translatable("strategy1.rts.needs", Component.translatable(u.requires.key())).withStyle(built ? ChatFormatting.GREEN : ChatFormatting.RED));
            more.add(Component.translatable("strategy1.tip.unit_peasant").withStyle(hands ? ChatFormatting.GREEN : ChatFormatting.RED));
            more.add(Component.translatable(barracks >= 0 ? "strategy1.tip.unit_at_barracks" : "strategy1.tip.unit_here").withStyle(ChatFormatting.GRAY));
            add(i++, unitIcon(u), new IconButton.Info(Component.translatable(u.key()), u.cost, Component.translatable(u.key() + ".info"), more),
                    me != null && built && hands && canPay(me, u.cost), () -> {
                if (barracks >= 0) structureAction(barracks, Payloads.StructureAction.MAKE, u.ordinal());
                else recruit(u);
            });
        }
    }

    private void unitPanel() {
        Map<String, List<Integer>> kinds = new LinkedHashMap<>();
        Map<String, Identifier> icons = new LinkedHashMap<>();
        for (int id : SELECTED) {
            Entity e = minecraft.level.getEntity(id);
            if (e == null) continue;
            String k = kindKey(e);
            kinds.computeIfAbsent(k, x -> new ArrayList<>()).add(id);
            icons.put(k, icon(e));
        }
        int i = 0;
        boolean ferry = false;
        for (int id : SELECTED) {
            Entity e = minecraft.level.getEntity(id);
            if (e != null && e.isPassenger() && e.getVehicle() instanceof ShipEntity || e instanceof ShipEntity sh && !sh.getPassengers().isEmpty()) ferry = true;
        }
        if (ferry) {
            add(i++, Icons.UNMAN, new IconButton.Info(Component.translatable("strategy1.rts.unload"), null, Component.translatable("strategy1.rts.unload_tip")), true,
                    () -> order(Payloads.UnitOrder.UNLOAD, null, -1));
        }
        add(i++, Icons.HOLD, new IconButton.Info(Component.translatable("strategy1.rts.hold"), null, Component.translatable("strategy1.rts.hold_tip"),
                List.of(Component.translatable("strategy1.tip.hold_key").withStyle(ChatFormatting.GRAY), Component.translatable("strategy1.rts.orders_tip").withStyle(ChatFormatting.GRAY))), true,
                () -> order(Payloads.UnitOrder.HOLD, null, -1));
        i++;
        for (Map.Entry<String, List<Integer>> e : kinds.entrySet()) {
            List<Integer> ids = e.getValue();
            add(i++, icons.get(e.getKey()), new IconButton.Info(Component.translatable(e.getKey()), null, Component.translatable("strategy1.rts.only")), true, () -> {
                SELECTED.clear();
                SELECTED.addAll(ids);
                rebuildWidgets();
            }).count(ids.size());
        }
    }

    private void structurePanel(Payloads.StructureInfo st, Payloads.FactionInfo me) {
        StructureType t = StructureType.of(st.type());
        boolean built = st.built() >= 100;
        int i = 0;
        switch (t) {
            case BARRACKS, KEEP -> {
                recruitPanel(me, t == StructureType.BARRACKS ? st.id() : -1);
                i = UnitType.values().length;
            }
            case WORKSHOP -> {
                add(i++, Icons.CATAPULT, new IconButton.Info(Component.translatable("strategy1.siege.catapult"), Rules.CATAPULT_COST,
                        Component.translatable("strategy1.siege.catapult.info")), built && canPay(me, Rules.CATAPULT_COST),
                        () -> structureAction(st.id(), Payloads.StructureAction.MAKE, Payloads.StructureAction.CATAPULT));
                add(i++, Icons.TREBUCHET, new IconButton.Info(Component.translatable("strategy1.siege.trebuchet"), Rules.TREBUCHET_COST,
                        Component.translatable("strategy1.siege.trebuchet.info")), built && canPay(me, Rules.TREBUCHET_COST),
                        () -> structureAction(st.id(), Payloads.StructureAction.MAKE, Payloads.StructureAction.TREBUCHET));
            }
            case MARKET -> {
                Payloads.Hud hud = ClientState.hud;
                Item[] goods = {Items.WHEAT, Items.OAK_LOG, Items.COBBLESTONE, Items.IRON_INGOT};
                for (int r = 0; r < 4; r++) {
                    int rr = r, price = hud == null ? 0 : hud.market()[r];
                    add(i++, goods[r], new IconButton.Info(Component.translatable("strategy1.market.sell", Component.translatable(Res.values()[r].key())),
                            null, Component.translatable("strategy1.market.sell_line", org.webtrade.strategy1.game.Market.LOT, price)),
                            built && me.res()[r] >= org.webtrade.strategy1.game.Market.LOT, () -> structureAction(st.id(), Payloads.StructureAction.SELL, rr)).count(price);
                }
                add(5, Icons.CARAVAN, new IconButton.Info(Component.translatable("strategy1.market.caravan"), org.webtrade.strategy1.game.Res.of(20, 20, 0, 0, 0),
                        Component.translatable("strategy1.market.caravan_line")), built, () -> structureAction(st.id(), Payloads.StructureAction.CARAVAN, 0))
                        .count(hud == null ? 0 : hud.caravans());
                i = perRow();
                for (int r = 0; r < 4; r++) {
                    int rr = r, price = hud == null ? 0 : hud.market()[4 + r];
                    add(i++, goods[r], new IconButton.Info(Component.translatable("strategy1.market.buy", Component.translatable(Res.values()[r].key())),
                            org.webtrade.strategy1.game.Res.of(0, 0, 0, 0, price), Component.translatable("strategy1.market.buy_line", org.webtrade.strategy1.game.Market.LOT)),
                            built && me.res()[4] >= price, () -> structureAction(st.id(), Payloads.StructureAction.BUY, rr)).count(price).chosen(true);
                }
            }
            case PORT -> {
                add(i++, Icons.TRANSPORT, new IconButton.Info(Component.translatable("strategy1.ship.transport"), Rules.TRANSPORT_COST,
                        Component.translatable("strategy1.ship.transport.info")), built && canPay(me, Rules.TRANSPORT_COST),
                        () -> structureAction(st.id(), Payloads.StructureAction.MAKE, Payloads.StructureAction.TRANSPORT));
                add(i++, Icons.WARSHIP, new IconButton.Info(Component.translatable("strategy1.ship.warship"), Rules.WARSHIP_COST,
                        Component.translatable("strategy1.ship.warship.info")), built && canPay(me, Rules.WARSHIP_COST),
                        () -> structureAction(st.id(), Payloads.StructureAction.MAKE, Payloads.StructureAction.WARSHIP));
            }
            default -> {
            }
        }
        if (t.tower() && built) {
            for (int e = 1; e <= 3; e++) {
                // (a wooden tower takes a ballista only)
                if (!t.carries(e)) continue;
                int en = e;
                int[] cost = org.webtrade.strategy1.build.Structures.engineCost(e);
                add(i++, Icons.engine(e), new IconButton.Info(Component.translatable("strategy1.engine." + e), cost, Component.translatable("strategy1.engine." + e + ".info")),
                        st.engine() != e && canPay(me, cost), () -> structureAction(st.id(), Payloads.StructureAction.UPGRADE, en)).chosen(st.engine() == e);
            }
            i++;
        }
        if (t.wall() && built && clicked != null) {
            int dx = clicked.getX() - st.box()[0], dz = clicked.getZ() - st.box()[2];
            add(i++, Icons.of("gate"), new IconButton.Info(Component.translatable("strategy1.rts.gate_make"), StructureType.GATE.cost(3),
                    Component.translatable("strategy1.rts.gate_make_tip"), List.of(Component.translatable("strategy1.build.gate.info").withStyle(ChatFormatting.GRAY))),
                    canPay(me, StructureType.GATE.cost(3)), () -> structureAction(st.id(), Payloads.StructureAction.GATE, Payloads.StructureAction.pack(dx, dz)));
        }
        if (t == StructureType.GATE && built) {
            add(i++, Icons.of("gate"), new IconButton.Info(Component.translatable("strategy1.rts.gate_hold"), null, Component.translatable("strategy1.rts.gate_hold_tip")),
                    true, () -> structureAction(st.id(), Payloads.StructureAction.GATE, 0)).chosen(st.gate() == 1);
        }
        if (t.manned()) {
            add(i++, Icons.UNMAN, new IconButton.Info(Component.translatable("strategy1.rts.unman"), null, Component.translatable("strategy1.rts.unman_tip")),
                    st.archers() > 0, () -> structureAction(st.id(), Payloads.StructureAction.UNMAN, 0)).count(st.archers());
        }
        if (t != StructureType.KEEP) {
            add(i + 1, Icons.DEMOLISH, new IconButton.Info(Component.translatable("strategy1.rts.demolish"), null, Component.translatable("strategy1.tip.demolish")), true,
                    () -> structureAction(st.id(), Payloads.StructureAction.DEMOLISH, 0));
        }
    }

    @Override
    public void tick() {
        SELECTED.removeIf(id -> !(minecraft.level.getEntity(id) instanceof UnitEntity u) || !u.isAlive());
        if (structure >= 0 && !ClientState.STRUCTURES.containsKey(structure)) structure = -1;
        if (ClientState.revision != seenRevision || sig() != seenStructures) rebuildWidgets();
    }

    // ------------------------------------------------------------------ the world under the mouse

    private double focal() {
        return (height / 2.0) / Math.tan(Math.toRadians(minecraft.gameRenderer.mainCamera().getFov()) / 2);
    }

    private Vec3 ray(double sx, double sy) {
        var cam = minecraft.gameRenderer.mainCamera();
        double f = focal();
        Vector3f v = new Vector3f((float) ((sx - width / 2.0) / f), (float) (-(sy - height / 2.0) / f), -1F).normalize();
        v.rotate(new Quaternionf(cam.rotation()));
        return new Vec3(v.x, v.y, v.z);
    }

    /** A point of the world on the screen (x, y, depth), or null behind the camera. */
    private Vec3 project(Vec3 world) {
        var cam = minecraft.gameRenderer.mainCamera();
        Vec3 rel = world.subtract(cam.position());
        Vector3f v = new Vector3f((float) rel.x, (float) rel.y, (float) rel.z);
        v.rotate(new Quaternionf(cam.rotation()).conjugate());
        if (v.z >= -0.1F) return null;
        double f = focal();
        return new Vec3(width / 2.0 + v.x / -v.z * f, height / 2.0 - v.y / -v.z * f, -v.z);
    }

    private record Pick(Vec3 ground, BlockPos block, Entity entity, Payloads.StructureInfo structure) {
    }

    private Pick pick(double sx, double sy) {
        Vec3 from = minecraft.gameRenderer.mainCamera().position(), dir = ray(sx, sy), to = from.add(dir.scale(300));
        BlockHitResult block = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, minecraft.player));
        Vec3 reach = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        EntityHitResult ent = ProjectileUtil.getEntityHitResult(minecraft.player, from, reach, new AABB(from, reach).inflate(1),
                e -> e instanceof LivingEntity && e != minecraft.player && !e.isSpectator(), from.distanceToSqr(reach));
        Payloads.StructureInfo st = null;
        if (block.getType() != HitResult.Type.MISS) {
            Vec3 c = Vec3.atCenterOf(block.getBlockPos());
            for (Payloads.StructureInfo s : ClientState.STRUCTURES.values()) {
                if (s.type() == StructureType.PATH.ordinal() || s.type() == StructureType.BRIDGE.ordinal()) continue;
                if (s.aabb().inflate(0.3).contains(c)) st = s;
            }
        }
        boolean miss = block.getType() == HitResult.Type.MISS;
        return new Pick(miss ? null : block.getLocation(), miss ? null : block.getBlockPos(), ent == null ? null : ent.getEntity(), st);
    }

    /** One of the enemy near a point of the ground (they're small, seen from high up). */
    private Entity enemyNear(Vec3 at, double r) {
        if (at == null) return null;
        Entity best = null;
        double bestD = r * r;
        for (Entity e : minecraft.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity l) || !l.isAlive() || !enemy(e)) continue;
            double d = e.position().distanceToSqr(at.x, e.getY(), at.z);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private boolean mine(Entity e) {
        return e instanceof UnitEntity u && u.faction() == ClientState.me() && u.isAlive() && !(e instanceof PeasantEntity);
    }

    private boolean enemy(Entity e) {
        if (e instanceof UnitEntity u) return ClientState.atWar(ClientState.me(), u.faction());
        return e instanceof net.minecraft.world.entity.monster.Enemy;
    }

    private List<UnitEntity> unitsNear() {
        List<UnitEntity> out = new ArrayList<>();
        for (Entity e : minecraft.level.entitiesForRendering()) if (e instanceof UnitEntity s && s.isAlive()) out.add(s);
        return out;
    }

    // ------------------------------------------------------------------ orders

    private void order(int kind, Vec3 at, int target) {
        orderTo(SELECTED, kind, at, target);
    }

    private void orderTo(Set<Integer> who, int kind, Vec3 at, int target) {
        if (who.isEmpty() || !ClientPlayNetworking.canSend(Payloads.UnitOrder.TYPE)) return;
        int[] ids = who.stream().mapToInt(Integer::intValue).toArray();
        Vec3 p = at == null ? Vec3.ZERO : at;
        ClientPlayNetworking.send(new Payloads.UnitOrder(ids, kind, p.x, p.y, p.z, target));
    }

    private void selectAll() {
        SELECTED.clear();
        structure = -1;
        for (UnitEntity s : unitsNear()) if (mine(s)) SELECTED.add(s.getId());
        rebuildWidgets();
    }

    private void recruit(UnitType u) {
        long here = Hex.at(Rts.focusX, Rts.focusZ).key();
        if (ClientPlayNetworking.canSend(Payloads.Action.TYPE)) ClientPlayNetworking.send(new Payloads.Action(Payloads.Action.RECRUIT, here, u.ordinal()));
    }

    private static void structureAction(int id, int action, int arg) {
        if (ClientPlayNetworking.canSend(Payloads.StructureAction.TYPE)) ClientPlayNetworking.send(new Payloads.StructureAction(id, action, arg));
    }

    private void openMap() {
        if (ClientPlayNetworking.canSend(Payloads.RequestSync.TYPE)) ClientPlayNetworking.send(new Payloads.RequestSync());
        minecraft.gui.setScreen(new StrategyMapScreen(0, Hex.at(Rts.focusX, Rts.focusZ).key(), Rts.focusX, Rts.focusZ, 0.35F));
    }

    private void mark(Vec3 at, DustParticleOptions o) {
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI / 5;
            minecraft.level.addParticle(o, true, true, at.x + Math.cos(a) * 1.4, at.y + 0.2, at.z + Math.sin(a) * 1.4, 0, 0.05, 0);
        }
    }

    private int rotation() {
        return Math.floorMod(Math.round(Rts.yaw / 90F) + turn, 4);
    }

    /** Right click: what the chosen units do, by what is under the mouse. */
    private void command(Pick p) {
        // a tower (or wall, or the keep) chosen: right click on one of the enemy is its target
        Payloads.StructureInfo chosen = ClientState.STRUCTURES.get(structure);
        // barracks, workshop or port chosen: right click on the ground is where what it sends out gathers
        if (chosen != null && chosen.owner() == ClientState.me() && StructureType.of(chosen.type()).musters() && SELECTED.isEmpty()
                && p.ground != null && chosen.door() != null && (p.entity == null || !enemy(p.entity))) {
            Vec3 d = chosen.door();
            if (ClientPlayNetworking.canSend(Payloads.StructureAction.TYPE)) ClientPlayNetworking.send(new Payloads.StructureAction(chosen.id(),
                    Payloads.StructureAction.RALLY, Payloads.StructureAction.pack((int) Math.floor(p.ground.x) - (int) Math.floor(d.x), (int) Math.floor(p.ground.z) - (int) Math.floor(d.z))));
            mark(p.ground, GO);
            return;
        }
        if (chosen != null && chosen.owner() == ClientState.me() && StructureType.of(chosen.type()).manned()) {
            Entity target = p.entity != null && enemy(p.entity) ? p.entity : enemyNear(p.ground, 3);
            if (target != null) {
                if (ClientPlayNetworking.canSend(Payloads.StructureAction.TYPE))
                    ClientPlayNetworking.send(new Payloads.StructureAction(chosen.id(), Payloads.StructureAction.AIM, target.getId()));
                aimed = target.getId();
                mark(target.position(), HIT);
                return;
            }
        }
        if (SELECTED.isEmpty()) return;
        Set<Integer> soldiers = new HashSet<>(), archers = new HashSet<>();
        for (int id : SELECTED) {
            if (minecraft.level.getEntity(id) instanceof SoldierEntity s) {
                soldiers.add(id);
                if (s.unit().ranged) archers.add(id);
            }
        }
        if (p.entity != null && enemy(p.entity)) {
            order(Payloads.UnitOrder.ATTACK, p.entity.position(), p.entity.getId());
            mark(p.entity.position(), HIT);
            return;
        }
        if (p.entity instanceof ShipEntity ship && mine(ship) && !soldiers.isEmpty()) {
            orderTo(soldiers, Payloads.UnitOrder.BOARD, ship.position(), ship.getId());
            mark(ship.position(), GO);
            return;
        }
        if (p.structure != null && ClientState.atWar(ClientState.me(), p.structure.owner())) {
            order(Payloads.UnitOrder.ATTACK_STRUCTURE, p.ground, p.structure.id());
            mark(p.ground == null ? p.structure.aabb().getCenter() : p.ground, HIT);
            return;
        }
        // (an outpost takes one soldier of any kind; towers and walls take bowmen)
        boolean outpost = p.structure != null && p.structure.type() == StructureType.OUTPOST.ordinal();
        Set<Integer> posting = outpost ? soldiers.stream().limit(1).collect(java.util.stream.Collectors.toSet()) : archers;
        if (p.structure != null && p.structure.owner() == ClientState.me() && StructureType.of(p.structure.type()).manned() && !posting.isEmpty()) {
            orderTo(posting, Payloads.UnitOrder.POST, p.ground, p.structure.id());
            Set<Integer> rest = new HashSet<>(SELECTED);
            rest.removeAll(posting);
            if (p.ground != null) orderTo(rest, Payloads.UnitOrder.MOVE, p.ground, -1);
            mark(p.structure.aabb().getCenter(), GO);
            return;
        }
        if (p.ground != null) {
            order(Payloads.UnitOrder.MOVE, p.ground, -1);
            mark(p.ground, GO);
        }
    }

    // ------------------------------------------------------------------ the mouse and keys

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (inMini(event.x(), event.y())) {
            double[] w = miniToWorld(event.x(), event.y());
            if (event.button() == 0) {
                miniDrag = true;
                lookAt(w[0], w[1]);
            } else if (event.button() == 1 && !SELECTED.isEmpty()) {
                int y = minecraft.level.hasChunkAt(BlockPos.containing(w[0], 64, w[1]))
                        ? minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(w[0]), (int) Math.floor(w[1])) : 64;
                order(Payloads.UnitOrder.MOVE, new Vec3(w[0], y, w[1]), -1);
            }
            return true;
        }
        if (event.y() > height - BAR || event.y() < TOP) return false;
        if (placing != null) {
            if (event.button() == 1) {
                placing = null;
                lineStart = null;
                rebuildWidgets();
                return true;
            }
            Pick p = pick(event.x(), event.y());
            if (p.block == null || event.button() != 0) return true;
            BlockPos at = p.block.above();
            if (placing.line) {
                if (lineStart == null) lineStart = at;
                else {
                    send(placing, lineStart, at);
                    lineStart = at;
                }
            } else {
                send(placing, at, at);
                if ((event.modifiers() & GLFW.GLFW_MOD_SHIFT) == 0) {
                    placing = null;
                    rebuildWidgets();
                }
            }
            return true;
        }
        if (event.button() == 0) {
            pressX = event.x();
            pressY = event.y();
            boxing = true;
            return true;
        }
        if (event.button() == 1) {
            command(pick(event.x(), event.y()));
            return true;
        }
        return false;
    }

    private void send(StructureType t, BlockPos a, BlockPos b) {
        if (ClientPlayNetworking.canSend(Payloads.Build.TYPE)) {
            ClientPlayNetworking.send(new Payloads.Build(t.ordinal(), a.getX(), a.getY(), a.getZ(), b.getX(), b.getZ(), rotation()));
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (miniDrag) {
            double[] w = miniToWorld(Mth.clamp(event.x(), width - 4 - mini(), width - 4), Mth.clamp(event.y(), height - 4 - mini(), height - 4));
            lookAt(w[0], w[1]);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (miniDrag && event.button() == 0) {
            miniDrag = false;
            return true;
        }
        if (boxing && event.button() == 0) {
            boxing = false;
            boolean add = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
            if (!add) SELECTED.clear();
            double x0 = Math.min(pressX, event.x()), x1 = Math.max(pressX, event.x());
            double y0 = Math.min(pressY, event.y()), y1 = Math.max(pressY, event.y());
            structure = -1;
            if (x1 - x0 < 4 && y1 - y0 < 4) {
                Pick p = pick(event.x(), event.y());
                if (mine(p.entity)) {
                    if (add && SELECTED.contains(p.entity.getId())) SELECTED.remove(p.entity.getId());
                    else SELECTED.add(p.entity.getId());
                } else if (p.structure != null && !add) {
                    structure = p.structure.id();
                    clicked = p.block;
                }
            } else {
                for (UnitEntity s : unitsNear()) {
                    if (!mine(s)) continue;
                    Vec3 sp = project(s.position().add(0, 1, 0));
                    if (sp != null && sp.x >= x0 && sp.x <= x1 && sp.y >= y0 && sp.y <= y1) SELECTED.add(s.getId());
                }
            }
            rebuildWidgets();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        Rts.zoom(sy);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            if (placing != null || structure >= 0 || !SELECTED.isEmpty()) {
                placing = null;
                lineStart = null;
                structure = -1;
                SELECTED.clear();
                rebuildWidgets();
                return true;
            }
            minecraft.gui.setScreen(new PauseScreen(true));
            return true;
        }
        if (placing != null && k == GLFW.GLFW_KEY_R) {
            turn++;
            return true;
        }
        if (Keys.MAP.matches(event)) {
            openMap();
            return true;
        }
        if (Keys.COMMANDER.matches(event)) {
            Rts.exit(minecraft);
            return true;
        }
        if (k == GLFW.GLFW_KEY_A && ctrl) {
            selectAll();
            return true;
        }
        if (k == GLFW.GLFW_KEY_TAB) {
            tab = Tab.values()[(tab.ordinal() + 1) % Tab.values().length];
            placing = null;
            lineStart = null;
            structure = -1;
            SELECTED.clear();
            rebuildWidgets();
            return true;
        }
        if (k == GLFW.GLFW_KEY_H || k == GLFW.GLFW_KEY_SPACE) {
            order(Payloads.UnitOrder.HOLD, null, -1);
            return true;
        }
        if (minecraft.options.keyChat.matches(event) || k == GLFW.GLFW_KEY_ENTER) {
            minecraft.gui.setScreen(new ChatScreen("", false));
            return true;
        }
        if (minecraft.options.keyCommand.matches(event)) {
            minecraft.gui.setScreen(new ChatScreen("/", false));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        structures(g);
        reaches(g);
        units(g);
        monsters(g);
        captures(g);
        if (placing != null) preview(g, mx, my);
        else hovered(g, mx, my);
        if (boxing && (Math.abs(mx - pressX) > 3 || Math.abs(my - pressY) > 3)) {
            int x0 = (int) Math.min(pressX, mx), x1 = (int) Math.max(pressX, mx), y0 = (int) Math.min(pressY, my), y1 = (int) Math.max(pressY, my);
            g.fill(x0, y0, x1, y1, 0x2060FF60);
            g.outline(x0, y0, x1 - x0, y1 - y0, 0xFF60FF60);
        }
        top(g);
        g.fill(0, height - BAR, width, height, 0xC0201810);
        g.fill(0, height - BAR, width, height - BAR + 1, Palette.BRASS_DARK);
        g.fill(x0() - 5, height - BAR + 6, x0() - 4, height - 6, Palette.BRASS_DARK);
        minimap(g);
        super.extractRenderState(g, mx, my, partialTick);
        info(g, mx, my);
        note(g, mx, my);
    }

    /** The treasury and the people, in pictures, along the top. */
    private void top(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, TOP, 0xC0201810);
        g.fill(0, TOP, width, TOP + 1, Palette.BRASS_DARK);
        stats.clear();
        var me = ClientState.mine();
        if (me == null) return;
        int x = 4;
        for (int i = 0; i < Res.COUNT; i++) {
            String id = Res.of(i).name().toLowerCase(java.util.Locale.ROOT);
            x = stat(g, x, StrategyHud.icon(i), compact(me.res()[i]), 0xFFFFFFFF, "res." + id, me.res()[i]);
        }
        x += 4;
        Payloads.Hud hud = ClientState.hud;
        if (hud != null) {
            x = stat(g, x, new ItemStack(Items.PLAYER_HEAD), (hud.peasants() + hud.army()) + "/" + hud.armyMax(), 0xFFFFE0A0, "people", hud.peasants() + hud.army(), hud.armyMax());
            x = stat(g, x, new ItemStack(Items.WOODEN_HOE), String.valueOf(hud.idle()), hud.idle() > 0 ? 0xFFA0FFA0 : 0xFFC0C0C0, "idle", hud.idle(), hud.peasants());
            x = stat(g, x, new ItemStack(Items.IRON_SWORD), String.valueOf(hud.army()), 0xFFFFFFFF, "army", hud.army());
            if (hud.mode() == org.webtrade.strategy1.game.Mode.TRADE.ordinal()) {
                int mine = 0;
                for (int k = 0; k + 1 < hud.ranking().length; k += 2) if (hud.ranking()[k] == ClientState.me()) mine = hud.ranking()[k + 1];
                x = stat(g, x, new ItemStack(Items.EMERALD), compact(mine) + "/" + compact(hud.goal()), 0xFF80FF90, "goal", mine, hud.goal());
            }
            if (hud.mode() == org.webtrade.strategy1.game.Mode.SIEGE.ordinal()) {
                // the siege: which night, how many of the dead are about, how long till dark (or dawn)
                int c = Math.abs(hud.clock());
                String clock = c / 60 + ":" + (c % 60 < 10 ? "0" : "") + c % 60;
                x = stat(g, x, new ItemStack(Items.ZOMBIE_HEAD), String.valueOf(hud.wave()), 0xFFFF8070, "wave", hud.wave());
                if (hud.monsters() > 0) x = stat(g, x, new ItemStack(Items.ROTTEN_FLESH), String.valueOf(hud.monsters()), 0xFFFF6050, "monsters", hud.monsters());
                x = stat(g, x, new ItemStack(hud.clock() < 0 ? Items.ENDER_EYE : Items.SUNFLOWER), clock, hud.clock() < 0 ? 0xFFFF7060 : 0xFFFFE070,
                        hud.clock() < 0 ? "night" : "day", clock);
            } else {
                int t = hud.turnLeft();
                String left = t / 60 + ":" + (t % 60 < 10 ? "0" : "") + t % 60;
                x = stat(g, x, new ItemStack(Items.CLOCK), left, 0xFFC0C0C0, "turn", left);
            }
        }
        // the trading race: the realms by how much richer they've grown, a coloured mark and a number each
        if (hud != null && hud.mode() == org.webtrade.strategy1.game.Mode.TRADE.ordinal()) {
            int ry = TOP + 4;
            for (int k = 0; k + 1 < hud.ranking().length && k < 10; k += 2) {
                int col = ClientState.color(hud.ranking()[k]);
                String v = compact(hud.ranking()[k + 1]);
                int rx = width - 8 - font.width(v) - 12;
                g.fill(rx - 2, ry - 2, width - 4, ry + 10, 0xA0201810);
                g.fill(rx, ry, rx + 8, ry + 8, 0xFF000000 | (col < 0 ? 0x888888 : col));
                g.text(font, v, rx + 11, ry, hud.ranking()[k] == ClientState.me() ? 0xFFFFE070 : 0xFFFFFFFF, true);
                ry += 13;
            }
        }
        // where the camera is, small, on the right
        Payloads.ProvinceInfo here = ClientState.province(Hex.at(Rts.focusX, Rts.focusZ));
        if (here != null) {
            Component c = Component.literal(here.name() + " · ").append(Terrain.of(here.terrain()).land() ? ClientState.name(here.owner())
                    : Component.translatable("strategy1.owner.sea"));
            int w = font.width(c);
            int rx = width - 2 * (ICON + 2) - 8 - w;
            if (rx > x + 8) g.text(font, c, rx, 7, 0xFFFFFFFF, true);
        }
    }

    private int stat(GuiGraphicsExtractor g, int x, ItemStack icon, String value, int color, String tip, Object... args) {
        g.item(icon, x, 3);
        g.text(font, value, x + 17, 7, color, true);
        int end = x + 19 + font.width(value);
        stats.add(new Stat(x - 2, end + 2, Component.translatable("strategy1.tip." + tip), Component.translatable("strategy1.tip." + tip + ".info", args)));
        return end + 5;
    }

    /** 19976 → 19.9k: the treasury's numbers kept short. */
    private static String compact(int n) {
        if (Math.abs(n) < 10000) return String.valueOf(n);
        if (Math.abs(n) < 1000000) return (n / 100) / 10.0 + "k";
        return (n / 100000) / 10.0 + "M";
    }

    /** The box on the right of the bottom panel: what is under the mouse (else what is chosen). */
    private void info(GuiGraphicsExtractor g, int mx, int my) {
        int x1 = width - mini() - 12, x0 = x1 - INFO, y0 = height - BAR + 5, y1 = height - 5;
        Ui.inset(g, x0, y0, x1, y1);
        Component name = null, line = null;
        int[] cost = null;
        if (placing != null) {
            name = Component.translatable(placing.key());
            cost = placing.cost;
            line = Component.translatable(placing.line ? "strategy1.rts.help_line" : "strategy1.rts.help_place");
        } else if (ClientState.STRUCTURES.get(structure) != null) {
            Payloads.StructureInfo s = ClientState.STRUCTURES.get(structure);
            name = Component.translatable(StructureType.of(s.type()).key()).append(" · ").append(ClientState.name(s.owner()));
            line = s.built() < 100 ? Component.translatable("strategy1.rts.building", s.built())
                    : Component.literal("❤ " + s.hp() + "/" + s.maxHp() + (s.posts() > 0 ? "   ➶ " + s.archers() + "/" + s.posts() : ""));
            if (s.built() >= 100 && StructureType.of(s.type()).manned() && s.owner() == ClientState.me())
                line = line.copy().append(" · ").append(Component.translatable("strategy1.tip.aim"));
            if (s.left() >= 0) line = line.copy().append(" · ").append(Component.translatable("strategy1.tip.left", s.left()));
            if (StructureType.of(s.type()).musters() && s.owner() == ClientState.me())
                line = line.copy().append(" · ").append(Component.translatable("strategy1.tip.rally"));
        } else if (!SELECTED.isEmpty()) {
            name = Component.translatable("strategy1.rts.selected", SELECTED.size());
            line = Component.translatable("strategy1.rts.orders_tip");
        } else {
            name = Component.translatable(tab.key());
            line = Component.translatable(tab == Tab.ARMY ? "strategy1.rts.army_tip" : "strategy1.rts.build_tip2");
        }
        int x = x0 + 5, y = y0 + 4;
        g.text(font, Ui.fit(font, name.getString(), INFO - 10), x, y, Palette.INK, false);
        y += 12;
        if (cost != null) {
            var me = ClientState.mine();
            int cx = x;
            for (int i = 0; i < Res.COUNT; i++) {
                if (cost[i] == 0) continue;
                g.pose().pushMatrix();
                g.pose().translate(cx, y - 2);
                g.pose().scale(0.75F, 0.75F);
                g.item(StrategyHud.icon(i), 0, 0);
                g.pose().popMatrix();
                String v = String.valueOf(cost[i]);
                boolean short_ = me == null || me.res()[i] < cost[i];
                g.text(font, v, cx + 13, y + 1, short_ ? Palette.BAD : Palette.INK_SOFT, false);
                cx += 16 + font.width(v) + 4;
            }
            y += 12;
        }
        if (line != null) {
            // as many lines as the box holds
            List<FormattedCharSequence> lines = font.split(line, INFO - 10);
            for (int i = 0; i < lines.size() && y + 9 <= y1 - 2; i++, y += 10) g.text(font, lines.get(i), x, y, Palette.TEXT_MUTED, false);
        }
    }

    private void line(GuiGraphicsExtractor g, Vec3 a, Vec3 b, int color) {
        Vec3 pa = project(a), pb = project(b);
        if (pa == null || pb == null) return;
        int n = (int) Math.max(1, Math.hypot(pb.x - pa.x, pb.y - pa.y) / 2);
        for (int i = 0; i <= n; i++) {
            int x = (int) (pa.x + (pb.x - pa.x) * i / n), y = (int) (pa.y + (pb.y - pa.y) * i / n);
            g.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }

    private void bar(GuiGraphicsExtractor g, int x, int y, int w, float f, int color) {
        g.fill(x - w / 2 - 1, y - 1, x + w / 2 + 1, y + 4, 0xC0000000);
        g.fill(x - w / 2, y, x - w / 2 + Math.round(w * Mth.clamp(f, 0, 1)), y + 3, 0xFF000000 | color);
    }

    /** Over each structure near: how far built, its strength; the chosen one outlined. */
    private void structures(GuiGraphicsExtractor g) {
        for (Payloads.StructureInfo s : ClientState.STRUCTURES.values()) {
            AABB b = s.aabb();
            Vec3 c = b.getCenter();
            if (Math.hypot(c.x - Rts.focusX, c.z - Rts.focusZ) > 110 || s.type() == StructureType.PATH.ordinal()) continue;
            Vec3 top = project(new Vec3(c.x, b.maxY + 0.8, c.z));
            int color = ClientState.color(s.owner());
            boolean chosen = s.id() == structure;
            if (chosen && s.owner() == ClientState.me() && s.door() != null) {
                Vec3 door = ground(s.door());
                ring(g, door, 1.5, 0xFF60D0FF);
                Vec3 rally = s.rally() == null ? null : ground(s.rally());
                if (rally != null) {
                    line(g, door.add(0, 0.2, 0), rally.add(0, 0.2, 0), 0xFF70FF70);
                    line(g, rally, rally.add(0, 2.5, 0), 0xFFFFFFFF);
                    line(g, rally.add(0, 2.5, 0), rally.add(0.9, 2.1, 0), 0xFF70FF70);
                    line(g, rally.add(0.9, 2.1, 0), rally.add(0, 1.7, 0), 0xFF70FF70);
                }
            }
            if (chosen) {
                int col = 0xFFFFF4A0;
                Vec3 p0 = new Vec3(b.minX, b.minY, b.minZ), p1 = new Vec3(b.maxX, b.minY, b.minZ), p2 = new Vec3(b.maxX, b.minY, b.maxZ),
                        p3 = new Vec3(b.minX, b.minY, b.maxZ);
                line(g, p0, p1, col);
                line(g, p1, p2, col);
                line(g, p2, p3, col);
                line(g, p3, p0, col);
            }
            if (top == null || top.z > 140) continue;
            int x = (int) top.x, y = (int) top.y;
            StructureType kind = StructureType.of(s.type());
            if (top.z < 70 && s.built() >= 100 && !kind.deposit() && !kind.wall() && kind != StructureType.GATE && kind != StructureType.BRIDGE) {
                var sprite = Icons.building(kind);
                if (sprite != null) {
                    g.fill(x - 11, y - 25, x + 11, y - 3, 0x90000000 | (color < 0 ? 0x666666 : color & 0xFFFFFF));
                    Ui.blit(g, sprite, x - 10, y - 24, x + 10, y - 4);
                }
            }
            if (s.built() < 100) {
                if (s.owner() != ClientState.me() && !chosen) continue;
                bar(g, x, y, 40, s.built() / 100F, 0xE0C040);
            } else if (s.maxHp() > 0 && (s.hp() < s.maxHp() || chosen)) {
                bar(g, x, y, 40, s.hp() / (float) s.maxHp(), color < 0 ? 0xAAAAAA : color);
            }
        }
    }

    /** Over every unit its health, in its realm's colour; the chosen ones framed; peasants with what they carry. */
    private void units(GuiGraphicsExtractor g) {
        for (UnitEntity s : unitsNear()) {
            Vec3 head = project(s.position().add(0, s.getBbHeight() + 0.35, 0));
            Vec3 feet = project(s.position());
            if (head == null || feet == null || head.z > 160) continue;
            int x = (int) head.x, y = (int) head.y;
            int h = (int) Math.max(6, feet.y - head.y), w = Math.max(4, (int) (h * (s instanceof SoldierEntity || s instanceof PeasantEntity ? 0.55 : 1.3)));
            if (s instanceof PeasantEntity) {
                if (head.z < 60 && s.getHealth() < s.getMaxHealth()) bar(g, x, y - 4, 14, s.getHealth() / s.getMaxHealth(), s.color());
                continue;
            }
            int bw = Mth.clamp(w + 6, 10, s instanceof SoldierEntity ? 30 : 60);
            float hp = s.getHealth() / s.getMaxHealth();
            g.fill(x - bw / 2 - 1, y - 4, x + bw / 2 + 1, y, 0xC0000000);
            g.fill(x - bw / 2, y - 3, x - bw / 2 + Math.round(bw * hp), y - 1, 0xFF000000 | s.color());
            if (s instanceof ShipEntity ship && !ship.getPassengers().isEmpty()) {
                Ui.centered(g, font, ship.getPassengers().size() + "/" + ship.kind().capacity, x, y - 13, 0xFFFFFFFF);
            }
            if (SELECTED.contains(s.getId())) {
                int x0 = x - w / 2 - 2, x1 = x + w / 2 + 2, y0 = y + 1, y1 = (int) feet.y + 2;
                int c = 0xFF70FF70, l = Math.max(3, (x1 - x0) / 3);
                g.fill(x0, y0, x0 + l, y0 + 1, c);
                g.fill(x0, y0, x0 + 1, y0 + l, c);
                g.fill(x1 - l, y0, x1, y0 + 1, c);
                g.fill(x1 - 1, y0, x1, y0 + l, c);
                g.fill(x0, y1 - 1, x0 + l, y1, c);
                g.fill(x0, y1 - l, x0 + 1, y1, c);
                g.fill(x1 - l, y1 - 1, x1, y1, c);
                g.fill(x1 - 1, y1 - l, x1, y1, c);
            }
        }
    }

    /** How far the chosen shoot: a ring round each bowman, and round a chosen tower (its engine's too). */
    private void reaches(GuiGraphicsExtractor g) {
        int n = 0;
        for (int id : SELECTED) {
            if (!(minecraft.level.getEntity(id) instanceof SoldierEntity s) || !s.unit().ranged || n++ > 12) continue;
            double r = s.isPassenger() ? 26 : StructureType.FIELD_REACH;
            ring(g, new Vec3(s.getX(), s.getY() + 0.1, s.getZ()), r, 0x90FFB040);
        }
        Payloads.StructureInfo st = ClientState.STRUCTURES.get(structure);
        if (st != null && StructureType.of(st.type()).manned() && st.built() >= 100) {
            StructureType t = StructureType.of(st.type());
            Vec3 c = st.aabb().getCenter();
            Vec3 at = ground(new Vec3(c.x, 0, c.z));
            if (st.archers() > 0 || t.tower()) ring(g, at, t.reach(), 0xA0FFB040);
            if (st.engine() > 0) ring(g, at, t.engineReach(st.engine()), 0xA0FF6040);
        }
    }

    /** Over each of the dead near: its health in red, a shield-bearer's shield over it, a bomber's lit fuse; the one aimed at marked. */
    private void monsters(GuiGraphicsExtractor g) {
        for (Entity e : minecraft.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity m) || !m.isAlive() || e instanceof UnitEntity || !(e instanceof net.minecraft.world.entity.monster.Enemy)) continue;
            if (Math.hypot(e.getX() - Rts.focusX, e.getZ() - Rts.focusZ) > 90) continue;
            // (not those down in the caves under the land)
            if (minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, e.getBlockX(), e.getBlockZ()) - e.getY() > 4) continue;
            Vec3 head = project(e.position().add(0, e.getBbHeight() + 0.35, 0));
            if (head == null || head.z > 160) continue;
            int x = (int) head.x, y = (int) head.y, bw = 16;
            float hp = m.getHealth() / m.getMaxHealth();
            g.fill(x - bw / 2 - 1, y - 4, x + bw / 2 + 1, y, 0xC0000000);
            g.fill(x - bw / 2, y - 3, x - bw / 2 + Math.round(bw * hp), y - 1, 0xFFE03020);
            if (e instanceof org.webtrade.strategy1.entity.ShieldZombie sz && sz.shield() > 0) {
                float sh = sz.shield() / (float) Math.max(1, sz.shieldMax());
                g.fill(x - bw / 2 - 1, y - 8, x + bw / 2 + 1, y - 4, 0xC0000000);
                g.fill(x - bw / 2, y - 7, x - bw / 2 + Math.round(bw * sh), y - 5, 0xFF9CC8F0);
            }
            if (e instanceof org.webtrade.strategy1.entity.BomberZombie bz && bz.fuse() >= 0 && (bz.fuse() / 3) % 2 == 0) {
                Ui.centered(g, font, "!", x, y - 14, 0xFFFF9020);
            }
            if (e.getId() == aimed) {
                int c = 0xFFFF4040, r = 6, cy = y - 12;
                g.fill(x - r, cy, x - 2, cy + 1, c);
                g.fill(x + 2, cy, x + r, cy + 1, c);
                g.fill(x, cy - r, x + 1, cy - 2, c);
                g.fill(x, cy + 2, x + 1, cy + r, c);
            }
        }
    }

    /** Over each province being taken: who takes it, how far along. */
    private void captures(GuiGraphicsExtractor g) {
        Payloads.Hud hud = ClientState.hud;
        if (hud == null) return;
        for (int i = 0; i < hud.keys().length; i++) {
            Hex h = Hex.of(hud.keys()[i]);
            int y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) h.centerX(), (int) h.centerZ());
            Vec3 at = project(new Vec3(h.centerX(), y + 12, h.centerZ()));
            if (at == null) continue;
            int color = ClientState.color(hud.by()[i]);
            int x = (int) at.x, ty = (int) at.y;
            g.item(new ItemStack(Items.BELL), x - 8, ty - 18);
            bar(g, x, ty, 60, hud.progress()[i] / (float) Rules.CAPTURE, color < 0 ? 0xFFFFFF : color);
        }
    }

    /** Where what is being placed would stand, outlined on the ground (green on your land, red elsewhere). */
    private void preview(GuiGraphicsExtractor g, int mx, int my) {
        if (my > height - BAR || my < TOP) return;
        Pick p = pick(mx, my);
        if (p.block == null) return;
        BlockPos at = p.block.above();
        int[] cost = placing.cost;
        if (placing.line) {
            BlockPos a = lineStart == null ? at : lineStart;
            if (placing.wall()) {
                a = snap(a, at);
                at = snap(at, a);
            }
            boolean own = owns(a.getX(), a.getZ()) && owns(at.getX(), at.getZ());
            int col = own ? 0xFF60FF60 : 0xFFFF5040;
            int len = (int) Math.round(Math.hypot(at.getX() - a.getX(), at.getZ() - a.getZ())) + 1;
            double h = placing == StructureType.PATH || placing == StructureType.BRIDGE ? 0.2 : placing == StructureType.WALL_STONE ? 5 : 4;
            line(g, Vec3.atBottomCenterOf(a), Vec3.atBottomCenterOf(at), col);
            line(g, Vec3.atBottomCenterOf(a).add(0, h, 0), Vec3.atBottomCenterOf(at).add(0, h, 0), col);
            cost = placing.cost(len);
            if (lineStart != null) {
                Vec3 mid = project(Vec3.atBottomCenterOf(a).lerp(Vec3.atBottomCenterOf(at), 0.5).add(0, h + 1, 0));
                if (mid != null) Ui.centered(g, font, String.valueOf(len), (int) mid.x, (int) mid.y, 0xFFFFFFFF);
            }
        } else {
            int r = rotation();
            double[][] c = {{placing.x0, placing.z0}, {placing.x1 + 1, placing.z0}, {placing.x1 + 1, placing.z1 + 1}, {placing.x0, placing.z1 + 1}};
            Vec3[] w = new Vec3[4];
            boolean own = true;
            for (int i = 0; i < 4; i++) {
                double x = c[i][0], z = c[i][1];
                double rx = switch (r) {
                    case 1 -> -z + 1;
                    case 2 -> -x + 1;
                    case 3 -> z;
                    default -> x;
                }, rz = switch (r) {
                    case 1 -> x;
                    case 2 -> -z + 1;
                    case 3 -> -x + 1;
                    default -> z;
                };
                w[i] = new Vec3(at.getX() + rx, at.getY(), at.getZ() + rz);
                if (!owns(w[i].x, w[i].z)) own = false;
            }
            // (a quarry or mine wants its deposit near)
            StructureType want = placing.needsDeposit();
            if (want != null) {
                boolean near = false;
                for (Payloads.StructureInfo d : ClientState.STRUCTURES.values()) {
                    if (d.type() == want.ordinal() && d.aabb().getCenter().distanceToSqr(at.getX(), d.aabb().getCenter().y, at.getZ()) < 6 * 6) near = true;
                }
                if (!near) own = false;
            }
            int col = own ? 0xFF60FF60 : 0xFFFF5040;
            for (int i = 0; i < 4; i++) {
                line(g, w[i], w[(i + 1) % 4], col);
                line(g, w[i], w[i].add(0, 3, 0), col);
            }
            line(g, w[0].lerp(w[1], 0.35).add(0, 0.1, 0), w[0].lerp(w[1], 0.65).add(0, 0.1, 0), 0xFFFFFFFF);
            // where what it makes comes out (a ship: out on the water)
            int dz = placing.doorZ();
            if (dz != 0 && (placing.musters() || placing == StructureType.KEEP)) {
                int[] t = switch (r) {
                    case 1 -> new int[]{-dz, 0};
                    case 2 -> new int[]{0, -dz};
                    case 3 -> new int[]{dz, 0};
                    default -> new int[]{0, dz};
                };
                Vec3 door = ground(new Vec3(at.getX() + t[0] + 0.5, at.getY(), at.getZ() + t[1] + 0.5));
                boolean wet = placing != StructureType.PORT || !minecraft.level.getFluidState(BlockPos.containing(door).below()).isEmpty()
                        || !minecraft.level.getFluidState(BlockPos.containing(door)).isEmpty();
                ring(g, door, placing == StructureType.PORT ? 2.5 : 1.5, wet ? 0xFF60D0FF : 0xFFFF5040);
            }
        }
        // the cost by the cursor, in pictures
        var me = ClientState.mine();
        int cx = mx + 12, cy = my + 12;
        for (int i = 0; i < Res.COUNT; i++) {
            if (cost[i] == 0) continue;
            g.item(StrategyHud.icon(i), cx, cy);
            g.text(font, String.valueOf(cost[i]), cx + 16, cy + 5, me != null && me.res()[i] >= cost[i] ? 0xFFFFFFFF : 0xFFFF7060, true);
            cx += 22 + font.width(String.valueOf(cost[i]));
        }
    }

    /** A point brought down (or up) to the top of the ground or water there. */
    private Vec3 ground(Vec3 p) {
        int y = minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) Math.floor(p.x), (int) Math.floor(p.z));
        return new Vec3(p.x, y + 0.05, p.z);
    }

    /** A ring on the ground. */
    private void ring(GuiGraphicsExtractor g, Vec3 c, double r, int color) {
        Vec3 prev = null;
        for (int i = 0; i <= 24; i++) {
            double a = i * Math.PI * 2 / 24;
            Vec3 p = new Vec3(c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r);
            if (prev != null) line(g, prev, p, color);
            prev = p;
        }
    }

    /** As the server does it: a wall's end in a tower, the keep or a wall moved out to its side, towards the other end. */
    private static BlockPos snap(BlockPos end, BlockPos other) {
        for (Payloads.StructureInfo o : ClientState.STRUCTURES.values()) {
            StructureType t = StructureType.of(o.type());
            if (!(t.tower() || t == StructureType.KEEP || t.wall())) continue;
            AABB b = o.aabb();
            double x = end.getX() + 0.5, z = end.getZ() + 0.5, tx = other.getX() + 0.5, tz = other.getZ() + 0.5;
            if (x < b.minX || x > b.maxX || z < b.minZ || z > b.maxZ) continue;
            double len = Math.hypot(tx - x, tz - z);
            if (len < 1) return end;
            for (double step = 0; step <= len; step += 0.5) {
                double px = x + (tx - x) * step / len, pz = z + (tz - z) * step / len;
                if (px < b.minX || px > b.maxX || pz < b.minZ || pz > b.maxZ) return BlockPos.containing(px, end.getY(), pz);
            }
            return end;
        }
        return end;
    }

    private boolean owns(double x, double z) {
        Payloads.ProvinceInfo q = ClientState.province(Hex.at(x, z));
        return q != null && q.owner() == ClientState.me();
    }

    /** A unit or building under the mouse: its picture and health by it. */
    private void hovered(GuiGraphicsExtractor g, int mx, int my) {
        if (boxing || my > height - BAR || my < TOP || inMini(mx, my)) return;
        Pick p = pick(mx, my);
        Identifier sprite = null;
        ItemStack icon = ItemStack.EMPTY;
        String text;
        int color;
        if (p.entity instanceof UnitEntity s) {
            sprite = icon(s);
            text = Component.translatable(kindKey(s)).getString() + (s instanceof PeasantEntity pe ? " · " + Component.translatable(pe.jobKey()).getString() : "")
                    + "  ❤ " + (int) Math.ceil(s.getHealth()) + "/" + (int) s.getMaxHealth();
            color = s.color();
        } else if (p.entity instanceof LivingEntity m && enemy(m)) {
            sprite = null;
            icon = new ItemStack(m instanceof org.webtrade.strategy1.entity.BomberZombie ? Items.TNT : m instanceof org.webtrade.strategy1.entity.ShieldZombie
                    ? Items.SHIELD : Items.ROTTEN_FLESH);
            text = m.getName().getString() + "  ❤ " + (int) Math.ceil(m.getHealth()) + "/" + (int) m.getMaxHealth()
                    + (m instanceof org.webtrade.strategy1.entity.ShieldZombie sz && sz.shield() > 0 ? "  ◈ " + sz.shield() + "/" + sz.shieldMax() : "");
            color = 0xB02020;
        } else if (p.structure != null) {
            StructureType t = StructureType.of(p.structure.type());
            sprite = Icons.building(t);
            if (sprite == null) icon = new ItemStack(t.icon);
            text = Component.translatable(t.key()).getString() + (t.deposit() ? "" : "  " + (p.structure.built() < 100 ? p.structure.built() + "%"
                    : p.structure.maxHp() > 0 ? "❤ " + p.structure.hp() + "/" + p.structure.maxHp() : ""));
            color = ClientState.color(p.structure.owner());
        } else return;
        int w = 26 + font.width(text);
        int x = Math.min(mx + 12, width - w - 2), y = my + 12;
        g.fill(x - 1, y - 1, x + w + 1, y + 21, 0xFF000000 | (color < 0 ? 0x888888 : color));
        g.fill(x, y, x + w, y + 20, 0xE0201810);
        if (sprite != null) Ui.blit(g, sprite, x, y, x + Icons.SIZE, y + Icons.SIZE);
        else g.item(icon, x + 2, y + 2);
        g.text(font, text, x + 22, y + 6, 0xFFFFFFFF, true);
    }

    // ------------------------------------------------------------------ the minimap

    /** Its side: a square in the bottom right corner, rising over the panel. */
    private int mini() {
        return Mth.clamp(height / 4, 64, 112);
    }

    private boolean inMini(double mx, double my) {
        int m = mini();
        return mx >= width - 4 - m && mx < width - 4 && my >= height - 4 - m && my < height - 4;
    }

    private double[] miniToWorld(double mx, double my) {
        int m = mini();
        double scale = m / (2 * MINI_R);
        return new double[]{Rts.focusX + (mx - (width - 4 - m / 2.0)) / scale, Rts.focusZ + (my - (height - 4 - m / 2.0)) / scale};
    }

    /** The camera over a point: at once here, and the server told (not for every little move of a drag). */
    private void lookAt(double x, double z) {
        Rts.focusX = x;
        Rts.focusZ = z;
        if (Double.isNaN(sentX) || Math.hypot(x - sentX, z - sentZ) > 12 || !miniDrag) {
            sentX = x;
            sentZ = z;
            Rts.jump(x, z);
        }
    }

    /** The land round the camera from above, north up: the realms' colours, the buildings, the units, where the camera looks. */
    private void minimap(GuiGraphicsExtractor g) {
        int m = mini(), x1 = width - 4, y1 = height - 4, x0 = x1 - m, y0 = y1 - m;
        g.fill(x0 - 3, y0 - 3, x1 + 3, y1 + 3, 0xFF201810);
        g.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, Palette.BRASS_DARK);
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, Palette.BRASS);
        g.fill(x0, y0, x1, y1, 0xFF22303A);
        double scale = m / (2 * MINI_R);
        double cx = x0 + m / 2.0, cz = y0 + m / 2.0;
        g.enableScissor(x0, y0, x1, y1);
        Identifier tex = MapTexture.get();
        if (tex != null) {
            g.pose().pushMatrix();
            g.pose().translate((float) (cx + (MapTexture.originX - Rts.focusX) * scale), (float) (cz + (MapTexture.originZ - Rts.focusZ) * scale));
            g.pose().scale((float) (scale * MapTexture.BPP), (float) (scale * MapTexture.BPP));
            g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0, 0, MapTexture.width, MapTexture.height, MapTexture.width, MapTexture.height);
            g.pose().popMatrix();
        }
        for (Payloads.StructureInfo st : ClientState.STRUCTURES.values()) {
            StructureType t = StructureType.of(st.type());
            if (t == StructureType.PATH) continue;
            Vec3 c = st.aabb().getCenter();
            int px = (int) (cx + (c.x - Rts.focusX) * scale), pz = (int) (cz + (c.z - Rts.focusZ) * scale);
            if (px < x0 - 2 || px > x1 + 2 || pz < y0 - 2 || pz > y1 + 2) continue;
            if (t.deposit()) {
                // the land's riches: a speck of their colour
                int col = t == StructureType.DEPOSIT_GOLD ? 0xFFF2D020 : t == StructureType.DEPOSIT_IRON ? 0xFFD8A080 : 0xFF707070;
                g.fill(px - 1, pz - 1, px + 1, pz + 1, col);
                continue;
            }
            int owner = ClientState.color(st.owner());
            int col = 0xFF000000 | (owner < 0 ? 0x888888 : owner);
            int r = t == StructureType.KEEP ? 2 : 1;
            g.fill(px - r - 1, pz - r - 1, px + r + 1, pz + r + 1, 0xFF000000);
            g.fill(px - r, pz - r, px + r, pz + r, col);
        }
        for (UnitEntity u : unitsNear()) {
            int px = (int) (cx + (u.getX() - Rts.focusX) * scale), pz = (int) (cz + (u.getZ() - Rts.focusZ) * scale);
            if (px < x0 || px >= x1 || pz < y0 || pz >= y1) continue;
            int col = SELECTED.contains(u.getId()) ? 0xFF70FF70 : 0xFF000000 | u.color();
            g.fill(px, pz, px + 1, pz + 1, col);
        }
        // the camera: the ground it shows, and which way it looks
        double half = Rts.distance * 1.1 * scale;
        int fx = (int) cx, fz = (int) cz;
        g.outline((int) (fx - half), (int) (fz - half), (int) (2 * half), (int) (2 * half), 0xC0FFFFFF);
        double yaw = Math.toRadians(Rts.yaw);
        double dx = -Math.sin(yaw), dz = Math.cos(yaw);
        for (int i = 0; i < 10; i++) g.fill((int) (fx + dx * i), (int) (fz + dz * i), (int) (fx + dx * i) + 1, (int) (fz + dz * i) + 1, 0xFFFFFFFF);
        g.disableScissor();
        g.text(font, "N", (int) cx - 2, y0 + 2, 0xFFFFE070, true);
    }

    // ------------------------------------------------------------------ the note by the mouse

    /** What the picture under the mouse is: its name, its cost, what it does and what it needs, in a note by the mouse. */
    private void note(GuiGraphicsExtractor g, int mx, int my) {
        if (boxing || miniDrag) return;
        Component name = null, line = null;
        int[] cost = null;
        List<Component> more = List.of();
        for (GuiEventListener c : children()) {
            if (c instanceof IconButton b && b.isHovered() && b.info != null) {
                name = b.info.name();
                cost = b.info.cost();
                line = b.info.line();
                more = b.info.more();
            }
        }
        if (name == null && my < TOP) {
            for (Stat st : stats) {
                if (mx >= st.x0 && mx < st.x1) {
                    name = st.name;
                    line = st.line;
                }
            }
        }
        if (name == null && inMini(mx, my)) {
            name = Component.translatable("strategy1.tip.minimap");
            line = Component.translatable("strategy1.tip.minimap.info");
        }
        if (name == null) return;
        int wrap = Math.min(200, width - 16);
        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (line != null && !line.getString().isEmpty()) {
            for (FormattedCharSequence l : font.split(line, wrap)) {
                lines.add(l);
                colors.add(0xFFF3E6C4);
            }
        }
        for (Component c : more) {
            for (FormattedCharSequence l : font.split(c, wrap)) {
                lines.add(l);
                colors.add(0xFFC8B898);
            }
        }
        var me = ClientState.mine();
        int costW = 0;
        if (cost != null) for (int i = 0; i < Res.COUNT; i++) if (cost[i] != 0) costW += 16 + font.width(String.valueOf(cost[i])) + 6;
        int w = Math.max(font.width(name), costW);
        for (FormattedCharSequence l : lines) w = Math.max(w, font.width(l));
        w += 10;
        int h = 6 + 10 + (costW > 0 ? 16 : 0) + (lines.isEmpty() ? 0 : 3 + lines.size() * 10) + 3;
        // above the mouse over the panel, below it over the land
        int x = mx + 10 + w > width - 2 ? mx - 10 - w : mx + 10;
        x = Mth.clamp(x, 2, Math.max(2, width - w - 2));
        int y = my > height / 2 ? my - h - 6 : my + 14;
        y = Mth.clamp(y, 2, Math.max(2, height - h - 2));
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, Palette.BRASS_DARK);
        g.fill(x, y, x + w, y + h, 0xF01C140C);
        g.fill(x, y, x + w, y + 1, 0x60FFE070);
        int ty = y + 5;
        g.text(font, name, x + 5, ty, 0xFFFFE070, true);
        ty += 11;
        if (costW > 0) {
            int cx = x + 5;
            for (int i = 0; i < Res.COUNT; i++) {
                if (cost[i] == 0) continue;
                g.item(StrategyHud.icon(i), cx, ty - 1);
                String v = String.valueOf(cost[i]);
                g.text(font, v, cx + 16, ty + 4, me != null && me.res()[i] >= cost[i] ? 0xFFFFFFFF : 0xFFFF7060, true);
                cx += 16 + font.width(v) + 6;
            }
            ty += 16;
        }
        if (!lines.isEmpty()) {
            g.fill(x + 4, ty, x + w - 4, ty + 1, 0x60C9A55A);
            ty += 3;
        }
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), x + 5, ty, colors.get(i), true);
            ty += 10;
        }
    }
}
