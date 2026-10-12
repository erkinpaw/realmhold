package org.webtrade.strategy1.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import org.webtrade.strategy1.client.render.SoldierRenderer;
import org.webtrade.strategy1.client.screen.StrategyMapScreen;
import org.webtrade.strategy1.network.Payloads;
import org.webtrade.strategy1.registry.ModContent;

public class Strategy1Client implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModContent.SOLDIER, SoldierRenderer::new);
        // (the engines and ships are drawn by the block displays the server puts on them)
        EntityRendererRegistry.register(ModContent.CATAPULT, net.minecraft.client.renderer.entity.NoopRenderer::new);
        EntityRendererRegistry.register(ModContent.TREBUCHET, net.minecraft.client.renderer.entity.NoopRenderer::new);
        EntityRendererRegistry.register(ModContent.TRANSPORT, net.minecraft.client.renderer.entity.NoopRenderer::new);
        EntityRendererRegistry.register(ModContent.WARSHIP, net.minecraft.client.renderer.entity.NoopRenderer::new);
        EntityRendererRegistry.register(ModContent.BOULDER, ctx -> new net.minecraft.client.renderer.entity.ThrownItemRenderer<>(ctx, 2.5F, false));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.StructureSync.TYPE, (p, ctx) -> ClientState.structures(p));
        Keys.register();
        StrategyHud.init();
        ClientPlayNetworking.registerGlobalReceiver(Payloads.MapSync.TYPE, (p, ctx) -> ClientState.accept(p));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Hud.TYPE, (p, ctx) -> ClientState.hud = p);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            ClientState.clear();
            Rts.disconnected();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (Keys.MAP.consumeClick()) {
                if (mc.player != null && mc.gui.screen() == null) {
                    if (ClientPlayNetworking.canSend(Payloads.RequestSync.TYPE)) ClientPlayNetworking.send(new Payloads.RequestSync());
                    mc.gui.setScreen(new StrategyMapScreen());
                }
            }
            Rts.tick(mc);
            Borders.tick(mc);
        });
    }
}
