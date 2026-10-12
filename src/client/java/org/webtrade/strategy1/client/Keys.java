package org.webtrade.strategy1.client;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.webtrade.strategy1.Strategy1;

/** The mod's keys (rebindable in Options → Controls). */
public final class Keys {

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Strategy1.id("strategy"));
    /** The strategic map. */
    public static final KeyMapping MAP = new KeyMapping("key.strategy1.map", GLFW.GLFW_KEY_M, CATEGORY);
    /** Into and out of the strategist's view (the camera over the land, no body). */
    public static final KeyMapping COMMANDER = new KeyMapping("key.strategy1.rts", GLFW.GLFW_KEY_G, CATEGORY);
    /** Show the borders of the provinces in the world. */
    public static final KeyMapping BORDERS = new KeyMapping("key.strategy1.borders", GLFW.GLFW_KEY_B, CATEGORY);

    private Keys() {
    }

    static void register() {
        for (KeyMapping k : new KeyMapping[]{MAP, COMMANDER, BORDERS}) KeyMappingHelper.registerKeyMapping(k);
    }
}
