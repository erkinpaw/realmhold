package org.webtrade.strategy1.build;

import com.mojang.math.Transformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.StrategyManager;
import org.webtrade.strategy1.mixin.BlockDisplayAccess;
import org.webtrade.strategy1.mixin.DisplayAccess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The engines on the towers, as models (blocks shown, not set in the world): a ballista and a catapult that turn to
 * what they shoot at and move when they shoot — the ballista's string snaps forward and its bolt flies, the
 * catapult's arm swings over and comes back down slowly.
 */
final class EngineModels {

    static final String TAG = "s1engine";
    private static final Map<Integer, Model> MODELS = new HashMap<>();

    private EngineModels() {
    }

    static void clear() {
        MODELS.clear();
    }

    /** Ticks since the engine last shot, its aim. */
    private static final class Model {
        final int engine;
        final List<Display.BlockDisplay> parts = new ArrayList<>();
        float yaw;
        int since = 1000;

        Model(int engine) {
            this.engine = engine;
        }
    }

    /** Where an engine sits: the middle of the tower's top. */
    static Vec3 seat(Structure s) {
        int top = s.type == StructureType.TOWER_STONE ? 11 : 7;
        return new Vec3(s.origin.getX() + 0.5, s.origin.getY() + top, s.origin.getZ() + 0.5);
    }

    /** The engine shot: it turns to its target and moves. */
    static void fired(Structure s, Vec3 at) {
        Model m = MODELS.get(s.id);
        if (m == null) return;
        Vec3 from = seat(s);
        m.yaw = (float) Math.toDegrees(Math.atan2(-(at.x - from.x), at.z - from.z));
        m.since = 0;
    }

    /** Every tick: the models made where they're wanted and seen, gone where the engine is, and moving when they shoot. */
    static void tick(ServerLevel level, long ticks) {
        StrategyData d = StrategyManager.data();
        if (ticks % 20 == 0) {
            for (Iterator<Map.Entry<Integer, Model>> it = MODELS.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Model> e = it.next();
                Structure s = d.structures.get(e.getKey());
                if (s == null || s.engine != e.getValue().engine || !level.hasChunkAt(s.origin)) {
                    for (Display.BlockDisplay p : e.getValue().parts) p.discard();
                    it.remove();
                }
            }
            for (Structure s : d.structures.values()) {
                if ((s.engine != 1 && s.engine != 2) || !s.built() || MODELS.containsKey(s.id) || !level.hasChunkAt(s.origin)) continue;
                Model m = new Model(s.engine);
                MODELS.put(s.id, m);
                make(level, s, m);
            }
            // (left over from before the world was last loaded: gone)
            if (ticks % 200 == 0) for (Display.BlockDisplay e : level.getEntities(EntityTypes.BLOCK_DISPLAY, x -> x.entityTags().contains(TAG))) {
                boolean ours = false;
                for (Model m : MODELS.values()) if (m.parts.contains(e)) ours = true;
                if (!ours) e.discard();
            }
        }
        for (Map.Entry<Integer, Model> e : MODELS.entrySet()) {
            Model m = e.getValue();
            Structure s = d.structures.get(e.getKey());
            if (s == null) continue;
            if (m.since < 1000) m.since++;
            if (m.since == 1 || m.since == 4 || m.since == 30 || ticks % 40 == 0) pose(m, s);
        }
    }

    private static Display.BlockDisplay part(ServerLevel level, Vec3 at, BlockState state) {
        Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
        if (d == null) return null;
        ((BlockDisplayAccess) d).strategy1$setBlockState(state);
        ((DisplayAccess) d).strategy1$setViewRange(4F);
        ((DisplayAccess) d).strategy1$setPosRotInterpolationDuration(4);
        d.addTag(TAG);
        d.snapTo(at.x, at.y, at.z, 0, 0);
        level.addFreshEntity(d);
        return d;
    }

    private static void make(ServerLevel level, Structure s, Model m) {
        Vec3 at = seat(s);
        BlockState[] kit = m.engine == 1
                ? new BlockState[]{Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(),
                Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState(), Blocks.IRON_BLOCK.defaultBlockState(), Blocks.IRON_BLOCK.defaultBlockState()}
                : new BlockState[]{Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_LOG.defaultBlockState(),
                Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.STRIPPED_OAK_LOG.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()};
        for (BlockState b : kit) {
            Display.BlockDisplay d = part(level, at, b);
            if (d != null) m.parts.add(d);
        }
        pose(m, s);
    }

