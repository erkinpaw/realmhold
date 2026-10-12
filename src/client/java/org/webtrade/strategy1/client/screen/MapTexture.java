package org.webtrade.strategy1.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.client.ClientState;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.hex.Terrain;
import org.webtrade.strategy1.network.Payloads;

/**
 * The picture of the strategic map: every province in its terrain's colour, washed with its holder's, the hexes'
 * edges drawn thin and the realms' borders strong. {@link #BPP} blocks to a pixel; redrawn when the map changes.
 */
final class MapTexture {

    static final int BPP = 4;
    static final Identifier ID = Strategy1.id("strategy_map");
    private static DynamicTexture texture;
    private static int revision = -1, explored = -1;
    private static long builtAt;
    /** The world's corner of the picture (blocks) and its size (pixels). */
    static int originX, originZ, width, height;

    private MapTexture() {
    }

    /** The picture, made again if the map has changed; null with no map. */
    static Identifier get() {
        if (ClientState.PROVINCES.isEmpty()) return null;
        long now = System.currentTimeMillis();
        if (revision != ClientState.revision || texture == null || explored != org.webtrade.strategy1.client.Explored.revision && now - builtAt > 1500) build();
        return ID;
    }

    private static void build() {
        revision = ClientState.revision;
        explored = org.webtrade.strategy1.client.Explored.revision;
        builtAt = System.currentTimeMillis();
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Payloads.ProvinceInfo p : ClientState.PROVINCES.values()) {
            Hex h = Hex.of(p.key());
            minX = Math.min(minX, h.centerX());
            maxX = Math.max(maxX, h.centerX());
            minZ = Math.min(minZ, h.centerZ());
            maxZ = Math.max(maxZ, h.centerZ());
        }
        originX = (int) Math.floor(minX - Hex.SIZE);
        originZ = (int) Math.floor(minZ - Hex.SIZE);
        int w = Math.min(1536, (int) Math.ceil((maxX - minX + 2 * Hex.SIZE) / BPP) + 1);
        int h = Math.min(1536, (int) Math.ceil((maxZ - minZ + 2 * Hex.SIZE) / BPP) + 1);
        var tm = Minecraft.getInstance().getTextureManager();
        if (texture == null || w != width || h != height) {
            if (texture != null) tm.release(ID);
            texture = new DynamicTexture(ID::toString, w, h, false);
            tm.register(ID, texture);
            width = w;
            height = h;
        }
        NativeImage img = texture.getPixels();
        long[] keys = new long[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) keys[z * w + x] = Hex.at(originX + x * BPP + BPP / 2.0, originZ + z * BPP + BPP / 2.0).key();
        }
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                long k = keys[z * w + x];
                Payloads.ProvinceInfo p = ClientState.PROVINCES.get(k);
                if (p == null) {
                    img.setPixel(x, z, ((x + z) & 4) == 0 ? 0xFFE4D2A6 : 0xFFD6C290);
                    continue;
                }
                Terrain t = Terrain.of(p.terrain());
                int owner = t.land() ? ClientState.color(p.owner()) : -1;
                // the land as it was seen, else the sketch of the province under a fog
                int seen = org.webtrade.strategy1.client.Explored.colour(Math.floorDiv(originX + x * BPP + BPP / 2, BPP), Math.floorDiv(originZ + z * BPP + BPP / 2, BPP));
                int rgb;
                if (seen != 0) rgb = owner >= 0 ? mix(seen & 0xFFFFFF, owner, 0.28) : seen & 0xFFFFFF;
                else {
                    rgb = owner >= 0 ? mix(t.color, owner, 0.55) : t.land() ? mix(t.color, 0xE4D2A6, 0.25) : t.color;
                    rgb = mix(rgb, 0xD8C7A0, 0.45);
                }
                // edges: the hex's own thin, a realm's border strong
                boolean edge = false, border = false;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], nz = z + d[1];
                    if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                    long nk = keys[nz * w + nx];
                    if (nk == k) continue;
                    edge = true;
                    Payloads.ProvinceInfo n = ClientState.PROVINCES.get(nk);
                    int no = n == null || !Terrain.of(n.terrain()).land() ? -2 : n.owner();
                    int mo = t.land() ? p.owner() : -2;
                    if (no != mo && owner >= 0) border = true;
                }
                if (border) rgb = mix(owner, 0x000000, 0.45);
                else if (edge) rgb = mix(rgb, 0x3B2A1C, 0.22);
                img.setPixel(x, z, 0xFF000000 | rgb);
            }
        }
        texture.upload();
    }

    static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    static void clear() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(ID);
        texture = null;
        revision = -1;
    }
}
