package org.webtrade.strategy1.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;

/** One of the dead twice as big as the rest: slow to kill, a heavy blow, and walls go down before it. */
public class GiantZombie extends DeadZombie {

    public GiantZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    public void arm(int night) {
        super.arm(night);
        base(Attributes.SCALE, 2.0);
        base(Attributes.KNOCKBACK_RESISTANCE, 0.8);
        base(Attributes.ATTACK_DAMAGE, 7 + night);
        times(Attributes.MOVEMENT_SPEED, 0.95);
        base(Attributes.MAX_HEALTH, 60 + night * 10);
    }
}
