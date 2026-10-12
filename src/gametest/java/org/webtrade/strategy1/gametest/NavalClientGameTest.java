package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.entity.ShipEntity;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.WaterPath;
import org.webtrade.strategy1.game.Actions;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.List;

/**
 * Ships: a port by the (thawed) lake at the capital, a transport made there (on open water, not in the pier), soldiers
 * aboard, and her way over the water to the far shore, where she puts them ashore.
 */
public class NavalClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[naval] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    private static boolean water(ServerLevel l, int x, int z) {
        int y = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        return l.getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER);
    }

    private static void look(ClientGameTestContext context, double x, double z, float distance, float yaw) {
        context.runOnClient(mc -> {
            Rts.jump(x, z);
            Rts.distance = distance;
            Rts.yaw = yaw;
        });
        context.waitTicks(60);
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
            long[] capital = new long[1];
            server.runOnServer(s -> capital[0] = me(s).capital);
            Hex home = Hex.of(capital[0]);
            double cx = home.centerX(), cz = home.centerZ();
            look(context, cx - 30, cz, 60, 200);
            server.waitFor(s -> Structures.has(me(s).id, StructureType.KEEP), 20 * 30);
            double[] port = new double[4];
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                for (int x = (int) cx - 110; x <= cx + 110; x++) for (int z = (int) cz - 110; z <= cz + 110; z++) {
                    if (!l.hasChunkAt(new BlockPos(x, 64, z))) continue;
                    BlockPos p = new BlockPos(x, l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
                    if (l.getBlockState(p).is(Blocks.ICE)) l.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
                }
                build(s, StructureType.BARRACKS, cx + 22, cz + 10);
                int[][] dirs = {{0, 1, 0}, {-1, 0, 1}, {0, -1, 2}, {1, 0, 3}};
                outer:
                for (int r = 8; r < 46; r += 2) for (int a = 0; a < 360; a += 10) {
                    int x = (int) (cx + Math.cos(Math.toRadians(a)) * r), z = (int) (cz + Math.sin(Math.toRadians(a)) * r);
                    if (water(l, x, z)) continue;
                    for (int[] d : dirs) {
                        boolean wet = true;
                        for (int k = 3; k <= 12; k++) if (!water(l, x + d[0] * k, z + d[1] * k)) wet = false;
                        if (wet && Structures.place(player(s), me(s), StructureType.PORT, new BlockPos(x, 70, z), null, d[2]) == null) {
                            port[0] = x;
                            port[1] = z;
                            port[2] = d[0];
                            port[3] = d[1];
                            log("port at {} {} facing {}", x, z, d[2]);
                            break outer;
                        }
                    }
                }
            });
            context.waitTicks(20 * 25);
            server.runOnServer(s -> {
                Faction f = me(s);
                Structure harbour = Structures.nearest(f.id, StructureType.PORT, new Vec3(cx, 64, cz));
                Structure barracks = Structures.nearest(f.id, StructureType.BARRACKS, new Vec3(cx, 64, cz));
                log("port {} barracks {}", harbour == null ? "-" : harbour.built(), barracks == null ? "-" : barracks.built());
                if (harbour != null) Actions.structure(player(s), new Payloads.StructureAction(harbour.id, Payloads.StructureAction.MAKE, Payloads.StructureAction.TRANSPORT));
                if (barracks != null) for (int i = 0; i < 4; i++)
                    Actions.structure(player(s), new Payloads.StructureAction(barracks.id, Payloads.StructureAction.MAKE, UnitType.MILITIA.ordinal()));
                for (ShipEntity sh : s.overworld().getEntitiesOfClass(ShipEntity.class, player(s).getBoundingBox().inflate(200))) {
                    BlockPos b = sh.blockPosition();
                    log("ship at {} in water {} block {}", b.toShortString(), s.overworld().getFluidState(b).is(FluidTags.WATER),
                            s.overworld().getBlockState(b.above()).getBlock());
                }
            });
            context.waitTicks(40);
            look(context, port[0] + port[2] * 10, port[1] + port[3] * 10, 30, 200);
            context.takeScreenshot("naval_spawned");
            server.runOnServer(s -> {
                ShipEntity ship = s.overworld().getEntitiesOfClass(ShipEntity.class, player(s).getBoundingBox().inflate(200)).stream().findFirst().orElse(null);
                List<SoldierEntity> foot = s.overworld().getEntitiesOfClass(SoldierEntity.class, player(s).getBoundingBox().inflate(200), e -> e.faction() == me(s).id);
                if (ship != null) Actions.order(player(s), new Payloads.UnitOrder(foot.stream().mapToInt(SoldierEntity::getId).toArray(), Payloads.UnitOrder.BOARD, 0, 0, 0, ship.getId()));
            });
            context.waitTicks(20 * 25);
            double[] far = new double[2];
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                ShipEntity ship = l.getEntitiesOfClass(ShipEntity.class, player(s).getBoundingBox().inflate(200)).stream().findFirst().orElse(null);
                if (ship == null) return;
                log("aboard {}", ship.getPassengers().size());
                // the far shore: land across the water, a long way round
                double best = 0;
                for (int a = 0; a < 360; a += 6) for (int r = 30; r < 110; r += 3) {
                    double x = ship.getX() + Math.cos(Math.toRadians(a)) * r, z = ship.getZ() + Math.sin(Math.toRadians(a)) * r;
                    if (water(l, (int) x, (int) z) || !l.hasChunkAt(BlockPos.containing(x, 64, z))) continue;
                    if (!water(l, (int) (x - Math.cos(Math.toRadians(a)) * 3), (int) (z - Math.sin(Math.toRadians(a)) * 3))) break;
                    Vec3 w = WaterPath.nearestWater(l, new Vec3(x, ship.getY(), z), ship.getY() + 0.35, 10);
                    List<Vec3> way = w == null ? null : WaterPath.find(l, ship.position(), w, ship.getY() + 0.35);
                    if (way != null && r > best) {
                        best = r;
                        far[0] = x;
                        far[1] = z;
                    }
                    break;
                }
                log("far shore {} {} ({} away)", (int) far[0], (int) far[1], (int) best);
                long t0 = System.nanoTime();
                List<Vec3> way = WaterPath.find(l, ship.position(), WaterPath.nearestWater(l, new Vec3(far[0], ship.getY(), far[1]), ship.getY() + 0.35, 18), ship.getY() + 0.35);
                log("path: {} legs in {} ms", way == null ? -1 : way.size(), (System.nanoTime() - t0) / 1_000_000);
                Actions.order(player(s), new Payloads.UnitOrder(new int[]{ship.getId()}, Payloads.UnitOrder.MOVE, far[0], 66, far[1], -1));
            });
            look(context, (port[0] + far[0]) / 2, (port[1] + far[1]) / 2, 80, 200);
            context.waitTicks(20 * 15);
            context.takeScreenshot("naval_sailing");
            context.waitTicks(20 * 25);
            server.runOnServer(s -> {
                ShipEntity ship = s.overworld().getEntitiesOfClass(ShipEntity.class, player(s).getBoundingBox().inflate(300)).stream().findFirst().orElse(null);
                if (ship != null) log("ship now at {} aboard {}, {} from the shore point", ship.blockPosition().toShortString(), ship.getPassengers().size(),
                        (int) Math.hypot(ship.getX() - far[0], ship.getZ() - far[1]));
                for (SoldierEntity e : s.overworld().getEntitiesOfClass(SoldierEntity.class, player(s).getBoundingBox().inflate(300), e -> e.faction() == me(s).id && !e.isPassenger()))
                    log("ashore: soldier at {}, {} from the shore point", e.blockPosition().toShortString(), (int) Math.hypot(e.getX() - far[0], e.getZ() - far[1]));
            });
            look(context, far[0], far[1], 35, 200);
            context.takeScreenshot("naval_landed");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }

    private static void build(MinecraftServer s, StructureType t, double x, double z) {
        int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        log("build {}: {}", t, Structures.place(player(s), me(s), t, BlockPos.containing(x, y, z), null, 0));
    }
}
