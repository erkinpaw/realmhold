package org.webtrade.strategy1.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.StrategyManager;

import java.util.EnumSet;

/**
 * One of the dead with a keg of powder on his head: he runs for the nearest wall, tower or building of the realm's,
 * lights the fuse there and blows a hole in it. Shot down on the way, the keg goes off where he falls, weaker.
 */
public class BomberZombie extends Zombie {

    private static final EntityDataAccessor<Integer> DATA_FUSE = SynchedEntityData.defineId(BomberZombie.class, EntityDataSerializers.INT);
    private static final int FUSE = 30;
    private boolean blown;

    public BomberZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FUSE, -1);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        goalSelector.addGoal(1, new Bomb());
    }

    public void arm(int night) {
        setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TNT));
        for (EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0);
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.2);
        var hp = getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(14 + night);
            setHealth(getMaxHealth());
        }
    }

    /** Ticks till it goes off, or -1 (not lit). */
    public int fuse() {
        return entityData.get(DATA_FUSE);
    }

    /** The keg goes off: walls and buildings round it broken, everyone near hurt. */
    private void blast(ServerLevel level, boolean full) {
        if (blown) return;
        blown = true;
        Vec3 at = position().add(0, 1, 0);
        level.sendParticles(full ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, at.x, at.y, at.z, full ? 1 : 3, 0.3, 0.3, 0.3, 0);
        level.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, full ? 2.5F : 1.5F, full ? 0.8F : 1.1F);
        Structures.impact(level, at, Faction.NEUTRAL, full ? 380 : 90, full ? 5 : 3);
        double r = full ? 4.5 : 3;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(r), x -> x != this && x.isAlive() && !(x instanceof net.minecraft.world.entity.player.Player)))
            e.hurtServer(level, damageSources().explosion(this, this), full ? 16 : 8);
    }

    @Override
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel level) blast(level, false);
        super.die(source);
    }

    @Override
    protected boolean convertsInWater() {
        return false;
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    /** To the nearest of the realm's walls, towers and buildings; there, the fuse. */
    private final class Bomb extends Goal {
        private int repath;
        private Structure target;

        Bomb() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return level() instanceof ServerLevel && StrategyManager.data() != null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            ServerLevel level = (ServerLevel) level();
            int fuse = fuse();
            if (fuse >= 0) {
                getNavigation().stop();
                if (fuse % 6 == 0) level.sendParticles(ParticleTypes.SMOKE, getX(), getY() + 2.2, getZ(), 3, 0.1, 0.1, 0.1, 0.02);
                if (fuse == 0) {
                    blast(level, true);
                    discard();
                    return;
                }
                entityData.set(DATA_FUSE, fuse - 1);
                return;
            }
            // at a wall or a building: light it
            if (tickCount % 5 == 0) {
                Structure s = Structures.touching(getBoundingBox().inflate(1.4), Faction.NEUTRAL);
                if (s != null) {
                    entityData.set(DATA_FUSE, FUSE);
                    level.playSound(null, blockPosition(), SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 1.5F, 1F);
                    return;
                }
            }
            if (target == null || !StrategyManager.data().structures.containsKey(target.id) || tickCount % 100 == 0) target = nearest();
            if (target == null) return;
            if (--repath > 0 && getNavigation().isInProgress()) return;
            repath = 20;
            // to the nearest point of it
            AABB b = target.box();
            double x = Math.max(b.minX, Math.min(b.maxX, getX())), z = Math.max(b.minZ, Math.min(b.maxZ, getZ()));
            Vec3 goal = new Vec3(x, b.minY, z);
            if (position().distanceTo(goal) > 30) {
                Vec3 dir = goal.subtract(position()).normalize().scale(24);
                goal = SoldierEntity.surface(level, getX() + dir.x, getZ() + dir.z);
            }
            getNavigation().moveTo(goal.x, goal.y, goal.z, 1.15);
        }

        private Structure nearest() {
            StrategyData d = StrategyManager.data();
            Structure best = null;
            double bestD = 90 * 90;
            for (Structure s : d.structures.values()) {
                if (s.planned || s.maxHp <= 0 || s.type.deposit() || s.type == StructureType.PATH || s.progress == 0) continue;
                Faction f = d.faction(s.owner);
                if (f == null || !f.player()) continue;
                double dist = s.center().distanceToSqr(position());
                // (walls and towers first: what keeps the dead out)
                if (s.type.wall() || s.type.tower()) dist *= 0.6;
                if (dist < bestD) {
                    bestD = dist;
                    best = s;
                }
            }
            return best;
        }
    }
}
