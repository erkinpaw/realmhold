package org.webtrade.strategy1.entity;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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
 * One of the dead with an iron-headed log for a ram: big, armoured, deaf to the soldiers round him, he goes for the
 * realm's gates (walls and towers if there are none) and batters them down, a heavy blow every two seconds.
 */
public class RamZombie extends DeadZombie {

    private static final int BEAT = 40;
    private int night = 1;

    public RamZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        goalSelector.addGoal(1, new Batter());
    }

    @Override
    public void arm(int night) {
        super.arm(night);
        this.night = night;
        base(Attributes.SCALE, 1.15);
        base(Attributes.KNOCKBACK_RESISTANCE, 1.0);
        base(Attributes.ARMOR, 8);
        times(Attributes.MOVEMENT_SPEED, 0.85);
        base(Attributes.MAX_HEALTH, 40 + night * 8);
        setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        setDropChance(EquipmentSlot.HEAD, 0);
    }

    /** How hard his ram comes down on a building. */
    public float blow() {
        return 45 + night * 5;
    }

    /** To the nearest of the realm's gates (else its walls and towers, else anything of it); there, the ram. */
    private final class Batter extends Goal {
        private int repath, beat = BEAT / 2;
        private Structure target;

        Batter() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return level() instanceof ServerLevel && StrategyManager.data() != null && (target = nearest()) != null;
        }

        @Override
        public boolean canContinueToUse() {
            return target != null && StrategyManager.data() != null && StrategyManager.data().structures.containsKey(target.id);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            ServerLevel level = (ServerLevel) level();
            // at a gate, a wall or a building: the ram
            Structure s = Structures.touching(getBoundingBox().inflate(1.6), Faction.NEUTRAL);
            if (s != null) {
                getNavigation().stop();
                Vec3 c = s.center();
                getLookControl().setLookAt(c.x, getEyeY(), c.z);
                if (--beat > 0) return;
                beat = BEAT;
                swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                Structures.strike(RamZombie.this, s.id, blow());
                level.playSound(null, blockPosition(), SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 1.4F, 0.7F);
                Vec3 at = position().add(Vec3.directionFromRotation(0, getYRot()).scale(1.6)).add(0, 1, 0);
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()), at.x, at.y, at.z, 16, 0.4, 0.4, 0.4, 0.1);
                return;
            }
            if (tickCount % 100 == 0) target = nearest();
            if (target == null) return;
            if (--repath > 0 && getNavigation().isInProgress()) return;
            repath = 20;
            AABB b = target.box();
            double x = Math.max(b.minX, Math.min(b.maxX, getX())), z = Math.max(b.minZ, Math.min(b.maxZ, getZ()));
            Vec3 goal = new Vec3(x, b.minY, z);
            if (position().distanceTo(goal) > 30) {
                Vec3 dir = goal.subtract(position()).normalize().scale(24);
                goal = SoldierEntity.surface(level, getX() + dir.x, getZ() + dir.z);
            }
            getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0);
        }

        private Structure nearest() {
            StrategyData d = StrategyManager.data();
            Structure best = null;
            double bestD = 120 * 120;
            for (Structure s : d.structures.values()) {
                if (s.planned || s.maxHp <= 0 || s.type.deposit() || s.type == StructureType.PATH || s.progress == 0) continue;
                Faction f = d.faction(s.owner);
                if (f == null || !f.player()) continue;
                double dist = s.center().distanceToSqr(position());
                // (the gates above all, then what keeps the dead out)
                if (s.type == StructureType.GATE) dist *= 0.2;
                else if (s.type.wall() || s.type.tower()) dist *= 0.6;
                if (dist < bestD) {
                    bestD = dist;
                    best = s;
                }
            }
            return best;
        }
    }
}
