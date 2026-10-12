package org.webtrade.strategy1.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Province;
import org.webtrade.strategy1.game.Res;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.StrategyManager;

import java.util.Arrays;
import java.util.Locale;

/** /strategy info — the realms; /strategy turn | give &lt;resource&gt; &lt;amount&gt; — for operators. */
public final class StrategyCommand {

    private StrategyCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) -> dispatcher.register(Commands.literal("strategy")
                .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
                .then(Commands.literal("turn").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> {
                            StrategyManager.forceTurn();
                            ctx.getSource().sendSuccess(() -> Component.literal("Turn advanced"), true);
                            return 1;
                        }))
                .then(Commands.literal("give").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("resource", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Res.values()).map(r -> r.name().toLowerCase(Locale.ROOT)), b))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
                                        .executes(ctx -> give(ctx.getSource(), StringArgumentType.getString(ctx, "resource"),
                                                IntegerArgumentType.getInteger(ctx, "amount"))))))));
    }

    private static int info(CommandSourceStack src) {
        StrategyData d = StrategyManager.data();
        if (d == null) return 0;
        src.sendSuccess(() -> Component.translatable("strategy1.cmd.turn", d.turn), false);
        for (Faction f : d.factions.values()) {
            if (!f.alive) continue;
            int prov = d.owned(f.id).size();
            Province cap = d.province(f.capital);
            src.sendSuccess(() -> Component.empty().append(f.name()).append(Component.translatable("strategy1.cmd.realm", prov,
                    Component.translatable("strategy1.age." + f.age), cap == null ? "?" : cap.name, (int) d.strength(f), f.wars.size())), false);
        }
        return 1;
    }

    private static int give(CommandSourceStack src, String res, int amount) {
        StrategyData d = StrategyManager.data();
        ServerPlayer p = src.getPlayer();
        if (d == null || p == null) return 0;
        Faction f = d.factionOf(p.getUUID());
        if (f == null) return 0;
        Res r;
        try {
            r = Res.valueOf(res.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return 0;
        }
        f.res[r.ordinal()] += amount;
        d.touch();
        src.sendSuccess(() -> Component.literal("+" + amount + " " + r.name()), false);
        return 1;
    }
}
