package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.entity.PeasantEntity;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.hex.Hex;

import java.util.Set;

/** A quarry right on its deposit: one to a deposit, what's left shown, dug out at last and its workers gone. */
public class MinesClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[mines] " + fmt, args);
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
            // the stone of the capital's province, laid out
            server.waitFor(s -> {
                Structure d = Structures.depositNear(StructureType.DEPOSIT_STONE, new Vec3(cx, 64, cz), 60);
                return d != null && !d.planned;
            }, 20 * 30);
            int[] dep = new int[1];
            server.runOnServer(s -> {
                Structure d = Structures.depositNear(StructureType.DEPOSIT_STONE, new Vec3(cx, 64, cz), 60);
                dep[0] = d.id;
                log("deposit at {}: {} left", d.origin.toShortString(), Structures.left(d));
                // too far from it: no
                if (build(s, StructureType.QUARRY, d.origin.getX() + 11, d.origin.getZ()) == null) throw new AssertionError("a quarry went up away from its deposit");
                // a little off it: put right on it
                if (build(s, StructureType.QUARRY, d.origin.getX() + 3, d.origin.getZ() - 2) != null) throw new AssertionError("no quarry on the deposit");
                Structure q = Structures.nearest(me(s).id, StructureType.QUARRY, Vec3.ZERO);
                for (Structure st : StrategyData.get(s).structures.values()) if (st.type == StructureType.QUARRY && st.owner == me(s).id) q = st;
                log("quarry at {} on deposit {} (its parent {})", q.origin.toShortString(), d.id, q.parent);
                if (q.parent != d.id || q.origin.getX() != d.origin.getX() || q.origin.getZ() != d.origin.getZ()) throw new AssertionError("not on the deposit");
                if (build(s, StructureType.QUARRY, d.origin.getX(), d.origin.getZ()) == null) throw new AssertionError("two quarries on one deposit");
                // (a little left in it, to see it dug out)
                d.left = 14;
            });
            server.waitFor(s -> Structures.has(me(s).id, StructureType.QUARRY), 20 * 30);
            int[] qid = new int[1];
            server.runOnServer(s -> qid[0] = Structures.nearest(me(s).id, StructureType.QUARRY, Vec3.ZERO).id);
            context.runOnClient(mc -> {
                RtsScreen.choose(Set.of(), qid[0]);
                mc.gui.setScreen(new RtsScreen());
            });
            double[] at = new double[2];
            server.runOnServer(s -> {
                Structure q = StrategyData.get(s).structures.get(qid[0]);
                at[0] = q.origin.getX();
                at[1] = q.origin.getZ();
            });
            context.runOnClient(mc -> {
                Rts.jump(at[0], at[1] - 6);
                Rts.distance = 26;
            });
            context.waitTicks(80);
            context.takeScreenshot("mine_left");
            server.waitFor(s -> StrategyData.get(s).structures.get(dep[0]).left == 0, 20 * 120);
            context.waitTicks(60);
            server.runOnServer(s -> {
                long hands = s.overworld().getEntitiesOfClass(PeasantEntity.class, player(s).getBoundingBox().inflate(300), p -> p.job == qid[0]).size();
                log("dug out; hands still there: {}", hands);
                if (hands > 0) throw new AssertionError("workers stay at a dug-out quarry");
            });
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
