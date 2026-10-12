package org.webtrade.strategy1.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
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
import org.webtrade.strategy1.client.ui.Ui;
import org.webtrade.strategy1.client.ui.UiButton;
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
 * The strategist's screen, over the world seen from the camera; the mouse is free.
 * <ul>
 * <li>Left click or drag a box: choose units (Shift adds); click a building: choose it.</li>
 * <li>Right click: the chosen units go there. On an enemy: attack it; on an enemy building: break it (siege engines
 * throw stones at it); on your ship: soldiers go aboard; on your tower or wall: archers man it. A ship sent onto
 * the land sails to the shore there and puts her soldiers ashore.</li>
 * <li>The Build tab: choose what to build, then click where (walls and paths: their two ends; R turns a building).</li>
 * <li>A chosen barracks raises soldiers, a workshop siege engines, a port ships; a tower or a wall lets its archers down.</li>
 * </ul>
 */
public class RtsScreen extends Screen {

    private static final int BAR = 62;
    /** The chosen units (entity ids) and the chosen structure; kept while the screen comes and goes. */
    private static final Set<Integer> SELECTED = new HashSet<>();
    private static int structure = -1;
    private static boolean buildTab;
    /** Being placed: what, which way (quarter turns), the first end of a line. */
    private static StructureType placing;
    private static int turn;
    private static BlockPos lineStart;

