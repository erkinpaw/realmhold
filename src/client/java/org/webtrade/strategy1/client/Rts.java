package org.webtrade.strategy1.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

/**
 * The strategist's eye: there is no body to walk about, only a camera over the land. It looks at a point on the
 * ground ({@link #focusX}, {@link #focusZ}) from above and behind at {@link #PITCH}: WASD or the screen's edges move
 * it, the wheel takes it higher or lower, Q/E turn it round. The player is a spectator the server keeps where the
 * camera is (so the land round it is loaded and its soldiers live).
 */
public final class Rts {

    public static final float PITCH = 55F;
    public static final float MIN = 12, MAX = 110;
    private static boolean active;
    /** Turned on by itself once the map has come (a fresh join). */
    private static boolean auto = true;
    public static double focusX, focusZ;
    private static double focusY = 64;
    public static float yaw = 180F, distance = 40F;
    private static boolean hudWasHidden;

    private Rts() {
    }

    public static boolean active() {
        return active;
    }

    public static void enter(Minecraft mc) {
        if (active || mc.player == null || !ClientPlayNetworking.canSend(Payloads.SetRts.TYPE)) return;
        active = true;
        ClientPlayNetworking.send(new Payloads.SetRts(true));
        focusX = mc.player.getX();
        focusZ = mc.player.getZ();
        var mine = ClientState.mine();
        if (mine != null && ClientState.PROVINCES.containsKey(mine.capital())) {
            Hex h = Hex.of(mine.capital());
            if (h.distToCenter(focusX, focusZ) > 48) {
                focusX = h.centerX();
                focusZ = h.centerZ();
            }
        }
        focusY = mc.player.getY();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        hudWasHidden = mc.gui.hud.isHidden();
        if (!hudWasHidden) mc.gui.hud.toggle();
        mc.gui.setScreen(new RtsScreen());
    }

    public static void exit(Minecraft mc) {
        if (!active) return;
        active = false;
        auto = false;
        if (ClientPlayNetworking.canSend(Payloads.SetRts.TYPE)) ClientPlayNetworking.send(new Payloads.SetRts(false));
        if (mc.gui.hud.isHidden() && !hudWasHidden) mc.gui.hud.toggle();
        if (mc.gui.screen() instanceof RtsScreen) mc.gui.setScreen(null);
    }

    static void disconnected() {
        Minecraft mc = Minecraft.getInstance();
        if (active && mc.gui.hud.isHidden() && !hudWasHidden) mc.gui.hud.toggle();
        active = false;
        auto = true;
    }

    /** Over a far point at once (from the map). */
    public static void jump(double x, double z) {
        focusX = x;
        focusZ = z;
        if (ClientPlayNetworking.canSend(Payloads.Jump.TYPE)) ClientPlayNetworking.send(new Payloads.Jump(x, z));
    }

    public static void zoom(double wheel) {
        distance = Mth.clamp(distance * (wheel > 0 ? 0.88F : 1 / 0.88F), MIN, MAX);
    }

    static void tick(Minecraft mc) {
        while (Keys.COMMANDER.consumeClick()) {
            if (mc.gui.screen() == null) enter(mc);
        }
        if (mc.player == null) return;
        if (!active) {
            if (auto && ClientState.map != null && mc.gui.screen() == null && mc.player.tickCount > 40) enter(mc);
            return;
        }
        // (a menu or the chat over it: back to the strategist's screen after)
        if (mc.gui.screen() == null) mc.gui.setScreen(new RtsScreen());
        if (mc.gui.screen() instanceof RtsScreen screen) move(mc, screen);
        place(mc);
    }

    private static boolean down(Minecraft mc, int key) {
        return InputConstants.isKeyDown(mc.getWindow(), key);
    }

    private static void move(Minecraft mc, RtsScreen screen) {
        double speed = distance * 0.035 * (down(mc, GLFW.GLFW_KEY_LEFT_SHIFT) ? 2.5 : 1);
        double fwd = 0, side = 0;
        if (down(mc, GLFW.GLFW_KEY_W) || down(mc, GLFW.GLFW_KEY_UP)) fwd++;
        if (down(mc, GLFW.GLFW_KEY_S) || down(mc, GLFW.GLFW_KEY_DOWN)) fwd--;
        if (down(mc, GLFW.GLFW_KEY_D) || down(mc, GLFW.GLFW_KEY_RIGHT)) side++;
        if (down(mc, GLFW.GLFW_KEY_A) || down(mc, GLFW.GLFW_KEY_LEFT)) side--;
        // the screen's edges
        double mx = mc.mouseHandler.xpos(), my = mc.mouseHandler.ypos();
        int ww = mc.getWindow().getScreenWidth(), wh = mc.getWindow().getScreenHeight();
        if (screen.edgeScroll() && mc.isWindowActive()) {
            if (mx <= 2) side--;
            if (mx >= ww - 3) side++;
            if (my <= 2) fwd++;
            if (my >= wh - 3) fwd--;
        }
        if (down(mc, GLFW.GLFW_KEY_Q)) yaw -= 3;
        if (down(mc, GLFW.GLFW_KEY_E)) yaw += 3;
        double a = Math.toRadians(yaw);
        double fx = -Math.sin(a), fz = Math.cos(a);
        focusX += (fx * fwd - fz * side) * speed;
        focusZ += (fz * fwd + fx * side) * speed;
    }

    /** The camera, and the spectator with it, behind and above the point looked at. */
    private static void place(Minecraft mc) {
        var p = mc.player;
        if (mc.level != null) {
            int ground = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(focusX), (int) Math.floor(focusZ));
            if (ground > mc.level.getMinY() + 1) focusY += (Math.max(ground, mc.level.getSeaLevel()) - focusY) * 0.15;
        }
        double a = Math.toRadians(yaw), pr = Math.toRadians(PITCH);
        double back = distance * Math.cos(pr), up = distance * Math.sin(pr);
        Vec3 cam = new Vec3(focusX + Math.sin(a) * back, focusY + up, focusZ - Math.cos(a) * back);
        if (p.gameMode() != GameType.SPECTATOR && mc.gameMode != null && mc.gameMode.getPlayerMode() != GameType.SPECTATOR) return;
        p.setDeltaMovement(Vec3.ZERO);
        p.setPos(cam.x, cam.y - p.getEyeHeight(), cam.z);
        p.yRotO = p.getYRot();
        p.xRotO = p.getXRot();
        p.setYRot(yaw);
        p.setXRot(PITCH);
        p.setYHeadRot(yaw);
    }
}
