package org.webtrade.strategy1.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.game.StrategyManager;
import org.webtrade.strategy1.registry.ModContent;

import java.util.ArrayList;
import java.util.List;

/**
 * A ship from a port: a transport (eight soldiers aboard) or a warship (four aboard, and a catapult on her deck).
 * She sails in a straight line to where she is sent and stops at the shore; sent onto the land she sails to the
 * shore there and puts her soldiers ashore. Archers aboard shoot from her deck; the warship throws stones at
 * enemy buildings and troops on the shore. Towers' archers shoot at her.
 */
public class ShipEntity extends UnitEntity {

    public enum Kind {
        TRANSPORT(160, 0.28, 8),
        WARSHIP(280, 0.24, 4);

        public final double health, speed;
        public final int capacity;

        Kind(double health, double speed, int capacity) {
            this.health = health;
            this.speed = speed;
            this.capacity = capacity;
        }

        public String key() {
            return "strategy1.ship." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum Mode {HOLD, MOVE, UNLOAD, ATTACK}

    public Mode mode = Mode.HOLD;
    public Vec3 anchor = Vec3.ZERO;
    /** Where the soldiers go ashore (an unloading). */
    private Vec3 ashore;
    public int targetStructure = -1, targetEntity = -1;
    private int reload = 40;
    /** The legs of her way over the water, and the one she's on. */
    private List<Vec3> legs = new ArrayList<>();
    private int leg;
    private Puppet puppet;

    public ShipEntity(EntityType<? extends ShipEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 160).add(Attributes.MOVEMENT_SPEED, 0.2)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0).add(Attributes.FOLLOW_RANGE, 48);
    }

    public Kind kind() {
        return getType() == ModContent.WARSHIP ? Kind.WARSHIP : Kind.TRANSPORT;
    }

    public static ShipEntity spawn(ServerLevel level, Kind kind, int faction, int color, Vec3 at, float yaw, java.util.UUID commander) {
        ShipEntity s = (kind == Kind.WARSHIP ? ModContent.WARSHIP : ModContent.TRANSPORT).create(level, EntitySpawnReason.MOB_SUMMONED);
        if (s == null) return null;
        s.setRealm(faction, color);
        s.commander = commander;
        s.getAttribute(Attributes.MAX_HEALTH).setBaseValue(kind.health);
        s.setHealth(s.getMaxHealth());
        s.setCustomName(Component.translatable(kind.key()).withColor(color));
        // (on open water with room round her, by the port; not in the pier or the shore)
        double y = Double.NaN;
        for (int dy = 4; dy >= -4 && Double.isNaN(y); dy--) y = waterTop(level, at.x, at.y + dy, at.z);
        if (Double.isNaN(y)) y = level.getSeaLevel();
        Vec3 water = WaterPath.nearestWater(level, at, y, 14);
        if (water == null) return null;
        s.snapTo(water.x, y - 0.35, water.z, yaw, 0);
        s.setYBodyRot(yaw);
        s.setYHeadRot(yaw);
        s.anchor = s.position();
        level.addFreshEntity(s);
        return s;
    }

    /** The top of the water at a column (searching down from y), or NaN with no water there. */
    static double waterTop(Level level, double x, double y, double z) {
        BlockPos.MutableBlockPos p = BlockPos.containing(x, y, z).mutable();
        for (int i = 0; i < 6; i++) {
            if (level.getFluidState(p).is(FluidTags.WATER) && !level.getFluidState(p.above()).is(FluidTags.WATER)) {
                // (open water: not under ice)
                return level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty() ? p.getY() + 1 : Double.NaN;
            }
            p.move(0, -1, 0);
        }
        return Double.NaN;
    }

