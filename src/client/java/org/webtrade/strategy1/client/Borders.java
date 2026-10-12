package org.webtrade.strategy1.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.level.levelgen.Heightmap;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.HashMap;
import java.util.Map;

/**
 * The borders of the realms drawn on the ground in their colours (each side of a border in its own holder's colour,
 * a little inside its own land), and a column of the holder's colour over each province's centre, where it is taken.
 * B turns them off and on.
 */
public final class Borders {

    private static boolean shown = true;
    private static final Map<Integer, DustParticleOptions> DUST = new HashMap<>();
    private static final DustParticleOptions NEUTRAL = new DustParticleOptions(0xFFBBBBBB, 1.2F);

    private Borders() {
    }

    private static DustParticleOptions dust(int rgb, float size) {
        return DUST.computeIfAbsent(rgb * 31 + (int) (size * 10), k -> new DustParticleOptions(0xFF000000 | rgb, size));
    }

    static void tick(Minecraft mc) {
        while (Keys.BORDERS.consumeClick()) shown = !shown;
        if (!shown || mc.player == null || mc.level == null || ClientState.map == null) return;
        if (mc.player.tickCount % 4 != 0) return;
        double px = Rts.active() ? Rts.focusX : mc.player.getX(), pz = Rts.active() ? Rts.focusZ : mc.player.getZ();
        Hex at = Hex.at(px, pz);
        for (int dq = -2; dq <= 2; dq++) {
            for (int dr = -2; dr <= 2; dr++) {
                Hex h = new Hex(at.q() + dq, at.r() + dr);
                if (h.distance(at) > 2) continue;
                Payloads.ProvinceInfo p = ClientState.province(h);
                if (p == null) continue;
                int color = ClientState.color(p.owner());
                if (color >= 0) edges(mc, h, p, color, px, pz);
                center(mc, h, p, color, px, pz);
            }
        }
    }

    private static void edges(Minecraft mc, Hex h, Payloads.ProvinceInfo p, int color, double px, double pz) {
        DustParticleOptions o = dust(color, 2.0F);
        double cx = h.centerX(), cz = h.centerZ();
        for (int e = 0; e < 6; e++) {
            Payloads.ProvinceInfo n = ClientState.province(h.neighbor(e));
            if (n != null && n.owner() == p.owner()) continue;
            double[] a = h.corner(e), b = h.corner(e + 1);
            double len = Hex.SIZE;
            for (double t = 0; t <= len; t += 1.6) {
                double x = a[0] + (b[0] - a[0]) * t / len, z = a[1] + (b[1] - a[1]) * t / len;
                // (a block inside its own land)
                x += (cx - x) * 0.02;
                z += (cz - z) * 0.02;
                double dx = x - px, dz = z - pz;
                if (dx * dx + dz * dz > 80 * 80) continue;
                int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
                mc.level.addParticle(o, true, false, x, y + 0.3, z, 0, 0, 0);
            }
        }
    }

    private static void center(Minecraft mc, Hex h, Payloads.ProvinceInfo p, int color, double px, double pz) {
        if (!org.webtrade.strategy1.hex.Terrain.of(p.terrain()).land()) return;
        double x = h.centerX(), z = h.centerZ(), dx = x - px, dz = z - pz;
        if (dx * dx + dz * dz > 120 * 120) return;
        DustParticleOptions o = color >= 0 ? dust(color, 2.2F) : NEUTRAL;
        int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        boolean capital = ClientState.isCapital(p);
        for (int i = 0; i < (capital ? 14 : 8); i++) mc.level.addParticle(o, true, true, x, y + 0.5 + i * 0.8, z, 0, 0, 0);
        // the ring where it is taken
        if (mc.player.tickCount % 20 == 0) {
            DustParticleOptions ring = dust(color >= 0 ? color : 0xBBBBBB, 1.0F);
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI * 2 / 24;
                double rx = x + Math.cos(a) * org.webtrade.strategy1.game.Rules.CAPTURE_RADIUS, rz = z + Math.sin(a) * org.webtrade.strategy1.game.Rules.CAPTURE_RADIUS;
                int ry = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(rx), (int) Math.floor(rz));
                mc.level.addParticle(ring, true, false, rx, ry + 0.2, rz, 0, 0, 0);
            }
        }
    }
}
