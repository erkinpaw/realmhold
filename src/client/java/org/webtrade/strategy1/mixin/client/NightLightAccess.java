package org.webtrade.strategy1.mixin.client;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.strategy1.client.Rts;

/** The strategist sees by night: the land a little lit, as by a moon (torches and fires still the brightest). */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class NightLightAccess {

    @Inject(method = "extract", at = @At("TAIL"))
    private void strategy1$moonlight(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        if (!Rts.active()) return;
        state.nightVisionEffectIntensity = Math.max(state.nightVisionEffectIntensity, Rts.NIGHT_LIGHT);
    }
}
