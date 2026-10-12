package org.webtrade.strategy1.entity;

import com.mojang.math.Transformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.webtrade.strategy1.mixin.BlockDisplayAccess;
import org.webtrade.strategy1.mixin.DisplayAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * The look of a siege engine or a ship: blocks shown (display entities, scaled and placed in the machine's own frame,
 * +z forward) that go and turn with it. Nothing to draw on the client side of the mod; the displays aren't kept with
 * the world (leftovers are swept away, see {@link #TAG}).
 */
public final class Puppet {

    /** Every display of a puppet has this tag, and "s1o:" + its owner's UUID. */
    public static final String TAG = "s1puppet";

    /** A block of the model: its corner at {@code at} (blocks, the machine's frame), its size, a tilt (degrees, round x). */
    public record Part(BlockState state, float x, float y, float z, float sx, float sy, float sz, float tilt) {
        public static Part of(BlockState s, float x, float y, float z, float sx, float sy, float sz) {
            return new Part(s, x, y, z, sx, sy, sz, 0);
        }
    }

    private final List<Part> parts;
    private final List<Display.BlockDisplay> shown = new ArrayList<>();

    public Puppet(List<Part> parts) {
        this.parts = parts;
    }

    public boolean owns(Entity e) {
        return shown.contains(e);
    }

    /** Keeps the model on its owner (makes it again if any of it went). */
    public void tick(ServerLevel level, Entity owner) {
        boolean whole = shown.size() == parts.size();
        for (Display.BlockDisplay d : shown) if (d.isRemoved()) whole = false;
        if (!whole) {
            remove();
            for (Part p : parts) {
                Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
                if (d == null) continue;
                ((BlockDisplayAccess) d).strategy1$setBlockState(p.state);
                Quaternionf tilt = new Quaternionf().rotationX((float) Math.toRadians(p.tilt));
                ((DisplayAccess) d).strategy1$setTransformation(new Transformation(new Vector3f(p.x, p.y, p.z), tilt,
                        new Vector3f(p.sx, p.sy, p.sz), new Quaternionf()));
                ((DisplayAccess) d).strategy1$setPosRotInterpolationDuration(3);
                ((DisplayAccess) d).strategy1$setViewRange(3F);
                d.addTag(TAG);
                d.addTag("s1o:" + owner.getUUID());
                d.snapTo(owner.getX(), owner.getY(), owner.getZ(), owner.getYRot(), 0);
                level.addFreshEntity(d);
                shown.add(d);
            }
        }
        for (Display.BlockDisplay d : shown) {
            d.setPos(owner.getX(), owner.getY(), owner.getZ());
            d.setYRot(owner.getYRot());
            d.setXRot(0);
        }
    }

    public void remove() {
        for (Display.BlockDisplay d : shown) d.discard();
        shown.clear();
    }
}
