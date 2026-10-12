package org.webtrade.strategy1.entity;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** The wool nearest to a realm's colour: its sails, banners and flags. */
public final class Dyes {

    private Dyes() {
    }

    public static Block wool(int rgb) {
        DyeColor best = DyeColor.WHITE;
        double bestD = Double.MAX_VALUE;
        for (DyeColor c : DyeColor.values()) {
            int d = c.getTextureDiffuseColor();
            double dr = ((d >> 16) & 255) - ((rgb >> 16) & 255), dg = ((d >> 8) & 255) - ((rgb >> 8) & 255), db = (d & 255) - (rgb & 255);
            double dist = dr * dr * 0.3 + dg * dg * 0.59 + db * db * 0.11;
            if (dist < bestD) {
                bestD = dist;
                best = c;
            }
        }
        return Blocks.WOOL.pick(best);
    }
}
