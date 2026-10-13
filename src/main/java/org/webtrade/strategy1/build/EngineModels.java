package org.webtrade.strategy1.build;

import com.mojang.math.Transformation;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
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
 * The engines on the towers, as models (blocks shown, not set in the world; logs laid along their length, so nothing
 * looks stretched): a ballista and a catapult that turn to what they shoot at and move when they shoot. The ballista's
 * arms fling forward and its string snaps to the bow as its bolt flies; it's wound back and a new bolt laid on. The
 * catapult's arm swings up round its axle against the crossbar and lets its stone go, and is wound down again.
 */
final class EngineModels {

    static final String TAG = "s1engine";
    private static final Map<Integer, Model> MODELS = new HashMap<>();

    private EngineModels() {
    }

    static void clear() {
        MODELS.clear();
    }

    /** Ticks since the engine last shot, its aim, and the shot itself (let fly once it has turned and moves). */
    private static final class Model {
        final int engine;
        final List<Display.BlockDisplay> parts = new ArrayList<>();
        float yaw;
        int since = 1000;
        Runnable shot;

        Model(int engine) {
            this.engine = engine;
        }
    }

    /** Where an engine sits: the middle of the tower's top. */
    static Vec3 seat(Structure s) {
        int top = s.type == StructureType.TOWER_STONE ? 11 : 7;
        return new Vec3(s.origin.getX() + 0.5, s.origin.getY() + top, s.origin.getZ() + 0.5);
    }

    /** Ticks an engine takes to turn to its target before it shoots (its parts turn in as many). */
    private static final int TURN = 6;

    /**
     * The engine shoots: it turns to its target, then moves, and the shot flies as it lets go (at once if there's no
     * model to see).
     */
    static void fired(Structure s, Vec3 at, Runnable shot) {
        Model m = MODELS.get(s.id);
        if (m == null) {
            shot.run();
            return;
        }
        if (m.shot != null) m.shot.run();
        Vec3 from = seat(s);
        m.yaw = (float) Math.toDegrees(Math.atan2(-(at.x - from.x), at.z - from.z));
        for (Display.BlockDisplay d : m.parts) d.setYRot(m.yaw);
        m.since = 0;
        m.shot = shot;
    }

    /** Ticks after it starts moving that an engine lets its shot go: a ballista at once, a catapult as its arm flies up. */
    private static int letGo(int engine) {
        return engine == 1 ? 1 : C_LET_GO;
    }

    /** Every tick: the models made where they're wanted and seen, gone where the engine is, and moving when they shoot. */
    static void tick(ServerLevel level, long ticks) {
        StrategyData d = StrategyManager.data();
        if (ticks % 20 == 0) {
            for (Iterator<Map.Entry<Integer, Model>> it = MODELS.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Model> e = it.next();
                Structure s = d.structures.get(e.getKey());
                if (s == null || s.engine != e.getValue().engine || !level.hasChunkAt(s.origin)) {
                    if (e.getValue().shot != null && s != null) e.getValue().shot.run();
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
            if (m.since >= 1000) continue;
            m.since++;
            if (m.shot != null && m.since >= TURN + letGo(m.engine)) {
                Runnable shot = m.shot;
                m.shot = null;
                shot.run();
            }
            int k = keyframe(m);
            if (k > 0) pose(m, k);
        }
    }

    private static Display.BlockDisplay part(ServerLevel level, Vec3 at, BlockState state) {
        Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
        if (d == null) return null;
        ((BlockDisplayAccess) d).strategy1$setBlockState(state);
        ((DisplayAccess) d).strategy1$setViewRange(4F);
        ((DisplayAccess) d).strategy1$setPosRotInterpolationDuration(6);
        d.addTag(TAG);
        d.snapTo(at.x, at.y, at.z, 0, 0);
        level.addFreshEntity(d);
        return d;
    }

    /** A log lying along an axis (its bark runs the length of the beam, its rings at the ends: nothing stretched). */
    private static BlockState log(net.minecraft.world.level.block.Block b, Direction.Axis axis) {
        return b.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
    }

    private static void make(ServerLevel level, Structure s, Model m) {
        Vec3 at = seat(s);
        BlockState string = Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState();
        BlockState[] kit = m.engine == 1
                ? new BlockState[]{
                log(Blocks.SPRUCE_LOG, Direction.Axis.Y), log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Z),
                log(Blocks.SPRUCE_LOG, Direction.Axis.X), log(Blocks.SPRUCE_LOG, Direction.Axis.X), string, string,
                log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Z), Blocks.IRON_BLOCK.defaultBlockState()}
                : new BlockState[]{
                log(Blocks.SPRUCE_LOG, Direction.Axis.Z), log(Blocks.SPRUCE_LOG, Direction.Axis.Z), log(Blocks.SPRUCE_LOG, Direction.Axis.X),
                log(Blocks.SPRUCE_LOG, Direction.Axis.Y), log(Blocks.SPRUCE_LOG, Direction.Axis.Y), log(Blocks.SPRUCE_LOG, Direction.Axis.X),
                log(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.X), log(Blocks.STRIPPED_OAK_LOG, Direction.Axis.Z),
                Blocks.COMPOSTER.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()};
        for (BlockState b : kit) {
            Display.BlockDisplay d = part(level, at, b);
            if (d != null) m.parts.add(d);
        }
        for (Display.BlockDisplay d : m.parts) d.setYRot(m.yaw);
        pose(m, 0);
    }

