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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.entity.ShipEntity;
import org.webtrade.strategy1.entity.SiegeEntity;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.game.Actions;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.StrategyManager;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Plays the RTS in a real world: buildings, towers, walls and a path put up round the capital; soldiers from the
 * barracks, engines from the workshop, ships from the port; archers sent up a tower; soldiers aboard a transport and
 * put ashore; an enemy tower shooting at the ships while the warship throws stones at it; catapults and a trebuchet
 * breaking an AI capital's fort. Screenshots in build/run/clientGameTest/screenshots.
 */
public class StrategyClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[test] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static void look(ClientGameTestContext context, double x, double z, float distance, float yaw) {
        context.runOnClient(mc -> {
            Rts.jump(x, z);
            Rts.distance = distance;
            Rts.yaw = yaw;
        });
        context.waitTicks(60);
    }

    private static boolean water(ServerLevel l, int x, int z) {
        int y = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos p = new BlockPos(x, y, z);
        return l.getFluidState(p).is(FluidTags.WATER) || l.getBlockState(p).is(net.minecraft.world.level.block.Blocks.ICE);
    }

    private static <T extends UnitEntity> List<T> mine(MinecraftServer s, Class<T> c) {
        Faction me = StrategyData.get(s).factionOf(player(s).getUUID());
        return s.overworld().getEntitiesOfClass(c, player(s).getBoundingBox().inflate(300), e -> e.faction() == me.id && e.isAlive());
    }

    private static int[] ids(List<? extends UnitEntity> l) {
        return l.stream().mapToInt(UnitEntity::getId).toArray();
    }

    private static String build(MinecraftServer s, StructureType t, double x, double z, double x2, double z2, int rot) {
        Faction me = StrategyData.get(s).factionOf(player(s).getUUID());
        int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        String err = Structures.place(player(s), me, t, BlockPos.containing(x, y, z), BlockPos.containing(x2, y, z2), rot);
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
            server.runCommand("weather clear");
            server.runCommand("difficulty easy");
            server.waitFor(s -> StrategyData.get(s).generated && StrategyData.get(s).factionOf(player(s).getUUID()) != null, 20 * 30);
            context.waitFor(mc -> Rts.active(), 20 * 20);
            context.waitTicks(40);
            long[] capital = new long[1];
            server.runOnServer(s -> {
                Faction me = StrategyData.get(s).factionOf(player(s).getUUID());
                capital[0] = me.capital;
                for (int i = 0; i < 3; i++) Actions.handle(player(s), new Payloads.Action(Payloads.Action.AGE, 0, 0));
                log("realm #{} age {} boosted {} wood {}", me.id, me.age, me.boosted, me.res[1]);
            });
            Hex home = Hex.of(capital[0]);
            double cx = home.centerX(), cz = home.centerZ();
            look(context, cx, cz, 70, 200);

            // (this seed's capital lies by a frozen lake: it thaws, for the ships)
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                int n = 0;
                for (int x = (int) cx - 110; x <= cx + 110; x++) {
                    for (int z = (int) cz - 110; z <= cz + 110; z++) {
                        if (!l.hasChunkAt(new BlockPos(x, 64, z))) continue;
                        int y = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                        BlockPos p = new BlockPos(x, y, z);
                        if (l.getBlockState(p).is(net.minecraft.world.level.block.Blocks.ICE)) {
                            l.setBlock(p, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 2);
                            n++;
                        }
                    }
                }
                log("thawed {} blocks of ice", n);
            });
            context.waitTicks(20);
            // the buildings round the capital
            double[] port = new double[4];
            server.runOnServer(s -> {
                build(s, StructureType.FARM, cx - 24, cz - 18, 0, 0, 0);
                build(s, StructureType.QUARRY, cx - 24, cz - 4, 0, 0, 0);
                build(s, StructureType.BLACKSMITH, cx - 24, cz + 8, 0, 0, 0);
                build(s, StructureType.BARRACKS, cx - 8, cz - 22, 0, 0, 0);
                build(s, StructureType.WORKSHOP, cx + 8, cz - 22, 0, 0, 0);
                build(s, StructureType.TOWER_WOOD, cx + 22, cz - 10, 0, 0, 0);
                build(s, StructureType.TOWER_STONE, cx + 22, cz + 10, 0, 0, 0);
                build(s, StructureType.WALL_WOOD, cx + 15, cz - 20, cx + 15, cz - 4, 0);
                build(s, StructureType.WALL_STONE, cx + 15, cz + 2, cx + 15, cz + 20, 0);
                build(s, StructureType.PATH, cx, cz + 5, cx, cz + 32, 0);
                // a port where the land meets the water, on our own land
                ServerLevel l = s.overworld();
                int[][] dirs = {{0, 1, 0}, {-1, 0, 1}, {0, -1, 2}, {1, 0, 3}};
                List<double[]> centres = new java.util.ArrayList<>();
                centres.add(new double[]{cx, cz});
                for (var q : StrategyData.get(s).owned(StrategyData.get(s).factionIdOf(player(s).getUUID()))) centres.add(new double[]{q.hex.centerX(), q.hex.centerZ()});
                outer:
                for (double[] c0 : centres)
                for (int r = 8; r < 40; r += 2) {
                    for (int a = 0; a < 360; a += 10) {
                        int x = (int) (c0[0] + Math.cos(Math.toRadians(a)) * r), z = (int) (c0[1] + Math.sin(Math.toRadians(a)) * r);
                        if (!l.hasChunkAt(new BlockPos(x, 64, z))) continue;
                        if (water(l, x, z)) continue;
                        for (int[] d : dirs) {
                            boolean wet = true;
                            for (int k = 3; k <= 10; k++) if (!water(l, x + d[0] * k, z + d[1] * k)) wet = false;
                            if (wet && build(s, StructureType.PORT, x, z, 0, 0, d[2]) == null) {
                                port[0] = x;
                                port[1] = z;
                                port[2] = d[0];
                                port[3] = d[1];
                                break outer;
                            }
                        }
                    }
                }
            });
            context.waitTicks(20 * 30);
            server.runOnServer(s -> {
                for (Structure st : StrategyData.get(s).structures.values()) {
                    if (st.owner == StrategyData.get(s).factionIdOf(player(s).getUUID()))
                        log("structure #{} {} built {} ({} / {} blocks) hp {}", st.id, st.type, st.built(), st.progress, st.pos.length, st.hp);
                }
            });
            look(context, cx, cz, 75, 160);
            context.takeScreenshot("build_overview");
            look(context, cx - 18, cz - 10, 32, 120);
            context.takeScreenshot("build_farm_mill_mine");
            look(context, cx + 12, cz, 34, 270);
            context.takeScreenshot("build_towers_walls");

            // soldiers from the barracks, engines from the workshop, ships from the port
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                Faction me = d.factionOf(player(s).getUUID());
                Structure barracks = Structures.nearest(me.id, StructureType.BARRACKS, new Vec3(cx, 64, cz));
                Structure workshop = Structures.nearest(me.id, StructureType.WORKSHOP, new Vec3(cx, 64, cz));
                Structure harbour = Structures.nearest(me.id, StructureType.PORT, new Vec3(cx, 64, cz));
                if (barracks != null) {
                    for (UnitType u : new UnitType[]{UnitType.ARCHER, UnitType.ARCHER, UnitType.ARCHER, UnitType.ARCHER, UnitType.KNIGHT, UnitType.KNIGHT, UnitType.GUARD})
                        Actions.structure(player(s), new Payloads.StructureAction(barracks.id, Payloads.StructureAction.MAKE, u.ordinal()));
                }
                if (workshop != null) {
                    Actions.structure(player(s), new Payloads.StructureAction(workshop.id, Payloads.StructureAction.MAKE, Payloads.StructureAction.CATAPULT));
                    Actions.structure(player(s), new Payloads.StructureAction(workshop.id, Payloads.StructureAction.MAKE, Payloads.StructureAction.CATAPULT));
                    Actions.structure(player(s), new Payloads.StructureAction(workshop.id, Payloads.StructureAction.MAKE, Payloads.StructureAction.TREBUCHET));
                }
                if (harbour != null) {
                    Actions.structure(player(s), new Payloads.StructureAction(harbour.id, Payloads.StructureAction.MAKE, Payloads.StructureAction.TRANSPORT));
                    Actions.structure(player(s), new Payloads.StructureAction(harbour.id, Payloads.StructureAction.MAKE, Payloads.StructureAction.WARSHIP));
                }
                log("made: soldiers {}, siege {}, ships {}", me.army, me.siege, me.ships);
            });
            context.waitTicks(40);
            look(context, cx, cz - 26, 30, 200);
            context.takeScreenshot("units_made");

            // two archers up the stone tower
            server.runOnServer(s -> {
                Faction me = StrategyData.get(s).factionOf(player(s).getUUID());
                Structure tower = Structures.nearest(me.id, StructureType.TOWER_STONE, new Vec3(cx, 64, cz));
                List<SoldierEntity> archers = mine(s, SoldierEntity.class).stream().filter(a -> a.unit().ranged && a.role == UnitEntity.Role.ARMY)
                        .limit(2).collect(Collectors.toList());
                if (tower != null) Actions.order(player(s), new Payloads.UnitOrder(ids(archers), Payloads.UnitOrder.POST, 0, 0, 0, tower.id));
            });
            context.waitTicks(20 * 15);
            server.runOnServer(s -> {
                Faction me = StrategyData.get(s).factionOf(player(s).getUUID());
                Structure tower = Structures.nearest(me.id, StructureType.TOWER_STONE, new Vec3(cx, 64, cz));
                log("stone tower archers {}", tower == null ? -1 : tower.archers);
            });
            look(context, cx + 22, cz + 10, 22, 250);
            context.takeScreenshot("tower_archers");

            // ships: soldiers aboard the transport, an enemy tower on the shore against the warship
            if (port[0] != 0 || port[1] != 0) {
                server.runOnServer(s -> {
                    ShipEntity transport = mine(s, ShipEntity.class).stream().filter(sh -> sh.kind() == ShipEntity.Kind.TRANSPORT).findFirst().orElse(null);
                    List<SoldierEntity> foot = mine(s, SoldierEntity.class).stream().filter(a -> a.role == UnitEntity.Role.ARMY).limit(4).collect(Collectors.toList());
                    if (transport != null) Actions.order(player(s), new Payloads.UnitOrder(ids(foot), Payloads.UnitOrder.BOARD, 0, 0, 0, transport.getId()));
                });
                context.waitTicks(20 * 20);
                server.runOnServer(s -> mine(s, ShipEntity.class).forEach(sh -> log("ship {} at {} {} aboard {}", sh.kind(), (int) sh.getX(), (int) sh.getZ(), sh.getPassengers().size())));
                look(context, port[0] + port[2] * 12, port[1] + port[3] * 12, 30, 200);
                context.takeScreenshot("ships_boarded");
                // out onto the water
                server.runOnServer(s -> {
                    for (ShipEntity sh : mine(s, ShipEntity.class)) {
                        Vec3 out = sh.position().add(port[2] * 20 + (sh.kind() == ShipEntity.Kind.WARSHIP ? port[3] * 8 : 0), 0, port[3] * 20 + (sh.kind() == ShipEntity.Kind.WARSHIP ? port[2] * 8 : 0));
                        Actions.order(player(s), new Payloads.UnitOrder(new int[]{sh.getId()}, Payloads.UnitOrder.MOVE, out.x, sh.getY() - 0.5, out.z, -1));
                    }
                });
                context.waitTicks(20 * 10);
                server.runOnServer(s -> mine(s, ShipEntity.class).forEach(sh -> log("sailed: ship {} at {} {}", sh.kind(), (int) sh.getX(), (int) sh.getZ())));
                look(context, port[0] + port[2] * 26, port[1] + port[3] * 26, 40, 200);
                context.takeScreenshot("ships_sailing");
                // an enemy tower on land near the warship
                server.runOnServer(s -> {
                    StrategyData d = StrategyData.get(s);
                    Faction me = d.factionOf(player(s).getUUID());
                    Faction ai = null;
                    for (Faction f : d.factions.values()) if (!f.player() && f.alive) ai = f;
                    StrategyManager.declareWar(ai.id, me.id);
                    ShipEntity war = mine(s, ShipEntity.class).stream().filter(sh -> sh.kind() == ShipEntity.Kind.WARSHIP).findFirst().orElse(null);
                    if (war == null) return;
                    ServerLevel l = s.overworld();
                    outer:
                    for (int r = 14; r < 30; r += 2) {
                        for (int a = 0; a < 360; a += 15) {
                            int x = (int) (war.getX() + Math.cos(Math.toRadians(a)) * r), z = (int) (war.getZ() + Math.sin(Math.toRadians(a)) * r);
                            boolean dry = true;
                            for (int dx = -3; dx <= 3; dx += 3) for (int dz = -3; dz <= 3; dz += 3) if (water(l, x + dx, z + dz)) dry = false;
                            if (!dry) continue;
                            Structure t = new Structure(d.nextStructure++, StructureType.TOWER_STONE, ai.id, new BlockPos(x, 64, z), null, 0);
                            t.planned = true;
                            t.archers = 4;
                            d.structures.put(t.id, t);
                            log("enemy tower at {} {}, {} from the warship", x, z, r);
                            break outer;
                        }
                    }
                });
                context.waitTicks(20 * 25);
                server.runOnServer(s -> {
                    mine(s, ShipEntity.class).forEach(sh -> log("after the duel: ship {} hp {}/{}", sh.kind(), (int) sh.getHealth(), (int) sh.getMaxHealth()));
                    StrategyData d = StrategyData.get(s);
                    for (Structure st : d.structures.values()) {
                        if (st.type == StructureType.TOWER_STONE && st.owner != d.factionIdOf(player(s).getUUID()) && !st.planned
                                && Math.hypot(st.origin.getX() - port[0], st.origin.getZ() - port[1]) < 80) log("enemy tower hp {}/{} archers {}", st.hp, st.maxHp, st.archers);
                    }
                });
                context.takeScreenshot("ships_vs_tower");
                // the transport puts her soldiers ashore by the port
                server.runOnServer(s -> mine(s, ShipEntity.class).stream().filter(sh -> sh.kind() == ShipEntity.Kind.TRANSPORT).forEach(sh ->
                        Actions.order(player(s), new Payloads.UnitOrder(new int[]{sh.getId()}, Payloads.UnitOrder.MOVE, port[0] - port[2] * 4, 75, port[1] - port[3] * 4, -1))));
                context.waitTicks(20 * 15);
                server.runOnServer(s -> mine(s, ShipEntity.class).forEach(sh -> log("after unloading: ship {} at {} {} aboard {}", sh.kind(), (int) sh.getX(),
                        (int) sh.getZ(), sh.getPassengers().size())));
                look(context, port[0], port[1], 30, 200);
                context.takeScreenshot("ships_unloaded");
            }

            // the AI capitals' forts
            double[] fort = new double[2];
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                Faction ai = null;
                double best = Double.MAX_VALUE;
                for (Faction f : d.factions.values()) {
                    if (f.player() || !f.alive) continue;
                    double dist = Hex.of(f.capital).distance(home);
                    if (dist < best) {
                        best = dist;
                        ai = f;
                    }
                }
                Hex h = Hex.of(ai.capital);
                fort[0] = h.centerX();
                fort[1] = h.centerZ();
            });
            look(context, fort[0], fort[1] + 30, 60, 180);
            context.waitTicks(60);
            context.takeScreenshot("ai_fort");

            // siege: an enemy tower and wall set up across the capital, the catapults and the trebuchet sent at them
            look(context, cx, cz - 30, 60, 180);
            int[] target = {-1, -1};
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                Faction me = d.factionOf(player(s).getUUID());
                Faction ai = null;
                for (Faction f : d.factions.values()) if (!f.player() && f.alive) ai = f;
                StrategyManager.declareWar(me.id, ai.id);
                Structure workshop = Structures.nearest(me.id, StructureType.WORKSHOP, new Vec3(cx, 64, cz));
                double tx = workshop.origin.getX() + 4, tz = workshop.origin.getZ() - 34;
                Structure t = new Structure(d.nextStructure++, StructureType.TOWER_WOOD, ai.id, BlockPos.containing(tx, 64, tz), null, 0);
                t.planned = true;
                t.archers = 2;
                d.structures.put(t.id, t);
                Structure w = new Structure(d.nextStructure++, StructureType.WALL_STONE, ai.id, BlockPos.containing(tx - 14, 64, tz + 4), BlockPos.containing(tx - 4, 64, tz + 4), 0);
                w.planned = true;
                w.archers = 1;
                d.structures.put(w.id, w);
                target[0] = t.id;
                target[1] = w.id;
                log("enemy tower #{} and wall #{} at {} {}", t.id, w.id, (int) tx, (int) tz);
            });
            context.waitTicks(60);
            server.runOnServer(s -> {
                List<SiegeEntity> engines = mine(s, SiegeEntity.class);
                log("engines {}", engines.size());
                List<SiegeEntity> cats = engines.stream().filter(g -> g.kind() == SiegeEntity.Kind.CATAPULT).collect(Collectors.toList());
                List<SiegeEntity> trebs = engines.stream().filter(g -> g.kind() == SiegeEntity.Kind.TREBUCHET).collect(Collectors.toList());
                Actions.order(player(s), new Payloads.UnitOrder(ids(cats), Payloads.UnitOrder.ATTACK_STRUCTURE, 0, 0, 0, target[0]));
                Actions.order(player(s), new Payloads.UnitOrder(ids(trebs), Payloads.UnitOrder.ATTACK_STRUCTURE, 0, 0, 0, target[1]));
            });
            look(context, cx + 6, cz - 40, 40, 180);
            context.waitTicks(20 * 8);
            context.takeScreenshot("siege_fire");
            look(context, cx + 6, cz - 30, 26, 200);
            context.takeScreenshot("siege_engines");
            context.waitTicks(20 * 30);
            look(context, cx + 6, cz - 48, 40, 180);
            context.takeScreenshot("siege_later");
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                for (int id : target) {
                    Structure st = d.structures.get(id);
                    log("siege target #{}: {}", id, st == null ? "destroyed" : st.type + " hp " + st.hp + "/" + st.maxHp);
                }
                mine(s, SiegeEntity.class).forEach(g -> log("engine {} hp {} at {} {}", g.kind(), (int) g.getHealth(), (int) g.getX(), (int) g.getZ()));
            });
            context.runOnClient(mc -> RtsScreen.showBuild(true));
            context.runOnClient(mc -> mc.gui.setScreen(new RtsScreen()));
            look(context, cx, cz, 70, 200);
            context.takeScreenshot("build_panel");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
