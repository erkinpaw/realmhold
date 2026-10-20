package org.webtrade.strategy1.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;

/** What's left of one of the dead: no legs, its guts trailing, it drags itself on its hands, low and slow. */
public class CrawlerZombie extends DeadZombie {

    public CrawlerZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    public void arm(int night) {
        super.arm(night);
        times(Attributes.MOVEMENT_SPEED, 0.75);
        base(Attributes.MAX_HEALTH, 14 + night);
    }
}