    private double pressX, pressY;
    private boolean boxing;
    private int seenRevision = -1, seenStructures = -1;
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
        buildTab = on;
    }

    // ------------------------------------------------------------------ layout

    private int structuresSig() {
        int h = ClientState.STRUCTURES.size();
        Payloads.StructureInfo s = ClientState.STRUCTURES.get(structure);
        if (s != null) h = h * 31 + (s.built() >= 100 ? 7 : 0) + s.archers() * 131 + s.owner() * 1009;
        return h;
    }

    @Override
    protected void init() {
        seenRevision = ClientState.revision;
        seenStructures = structuresSig();
        int y = height - BAR + 18;
        int x = 6;
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.rts.hold"), b -> order(Payloads.UnitOrder.HOLD, null, -1))
                .bounds(x, y, 70, 16).tooltip(Tooltip.create(Component.translatable("strategy1.rts.hold_tip"))).build());
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.rts.all"), b -> selectAll())
                .bounds(x + 72, y, 70, 16).tooltip(Tooltip.create(Component.translatable("strategy1.rts.all_tip"))).build());
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.rts.map"), b -> openMap()).bounds(x + 144, y, 56, 16).build());
        addRenderableWidget(UiButton.make(Component.translatable(buildTab ? "strategy1.rts.tab_army" : "strategy1.rts.tab_build"), b -> {
            buildTab = !buildTab;
            placing = null;
            lineStart = null;
            structure = -1;
            rebuildWidgets();
        }).bounds(x + 202, y, 76, 16).build());
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.rts.leave"), b -> Rts.exit(minecraft))
                .bounds(width - 64, 4, 60, 14).tooltip(Tooltip.create(Component.translatable("strategy1.rts.leave_tip"))).build());
        int row = height - BAR + 38;
        var me = ClientState.mine();
        Payloads.StructureInfo st = ClientState.STRUCTURES.get(structure);
        if (st != null && me != null && st.owner() == me.id()) structureButtons(st, me, row);
        else if (buildTab) buildButtons(me, row);
        else recruitButtons(me, row, -1);
    }

    private static boolean canPay(Payloads.FactionInfo f, int[] cost) {
        if (f == null) return false;
        for (int i = 0; i < cost.length; i++) if (f.res()[i] < cost[i]) return false;
        return true;
    }

    private static Component costLine(int[] c) {
        MutableComponent m = Component.empty();
        boolean first = true;
        for (int i = 0; i < Res.COUNT; i++) {
            if (c[i] == 0) continue;
            if (!first) m.append(", ");
            m.append(Component.translatable(Res.values()[i].key())).append(" " + c[i]);
            first = false;
        }
        return first ? Component.literal("—") : m;
    }

    /** Raise soldiers (at the chosen barracks, or wherever the camera is). */
    private void recruitButtons(Payloads.FactionInfo me, int y, int barracks) {
        UnitType[] units = UnitType.values();
        int bw = Math.min(90, (width - 12) / units.length - 2), bx = 6;
        for (int i = 0; i < units.length; i++) {
            UnitType u = units[i];
            boolean ok = me != null && u.age <= me.age() && me.army() < Rules.maxArmy(me.age()) && canPay(me, u.cost);
            Component tip = Component.translatable("strategy1.rts.recruit_tip", Component.translatable(u.key()), costLine(u.cost), (int) u.health,
                    (int) u.damage, Component.translatable("strategy1.age." + u.age));
            UiButton b = addRenderableWidget(UiButton.make(Component.literal((i + 1) + ". ").append(Component.translatable(u.key())), x0 -> {
                if (barracks >= 0) structureAction(barracks, Payloads.StructureAction.MAKE, u.ordinal());
                else recruit(u);
            }).bounds(bx + i * (bw + 2), y, bw, 16).tooltip(Tooltip.create(tip)).build());
            b.active = ok;
        }
    }

    private void buildButtons(Payloads.FactionInfo me, int y) {
        StructureType[] types = StructureType.values();
        int bw = Math.max(40, (width - 12) / types.length - 2);
        for (int i = 0; i < types.length; i++) {
            StructureType t = types[i];
            boolean ok = me != null && t.age <= me.age();
            Component tip = Component.translatable("strategy1.rts.build_tip", Component.translatable(t.key()),
                    Component.translatable(t.key() + ".info"), costLine(t.cost), Component.translatable("strategy1.age." + t.age),
                    Component.translatable(t.line ? "strategy1.rts.per_block" : "strategy1.rts.once"));
            UiButton b = addRenderableWidget(UiButton.make(Component.translatable(t.key() + ".short"), x0 -> {
                placing = t;
                lineStart = null;
                structure = -1;
                rebuildWidgets();
            }).bounds(6 + i * (bw + 2), y, bw, 16).tooltip(Tooltip.create(tip)).style(placing == t ? UiButton.Style.TAB_OPEN : UiButton.Style.PLANK).build());
            b.active = ok;
        }
    }

    private void structureButtons(Payloads.StructureInfo st, Payloads.FactionInfo me, int y) {
        StructureType t = StructureType.of(st.type());
        int x = 6;
        boolean built = st.built() >= 100;
        switch (t) {
            case BARRACKS -> {
                recruitButtons(me, y, st.id());
                return;
            }
            case WORKSHOP -> {
                x = make(x, y, Component.translatable("strategy1.siege.catapult"), Payloads.StructureAction.CATAPULT, st, built && me.age() >= 2,
                        Rules.CATAPULT_COST, 2);
                x = make(x, y, Component.translatable("strategy1.siege.trebuchet"), Payloads.StructureAction.TREBUCHET, st, built && me.age() >= 3,
                        Rules.TREBUCHET_COST, 3);
            }
            case PORT -> {
                x = make(x, y, Component.translatable("strategy1.ship.transport"), Payloads.StructureAction.TRANSPORT, st, built, Rules.TRANSPORT_COST, 1);
                x = make(x, y, Component.translatable("strategy1.ship.warship"), Payloads.StructureAction.WARSHIP, st, built && me.age() >= 2,
                        Rules.WARSHIP_COST, 2);
            }
            default -> {
            }
        }
        if (t.manned()) {
            UiButton b = addRenderableWidget(UiButton.make(Component.translatable("strategy1.rts.unman"),
                    x0 -> structureAction(st.id(), Payloads.StructureAction.UNMAN, 0)).bounds(x, y, 110, 16)
                    .tooltip(Tooltip.create(Component.translatable("strategy1.rts.unman_tip"))).build());
            b.active = st.archers() > 0;
        }
        addRenderableWidget(UiButton.make(Component.translatable("strategy1.rts.demolish"),
                x0 -> structureAction(st.id(), Payloads.StructureAction.DEMOLISH, 0)).bounds(width - 96, y, 90, 16).build());
    }

    private int make(int x, int y, Component name, int what, Payloads.StructureInfo st, boolean ok, int[] cost, int age) {
        Component tip = Component.translatable("strategy1.rts.make_tip", name, costLine(cost), Component.translatable("strategy1.age." + age));
        UiButton b = addRenderableWidget(UiButton.make(name, x0 -> structureAction(st.id(), Payloads.StructureAction.MAKE, what))
                .bounds(x, y, 110, 16).tooltip(Tooltip.create(tip)).build());
        b.active = ok && canPay(ClientState.mine(), cost);
        return x + 112;
    }

    @Override
    public void tick() {
        if (ClientState.revision != seenRevision || structuresSig() != seenStructures) rebuildWidgets();
        SELECTED.removeIf(id -> !(minecraft.level.getEntity(id) instanceof UnitEntity u) || !u.isAlive());
        if (structure >= 0 && !ClientState.STRUCTURES.containsKey(structure)) {
            structure = -1;
            rebuildWidgets();
        }
    }

    // ------------------------------------------------------------------ the world under the mouse

    private double focal() {
        return (height / 2.0) / Math.tan(Math.toRadians(minecraft.gameRenderer.mainCamera().getFov()) / 2);
    }

    /** The way from the camera through a point of the screen. */
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
                if (s.type() == StructureType.PATH.ordinal()) continue;
                if (s.aabb().inflate(0.3).contains(c)) st = s;
            }
        }
        boolean miss = block.getType() == HitResult.Type.MISS;
        return new Pick(miss ? null : block.getLocation(), miss ? null : block.getBlockPos(), ent == null ? null : ent.getEntity(), st);
    }

    private boolean mine(Entity e) {
        return e instanceof UnitEntity u && u.faction() == ClientState.me() && u.isAlive();
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
        for (UnitEntity s : unitsNear()) if (mine(s) && !s.isPassenger()) SELECTED.add(s.getId());
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
        if (p.structure != null && p.structure.owner() == ClientState.me() && StructureType.of(p.structure.type()).manned() && !archers.isEmpty()) {
            orderTo(archers, Payloads.UnitOrder.POST, p.ground, p.structure.id());
            Set<Integer> rest = new HashSet<>(SELECTED);
            rest.removeAll(archers);
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
        if (event.y() > height - BAR) return false;
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
                    // (the next length goes on from the end of this one)
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
    public boolean mouseReleased(MouseButtonEvent event) {
        if (boxing && event.button() == 0) {
            boxing = false;
            boolean add = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
            int before = structure;
            if (!add) SELECTED.clear();
            double x0 = Math.min(pressX, event.x()), x1 = Math.max(pressX, event.x());
            double y0 = Math.min(pressY, event.y()), y1 = Math.max(pressY, event.y());
            if (x1 - x0 < 4 && y1 - y0 < 4) {
                Pick p = pick(event.x(), event.y());
                structure = -1;
                if (mine(p.entity)) {
                    if (add && SELECTED.contains(p.entity.getId())) SELECTED.remove(p.entity.getId());
                    else SELECTED.add(p.entity.getId());
                } else if (p.structure != null && !add) {
                    structure = p.structure.id();
                }
            } else {
                structure = -1;
                for (UnitEntity s : unitsNear()) {
                    if (!mine(s) || s.isPassenger()) continue;
                    Vec3 sp = project(s.position().add(0, 1, 0));
                    if (sp != null && sp.x >= x0 && sp.x <= x1 && sp.y >= y0 && sp.y <= y1) SELECTED.add(s.getId());
                }
            }
            if (structure != before) rebuildWidgets();
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
            if (placing != null || structure >= 0) {
                placing = null;
                lineStart = null;
                structure = -1;
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
            buildTab = !buildTab;
            placing = null;
            lineStart = null;
            rebuildWidgets();
            return true;
        }
        if (k == GLFW.GLFW_KEY_H || k == GLFW.GLFW_KEY_SPACE) {
            order(Payloads.UnitOrder.HOLD, null, -1);
            return true;
        }
        if (k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_5 && !buildTab) {
            UnitType u = UnitType.of(k - GLFW.GLFW_KEY_1);
            var me = ClientState.mine();
            Payloads.StructureInfo st = ClientState.STRUCTURES.get(structure);
            if (me != null && u.age <= me.age()) {
                if (st != null && st.type() == StructureType.BARRACKS.ordinal()) structureAction(st.id(), Payloads.StructureAction.MAKE, u.ordinal());
                else recruit(u);
            }
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
        units(g);
        captures(g);
        StrategyHud.treasury(minecraft, g, 4, 4);
        if (placing != null) preview(g, mx, my);
        else hovered(g, mx, my);
        if (boxing && (Math.abs(mx - pressX) > 3 || Math.abs(my - pressY) > 3)) {
            int x0 = (int) Math.min(pressX, mx), x1 = (int) Math.max(pressX, mx), y0 = (int) Math.min(pressY, my), y1 = (int) Math.max(pressY, my);
            g.fill(x0, y0, x1, y1, 0x2060FF60);
            g.outline(x0, y0, x1 - x0, y1 - y0, 0xFF60FF60);
        }
        bottom(g);
        super.extractRenderState(g, mx, my, partialTick);
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

    /** Over each structure near: how far built, its strength, the archers on it; the chosen one outlined. */
    private void structures(GuiGraphicsExtractor g) {
        for (Payloads.StructureInfo s : ClientState.STRUCTURES.values()) {
            AABB b = s.aabb();
            Vec3 c = b.getCenter();
            if (Math.hypot(c.x - Rts.focusX, c.z - Rts.focusZ) > 110 || s.type() == StructureType.PATH.ordinal()) continue;
            Vec3 top = project(new Vec3(c.x, b.maxY + 0.8, c.z));
            int color = ClientState.color(s.owner());
            boolean chosen = s.id() == structure;
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
            if (s.built() < 100) {
                if (s.owner() != ClientState.me() && !chosen) continue;
                bar(g, x, y, 40, s.built() / 100F, 0xE0C040);
                Ui.centered(g, font, s.built() + "%", x, y - 10, 0xFFFFE070);
            } else if (s.maxHp() > 0 && (s.hp() < s.maxHp() || chosen)) {
                bar(g, x, y, 40, s.hp() / (float) s.maxHp(), color < 0 ? 0xAAAAAA : color);
            }
            if (s.posts() > 0 && s.built() >= 100 && chosen) {
                Ui.centered(g, font, "↑ " + s.archers() + "/" + s.posts(), x, y - 10, 0xFFFFFFFF);
            }
        }
    }

    /** Over every unit its health, in its realm's colour; the chosen ones framed. */
    private void units(GuiGraphicsExtractor g) {
        for (UnitEntity s : unitsNear()) {
            Vec3 head = project(s.position().add(0, s.getBbHeight() + 0.35, 0));
            Vec3 feet = project(s.position());
            if (head == null || feet == null || head.z > 160) continue;
            int x = (int) head.x, y = (int) head.y;
            int h = (int) Math.max(6, feet.y - head.y), w = Math.max(4, (int) (h * (s instanceof SoldierEntity ? 0.55 : 1.3)));
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

    /** Over each province being taken: who takes it, how far along. */
    private void captures(GuiGraphicsExtractor g) {
        Payloads.Hud hud = ClientState.hud;
        if (hud == null) return;
        for (int i = 0; i < hud.keys().length; i++) {
            Hex h = Hex.of(hud.keys()[i]);
            int y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) h.centerX(), (int) h.centerZ());
            Vec3 at = project(new Vec3(h.centerX(), y + 8, h.centerZ()));
            if (at == null) continue;
            int color = ClientState.color(hud.by()[i]);
            Payloads.ProvinceInfo p = ClientState.PROVINCES.get(hud.keys()[i]);
            Component t = Component.translatable("strategy1.hud.capturing", ClientState.name(hud.by()[i]), p == null ? "?" : p.name());
            int x = (int) at.x, ty = (int) at.y;
            g.text(font, t, x - font.width(t) / 2, ty - 12, 0xFFFFFFFF, true);
            bar(g, x, ty, 90, hud.progress()[i] / (float) Rules.CAPTURE, color < 0 ? 0xFFFFFF : color);
        }
    }

    /** Where what is being placed would stand, outlined on the ground (green on your land, red elsewhere). */
    private void preview(GuiGraphicsExtractor g, int mx, int my) {
        if (my > height - BAR) return;
        Pick p = pick(mx, my);
        if (p.block == null) return;
        BlockPos at = p.block.above();
        var me = ClientState.mine();
        Component label;
        int[] cost = placing.cost;
        if (placing.line) {
            BlockPos a = lineStart == null ? at : lineStart;
            boolean own = owns(a.getX(), a.getZ()) && owns(at.getX(), at.getZ());
            int col = own ? 0xFF60FF60 : 0xFFFF5040;
            int len = (int) Math.round(Math.hypot(at.getX() - a.getX(), at.getZ() - a.getZ())) + 1;
            double h = placing == StructureType.PATH ? 0.2 : placing == StructureType.WALL_STONE ? 5 : 4;
            line(g, Vec3.atBottomCenterOf(a), Vec3.atBottomCenterOf(at), col);
            line(g, Vec3.atBottomCenterOf(a).add(0, h, 0), Vec3.atBottomCenterOf(at).add(0, h, 0), col);
            cost = placing.cost(len);
            label = lineStart == null ? Component.translatable("strategy1.rts.line_start", Component.translatable(placing.key()))
                    : Component.translatable("strategy1.rts.line_end", Component.translatable(placing.key()), len, costLine(cost));
        } else {
            int r = rotation();
            double[][] c = {{placing.x0, placing.z0}, {placing.x1 + 1, placing.z0}, {placing.x1 + 1, placing.z1 + 1}, {placing.x0, placing.z1 + 1}};
            Vec3[] w = new Vec3[4];
            boolean own = true;
            for (int i = 0; i < 4; i++) {
                double x = c[i][0], z = c[i][1];
                // (the corners of the blocks, turned as the server turns them round the origin block)
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
            int col = own ? 0xFF60FF60 : 0xFFFF5040;
            for (int i = 0; i < 4; i++) {
                line(g, w[i], w[(i + 1) % 4], col);
                line(g, w[i], w[i].add(0, 3, 0), col);
            }
            // the door's side
            line(g, w[0].lerp(w[1], 0.35).add(0, 0.1, 0), w[0].lerp(w[1], 0.65).add(0, 0.1, 0), 0xFFFFFFFF);
            label = Component.translatable("strategy1.rts.place", Component.translatable(placing.key()), costLine(cost));
        }
        int tw = font.width(label) + 8;
        int x = Math.min(mx + 12, width - tw - 2), y = my + 14;
        g.fill(x, y, x + tw, y + 13, 0xC0201810);
        g.text(font, label, x + 4, y + 3, canPay(me, cost) ? 0xFFFFFFFF : 0xFFFF8070, true);
    }

    private boolean owns(double x, double z) {
        Payloads.ProvinceInfo q = ClientState.province(Hex.at(x, z));
        return q != null && q.owner() == ClientState.me();
    }

    /** What is under the mouse, by it. */
    private void hovered(GuiGraphicsExtractor g, int mx, int my) {
        if (boxing || my > height - BAR) return;
        Pick p = pick(mx, my);
        Component line;
        if (p.entity instanceof UnitEntity s) {
            line = Component.empty().append(s.getDisplayName()).append(" · ").append(ClientState.name(s.faction()))
                    .append(" · " + (int) Math.ceil(s.getHealth()) + "/" + (int) s.getMaxHealth());
        } else if (p.structure != null) {
            line = structureLine(p.structure);
        } else if (p.ground != null) {
            Payloads.ProvinceInfo q = ClientState.province(Hex.at(p.ground.x, p.ground.z));
            if (q == null) return;
            boolean land = Terrain.of(q.terrain()).land();
            line = Component.literal(q.name() + " · ").append(land ? ClientState.name(q.owner()) : Component.translatable("strategy1.owner.sea"))
                    .append(land ? Component.literal(" · ").append(Component.translatable("strategy1.hud.garrison", q.garrison())) : Component.empty());
        } else return;
        int w = font.width(line) + 8;
        int x = Math.min(mx + 12, width - w - 2), y = my + 14;
        g.fill(x, y, x + w, y + 13, 0xC0201810);
        g.text(font, line, x + 4, y + 3, 0xFFFFFFFF, true);
    }

    private static MutableComponent structureLine(Payloads.StructureInfo s) {
        MutableComponent c = Component.translatable(StructureType.of(s.type()).key()).append(" · ").append(ClientState.name(s.owner()));
        if (s.built() < 100) c.append(" · ").append(Component.translatable("strategy1.rts.building", s.built()));
        else if (s.maxHp() > 0) c.append(" · " + s.hp() + "/" + s.maxHp());
        if (s.posts() > 0) c.append(" · ").append(Component.translatable("strategy1.rts.archers", s.archers(), s.posts()));
        return c;
    }

    private void bottom(GuiGraphicsExtractor g) {
        g.fill(0, height - BAR, width, height, 0xB0201810);
        g.fill(0, height - BAR, width, height - BAR + 1, 0xFF8C6F33);
        int y = height - BAR + 5;
        Component sel;
        Payloads.StructureInfo st = ClientState.STRUCTURES.get(structure);
        if (st != null) sel = structureLine(st);
        else if (placing != null) sel = Component.translatable(placing.line ? "strategy1.rts.placing_line" : "strategy1.rts.placing", Component.translatable(placing.key()));
        else {
            Map<String, Integer> kinds = new LinkedHashMap<>();
            for (int id : SELECTED) {
                Entity e = minecraft.level.getEntity(id);
                String key = e instanceof SoldierEntity s ? s.unit().key() : e instanceof SiegeEntity sg ? sg.kind().key() : e instanceof ShipEntity sh ? sh.kind().key() : null;
                if (key != null) kinds.merge(key, 1, Integer::sum);
            }
            if (kinds.isEmpty()) sel = Component.translatable(buildTab ? "strategy1.rts.build_hint" : "strategy1.rts.none_selected");
            else {
                MutableComponent c = Component.translatable("strategy1.rts.selected", SELECTED.size());
                kinds.forEach((k, n) -> c.append(Component.literal("  " + n + "× ")).append(Component.translatable(k)));
                sel = c;
            }
        }
        g.text(font, Ui.fit(font, sel.getString(), width - 300), 8, y, 0xFFFFF0C0, true);
        Payloads.ProvinceInfo here = ClientState.province(Hex.at(Rts.focusX, Rts.focusZ));
        var me = ClientState.mine();
        Component where = here != null && me != null && here.owner() == me.id() ? Component.translatable("strategy1.rts.recruit_here", here.name())
                : Component.translatable("strategy1.rts.recruit_capital");
        String ws = Ui.fit(font, where.getString(), 280);
        g.text(font, ws, width - 8 - font.width(ws), y, 0xFFC8C8C8, true);
        Component help = Component.translatable(placing != null ? "strategy1.rts.help_place" : "strategy1.rts.help");
        float k = Math.min(0.8F, (width - 8) / (float) font.width(help));
        g.pose().pushMatrix();
        g.pose().translate((width - font.width(help) * k) / 2F, height - 7);
        g.pose().scale(k, k);
        g.text(font, help, 0, 0, 0xFF9A8A70, false);
        g.pose().popMatrix();
    }
}
