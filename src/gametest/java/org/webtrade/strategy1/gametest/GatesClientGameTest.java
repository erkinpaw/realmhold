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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Hex;

/** A gate in a wall: shut with nobody near, open for the realm's own, shut before the enemy even with its own by, open when held. */
public class GatesClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[gates] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    private static Structure last(MinecraftServer s, StructureType t) {
        Structure out = null;
        for (Structure st : StrategyData.get(s).structures.values()) if (st.owner == me(s).id && st.type == t && (out == null || st.id > out.id)) out = st;
        return out;
    }

    /** How many of the gate's portcullis blocks are down. */
    private static int down(ServerLevel l, Structure g) {
        int n = 0;
        for (long p : g.leaves) if (!l.getBlockState(BlockPos.of(p)).isAir()) n++;
        return n;
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
            // a wall across the land south of the keep, and a gate in its middle
            double wx = cx - 20, wz = cz - 20;
            server.runOnServer(s -> {
                int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) wx, (int) wz);
                log("wall: {}", Structures.place(player(s), me(s), StructureType.WALL_WOOD, BlockPos.containing(wx, y, wz), BlockPos.containing(wx + 12, y, wz), 0));
            });
            server.waitFor(s -> last(s, StructureType.WALL_WOOD) != null && last(s, StructureType.WALL_WOOD).built(), 20 * 30);
            server.runOnServer(s -> {
                Structure w = last(s, StructureType.WALL_WOOD);
                int dx = (int) (wx + 6) - (int) Math.floor(w.box().minX), dz = (int) wz - (int) Math.floor(w.box().minZ);
                log("gate: {}", Structures.gate(me(s), w.id, dx, dz));
            });
            server.waitFor(s -> last(s, StructureType.GATE) != null && last(s, StructureType.GATE).built(), 20 * 30);
            context.waitTicks(20);
            int[] state = new int[1];
            server.runOnServer(s -> {
                Structure g = last(s, StructureType.GATE);
                state[0] = down(s.overworld(), g);
                log("nobody near: {} of {} down", state[0], g.leaves.length);
                if (state[0] < g.leaves.length) throw new AssertionError("the gate isn't shut");
            });
            context.runOnClient(mc -> {
                Rts.jump(wx + 6, wz - 4);
                Rts.distance = 22;
                Rts.yaw = 0;
            });
            // a soldier walks through
            int[] man = new int[1];
            server.runOnServer(s -> {
                SoldierEntity m = SoldierEntity.spawn(s.overworld(), UnitType.MILITIA, me(s).id, me(s).color, new Vec3(wx + 6, 0, wz + 6), UnitEntity.Role.ARMY);
                man[0] = m.getId();
                m.order(SoldierEntity.Mode.MOVE, SoldierEntity.surface(s.overworld(), wx + 6, wz - 8));
            });
            int[] opened = {0};
            for (int t = 0; t < 30; t++) {
                context.waitTicks(10);
                if (t == 8) context.takeScreenshot("gate_open");
                server.runOnServer(s -> {
                    if (down(s.overworld(), last(s, StructureType.GATE)) == 0) opened[0]++;
                });
            }
            server.runOnServer(s -> {
                var m = s.overworld().getEntity(man[0]);
                log("the soldier: at {} (gate was open {} times)", m == null ? "-" : m.blockPosition().toShortString(), opened[0]);
                if (m == null || m.getZ() > wz - 1) throw new AssertionError("the soldier didn't get through");
            });
            // the enemy at the gate, the soldier still by it: shut
            server.runOnServer(s -> {
                var m = (SoldierEntity) s.overworld().getEntity(man[0]);
                m.order(SoldierEntity.Mode.MOVE, SoldierEntity.surface(s.overworld(), wx + 6, wz - 3));
                Zombie z = EntityTypes.ZOMBIE.create(s.overworld(), EntitySpawnReason.EVENT);
                Vec3 at = SoldierEntity.surface(s.overworld(), wx + 6, wz + 5);
                z.snapTo(at.x, at.y, at.z, 0, 0);
                z.setNoAi(true);
                s.overworld().addFreshEntity(z);
            });
            context.waitTicks(40);
            server.runOnServer(s -> {
                Structure g = last(s, StructureType.GATE);
                int d = down(s.overworld(), g);
                log("enemy at the gate: {} of {} down", d, g.leaves.length);
                if (d < g.leaves.length) throw new AssertionError("the gate opened with the enemy at it");
                Structures.holdGate(me(s), g.id);
            });
            context.waitTicks(20);
            server.runOnServer(s -> {
                Structure g = last(s, StructureType.GATE);
                int d = down(s.overworld(), g);
                log("held open: {} down", d);
                if (d != 0) throw new AssertionError("the gate didn't open when held");
            });
            context.takeScreenshot("gate_held");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
