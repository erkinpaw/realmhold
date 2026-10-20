package org.webtrade.strategy1.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.webtrade.strategy1.game.StrategyManager;
import org.webtrade.strategy1.registry.ModContent;
import org.webtrade.strategy1.registry.Parts;

import java.util.List;

/**
 * A catapult or a trebuchet: slow to roll, but it throws stones over walls. Sent at an enemy building it rolls into
 * range and breaks it; standing, it throws at whatever enemy building (else enemy troops) comes into its range.
 */
public class SiegeEntity extends UnitEntity {

    public enum Kind {
        CATAPULT(120, 0.17, 6, 34, 60, 9, 2.5F, 90),
        TREBUCHET(170, 0.12, 12, 56, 140, 12, 3F, 150);

        public final double health, speed, min, max;
        public final float structureDamage, unitDamage, radius;
        public final int reload;

        Kind(double health, double speed, double min, double max, float structureDamage, float unitDamage, float radius, int reload) {
            this.health = health;
            this.speed = speed;
            this.min = min;
            this.max = max;
            this.structureDamage = structureDamage;
            this.unitDamage = unitDamage;
            this.radius = radius;
            this.reload = reload;
        }

        public String key() {
            return "strategy1.siege." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum Mode {HOLD, MOVE, ATTACK}

    public Mode mode = Mode.HOLD;
    public Vec3 anchor = Vec3.ZERO;
    /** What it is sent against: a building (id) or an entity (id). */
    public int targetStructure = -1, targetEntity = -1;
    private int reload = 40, repath;
    private Puppet puppet;

    public SiegeEntity(EntityType<? extends SiegeEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 120).add(Attributes.MOVEMENT_SPEED, 0.15)
                .add(Attributes.FOLLOW_RANGE, 64).add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    public Kind kind() {
        return getType() == ModContent.TREBUCHET ? Kind.TREBUCHET : Kind.CATAPULT;
    }

    public static SiegeEntity spawn(ServerLevel level, Kind kind, int faction, int color, Vec3 at, java.util.UUID commander) {
        SiegeEntity s = (kind == Kind.TREBUCHET ? ModContent.TREBUCHET : ModContent.CATAPULT).create(level, EntitySpawnReason.MOB_SUMMONED);
        if (s == null) return null;
        s.setRealm(faction, color);
        s.commander = commander;
        s.getAttribute(Attributes.MAX_HEALTH).setBaseValue(kind.health);
        s.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(kind.speed);
        s.setHealth(s.getMaxHealth());
        s.setCustomName(Component.translatable(kind.key()).withColor(color));
        Vec3 p = SoldierEntity.surface(level, at.x, at.z);
        s.snapTo(p.x, p.y, p.z, level.getRandom().nextFloat() * 360, 0);
        s.anchor = p;
        level.addFreshEntity(s);
        return s;
    }

    public void order(Mode mode, Vec3 at, int structure, int entity) {
        this.mode = mode;
        if (at != null) anchor = at;
        targetStructure = structure;
        targetEntity = entity;
        getNavigation().stop();
        repath = 0;
    }

    // ------------------------------------------------------------------ the look

    private List<Puppet.Part> model() {
        BlockState flag = Dyes.wool(color()).defaultBlockState();
        if (kind() == Kind.CATAPULT) {
            return List.of(
                    part(Parts.SIEGE_CATAPULT, Parts.at(Parts.SIEGE_CATAPULT), 0),
                    part(Parts.SIEGE_CATAPULT_ARM, new Vector3f(-0.1F, 0.62F, -1.0F), -28),
                    Puppet.Part.of(flag, 0.62F, 1.7F, 0.1F, 0.05F, 0.4F, 0.5F));
        }
        // (the arm in two lengths, the second on from the end of the first)
        Vector3f arm = new Vector3f(-0.12F, 3.35F, -2.8F);
        Vector3f arm2 = new org.joml.Quaternionf().rotationX((float) Math.toRadians(22)).transform(new Vector3f(0, 0, 2.0F)).add(arm);
        return List.of(
                part(Parts.TREBUCHET_BASE, Parts.at(Parts.TREBUCHET_BASE), 0),
                part(Parts.TREBUCHET_FRAME, Parts.at(Parts.TREBUCHET_FRAME), 0),
                part(Parts.TREBUCHET_BRACE, new Vector3f(-1.05F, 0.55F, -1.2F), -55),
                part(Parts.TREBUCHET_BRACE, new Vector3f(0.8F, 0.55F, -1.2F), -55),
                part(Parts.TREBUCHET_ARM, arm, 22),
                part(Parts.TREBUCHET_ARM, arm2, 22),
                Puppet.Part.of(flag, 0.88F, 3.5F, -0.1F, 0.05F, 0.5F, 0.6F));
    }

    /** One of the engine's parts (its own model, at the world's scale), there and tilted so (degrees, round x). */
    private static Puppet.Part part(int part, Vector3f at, float tilt) {
        return new Puppet.Part(Parts.state(part), at.x, at.y, at.z, 1, 1, 1, tilt);
    }

    // ------------------------------------------------------------------ its day

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) return;
        if (puppet == null) puppet = new Puppet(model());
        if (isAlive()) puppet.tick(level, this);
        if (reload > 0) reload--;
        if (role == Role.ARMY && isAlive()) StrategyManager.armyTick(this);
        Kind k = kind();
        Vec3 aim = null;
        if (mode == Mode.ATTACK) {
            if (targetStructure >= 0) aim = StrategyManager.structureAim(targetStructure, faction());
            else if (level.getEntity(targetEntity) instanceof LivingEntity e && e.isAlive() && hostileTo(e)) aim = e.position();
            if (aim == null) order(Mode.HOLD, position(), -1, -1);
        }
        if (mode == Mode.MOVE) {
            if (position().distanceTo(anchor) < 2.5) order(Mode.HOLD, position(), -1, -1);
            else walk(anchor);
            return;
        }
        if (aim == null && mode == Mode.HOLD && tickCount % 20 == 0) {
            // standing: the nearest enemy building in range, else enemy troops
            aim = StrategyManager.nearestEnemyStructure(faction(), position(), k.min, k.max);
            if (aim == null) {
                LivingEntity best = null;
                double bestD = Double.MAX_VALUE;
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(position(), position()).inflate(k.max, 16, k.max), this::hostileTo)) {
                    double d = e.distanceTo(this);
                    if (d >= k.min && d <= k.max && d < bestD) {
                        bestD = d;
                        best = e;
                    }
                }
                if (best != null) aim = best.position();
            }
            if (aim != null) holdAim = aim;
        } else if (mode == Mode.HOLD) aim = holdAim;
        if (aim == null) return;
        double d = Math.hypot(aim.x - getX(), aim.z - getZ());
        if (mode == Mode.ATTACK && d > k.max - 2) {
            walk(aim);
            return;
        }
        if (d > k.max || d < k.min) {
            if (mode == Mode.HOLD) holdAim = null;
            return;
        }
        getNavigation().stop();
        float yaw = (float) Math.toDegrees(Math.atan2(-(aim.x - getX()), aim.z - getZ()));
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
        if (reload == 0) {
            reload = k.reload + random.nextInt(20);
            double spread = d * 0.06;
            Vec3 at = aim.add(random.nextGaussian() * spread, 0, random.nextGaussian() * spread);
            Vec3 from = position().add(0, kind() == Kind.TREBUCHET ? 3.5 : 1.6, 0);
            BoulderEntity.fire(level, this, from, at, k.structureDamage, k.unitDamage, k.radius);
            level.sendParticles(ParticleTypes.CLOUD, from.x, from.y, from.z, 4, 0.3, 0.2, 0.3, 0.02);
        }
    }

    private Vec3 holdAim;

    private void walk(Vec3 to) {
        if (--repath > 0 && getNavigation().isInProgress()) return;
        repath = 20;
        Vec3 goal = to;
        if (position().distanceTo(to) > 30 && level() instanceof ServerLevel sl) {
            Vec3 dir = to.subtract(position()).normalize().scale(24);
            goal = SoldierEntity.surface(sl, getX() + dir.x, getZ() + dir.z);
        }
        getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (puppet != null) puppet.remove();
        super.remove(reason);
    }

    public boolean owns(net.minecraft.world.entity.Entity e) {
        return puppet != null && puppet.owns(e);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putDouble("AX", anchor.x);
        out.putDouble("AY", anchor.y);
        out.putDouble("AZ", anchor.z);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        anchor = new Vec3(in.getDoubleOr("AX", getX()), in.getDoubleOr("AY", getY()), in.getDoubleOr("AZ", getZ()));
        mode = Mode.HOLD;
    }
}
