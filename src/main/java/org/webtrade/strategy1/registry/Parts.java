package org.webtrade.strategy1.registry;

import net.minecraft.world.level.block.state.BlockState;

/** The engines' parts as the states of {@link ModContent#PART} (made by tools/parts.py: run it again to change them). */
public final class Parts {

    public static final int BALLISTA_BASE = 0;
    public static final int BALLISTA_ARM_LEFT = 1;
    public static final int BALLISTA_ARM_RIGHT = 2;
    public static final int BALLISTA_BOLT = 3;
    public static final int CATAPULT_FRAME = 4;
    public static final int CATAPULT_ARM = 5;
    public static final int SIEGE_CATAPULT = 6;
    public static final int SIEGE_CATAPULT_ARM = 7;
    public static final int TREBUCHET_BASE = 8;
    public static final int TREBUCHET_FRAME = 9;
    public static final int TREBUCHET_BRACE = 10;
    public static final int TREBUCHET_ARM = 11;

    public static final int COUNT = 12;

    /** Where each part's model stands in its machine's frame (blocks): where to put its display. */
    private static final float[][] AT = {
            {0F, 0F, 0F},
            {0F, 0F, 0F},
            {0F, 0F, 0F},
            {0F, 0F, 0F},
            {-0.5F, 0.25F, -0.55F},
            {0F, 0F, 0F},
            {-0.15F, 0.35F, -0.1F},
            {0F, 0F, 0F},
            {-0.45F, -0.1F, -0.5F},
            {-0.05F, 1.55F, 0.325F},
            {0F, 0F, 0F},
            {0F, 0F, 0F}};

    private Parts() {
    }

    public static BlockState state(int part) {
        return ModContent.PART.defaultBlockState().setValue(PartBlock.PART, part);
    }

    public static org.joml.Vector3f at(int part) {
        return new org.joml.Vector3f(AT[part][0], AT[part][1], AT[part][2]);
    }
}
