package org.webtrade.strategy1.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The display entities' setters, for the siege engines' and ships' block models. */
@Mixin(Display.class)
public interface DisplayAccess {

    @Invoker("setTransformation")
    void strategy1$setTransformation(Transformation t);

    @Invoker("setPosRotInterpolationDuration")
    void strategy1$setPosRotInterpolationDuration(int ticks);

    @Invoker("setViewRange")
    void strategy1$setViewRange(float range);
}
