package org.webtrade.strategy1;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webtrade.strategy1.command.StrategyCommand;
import org.webtrade.strategy1.game.StrategyManager;
import org.webtrade.strategy1.network.Net;
import org.webtrade.strategy1.registry.ModContent;

/**
 * Realms of the hex map: a strategy played in the world, in third person. The world round the spawn is cut into
 * hexagonal provinces; AI kingdoms and the players' realms grow on them, gather their resources, raise armies, make
 * war and take each other's land.
 */
public class Strategy1 implements ModInitializer {

    public static final String MOD_ID = "strategy1";
    public static final Logger LOGGER = LoggerFactory.getLogger("Strategy1");

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModContent.register();
        Net.register();
        StrategyManager.init();
        StrategyCommand.register();
        LOGGER.info("Strategy1 initialized");
    }
}
