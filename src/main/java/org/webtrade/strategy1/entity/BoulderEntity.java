package org.webtrade.strategy1.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.game.StrategyManager;
import org.webtrade.strategy1.registry.ModContent;

/**
 * A stone from a catapult, a trebuchet or a warship: it flies a high arc to the point aimed at (and lands there, give
 * or take), breaks into the buildings there and hurts the enemies round it.
 */
public class BoulderEntity extends ThrowableItemProjectile {

    private Vec3 from = Vec3.ZERO, to = Vec3.ZERO;
    private int flight = 1, age;
    private double height;
    /** Damage to buildings and to those standing near; the radius it hurts in. */
    private float structureDamage, unitDamage, radius;
    private UnitEntity shooter;
    /** A plague-bearer's bile instead (it poisons where it lands): who spat it. */
    private net.minecraft.world.entity.Mob spitter;

    public BoulderEntity(EntityType<? extends BoulderEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    @Override
    protected Item getDefaultItem() {
        return Items.COBBLESTONE;
    }

    /** Throws a stone from a machine to a point. */
    public static void fire(ServerLevel level, UnitEntity shooter, Vec3 from, Vec3 to, float structureDamage, float unitDamage, float radius) {
        BoulderEntity b = ModContent.BOULDER.create(level, EntitySpawnReason.TRIGGERED);
        if (b == null) return;
        b.shooter = shooter;
        b.from = from;
        b.to = to;
        double d = from.distanceTo(to);
        b.flight = Math.max(12, (int) (d * 1.3));
        b.height = Math.max(5, d * 0.4);
        b.structureDamage = structureDamage;
        b.unitDamage = unitDamage;
        b.radius = radius;
        b.setOwner(shooter);
        b.snapTo(from.x, from.y, from.z, 0, 0);
        b.setDeltaMovement(b.at(1).subtract(from));
        level.addFreshEntity(b);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.4F, 0.5F);
    }

    /** A plague-bearer spits bile at a point: a low arc, a puddle of poison where it lands. */
    public static void spit(ServerLevel level, net.minecraft.world.entity.Mob spitter, Vec3 from, Vec3 to) {
        BoulderEntity b = ModContent.BILE.create(level, EntitySpawnReason.TRIGGERED);
        if (b == null) return;
        b.setItem(new net.minecraft.world.item.ItemStack(Items.SLIME_BALL));
        b.spitter = spitter;
        b.from = from;
        b.to = to;
        double d = from.distanceTo(to);
        b.flight = Math.max(8, (int) (d * 1.1));
        b.height = Math.max(1.5, d * 0.2);
        b.unitDamage = 3;
        b.radius = 1.6F;
        b.setOwner(spitter);
        b.snapTo(from.x, from.y, from.z, 0, 0);
        b.setDeltaMovement(b.at(1).subtract(from));
        level.addFreshEntity(b);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 1.4F, 0.6F);
    }

    private Vec3 at(int t) {
        double f = Math.min(1, t / (double) flight);
        Vec3 p = from.lerp(to, f);
        return p.add(0, 4 * height * f * (1 - f), 0);
    }

    @Override
    public void tick() {
        xo = getX();
        yo = getY();
        zo = getZ();
        if (!(level() instanceof ServerLevel level)) {
            setPos(position().add(getDeltaMovement()));
            return;
        }
        age++;
        Vec3 next = at(age);
        setDeltaMovement(next.subtract(position()));
        setPos(next);
        if (age % 2 == 0) level.sendParticles(spitter != null ? ParticleTypes.ITEM_SLIME : ParticleTypes.SMOKE, getX(), getY(), getZ(), 1, 0.1, 0.1, 0.1, 0);
        if (age >= flight) {
            if (spitter != null) splash(level);
            else impact(level);
        }
        if (age > flight + 40) discard();
    }

    private void impact(ServerLevel level) {
        level.sendParticles(ParticleTypes.EXPLOSION, to.x, to.y + 0.5, to.z, 2, 0.6, 0.4, 0.6, 0);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, to.x, to.y + 0.5, to.z, 6, 0.8, 0.3, 0.8, 0.02);
        level.playSound(null, to.x, to.y, to.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.2F, 0.8F);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(to, to).inflate(radius))) {
            if (shooter != null ? !shooter.hostileTo(e) : !(e instanceof net.minecraft.world.entity.monster.Enemy)) continue;
            e.hurtServer(level, damageSources().thrown(this, shooter), unitDamage);
        }
        if (shooter != null) StrategyManager.impact(level, to, shooter.faction(), structureDamage, radius + 1.5F);
        discard();
    }

    /** The bile lands: the living it splashes hurt, and a puddle of poison there a while (the dead don't mind it). */
    private void splash(ServerLevel level) {
        level.sendParticles(ParticleTypes.ITEM_SLIME, to.x, to.y + 0.3, to.z, 20, 0.6, 0.2, 0.6, 0.1);
        level.playSound(null, to.x, to.y, to.z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.2F, 0.7F);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(to, to).inflate(radius),
                x -> !(x instanceof net.minecraft.world.entity.monster.Enemy) && !(x instanceof net.minecraft.world.entity.player.Player))) {
            e.hurtServer(level, damageSources().mobProjectile(this, spitter), unitDamage);
            e.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 80, 0));
        }
        net.minecraft.world.entity.AreaEffectCloud cloud = new net.minecraft.world.entity.AreaEffectCloud(level, to.x, to.y, to.z);
        cloud.setRadius(1.8F);
        cloud.setRadiusPerTick(-0.01F);
        cloud.setDuration(80);
        cloud.setWaitTime(0);
        cloud.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 60, 0));
        level.addFreshEntity(cloud);
        discard();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
