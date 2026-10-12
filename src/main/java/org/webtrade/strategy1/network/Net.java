package org.webtrade.strategy1.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.webtrade.strategy1.game.Actions;
import org.webtrade.strategy1.game.StrategyManager;

public final class Net {

    private Net() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().registerLarge(Payloads.MapSync.TYPE, Payloads.MapSync.CODEC, 4 * 1024 * 1024);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.Hud.TYPE, Payloads.Hud.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.Action.TYPE, Payloads.Action.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.UnitOrder.TYPE, Payloads.UnitOrder.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.SetRts.TYPE, Payloads.SetRts.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.Jump.TYPE, Payloads.Jump.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.RequestSync.TYPE, Payloads.RequestSync.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(Payloads.StructureSync.TYPE, Payloads.StructureSync.CODEC, 1024 * 1024);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.Build.TYPE, Payloads.Build.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.StructureAction.TYPE, Payloads.StructureAction.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Build.TYPE, (p, ctx) -> Actions.build(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.StructureAction.TYPE, (p, ctx) -> Actions.structure(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Action.TYPE, (p, ctx) -> Actions.handle(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.UnitOrder.TYPE, (p, ctx) -> Actions.order(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.SetRts.TYPE, (p, ctx) -> StrategyManager.setRts(ctx.player(), p.on()));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Jump.TYPE, (p, ctx) -> StrategyManager.jump(ctx.player(), p.x(), p.z()));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.RequestSync.TYPE, (p, ctx) -> StrategyManager.sendMap(ctx.player()));
    }
}