    public void order(Mode mode, Vec3 at, int structure, int entity) {
        this.mode = mode;
        if (at != null) anchor = at;
        ashore = mode == Mode.UNLOAD ? at : null;
        targetStructure = structure;
        targetEntity = entity;
        legs = new ArrayList<>();
        leg = 0;
        if ((mode == Mode.MOVE || mode == Mode.UNLOAD) && at != null && level() instanceof ServerLevel level) {
            double y = getY() + 0.35;
            // where on the water she makes for: the point itself, or the water nearest the shore she's sent to
            Vec3 goal = WaterPath.open(level, (int) Math.floor(at.x), (int) Math.floor(at.z), y) ? new Vec3(at.x, y, at.z)
                    : WaterPath.nearestWater(level, at, y, 18);
            if (goal == null) goal = at;
            anchor = goal;
            List<Vec3> way = WaterPath.find(level, position(), goal, y);
            legs = way != null && !way.isEmpty() ? way : new ArrayList<>(List.of(goal));
        }
    }

    // ------------------------------------------------------------------ the look

    private List<Puppet.Part> model() {
        BlockState sail = Dyes.wool(color()).defaultBlockState();
        List<Puppet.Part> m = new ArrayList<>();
        if (kind() == Kind.TRANSPORT) {
            BlockState hull = Blocks.OAK_PLANKS.defaultBlockState(), dark = Blocks.SPRUCE_PLANKS.defaultBlockState();
            m.add(Puppet.Part.of(hull, -1.2F, -0.3F, -2.4F, 2.4F, 0.7F, 4.8F));
            m.add(Puppet.Part.of(hull, -0.8F, -0.2F, 2.4F, 1.6F, 0.6F, 0.8F));
            m.add(Puppet.Part.of(hull, -0.4F, -0.1F, 3.2F, 0.8F, 0.5F, 0.5F));
            m.add(Puppet.Part.of(dark, -1.25F, 0.4F, -2.45F, 0.15F, 0.3F, 4.9F));
            m.add(Puppet.Part.of(dark, 1.1F, 0.4F, -2.45F, 0.15F, 0.3F, 4.9F));
            m.add(Puppet.Part.of(dark, -1.2F, 0.4F, -2.5F, 2.4F, 0.5F, 0.4F));
            m.add(Puppet.Part.of(Blocks.SPRUCE_LOG.defaultBlockState(), -0.12F, 0.4F, 0.4F, 0.24F, 4.2F, 0.24F));
            m.add(Puppet.Part.of(Blocks.SPRUCE_LOG.defaultBlockState(), -1.3F, 3.9F, 0.45F, 2.6F, 0.14F, 0.14F));
            m.add(Puppet.Part.of(sail, -1.25F, 1.6F, 0.6F, 2.5F, 2.3F, 0.06F));
        } else {
            BlockState hull = Blocks.DARK_OAK_PLANKS.defaultBlockState(), trim = Blocks.SPRUCE_PLANKS.defaultBlockState();
            m.add(Puppet.Part.of(hull, -1.5F, -0.4F, -3.2F, 3.0F, 0.9F, 6.4F));
            m.add(Puppet.Part.of(hull, -1.0F, -0.3F, 3.2F, 2.0F, 0.8F, 1.0F));
            m.add(Puppet.Part.of(hull, -0.5F, -0.2F, 4.2F, 1.0F, 0.7F, 0.6F));
            m.add(Puppet.Part.of(trim, -1.55F, 0.5F, -3.25F, 0.15F, 0.4F, 6.5F));
            m.add(Puppet.Part.of(trim, 1.4F, 0.5F, -3.25F, 0.15F, 0.4F, 6.5F));
            m.add(Puppet.Part.of(hull, -1.5F, 0.5F, -3.3F, 3.0F, 0.9F, 1.0F));
            for (float z : new float[]{-1.0F, 1.6F}) {
                m.add(Puppet.Part.of(Blocks.SPRUCE_LOG.defaultBlockState(), -0.14F, 0.5F, z, 0.28F, 5.0F, 0.28F));
                m.add(Puppet.Part.of(Blocks.SPRUCE_LOG.defaultBlockState(), -1.5F, 4.6F, z + 0.05F, 3.0F, 0.15F, 0.15F));
                m.add(Puppet.Part.of(sail, -1.45F, 2.0F, z + 0.2F, 2.9F, 2.6F, 0.06F));
            }
            // the deck catapult
            m.add(Puppet.Part.of(Blocks.OAK_LOG.defaultBlockState(), -0.5F, 0.5F, 2.4F, 1.0F, 0.25F, 0.8F));
            m.add(new Puppet.Part(Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), -0.08F, 0.7F, 2.3F, 0.16F, 0.16F, 1.3F, -30));
        }
        return m;
    }

    // ------------------------------------------------------------------ sailing

    @Override
    public void travel(Vec3 input) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) return;
        if (puppet == null) puppet = new Puppet(model());
        if (isAlive()) puppet.tick(level, this);
        if (role == Role.ARMY && isAlive()) StrategyManager.armyTick(this);
        setDeltaMovement(Vec3.ZERO);
        double top = waterTop(level, getX(), getY() + 1.5, getZ());
        if (!Double.isNaN(top) && Math.abs(top - 0.35 - getY()) > 0.01) setPos(getX(), Mth.lerp(0.3, getY(), top - 0.35), getZ());
        if (mode == Mode.MOVE || mode == Mode.UNLOAD) sail(level);
        if (reload > 0) reload--;
        if (kind() == Kind.WARSHIP) fight(level);
    }

    private void sail(ServerLevel level) {
        Vec3 to = leg < legs.size() ? legs.get(leg) : anchor;
        double dx = to.x - getX(), dz = to.z - getZ(), d = Math.sqrt(dx * dx + dz * dz);
        if (d < 2.5) {
            if (leg < legs.size() - 1) {
                leg++;
                return;
            }
            arrive(level);
            return;
        }
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yaw = getYRot() + Mth.clamp(Mth.wrapDegrees(want - getYRot()), -5F, 5F);
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
        double a = Math.toRadians(yaw), fx = -Math.sin(a), fz = Math.cos(a);
        double sp = kind().speed * (Math.abs(Mth.wrapDegrees(want - yaw)) > 60 ? 0.35 : 1);
        double nx = getX() + fx * sp, nz = getZ() + fz * sp;
        // her bow ahead in water, or she has come to the shore
        double reach = kind() == Kind.WARSHIP ? 4.6 : 3.4;
        if (Double.isNaN(waterTop(level, nx + fx * reach, getY() + 1.5, nz + fz * reach))) {
            // land ahead: a leg cut close, she goes on to the next; at the end, she is at the shore
            if (leg < legs.size() - 1 && d < 8) {
                leg++;
                return;
            }
            if (Double.isNaN(waterTop(level, nx, getY() + 1.5, nz)) || leg >= legs.size() - 1) {
                arrive(level);
                return;
            }
        }
        setPos(nx, getY(), nz);
    }

    private void arrive(ServerLevel level) {
        if (mode == Mode.UNLOAD && ashore != null) unload(level, ashore);
        mode = Mode.HOLD;
        anchor = position();
    }

    /** Puts her soldiers ashore on the land nearest the point they were sent to. */
    /** The land nearest her (within 24 blocks), to put her soldiers on; null if there's none. */
    public Vec3 nearestLand(ServerLevel level) {
        for (int r = 3; r <= 24; r += 2) {
            for (int a = 0; a < 360; a += 15) {
                double x = getX() + Math.cos(Math.toRadians(a)) * r, z = getZ() + Math.sin(Math.toRadians(a)) * r;
                if (!level.hasChunkAt(BlockPos.containing(x, 64, z))) continue;
                if (Double.isNaN(waterTop(level, x, getY() + 3, z))) return SoldierEntity.surface(level, x, z);
            }
        }
        return null;
    }

    private void unload(ServerLevel level, Vec3 to) {
        List<Entity> aboard = new ArrayList<>(getPassengers());
        if (aboard.isEmpty()) return;
        Vec3 land = null;
        Vec3 dir = new Vec3(to.x - getX(), 0, to.z - getZ());
        if (dir.lengthSqr() < 1e-4) dir = getLookAngle();
        dir = dir.normalize();
        for (int i = 2; i < 16 && land == null; i++) {
            Vec3 p = position().add(dir.scale(i));
            if (Double.isNaN(waterTop(level, p.x, getY() + 3, p.z))) land = SoldierEntity.surface(level, p.x, p.z);
        }
        if (land == null) return;
        for (Entity e : aboard) {
            e.stopRiding();
            if (e instanceof SoldierEntity s) {
                Vec3 at = SoldierEntity.surface(level, land.x + random.nextGaussian(), land.z + random.nextGaussian());
                s.snapTo(at.x, at.y, at.z, getYRot(), 0);
                s.order(SoldierEntity.Mode.MOVE, to.distanceTo(land) < 14 ? to : land);
            }
        }
    }

    /** The warship's deck catapult: the building or enemy she was sent at, else anything enemy in range. */
    private void fight(ServerLevel level) {
        Vec3 aim = null;
        if (mode == Mode.ATTACK) {
            if (targetStructure >= 0) aim = StrategyManager.structureAim(targetStructure, faction());
            else if (level.getEntity(targetEntity) instanceof LivingEntity e && e.isAlive() && hostileTo(e)) aim = e.position();
            if (aim == null) mode = Mode.HOLD;
        }
        if (reload > 0) return;
        if (aim == null) aim = StrategyManager.nearestEnemyStructure(faction(), position(), 6, 30);
        if (aim == null) {
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(position(), position()).inflate(28, 12, 28), this::hostileTo)) {
                if (e.distanceTo(this) > 6) {
                    aim = e.position();
                    break;
                }
            }
        }
        if (aim == null) return;
        double d = Math.hypot(aim.x - getX(), aim.z - getZ());
        if (d > 32 || d < 5) return;
        reload = 80 + random.nextInt(20);
        // (from the deck engine, out over the rail towards what she shoots at)
        Vec3 toward = new Vec3(aim.x - getX(), 0, aim.z - getZ()).normalize();
        Vec3 from = position().add(toward.scale(1.8)).add(0, 2.0, 0);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, from.x, from.y, from.z, 6, 0.3, 0.2, 0.3, 0.02);
        BoulderEntity.fire(level, this, from,
                aim.add(random.nextGaussian() * d * 0.05, 0, random.nextGaussian() * d * 0.05), 50, 8, 2.2F);
    }

    // ------------------------------------------------------------------ aboard

    /** A soldier of hers comes aboard, if there's room. */
    public boolean board(SoldierEntity s) {
        if (s.faction() != faction() || getPassengers().size() >= kind().capacity || s.distanceTo(this) > 6) return false;
        s.getNavigation().stop();
        s.mode = SoldierEntity.Mode.HOLD;
        return s.startRiding(this, true, true);
    }

    @Override
    protected boolean canAddPassenger(Entity e) {
        return e instanceof SoldierEntity && getPassengers().size() < kind().capacity;
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    @Override
    protected void positionRider(Entity rider, MoveFunction move) {
        int i = getPassengers().indexOf(rider);
        if (i < 0) return;
        double side = (i % 2 == 0 ? -0.55 : 0.55), along = (kind() == Kind.WARSHIP ? 2.2 : 1.6) - (i / 2) * 1.0;
        double a = Math.toRadians(getYRot()), fx = -Math.sin(a), fz = Math.cos(a);
        // (right of the bow is (−fz, fx))
        double x = getX() + fx * along - fz * side, z = getZ() + fz * along + fx * side;
        move.accept(rider, x, getY() + 0.75, z);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return position().add(0, 1, 0);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (puppet != null) puppet.remove();
        super.remove(reason);
    }

    public boolean owns(Entity e) {
        return puppet != null && puppet.owns(e);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        mode = Mode.HOLD;
        anchor = position();
    }
}
