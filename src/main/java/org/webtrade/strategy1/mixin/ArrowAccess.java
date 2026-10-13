package org.webtrade.strategy1.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.StrategyManager;

/** A realm's arrows fly past its own and its allies (bowmen behind one another can shoot). */
@Mixin(AbstractArrow.class)
public abstract class ArrowAccess {

    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void strategy1$pastFriends(Entity e, CallbackInfoReturnable<Boolean> cir) {
        StrategyData d = StrategyManager.data();
        if (d == null) return;
        if (((AbstractArrow) (Object) this).getOwner() instanceof UnitEntity by && e instanceof UnitEntity u && !d.hostile(by.faction(), u.faction())) {
            cir.setReturnValue(false);
        }
    }
}