    /**
     * A part as a box: turned by {@code q} round {@code pivot}, its near corner at {@code from} from the pivot (in the
     * turned frame), {@code size} big, there in so many ticks.
     */
    private static void box(Display.BlockDisplay d, Vector3f pivot, Quaternionf q, Vector3f from, Vector3f size, int ticks) {
        DisplayAccess a = (DisplayAccess) d;
        a.strategy1$setTransformationInterpolationDelay(0);
        a.strategy1$setTransformationInterpolationDuration(ticks);
        Vector3f t = q.transform(new Vector3f(from)).add(pivot);
        a.strategy1$setTransformation(new Transformation(t, new Quaternionf(q), size, new Quaternionf()));
    }

    /** An unturned box from one corner to the other. */
    private static void box(Display.BlockDisplay d, float x0, float y0, float z0, float x1, float y1, float z1, int ticks) {
        box(d, new Vector3f(x0, y0, z0), new Quaternionf(), new Vector3f(), new Vector3f(x1 - x0, y1 - y0, z1 - z0), ticks);
    }

    private static final float GONE = 0.001F;

    // ---- the ballista (+z its front): its bow's arms swept back while it's drawn, flung forward when it shoots

    /** After a shot: the arms flung forward (so many ticks), wound back from B_WIND, a new bolt laid on at B_DRAWN. */
    private static final int B_SNAP = 2, B_WIND = 12, B_DRAWN = 32;
    private static final float B_BOW_Z = 0.72F, B_ARM = 0.95F, B_Y = 0.67F;

    private static void ballista(List<Display.BlockDisplay> p, int since, int ticks) {
        box(p.get(0), -0.15F, 0, -0.15F, 0.15F, 0.55F, 0.15F, ticks);
        box(p.get(1), -0.12F, 0.55F, -0.95F, 0.12F, 0.79F, 0.95F, ticks);
        // the bow's arms, swept back while it's drawn
        boolean drawn = since >= B_WIND;
        float sweep = (float) Math.toRadians(drawn ? 26 : 6);
        Vector3f left = new Vector3f(-0.12F, B_Y, B_BOW_Z), right = new Vector3f(0.12F, B_Y, B_BOW_Z);
        Quaternionf ql = new Quaternionf().rotationY(-sweep), qr = new Quaternionf().rotationY(sweep);
        box(p.get(2), left, ql, new Vector3f(-B_ARM, -0.06F, -0.06F), new Vector3f(B_ARM, 0.12F, 0.12F), ticks);
        box(p.get(3), right, qr, new Vector3f(0, -0.06F, -0.06F), new Vector3f(B_ARM, 0.12F, 0.12F), ticks);
        // the string from the arms' tips to where it's drawn (the bow's line when it's loosed)
        Vector3f lt = ql.transform(new Vector3f(-B_ARM, 0, 0)).add(left), rt = qr.transform(new Vector3f(B_ARM, 0, 0)).add(right);
        Vector3f nock = new Vector3f(0, B_Y, drawn ? -0.6F : lt.z);
        string(p.get(4), nock, lt, ticks);
        string(p.get(5), nock, rt, ticks);
        // the bolt on the stock (gone while it flies; laid on once it's drawn)
        float k = since >= B_DRAWN ? 1 : GONE;
        box(p.get(6), new Vector3f(0, 0.83F, -0.55F), new Quaternionf(), new Vector3f(-0.04F * k, -0.04F * k, 0), new Vector3f(0.08F * k, 0.08F * k, 1.45F * k), ticks);
        box(p.get(7), new Vector3f(0, 0.83F, 0.95F), new Quaternionf(), new Vector3f(-0.07F * k, -0.07F * k, -0.07F * k), new Vector3f(0.14F * k, 0.14F * k, 0.14F * k), ticks);
    }

    /** A length of string from a to b (a thin box along it). */
    private static void string(Display.BlockDisplay d, Vector3f a, Vector3f b, int ticks) {
        float dx = b.x - a.x, dz = b.z - a.z, len = (float) Math.sqrt(dx * dx + dz * dz);
        Quaternionf q = new Quaternionf().rotationY((float) Math.atan2(-dz, dx));
        box(d, a, q, new Vector3f(0, -0.02F, -0.02F), new Vector3f(len, 0.04F, 0.04F), ticks);
    }