    private static void set(Display.BlockDisplay d, float x, float y, float z, float sx, float sy, float sz, float tilt, int ticks) {
        DisplayAccess a = (DisplayAccess) d;
        a.strategy1$setTransformationInterpolationDelay(0);
        a.strategy1$setTransformationInterpolationDuration(ticks);
        a.strategy1$setTransformation(new Transformation(new Vector3f(x, y, z), new Quaternionf().rotationX((float) Math.toRadians(tilt)),
                new Vector3f(sx, sy, sz), new Quaternionf()));
    }

    /** The model as it stands now (+z its front): loaded and waiting, just shot, or coming back. */
    private static void pose(Model m, Structure s) {
        if (m.parts.size() < 6) return;
        for (Display.BlockDisplay d : m.parts) {
            d.setYRot(m.yaw);
            d.setXRot(0);
        }
        List<Display.BlockDisplay> p = m.parts;
        boolean shot = m.since < 30;
        if (m.engine == 1) {
            // the ballista: a post, the stock along it, the bow across its front, the string, the bolt
            set(p.get(0), -0.15F, 0, -0.15F, 0.3F, 0.6F, 0.3F, 0, 1);
            set(p.get(1), -0.15F, 0.6F, -0.8F, 0.3F, 0.22F, 1.7F, 0, 1);
            set(p.get(2), -1.0F, 0.62F, 0.75F, 2.0F, 0.14F, 0.14F, 0, 1);
            // the string drawn back, snapped forward when it shoots, drawn back again
            float string = shot && m.since < 25 ? 0.72F : -0.55F;
            set(p.get(3), -0.95F, 0.66F, string, 1.9F, 0.04F, 0.04F, 0, shot ? 2 : 14);
            // the bolt on the stock (gone while it flies, back when it's loaded)
            boolean loaded = !shot || m.since >= 25;
            float k = loaded ? 1 : 0.001F;
            set(p.get(4), -0.04F, 0.84F, -0.5F, 0.08F * k, 0.08F * k, 1.5F * k, 0, 1);
            set(p.get(5), -0.07F, 0.81F, 1.0F, 0.14F * k, 0.14F * k, 0.2F * k, 0, 1);
        } else {
            // the catapult: a frame, two uprights, a crossbar, the arm with its stone
            set(p.get(0), -0.75F, 0, -0.9F, 1.5F, 0.2F, 1.8F, 0, 1);
            set(p.get(1), -0.6F, 0.2F, 0.05F, 0.2F, 0.9F, 0.2F, 0, 1);
            set(p.get(2), 0.4F, 0.2F, 0.05F, 0.2F, 0.9F, 0.2F, 0, 1);
            set(p.get(3), -0.6F, 1.0F, 0.05F, 1.2F, 0.18F, 0.18F, 0, 1);
            // the arm: back and low, loaded; over and up when it throws; back slowly
            float tilt = m.since < 4 ? -55 : m.since < 30 && m.since >= 4 ? -60 : -158;
            int dur = m.since < 4 ? 3 : 24;
            Vec3 pivot = new Vec3(-0.09, 0.35, 0.15);
            set(p.get(4), (float) pivot.x, (float) pivot.y, (float) pivot.z, 0.18F, 0.18F, 1.9F, tilt, dur);
            // the stone in the cup at the arm's end (thrown: gone till it's loaded again)
            double a = Math.toRadians(tilt);
            double ey = -Math.sin(a) * 1.7, ez = Math.cos(a) * 1.7;
            boolean loaded = m.since >= 30;
            float sz = loaded ? 0.42F : 0.001F;
            set(p.get(5), (float) (pivot.x - 0.12), (float) (pivot.y + ey), (float) (pivot.z + ez), sz, sz, sz, tilt, dur);
        }
    }

    /** All the models gone (the server stops). */
    static void removeAll() {
        for (Model m : MODELS.values()) for (Entity e : m.parts) e.discard();
        MODELS.clear();
    }
}
