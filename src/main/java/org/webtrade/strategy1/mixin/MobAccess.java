package org.webtrade.strategy1.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A mob's goals, to give the night's dead their own (march on the keep, fight the realm's people, break its walls). */
@Mixin(Mob.class)
public interface MobAccess {

    @Accessor("goalSelector")
    GoalSelector strategy1$goals();

    @Accessor("targetSelector")
    GoalSelector strategy1$targets();
}
