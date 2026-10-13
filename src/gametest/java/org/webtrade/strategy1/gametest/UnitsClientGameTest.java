package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.entity.PeasantEntity;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Hex;

import java.util.Set;

/** The healer heals those round him; a fight as it looks; the night, the folk with torches. */
public class UnitsClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[units] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    private static SoldierEntity man(ServerLevel l, Faction f, UnitType u, double x, double z) {
        SoldierEntity s = SoldierEntity.spawn(l, u, f.id, f.color, new Vec3(x, 0, z), UnitEntity.Role.ARMY);
        s.order(SoldierEntity.Mode.HOLD, s.position());
        return s;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("4242")).create()) {
            context.getInput().resizeWindow(1600, 900);
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            context.runOnClient(mc -> {
                mc.options.renderDistance().set(10);
                mc.options.simulationDistance().set(10);
                mc.options.broadcastOptions();
            });
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("time set 6000");
            server.waitFor(s -> StrategyData.get(s).generated && me(s) != null, 20 * 30);
            context.waitFor(mc -> Rts.active(), 20 * 20);
            long[] cap = new long[1];
            server.runOnServer(s -> cap[0] = me(s).capital);
            Hex h = Hex.of(cap[0]);
            double cx = h.centerX(), cz = h.centerZ();
            server.waitFor(s -> Structures.has(me(s).id, StructureType.KEEP), 20 * 30);
            // the healer and the wounded
            int[] ids = new int[3];
            server.runOnServer(s -> {
                SoldierEntity healer = man(s.overworld(), me(s), UnitType.HEALER, cx + 10, cz + 20);
                SoldierEntity hurt = man(s.overworld(), me(s), UnitType.MILITIA, cx + 13, cz + 20);
                hurt.setHealth(5);
                ids[0] = healer.getId();
                ids[1] = hurt.getId();
            });
            context.waitTicks(20 * 8);
            server.runOnServer(s -> {
                float hp = ((SoldierEntity) s.overworld().getEntity(ids[1])).getHealth();
                log("the wounded after 8 s by the healer: {} (was 5)", hp);
                if (hp < 6.5) throw new AssertionError("the healer didn't heal");
            });
            context.waitTicks(20);
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(ids[0]), -1);
                mc.gui.setScreen(new RtsScreen());
                Rts.jump(cx + 11, cz + 16);
                Rts.distance = 14;
                Rts.yaw = 0;
            });
            context.waitTicks(50);
            context.takeScreenshot("units_healer");
            // a fight: swordsmen and bowmen against the dead
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                for (int i = 0; i < 2; i++) man(l, me(s), UnitType.SWORDSMAN, cx - 10 + i * 2, cz + 20);
                for (int i = 0; i < 2; i++) man(l, me(s), UnitType.ARCHER, cx - 9 + i * 2, cz + 16);
                for (int i = 0; i < 3; i++) {
                    Zombie z = EntityTypes.ZOMBIE.create(l, EntitySpawnReason.EVENT);
                    Vec3 at = SoldierEntity.surface(l, cx - 10 + i * 2, cz + 26);
                    z.snapTo(at.x, at.y, at.z, 180, 0);
                    z.setPersistenceRequired();
                    l.addFreshEntity(z);
                }
            });
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), -1);
                Rts.jump(cx - 9, cz + 18);
                Rts.distance = 14;
                Rts.yaw = 30;
            });
            context.waitTicks(30);
            context.takeScreenshot("units_fight");
            // the night: brighter for the strategist, the folk with torches
            server.waitFor(s -> me(s).peasants >= 2, 20 * 40);
            server.runCommand("time set 18000");
            context.waitTicks(40);
            server.runOnServer(s -> {
                int lit = 0, torches = 0;
                for (PeasantEntity p : s.overworld().getEntitiesOfClass(PeasantEntity.class, player(s).getBoundingBox().inflate(300), e -> e.faction() == me(s).id)) {
                    if (p.getOffhandItem().is(net.minecraft.world.item.Items.TORCH)) torches++;
                    // (the light follows them a few steps behind)
                    for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-4, -2, -4), p.blockPosition().offset(4, 3, 4)))
                        if (s.overworld().getBlockState(q).is(Blocks.LIGHT)) lit++;
                }
                log("by night: {} with torches, {} lights by them", torches, lit);
                if (torches == 0 || lit == 0) throw new AssertionError("no torches by night");
            });
            context.runOnClient(mc -> {
                Rts.jump(cx, cz - 4);
                Rts.distance = 34;
                Rts.yaw = 0;
            });
            context.waitTicks(60);
            context.takeScreenshot("units_night");
            server.runCommand("time set 6000");
            context.waitTicks(20);
            server.runOnServer(s -> {
                int lit = 0;
                for (PeasantEntity p : s.overworld().getEntitiesOfClass(PeasantEntity.class, player(s).getBoundingBox().inflate(300), e -> true)) {
                    for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-4, -2, -4), p.blockPosition().offset(4, 3, 4)))
                        if (s.overworld().getBlockState(q).is(Blocks.LIGHT)) lit++;
                }
                log("by day: {} lights left", lit);
                if (lit > 0) throw new AssertionError("torches still lit by day");
            });
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
