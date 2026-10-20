package org.webtrade.strategy1.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * One of the dead swollen with the plague, boils all over him: slow and hard to bring down, he spits bile at the living
 * from a little way off (a puddle of poison where it lands), and bursts into a cloud of it when he falls. The dead
 * aren't harmed by poison.
 */
public class PlagueZombie extends DeadZombie {

    /** How near he spits from (no nearer than he'd rather bite), and how often. */
    private static final double SPIT_MIN = 3.5, SPIT_MAX = 13;
    private static final int RELOAD = 90, WIND_UP = 10;
    private int reload = 40;

    public PlagueZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        goalSelector.addGoal(1, new Spit());
    }

    @Override
    public void arm(int night) {
        super.arm(night);
        base(Attributes.SCALE, 1.25);
        times(Attributes.MOVEMENT_SPEED, 0.8);
        base(Attributes.MAX_HEALTH, 34 + night * 4);
    }

    @Override
    public void tick() {
        super.tick();
        if (reload > 0) reload--;
        if (tickCount % 12 == 0 && level() instanceof ServerLevel level && isAlive())
            level.sendParticles(ParticleTypes.ITEM_SLIME, getX(), getY() + getBbHeight() * 0.6, getZ(), 2, 0.3, 0.3, 0.3, 0.02);
    }

    @Override
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel level) {
            AreaEffectCloud cloud = new AreaEffectCloud(level, getX(), getY(), getZ());
            cloud.setRadius(3.5F);
            cloud.setRadiusPerTick(-0.01F);
            cloud.setDuration(160);
            cloud.setWaitTime(0);
            cloud.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1));
            level.addFreshEntity(cloud);
            level.sendParticles(ParticleTypes.ITEM_SLIME, getX(), getY() + 1, getZ(), 30, 0.6, 0.6, 0.6, 0.15);
            level.playSound(null, blockPosition(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.6F, 0.6F);
        }
        super.die(source);
    }

    /** At one of the living a little way off, in sight: he stops, gathers it up and spits. */
    private final class Spit extends Goal {
        private int windUp;

        Spit() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private boolean inReach(LivingEntity t) {
            if (t == null || !t.isAlive()) return false;
            double d = distanceTo(t);
            return d >= SPIT_MIN && d <= SPIT_MAX && hasLineOfSight(t);
        }

        @Override
        public boolean canUse() {
            return reload <= 0 && inReach(getTarget());
        }

        @Override
        public boolean canContinueToUse() {
            return windUp > 0 && getTarget() != null && getTarget().isAlive();
        }

        @Override
        public void start() {
            windUp = WIND_UP;
            getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = getTarget();
            if (t == null) return;
            getLookControl().setLookAt(t, 30, 30);
            if (--windUp > 0) return;
            reload = RELOAD;
            swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (level() instanceof ServerLevel level) {
                Vec3 look = Vec3.directionFromRotation(0, getYHeadRot()).scale(0.6);
                Vec3 from = getEyePosition().add(look).subtract(0, 0.2, 0);
                // (where it'll be when the bile gets there)
                Vec3 to = t.position().add(t.getDeltaMovement().scale(distanceTo(t) * 0.9));
                BoulderEntity.spit(level, PlagueZombie.this, from, to);
            }
        }
    }
}
