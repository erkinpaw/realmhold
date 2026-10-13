package org.webtrade.strategy1.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.hex.Hex;

import java.util.ArrayList;
import java.util.List;

/**
 * A building, tower, wall or path a realm put up: where, which way, its blocks (laid one after another as it is
 * built: first the ground made ready, then the building), its strength, and the archers standing on it.
 */
public final class Structure {

    public final int id;
    public final StructureType type;
    public int owner;
    public BlockPos origin, end;
    public final int rot;
    public int hp, maxHp;
    /** Blocks laid so far. */
    public int progress;
    /** Archers on it (counted; they're put there when anyone is near to see them). */
    public int archers;
    /** Its blocks, in the order they're laid; the first {@link #prep} only make the ground ready (not part of it). */
    public long[] pos = new long[0];
    public int[] state = new int[0];
    public List<BlockState> palette = new ArrayList<>();
    public int prep;
    /** Where archers stand on it, and where what it makes comes out (barracks, workshop, port). */
    public List<Vec3> spots = new ArrayList<>();
    public Vec3 door;
    /** Where the units it sends out go to gather (null: they stay at its door). */
    public Vec3 rally;
    /** Not laid out yet (an AI's fort waiting for its land to be loaded). */
    public boolean planned;
    /** Put up at once (a fort or keep there from the start). */
    public boolean instant;
    /** A gate's: the wall it's cut in, where its portcullis hangs, held open by hand, open now. */
    public int parent = -1;
    public long[] leaves = new long[0];
    public boolean held, open, leavesLaid;
    /** A tower's engine on its top: 0 none, 1 ballista, 2 catapult, 3 fire. */
    public int engine;
    private AABB box;

    public Structure(int id, StructureType type, int owner, BlockPos origin, BlockPos end, int rot) {
        this.id = id;
        this.type = type;
        this.owner = owner;
        this.origin = origin;
        this.end = end == null ? origin : end;
        this.rot = rot;
    }

    public boolean built() {
        return !planned && progress >= pos.length;
    }

    public int length() {
        return Math.max(1, (int) Math.round(Math.sqrt(origin.distSqr(new BlockPos(end.getX(), origin.getY(), end.getZ())))) + 1);
    }

    public int posts() {
        if (type.wall()) return Math.max(1, length() / 3);
        return type.posts;
    }

    public long province() {
        return Hex.at(origin.getX() + 0.5, origin.getZ() + 0.5).key();
    }

    /** The box its blocks fill (or the ground it's meant for, not laid out yet). */
    public AABB box() {
        if (box != null) return box;
        if (pos.length == 0) {
            if (type.line) box = new AABB(Vec3.atLowerCornerOf(origin), Vec3.atLowerCornerOf(end)).inflate(1, 0, 1).expandTowards(0, 5, 0);
            else box = new AABB(origin).inflate(Math.max(-type.x0, type.x1), 0, Math.max(-type.z0, type.z1)).expandTowards(0, 6, 0);
            return box;
        }
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (int i = prep; i < pos.length; i++) {
            if (palette.get(state[i]).isAir()) continue;
            BlockPos p = BlockPos.of(pos[i]);
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX());
            y1 = Math.max(y1, p.getY());
            z1 = Math.max(z1, p.getZ());
        }
        if (x0 == Integer.MAX_VALUE) return new AABB(origin);
        box = new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
        return box;
    }

    public void laidOut() {
        box = null;
    }

    public Vec3 center() {
        AABB b = box();
        return new Vec3((b.minX + b.maxX) / 2, b.minY + (b.maxY - b.minY) * 0.4, (b.minZ + b.maxZ) / 2);
    }

    // ------------------------------------------------------------------ saving

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putInt("type", type.ordinal());
        t.putInt("owner", owner);
        t.putLong("origin", origin.asLong());
        t.putLong("end", end.asLong());
        t.putInt("rot", rot);
        t.putInt("hp", hp);
        t.putInt("maxHp", maxHp);
        t.putInt("progress", progress);
        t.putInt("archers", archers);
        t.putBoolean("planned", planned);
        t.putBoolean("instant", instant);
        t.putInt("engine", engine);
        if (type == StructureType.GATE) {
            t.putInt("parent", parent);
            t.putLongArray("leaves", leaves);
            t.putBoolean("held", held);
        }
        t.putInt("prep", prep);
        t.putLongArray("pos", pos);
        t.putIntArray("state", state);
        ListTag pal = new ListTag();
        for (BlockState s : palette) pal.add(NbtUtils.writeBlockState(s));
        t.put("palette", pal);
        ListTag sp = new ListTag();
        for (Vec3 v : spots) {
            CompoundTag e = new CompoundTag();
            e.putDouble("x", v.x);
            e.putDouble("y", v.y);
            e.putDouble("z", v.z);
            sp.add(e);
        }
        t.put("spots", sp);
        if (rally != null) {
            t.putDouble("rx", rally.x);
            t.putDouble("rz", rally.z);
        }
        if (door != null) {
            t.putDouble("dx", door.x);
            t.putDouble("dy", door.y);
            t.putDouble("dz", door.z);
        }
        return t;
    }

    public static Structure load(CompoundTag t) {
        Structure s = new Structure(t.getIntOr("id", 0), StructureType.of(t.getIntOr("type", 0)), t.getIntOr("owner", 0),
                BlockPos.of(t.getLongOr("origin", 0)), BlockPos.of(t.getLongOr("end", 0)), t.getIntOr("rot", 0));
        s.hp = t.getIntOr("hp", 1);
        s.maxHp = t.getIntOr("maxHp", 1);
        s.progress = t.getIntOr("progress", 0);
        s.archers = t.getIntOr("archers", 0);
        s.planned = t.getBooleanOr("planned", false);
        s.instant = t.getBooleanOr("instant", false);
        s.engine = t.getIntOr("engine", 0);
        s.parent = t.getIntOr("parent", -1);
        s.leaves = t.getLongArray("leaves").orElse(new long[0]);
        s.held = t.getBooleanOr("held", false);
        s.prep = t.getIntOr("prep", 0);
        s.pos = t.getLongArray("pos").orElse(new long[0]);
        s.state = t.getIntArray("state").orElse(new int[0]);
        var blocks = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        ListTag pal = t.getListOrEmpty("palette");
        for (int i = 0; i < pal.size(); i++) s.palette.add(NbtUtils.readBlockState(blocks, pal.getCompoundOrEmpty(i)));
        ListTag sp = t.getListOrEmpty("spots");
        for (int i = 0; i < sp.size(); i++) {
            CompoundTag e = sp.getCompoundOrEmpty(i);
            s.spots.add(new Vec3(e.getDoubleOr("x", 0), e.getDoubleOr("y", 0), e.getDoubleOr("z", 0)));
        }
        if (t.contains("rx")) s.rally = new Vec3(t.getDoubleOr("rx", 0), 0, t.getDoubleOr("rz", 0));
        if (t.contains("dx")) s.door = new Vec3(t.getDoubleOr("dx", 0), t.getDoubleOr("dy", 0), t.getDoubleOr("dz", 0));
        if (s.state.length != s.pos.length) {
            s.pos = new long[0];
            s.state = new int[0];
            s.planned = true;
        }
        return s;
    }
}