    // ---- the catapult (+z its front): its arm lies back, swings up against the crossbar and throws, is wound down again

    private static final Vector3f C_PIVOT = new Vector3f(0, 0.42F, -0.05F);
    /** The arm at rest (back, nearly level) and thrown (up against the crossbar), degrees about x; its length. */
    private static final float C_REST = -173, C_THROWN = -68, C_ARM = 1.5F;
    /** After a shot: the throw takes C_THROW ticks, the stone is let go at C_LET_GO, the arm is wound down from C_HOLD to C_WOUND, loaded at C_LOADED. */
    private static final int C_THROW = 4, C_LET_GO = 3, C_HOLD = 20, C_WOUND = 60, C_LOADED = 64;

    private static float catapultAngle(int since) {
        if (since >= C_WOUND) return C_REST;
        if (since <= C_THROW) {
            float f = since / (float) C_THROW;
            return C_REST + (C_THROWN - C_REST) * f * f;
        }
        if (since <= C_HOLD) return C_THROWN;
        float f = (since - C_HOLD) / (float) (C_WOUND - C_HOLD);
        f = f * f * (3 - 2 * f);
        return C_THROWN + (C_REST - C_THROWN) * f;
    }

    private static void catapult(List<Display.BlockDisplay> p, int since, int ticks) {
        // the frame: two beams along, one across at the back; two uprights and the crossbar the arm strikes; the axle
        box(p.get(0), -0.62F, 0, -1.55F, -0.42F, 0.2F, 0.85F, ticks);
        box(p.get(1), 0.42F, 0, -1.55F, 0.62F, 0.2F, 0.85F, ticks);
        box(p.get(2), -0.42F, 0.02F, -1.45F, 0.42F, 0.18F, -1.27F, ticks);
        box(p.get(3), -0.62F, 0.2F, 0.42F, -0.44F, 1.32F, 0.6F, ticks);
        box(p.get(4), 0.44F, 0.2F, 0.42F, 0.62F, 1.32F, 0.6F, ticks);
        box(p.get(5), -0.62F, 1.32F, 0.42F, 0.62F, 1.5F, 0.6F, ticks);
        box(p.get(6), -0.42F, C_PIVOT.y - 0.07F, C_PIVOT.z - 0.07F, 0.42F, C_PIVOT.y + 0.07F, C_PIVOT.z + 0.07F, ticks);
        // the arm, turned round the axle
        Quaternionf q = new Quaternionf().rotationX((float) Math.toRadians(catapultAngle(since)));
        box(p.get(7), C_PIVOT, q, new Vector3f(-0.08F, -0.08F, 0), new Vector3f(0.16F, 0.16F, C_ARM), ticks);
        // the cup at its end, open to the side that throws (turned over: a composter is open at its +y)
        Quaternionf cup = new Quaternionf(q).rotateX((float) Math.PI);
        box(p.get(8), C_PIVOT, cup, new Vector3f(-0.21F, 0.06F, -C_ARM - 0.02F), new Vector3f(0.42F, 0.42F, 0.42F), ticks);
        // the stone in the cup (let go as the arm flies up; a new one once it's wound down)
        float r = since < C_LET_GO || since >= C_LOADED ? 0.3F : GONE;
        Vector3f in = q.transform(new Vector3f(0, -0.3F, C_ARM - 0.19F)).add(C_PIVOT);
        box(p.get(9), in, new Quaternionf(), new Vector3f(-r / 2, -r / 2, -r / 2), new Vector3f(r, r, r), ticks);
    }

    /** The model as it will stand so many ticks from now (by the ticks since it began to shoot then), moving there meanwhile. */
    private static void pose(Model m, int ticks) {
        if (m.parts.size() < (m.engine == 1 ? 8 : 10)) return;
        int t = m.since >= 1000 ? 1000 : m.since - TURN;
        if (m.engine == 1) ballista(m.parts, t + ticks, ticks);
        else catapult(m.parts, t + ticks, ticks);
    }

    /** Whether the model takes a new pose this tick after a shot, and how many ticks it moves to it (0: none now). */
    private static int keyframe(Model m) {
        int t = m.since - TURN;
        if (t <= 0) return 0;
        if (m.engine == 1) {
            if (t == 1) return B_SNAP;
            if (t == B_WIND) return B_DRAWN - B_WIND - 2;
            return t == B_DRAWN ? 1 : 0;
        }
        // (the arm posed often as it swings, so it turns on its axle instead of cutting straight across)
        if (t <= C_THROW || t == C_LOADED) return 1;
        if (t >= C_HOLD && t < C_WOUND && (t - C_HOLD) % 4 == 0) return 4;
        return 0;
    }

    /** All the models gone (the server stops). */
    static void removeAll() {
        for (Model m : MODELS.values()) for (Entity e : m.parts) e.discard();
        MODELS.clear();
    }
}
