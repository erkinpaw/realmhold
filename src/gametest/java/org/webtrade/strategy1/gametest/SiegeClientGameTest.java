package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.game.Actions;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Mode;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.game.Waves;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The siege of the dead: a world made in the siege mode, the keep walled and towered (a ballista, a catapult and a
 * fire brazier on the towers, archers on them), a few soldiers; then night falls and the first waves come.
 */
public class SiegeClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[siege] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    private static void look(ClientGameTestContext context, double x, double z, float distance, float yaw) {
        context.runOnClient(mc -> {
            Rts.jump(x, z);
            Rts.distance = distance;
            Rts.yaw = yaw;
        });
        context.waitTicks(60);
    }

    private static String build(MinecraftServer s, StructureType t, double x, double z, double x2, double z2) {
        int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        String err = Structures.place(player(s), me(s), t, BlockPos.containing(x, y, z), BlockPos.containing(x2, y, z2), 0);
        log("build {} at {} {}: {}", t, (int) x, (int) z, err == null ? "ok" : err);
        return err;
    }

    private static void report(MinecraftServer s, String when) {
        StrategyData d = StrategyData.get(s);
        Faction f = me(s);
        int hurt = 0, gone = 0;
        StringBuilder towers = new StringBuilder();
        for (Structure st : d.structures.values()) {
            if (st.owner != f.id) continue;
            if (st.hp < st.maxHp) hurt++;
            if (st.type.tower()) towers.append(st.type).append(" engine ").append(st.engine).append(" archers ").append(st.archers).append(" hp ").append(st.hp).append("; ");
        }
        long soldiers = s.overworld().getEntitiesOfClass(SoldierEntity.class, player(s).getBoundingBox().inflate(300), e -> e.faction() == f.id).size();
        log("{}: wave {} monsters {} provinces {} alive {} soldiers {} peasants {} damaged structures {} towers [{}]", when, d.wave, Waves.alive(),
                d.owned(f.id).size(), f.alive, soldiers, f.peasants, hurt, towers);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        Mode.Pending.mode = Mode.SIEGE;
        Mode.Pending.realms = 0;
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
            server.runCommand("time set 6000");
            server.runCommand("weather clear");
            server.runCommand("difficulty normal");
            server.waitFor(s -> StrategyData.get(s).generated && me(s) != null, 20 * 30);
            context.waitFor(mc -> Rts.active(), 20 * 20);
            long[] capital = new long[1];
            server.runOnServer(s -> {
                capital[0] = me(s).capital;
                log("mode {} realms {}", StrategyData.get(s).mode, StrategyData.get(s).factions.size());
            });
            Hex home = Hex.of(capital[0]);
            double cx = home.centerX(), cz = home.centerZ();
            look(context, cx, cz, 60, 200);
            server.waitFor(s -> Structures.has(me(s).id, StructureType.KEEP), 20 * 30);
            // the defences
            server.runOnServer(s -> {
                for (double[] o : new double[][]{{-14, 22}, {-24, 10}, {24, 10}, {-14, 32}, {14, 34}})
                    if (!Structures.has(me(s).id, StructureType.BARRACKS)) build(s, StructureType.BARRACKS, cx + o[0], cz + o[1], 0, 0);
                build(s, StructureType.QUARRY, cx + 40, cz + 40, 0, 0);
                build(s, StructureType.BLACKSMITH, cx + 14, cz + 22, 0, 0);
                build(s, StructureType.TOWER_WOOD, cx - 16, cz - 8, 0, 0);
                build(s, StructureType.WALL_WOOD, cx - 13, cz - 14, cx + 13, cz - 14);
                build(s, StructureType.WALL_WOOD, cx - 18, cz - 4, cx - 18, cz + 16);
            });
            context.waitTicks(20 * 10);
            server.runOnServer(s -> {
                // (the quarry's deposit may be far: the stone towers want any quarry of the realm's)
                if (!Structures.has(me(s).id, StructureType.QUARRY)) {
                    Structure dep = Structures.depositNear(StructureType.DEPOSIT_STONE, new Vec3(cx, 64, cz), 300);
                    if (dep != null) for (int[] o : new int[][]{{8, 0}, {-8, 0}, {0, 8}, {0, -8}}) if (!Structures.has(me(s).id, StructureType.QUARRY))
                        build(s, StructureType.QUARRY, dep.origin.getX() + o[0], dep.origin.getZ() + o[1], 0, 0);
                }
            });
            context.waitTicks(20 * 12);
            server.runOnServer(s -> {
                build(s, StructureType.TOWER_STONE, cx + 16, cz - 8, 0, 0);
                build(s, StructureType.TOWER_STONE, cx + 4, cz - 22, 0, 0);
            });
            context.waitTicks(20 * 25);
            server.runOnServer(s -> {
                Faction f = me(s);
                int e = 1;
                for (Structure st : StrategyData.get(s).structures.values()) {
                    if (st.owner == f.id && st.type.tower() && st.built()) Actions.structure(player(s), new Payloads.StructureAction(st.id, Payloads.StructureAction.UPGRADE, e++));
                }
                Structure barracks = Structures.nearest(f.id, StructureType.BARRACKS, new Vec3(cx, 64, cz));
                if (barracks != null) {
                    for (UnitType u : new UnitType[]{UnitType.ARCHER, UnitType.ARCHER, UnitType.ARCHER, UnitType.ARCHER, UnitType.SWORDSMAN, UnitType.SWORDSMAN, UnitType.KNIGHT})
                        Actions.structure(player(s), new Payloads.StructureAction(barracks.id, Payloads.StructureAction.MAKE, u.ordinal()));
                }
            });
            context.waitTicks(40);
            server.runOnServer(s -> {
                Faction f = me(s);
                List<SoldierEntity> archers = s.overworld().getEntitiesOfClass(SoldierEntity.class, player(s).getBoundingBox().inflate(200),
                        a -> a.faction() == f.id && a.unit().ranged).stream().collect(Collectors.toList());
                int k = 0;
                for (Structure st : StrategyData.get(s).structures.values()) {
                    if (st.owner != f.id || !st.type.tower() || k + 1 >= archers.size()) continue;
                    Actions.order(player(s), new Payloads.UnitOrder(new int[]{archers.get(k).getId(), archers.get(k + 1).getId()}, Payloads.UnitOrder.POST, 0, 0, 0, st.id));
                    k += 2;
                }
                List<SoldierEntity> foot = s.overworld().getEntitiesOfClass(SoldierEntity.class, player(s).getBoundingBox().inflate(200),
                        a -> a.faction() == f.id && !a.unit().ranged);
                Actions.order(player(s), new Payloads.UnitOrder(foot.stream().mapToInt(SoldierEntity::getId).toArray(), Payloads.UnitOrder.MOVE, cx, 70, cz - 4, -1));
                report(s, "ready");
            });
            context.waitTicks(20 * 12);
            look(context, cx, cz, 60, 200);
            context.takeScreenshot("siege_day");
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), -1);
                mc.gui.setScreen(new RtsScreen());
            });
            server.runOnServer(s -> {
                for (Structure st : StrategyData.get(s).structures.values()) if (st.owner == me(s).id && st.type == StructureType.TOWER_STONE) chosen[0] = st.id;
            });
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), chosen[0]);
                mc.gui.setScreen(new RtsScreen());
            });
            look(context, cx + 16, cz - 8, 28, 200);
            context.takeScreenshot("siege_tower_engines");
            context.runOnClient(mc -> RtsScreen.choose(Set.of(), -1));

            // night: the first wave
            server.runCommand("time set 14000");
            context.waitTicks(40);
            server.runOnServer(s -> report(s, "night falls"));
            look(context, cx, cz - 10, 70, 200);
            context.waitTicks(20 * 15);
            context.takeScreenshot("siege_wave_coming");
            context.waitTicks(20 * 20);
            server.runOnServer(s -> report(s, "the wave at the walls"));
            look(context, cx, cz - 6, 45, 200);
            context.takeScreenshot("siege_battle");
            context.waitTicks(20 * 25);
            server.runOnServer(s -> report(s, "later"));
            context.takeScreenshot("siege_battle_later");
            // the fifth night's brute
            server.runOnServer(s -> {
                StrategyData.get(s).wave = 4;
                Waves.start(s.overworld(), StrategyData.get(s));
                report(s, "night 5");
            });
            context.waitTicks(20 * 30);
            look(context, cx, cz - 6, 55, 200);
            context.takeScreenshot("siege_night5");
            server.runOnServer(s -> {
                for (Mob m : s.overworld().getEntitiesOfClass(Mob.class, player(s).getBoundingBox().inflate(200), m -> m.entityTags().contains(Waves.TAG + "_brute")))
                    log("brute hp {} at {}", (int) m.getHealth(), m.blockPosition().toShortString());
                report(s, "night 5 later");
            });
            server.runCommand("time set 23500");
            context.waitTicks(20 * 4);
            server.runOnServer(s -> report(s, "dawn"));
            context.takeScreenshot("siege_dawn");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
        Mode.Pending.mode = null;
    }

    private static final int[] chosen = {-1};
}
