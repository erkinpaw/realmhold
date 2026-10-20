package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Hex;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The towers: a wooden one takes a ballista only, a stone one eight bowmen; the engines are models that turn and
 * shoot; bowmen behind one another all shoot; how far they shoot shown.
 */
public class TowersClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[towers] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    private static String build(MinecraftServer s, StructureType t, double x, double z) {
        int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        String err = Structures.place(player(s), me(s), t, BlockPos.containing(x, y, z), BlockPos.containing(x, y, z), 0);
        log("build {} at {} {}: {}", t, (int) x, (int) z, err == null ? "ok" : err);
        return err;
    }

    private static Structure last(MinecraftServer s, StructureType t) {
        Structure out = null;
        for (Structure st : StrategyData.get(s).structures.values()) if (st.owner == me(s).id && st.type == t && (out == null || st.id > out.id)) out = st;
        return out;
    }

    private static Zombie zombie(ServerLevel l, double x, double z) {
        Zombie m = EntityTypes.ZOMBIE.create(l, EntitySpawnReason.EVENT);
        Vec3 at = SoldierEntity.surface(l, x, z);
        m.snapTo(at.x, at.y, at.z, 0, 0);
        m.setNoAi(true);
        m.setPersistenceRequired();
        l.addFreshEntity(m);
        return m;
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
            server.waitFor(s -> {
                Structure d = Structures.depositNear(StructureType.DEPOSIT_STONE, new Vec3(cx, 64, cz), 60);
                return d != null && !d.planned;
            }, 20 * 30);
            server.runOnServer(s -> {
                Structure d = Structures.depositNear(StructureType.DEPOSIT_STONE, new Vec3(cx, 64, cz), 60);
                build(s, StructureType.QUARRY, d.origin.getX(), d.origin.getZ());
                build(s, StructureType.TOWER_WOOD, cx + 20, cz - 6);
            });
            server.waitFor(s -> Structures.has(me(s).id, StructureType.QUARRY) && Structures.has(me(s).id, StructureType.TOWER_WOOD), 20 * 40);
            server.runOnServer(s -> build(s, StructureType.TOWER_STONE, cx - 22, cz - 4));
            server.waitFor(s -> Structures.has(me(s).id, StructureType.TOWER_STONE), 20 * 60);
            int[] ids = new int[2];
            server.runOnServer(s -> {
                Structure w = last(s, StructureType.TOWER_WOOD), st = last(s, StructureType.TOWER_STONE);
                ids[0] = w.id;
                ids[1] = st.id;
                boolean woodCatapult = Structures.upgrade(me(s), w.id, 2), woodBallista = Structures.upgrade(me(s), w.id, 1), stoneCatapult = Structures.upgrade(me(s), st.id, 2);
                log("wood: catapult {} ballista {}; stone: catapult {}", woodCatapult, woodBallista, stoneCatapult);
                if (woodCatapult || !woodBallista || !stoneCatapult) throw new AssertionError("the engines' rules");
                // eight bowmen on the stone tower
                int manned = 0;
                for (int i = 0; i < 9; i++) {
                    SoldierEntity a = SoldierEntity.spawn(s.overworld(), UnitType.ARCHER, me(s).id, me(s).color, new Vec3(st.origin.getX() + 4, 0, st.origin.getZ()), UnitEntity.Role.ARMY);
                    if (Structures.man(a, st.id)) manned++;
                    else a.discard();
                }
                log("bowmen on the stone tower: {} (of 9 sent)", manned);
                if (manned != 8) throw new AssertionError("a stone tower holds 8");
            });
            context.waitTicks(40);
            // the engines close up, loaded
            double[] seats = new double[4];
            server.runOnServer(s -> {
                Structure w = StrategyData.get(s).structures.get(ids[0]), st = StrategyData.get(s).structures.get(ids[1]);
                seats[0] = w.origin.getX() + 0.5;
                seats[1] = w.origin.getZ() + 0.5;
                seats[2] = st.origin.getX() + 0.5;
                seats[3] = st.origin.getZ() + 0.5;
            });
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), -1);
                mc.gui.setScreen(new RtsScreen());
                Rts.jump(seats[0], seats[1]);
                Rts.distance = 7;
                Rts.yaw = 300;
            });
            context.waitTicks(30);
            context.takeScreenshot("engine_ballista");
            context.runOnClient(mc -> {
                Rts.jump(seats[2], seats[3]);
                Rts.distance = 8;
                Rts.yaw = 240;
            });
            context.waitTicks(30);
            context.takeScreenshot("engine_catapult");
            // the engines as models, shooting at the dead
            int[] dead = new int[2];
            server.runOnServer(s -> {
                long models = s.overworld().getEntities(EntityTypes.BLOCK_DISPLAY, e -> e.entityTags().contains("s1engine")).size();
                log("engine models: {} blocks", models);
                if (models < 9) throw new AssertionError("no engine models");
            });
            // the ballista at one close under its tower: its bolts go out past the tower's top and reach him
            int[] close = new int[1];
            server.runOnServer(s -> {
                Structure w = StrategyData.get(s).structures.get(ids[0]);
                Zombie z = zombie(s.overworld(), w.origin.getX() + 5.5, w.origin.getZ() + 3.5);
                z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(400);
                z.setHealth(400);
                close[0] = z.getId();
            });
            boolean[] struck = new boolean[1];
            for (int t = 0; t < 60 && !struck[0]; t++) {
                context.waitTicks(2);
                server.runOnServer(s -> {
                    if (!(s.overworld().getEntity(close[0]) instanceof Zombie z) || z.getLastDamageSource() == null) return;
                    var by = z.getLastDamageSource().getDirectEntity();
                    if (by != null && by.entityTags().contains(org.webtrade.strategy1.entity.ShieldZombie.BOLT)) struck[0] = true;
                });
            }
            server.runOnServer(s -> {
                log("the ballista's bolt at one close under the tower: {}", struck[0]);
                if (s.overworld().getEntity(close[0]) != null) s.overworld().getEntity(close[0]).discard();
                if (!struck[0]) throw new AssertionError("the ballista can't shoot close");
                Structure w = StrategyData.get(s).structures.get(ids[0]), st = StrategyData.get(s).structures.get(ids[1]);
                dead[0] = zombie(s.overworld(), w.origin.getX() + 22, w.origin.getZ() + 6).getId();
                dead[1] = zombie(s.overworld(), st.origin.getX() - 26, st.origin.getZ() + 4).getId();
            });
            double[] at = new double[2];
            server.runOnServer(s -> {
                Structure w = StrategyData.get(s).structures.get(ids[0]);
                at[0] = w.origin.getX();
                at[1] = w.origin.getZ();
            });
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), ids[0]);
                mc.gui.setScreen(new RtsScreen());
                Rts.jump(at[0] + 4, at[1]);
                Rts.distance = 16;
                Rts.yaw = 270;
            });
            for (int t = 0; t < 12; t++) {
                context.waitTicks(10);
                if (t == 6) context.takeScreenshot("tower_ballista");
            }
            context.waitTicks(20 * 15);
            server.runOnServer(s -> {
                boolean a = s.overworld().getEntity(dead[0]) instanceof Zombie z && z.isAlive(), b = s.overworld().getEntity(dead[1]) instanceof Zombie z2 && z2.isAlive();
                log("the dead by the wooden tower alive: {}; by the stone tower: {}", a, b);
                if (a || b) throw new AssertionError("the towers didn't kill them");
            });
            // bowmen one behind another: all shoot, none hurt
            List<Integer> bowmen = new java.util.ArrayList<>();
            int[] foe = new int[1];
            server.runOnServer(s -> {
                for (int i = 0; i < 4; i++) {
                    SoldierEntity a = SoldierEntity.spawn(s.overworld(), UnitType.ARCHER, me(s).id, me(s).color, new Vec3(cx + 4 + i * 1.2, 0, cz + 30), UnitEntity.Role.ARMY);
                    a.order(SoldierEntity.Mode.HOLD, a.position());
                    bowmen.add(a.getId());
                }
                Zombie z = zombie(s.overworld(), cx + 30, cz + 30);
                z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(60);
                z.setHealth(60);
                foe[0] = z.getId();
            });
            context.waitTicks(20);
            context.runOnClient(mc -> {
                RtsScreen.choose(new HashSet<>(bowmen), -1);
                mc.gui.setScreen(new RtsScreen());
                Rts.jump(cx + 17, cz + 26);
                Rts.distance = 30;
                Rts.yaw = 0;
            });
            context.waitTicks(20 * 4);
            context.takeScreenshot("tower_reach");
            context.waitTicks(20 * 16);
            server.runOnServer(s -> {
                float hurt = 0;
                for (int id : bowmen) if (s.overworld().getEntity(id) instanceof SoldierEntity a) hurt += a.getMaxHealth() - a.getHealth();
                boolean alive = s.overworld().getEntity(foe[0]) instanceof Zombie z && z.isAlive();
                log("bowmen in a file: their hurt {}, the zombie alive {}", hurt, alive);
                if (hurt > 0 || alive) throw new AssertionError("bowmen in a file");
            });
            // by night their arrows are lit
            server.runCommand("time set 18000");
            server.runOnServer(s -> {
                SoldierEntity a = SoldierEntity.spawn(s.overworld(), UnitType.ARCHER, me(s).id, me(s).color, new Vec3(cx + 4, 0, cz + 40), UnitEntity.Role.ARMY);
                a.order(SoldierEntity.Mode.HOLD, a.position());
                Zombie z = zombie(s.overworld(), cx + 18, cz + 40);
                z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(300);
                z.setHealth(300);
            });
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), -1);
                Rts.jump(cx + 11, cz + 36);
                Rts.distance = 16;
                Rts.yaw = 0;
            });
            boolean[] lit = new boolean[1];
            for (int t = 0; t < 40 && !lit[0]; t++) {
                context.waitTicks(3);
                server.runOnServer(s -> {
                    for (var a : s.overworld().getEntitiesOfClass(net.minecraft.world.entity.projectile.arrow.Arrow.class, new net.minecraft.world.phys.AABB(cx - 10, -64, cz + 30, cx + 30, 320, cz + 50),
                            a -> a.isOnFire() && !a.entityTags().contains(org.webtrade.strategy1.entity.ShieldZombie.BOLT))) lit[0] = true;
                });
            }
            log("fire arrows by night: {}", lit[0]);
            if (!lit[0]) throw new AssertionError("no fire arrows by night");
            context.takeScreenshot("fire_arrows");
            server.runCommand("time set 6000");
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), ids[1]);
                mc.gui.setScreen(new RtsScreen());
                Rts.jump(cx - 22, cz - 4);
                Rts.distance = 60;
                Rts.yaw = 0;
            });
            context.waitTicks(60);
            context.takeScreenshot("tower_stone_reach");
            // the field catapult and the trebuchet close up (their parts' own models)
            server.runOnServer(s -> {
                org.webtrade.strategy1.entity.SiegeEntity.spawn(s.overworld(), org.webtrade.strategy1.entity.SiegeEntity.Kind.CATAPULT, me(s).id, me(s).color, new Vec3(cx + 4, 0, cz + 50), null).setYRot(30);
                org.webtrade.strategy1.entity.SiegeEntity.spawn(s.overworld(), org.webtrade.strategy1.entity.SiegeEntity.Kind.TREBUCHET, me(s).id, me(s).color, new Vec3(cx + 10, 0, cz + 50), null).setYRot(30);
            });
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), -1);
                Rts.jump(cx + 7, cz + 50);
                Rts.distance = 9;
                Rts.yaw = 210;
            });
            context.waitTicks(40);
            context.takeScreenshot("field_engines");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
