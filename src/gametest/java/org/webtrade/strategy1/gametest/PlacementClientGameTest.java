package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.game.Actions;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.Set;

/**
 * Putting up: nothing on top of another building; a wall begun at a tower runs from its side; a bridge from high
 * ground to low slopes down; soldiers from the barracks go to its gathering point.
 */
public class PlacementClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[place] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    private static String build(MinecraftServer s, StructureType t, double x, double z, double x2, double z2) {
        int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        String err = Structures.place(player(s), me(s), t, BlockPos.containing(x, y, z), BlockPos.containing(x2, y, z2), 0);
        log("build {} at {} {} .. {} {}: {}", t, (int) x, (int) z, (int) x2, (int) z2, err == null ? "ok" : err);
        return err;
    }

    private static Structure last(MinecraftServer s, StructureType t) {
        Structure out = null;
        for (Structure st : StrategyData.get(s).structures.values()) if (st.owner == me(s).id && st.type == t && (out == null || st.id > out.id)) out = st;
        return out;
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

            // 1. a farm on the keep: no
            server.runOnServer(s -> {
                Structure keep = last(s, StructureType.KEEP);
                String err = build(s, StructureType.FARM, keep.origin.getX(), keep.origin.getZ(), 0, 0);
                if (err == null) throw new AssertionError("a farm went up on the keep");
            });

            // 2. a wall from a tower
            server.runOnServer(s -> build(s, StructureType.TOWER_WOOD, cx + 22, cz - 10, 0, 0));
            server.waitFor(s -> last(s, StructureType.TOWER_WOOD) != null && last(s, StructureType.TOWER_WOOD).built(), 20 * 30);
            server.runOnServer(s -> {
                Structure tower = last(s, StructureType.TOWER_WOOD);
                String err = build(s, StructureType.WALL_WOOD, tower.origin.getX(), tower.origin.getZ(), tower.origin.getX() + 14, tower.origin.getZ() + 2);
                Structure wall = last(s, StructureType.WALL_WOOD);
                AABB b = tower.box();
                log("tower box x {}..{} z {}..{}; wall from {} to {}", b.minX, b.maxX, b.minZ, b.maxZ, wall == null ? "-" : wall.origin.toShortString(),
                        wall == null ? "-" : wall.end.toShortString());
                if (err != null || wall == null) throw new AssertionError("no wall from the tower: " + err);
                double gap = wall.origin.getX() + 0.5 - b.maxX;
                if (gap < 0 || gap > 1.5) throw new AssertionError("the wall doesn't start at the tower's side: " + gap);
            });

            // 3. a bridge down a slope
            int[] ends = new int[4];
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                int bestDiff = 0;
                for (int dx = -40; dx <= 40; dx += 4) {
                    for (int dz = -40; dz <= 40; dz += 4) {
                        int x = (int) cx + dx, z = (int) cz + dz;
                        if (Math.hypot(dx, dz) < 18) continue;
                        int h0 = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), h1 = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 12, z);
                        int diff = Math.abs(h0 - h1);
                        if (diff > bestDiff && diff <= 9 && owned(s, x, z) && owned(s, x + 12, z)) {
                            bestDiff = diff;
                            ends[0] = x;
                            ends[1] = z;
                            ends[2] = x + 12;
                            ends[3] = z;
                        }
                    }
                }
                log("slope: {} blocks", bestDiff);
                build(s, StructureType.BRIDGE, ends[0], ends[1], ends[2], ends[3]);
            });
            server.waitFor(s -> last(s, StructureType.BRIDGE) == null || last(s, StructureType.BRIDGE).built(), 20 * 30);
            server.runOnServer(s -> {
                Structure br = last(s, StructureType.BRIDGE);
                if (br == null) return;
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (long p : br.pos) {
                    int y = BlockPos.of(p).getY();
                    lo = Math.min(lo, y);
                    hi = Math.max(hi, y);
                }
                log("bridge from {} to {}: blocks {}..{}", br.origin.toShortString(), br.end.toShortString(), lo, hi);
            });
            context.runOnClient(mc -> {
                Rts.jump((ends[0] + ends[2]) / 2.0, ends[1]);
                Rts.distance = 26;
                Rts.yaw = 0;
            });
            context.waitTicks(60);
            context.takeScreenshot("place_bridge");

            // 4. the barracks' gathering point
            server.runOnServer(s -> build(s, StructureType.BARRACKS, cx - 8, cz - 22, 0, 0));
            server.waitFor(s -> Structures.has(me(s).id, StructureType.BARRACKS) && me(s).peasants >= 2, 20 * 60);
            double[] rally = new double[2];
            int[] bid = new int[1];
            server.runOnServer(s -> {
                Structure b = last(s, StructureType.BARRACKS);
                bid[0] = b.id;
                Vec3 door = Structures.door(b);
                Actions.structure(player(s), new Payloads.StructureAction(b.id, Payloads.StructureAction.RALLY, Payloads.StructureAction.pack(-10, -6)));
                rally[0] = b.rally.x;
                rally[1] = b.rally.z;
                log("door {} rally {}", door, b.rally);
                for (int i = 0; i < 2; i++) Actions.structure(player(s), new Payloads.StructureAction(b.id, Payloads.StructureAction.MAKE, UnitType.MILITIA.ordinal()));
            });
            context.waitTicks(20 * 15);
            server.runOnServer(s -> {
                var men = s.overworld().getEntitiesOfClass(SoldierEntity.class, player(s).getBoundingBox().inflate(300), e -> e.faction() == me(s).id && e.role == UnitEntity.Role.ARMY);
                for (SoldierEntity m : men) log("soldier at {} ({} from the rally)", m.blockPosition().toShortString(), (int) Math.hypot(m.getX() - rally[0], m.getZ() - rally[1]));
                if (men.isEmpty() || men.stream().anyMatch(m -> Math.hypot(m.getX() - rally[0], m.getZ() - rally[1]) > 5)) throw new AssertionError("not at the rally");
            });
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), bid[0]);
                mc.gui.setScreen(new RtsScreen());
                Rts.jump(cx - 12, cz - 30);
                Rts.distance = 30;
                Rts.yaw = 0;
            });
            context.waitTicks(60);
            context.takeScreenshot("place_rally");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }

    private static boolean owned(MinecraftServer s, double x, double z) {
        var p = StrategyData.get(s).province(Hex.at(x, z));
        return p != null && p.owner == me(s).id;
    }
}
