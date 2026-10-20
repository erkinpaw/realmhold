package org.webtrade.strategy1.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.client.screen.RtsScreen;
import org.webtrade.strategy1.entity.BoulderEntity;
import org.webtrade.strategy1.entity.CrawlerZombie;
import org.webtrade.strategy1.entity.DeadZombie;
import org.webtrade.strategy1.entity.GiantZombie;
import org.webtrade.strategy1.entity.PlagueZombie;
import org.webtrade.strategy1.entity.RamZombie;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Mode;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.game.Waves;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.registry.ModContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The siege's dead with a look of their own (the plague-bearer, the ram-bearer, the crawler, the giant), each in its
 * three skins, close up by day and by night; the plague-bearer spits bile at a bowman and bursts into poison; the
 * ram-bearer batters a gate; the first night's wave brings one of each.
 */
public class DeadClientGameTest implements FabricClientGameTest {

    private static final List<EntityType<? extends DeadZombie>> KINDS = List.of(ModContent.PLAGUE_ZOMBIE, ModContent.RAM_ZOMBIE,
            ModContent.CRAWLER_ZOMBIE, ModContent.GIANT_ZOMBIE);
    private static final String[] NAMES = {"plague", "ram", "crawler", "giant"};

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[dead] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    private static <T extends DeadZombie> T spawn(ServerLevel l, EntityType<T> type, double x, double z, int skin, float yaw, boolean still) {
        T m = type.create(l, EntitySpawnReason.EVENT);
        Vec3 at = SoldierEntity.surface(l, x, z);
        m.snapTo(at.x, at.y, at.z, yaw, 0);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        m.setPersistenceRequired();
        m.arm(1);
        m.setSkin(skin);
        if (still) m.setNoAi(true);
        l.addFreshEntity(m);
        return m;
    }

    private static Structure last(MinecraftServer s, StructureType t) {
        Structure last = null;
        for (Structure st : StrategyData.get(s).structures.values()) if (st.owner == me(s).id && st.type == t) last = st;
        return last;
    }

    private static void look(ClientGameTestContext context, double x, double z, float distance, float yaw) {
        context.runOnClient(mc -> {
            Rts.jump(x, z);
            Rts.distance = distance;
            Rts.yaw = yaw;
        });
        context.waitTicks(25);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        Mode.Pending.mode = Mode.SIEGE;
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
            context.runOnClient(mc -> RtsScreen.choose(Set.of(), -1));

            // 1. all of them in a row, each kind in its three skins, facing the camera
            double lx = cx + 24, lz = cz + 26;
            List<Integer> still = new ArrayList<>();
            // (on a flat floor, clear above, so the land doesn't hide any of them)
            int[] floor = new int[1];
            server.runOnServer(s -> floor[0] = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) lx, (int) lz) - 1);
            server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:air", (int) lx - 4, floor[0] + 1, (int) lz - 4, (int) lx + 12, floor[0] + 8, (int) lz + 20));
            server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:smooth_stone", (int) lx - 4, floor[0], (int) lz - 4, (int) lx + 12, floor[0], (int) lz + 20));
            server.runOnServer(s -> {
                for (int k = 0; k < KINDS.size(); k++)
                    for (int skin = 0; skin < DeadZombie.SKINS; skin++)
                        still.add(spawn(s.overworld(), KINDS.get(k), lx + skin * 3.5, lz + k * 5, skin, 0, true).getId());
            });
            context.waitTicks(20);
            server.runOnServer(s -> {
                for (int id : still) {
                    if (!(s.overworld().getEntity(id) instanceof DeadZombie d)) throw new AssertionError("a dead one is missing");
                    if (d instanceof GiantZombie && d.getAttributeValue(Attributes.SCALE) < 1.9) throw new AssertionError("the giant isn't big");
                    if (d instanceof CrawlerZombie && d.getBbHeight() > 0.8) throw new AssertionError("the crawler stands too tall");
                    log("{} skin {}: pose {}, alive {}", d.getType().toShortString(), d.skin(), d.getPose(), d.isAlive());
                }
                log("in a row: {}", still.size());
            });
            look(context, lx + 3.5, lz + 7, 22, 180);
            context.takeScreenshot("dead_all_day");
            look(context, lx + 3.5, lz + 7, 22, 0);
            context.takeScreenshot("dead_all_back");
            for (int k = 0; k < KINDS.size(); k++) {
                float d = k == 3 ? 11 : k == 2 ? 6 : 7.5F;
                look(context, lx + 3.5, lz + k * 5, d, 180);
                context.takeScreenshot("dead_" + NAMES[k] + "_front");
                look(context, lx + 3.5, lz + k * 5, d, 20);
                context.takeScreenshot("dead_" + NAMES[k] + "_back");
                look(context, lx + 3.5, lz + k * 5, d, 270);
                context.takeScreenshot("dead_" + NAMES[k] + "_side");
            }
            server.runCommand("time set 18000");
            look(context, lx + 3.5, lz + 7, 20, 210);
            context.takeScreenshot("dead_all_night");
            look(context, lx + 3.5, lz + 10, 8, 160);
            context.takeScreenshot("dead_crawler_night");
            server.runCommand("time set 6000");
            server.runOnServer(s -> {
                for (int id : still) if (s.overworld().getEntity(id) != null) s.overworld().getEntity(id).discard();
            });

            // 2. crawlers dragging themselves along, and a giant walking
            double wx = cx - 26, wz = cz + 20;
            server.runOnServer(s -> {
                for (int i = 0; i < 3; i++) {
                    CrawlerZombie c = spawn(s.overworld(), ModContent.CRAWLER_ZOMBIE, wx + i * 2.5, wz, i, 0, false);
                    c.getNavigation().moveTo(wx + i * 2.5, SoldierEntity.surface(s.overworld(), wx, wz + 30).y, wz + 30, 1.0);
                }
                GiantZombie g = spawn(s.overworld(), ModContent.GIANT_ZOMBIE, wx + 9, wz - 2, 1, 0, false);
                g.getNavigation().moveTo(wx + 9, SoldierEntity.surface(s.overworld(), wx + 9, wz + 30).y, wz + 30, 1.0);
            });
            // (the camera after the first of them as it goes)
            String[] crawl = {"dead_crawling", "dead_crawling_front", "dead_crawling_close", "dead_crawling_side"};
            float[][] views = {{9, 330}, {6, 180}, {4.5F, 220}, {5, 270}};
            for (int t = 0; t < crawl.length; t++) {
                context.waitTicks(20);
                double[] c = new double[2];
                server.runOnServer(s -> {
                    for (CrawlerZombie z : s.overworld().getEntitiesOfClass(CrawlerZombie.class, new AABB(wx - 10, -64, wz - 10, wx + 20, 320, wz + 50))) {
                        c[0] = z.getX();
                        c[1] = z.getZ();
                    }
                });
                look(context, c[0], c[1] + (t == 0 ? 2 : 0), views[t][0], views[t][1]);
                context.takeScreenshot(crawl[t]);
            }
            server.runOnServer(s -> {
                for (DeadZombie d : s.overworld().getEntitiesOfClass(DeadZombie.class, new AABB(wx - 10, -64, wz - 10, wx + 20, 320, wz + 50))) d.discard();
            });

            // 3. the plague-bearer spits bile at a bowman; felled, he bursts into poison
            double px = cx + 30, pz = cz - 22;
            int[] bowman = new int[1], plague = new int[1];
            server.runOnServer(s -> {
                SoldierEntity a = SoldierEntity.spawn(s.overworld(), UnitType.ARCHER, me(s).id, me(s).color, new Vec3(px, 0, pz), UnitEntity.Role.ARMY);
                a.order(SoldierEntity.Mode.HOLD, a.position());
                a.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500);
                a.setHealth(500);
                bowman[0] = a.getId();
                PlagueZombie z = spawn(s.overworld(), ModContent.PLAGUE_ZOMBIE, px + 9, pz, 2, 90, false);
                z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400);
                z.setHealth(400);
                z.setTarget(a);
                plague[0] = z.getId();
            });
            look(context, px + 4.5, pz + 3, 11, 0);
            boolean[] spat = new boolean[1], poisoned = new boolean[1];
            boolean shot = false;
            for (int t = 0; t < 80 && !poisoned[0]; t++) {
                context.waitTicks(2);
                server.runOnServer(s -> {
                    if (s.overworld().getEntity(plague[0]) instanceof PlagueZombie z && s.overworld().getEntity(bowman[0]) instanceof SoldierEntity a) {
                        z.setTarget(a);
                        if (!s.overworld().getEntitiesOfClass(BoulderEntity.class, z.getBoundingBox().inflate(14)).isEmpty()) spat[0] = true;
                        if (a.hasEffect(MobEffects.POISON)) poisoned[0] = true;
                    }
                });
                if (spat[0] && !shot) {
                    shot = true;
                    context.takeScreenshot("dead_plague_spits");
                }
            }
            log("the plague-bearer spat: {}, the bowman poisoned: {}", spat[0], poisoned[0]);
            if (!spat[0] || !poisoned[0]) throw new AssertionError("the plague-bearer didn't spit bile");
            context.takeScreenshot("dead_plague_puddle");
            boolean[] cloud = new boolean[1];
            server.runOnServer(s -> {
                if (s.overworld().getEntity(plague[0]) instanceof PlagueZombie z) z.hurtServer(s.overworld(), s.overworld().damageSources().generic(), 1000);
            });
            context.waitTicks(10);
            server.runOnServer(s -> cloud[0] = !s.overworld().getEntitiesOfClass(AreaEffectCloud.class, new AABB(px - 2, -64, pz - 6, px + 16, 320, pz + 6)).isEmpty());
            log("a cloud of poison where he fell: {}", cloud[0]);
            if (!cloud[0]) throw new AssertionError("no cloud of poison");
            context.takeScreenshot("dead_plague_cloud");
            server.runOnServer(s -> {
                if (s.overworld().getEntity(bowman[0]) != null) s.overworld().getEntity(bowman[0]).discard();
            });

            // 4. the ram-bearer goes for a gate and batters it
            double gx = cx - 20, gz = cz - 22;
            server.runOnServer(s -> {
                int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) gx, (int) gz);
                log("wall: {}", Structures.place(player(s), me(s), StructureType.WALL_WOOD, BlockPos.containing(gx, y, gz), BlockPos.containing(gx + 12, y, gz), 0));
            });
            server.waitFor(s -> last(s, StructureType.WALL_WOOD) != null && last(s, StructureType.WALL_WOOD).built(), 20 * 40);
            server.runOnServer(s -> {
                Structure w = last(s, StructureType.WALL_WOOD);
                int dx = (int) (gx + 6) - (int) Math.floor(w.box().minX), dz = (int) gz - (int) Math.floor(w.box().minZ);
                log("gate: {}", Structures.gate(me(s), w.id, dx, dz));
            });
            server.waitFor(s -> last(s, StructureType.GATE) != null && last(s, StructureType.GATE).built(), 20 * 40);
            int[] hp0 = new int[1];
            server.runOnServer(s -> {
                Structure g = last(s, StructureType.GATE);
                g.hp = g.maxHp;
                hp0[0] = g.hp;
                spawn(s.overworld(), ModContent.RAM_ZOMBIE, gx + 6, gz - 12, 0, 0, false);
            });
            look(context, gx + 6, gz - 6, 13, 300);
            context.takeScreenshot("dead_ram_coming");
            boolean[] hit = new boolean[1];
            boolean pictured = false;
            for (int t = 0; t < 40 && !hit[0]; t++) {
                context.waitTicks(10);
                server.runOnServer(s -> {
                    Structure g = last(s, StructureType.GATE);
                    if (g == null || g.hp < hp0[0]) hit[0] = true;
                });
                if (!pictured && t > 10) {
                    pictured = true;
                    look(context, gx + 6, gz - 2, 7, 300);
                }
            }
            context.takeScreenshot("dead_ram_at_gate");
            server.runOnServer(s -> {
                Structure g = last(s, StructureType.GATE);
                log("the gate: {} of {}", g == null ? 0 : g.hp, hp0[0]);
            });
            if (!hit[0]) throw new AssertionError("the ram-bearer didn't batter the gate");
            server.runOnServer(s -> {
                for (DeadZombie d : s.overworld().getEntitiesOfClass(DeadZombie.class, new AABB(gx - 20, -64, gz - 30, gx + 30, 320, gz + 20))) d.discard();
            });

            // 5. a fight: the realm's soldiers against two of each, on a flat floor
            double bx = cx + 6, bz = cz - 40;
            int[] by = new int[1];
            server.runOnServer(s -> by[0] = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) bx, (int) bz) - 1);
            server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:air", (int) bx - 14, by[0] + 1, (int) bz - 10, (int) bx + 14, by[0] + 10, (int) bz + 18));
            server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:grass_block", (int) bx - 14, by[0], (int) bz - 10, (int) bx + 14, by[0], (int) bz + 18));
            List<Integer> soldiers = new ArrayList<>(), dead = new ArrayList<>();
            server.runOnServer(s -> {
                for (int i = 0; i < 8; i++) {
                    SoldierEntity m = SoldierEntity.spawn(s.overworld(), i < 5 ? UnitType.MILITIA : UnitType.ARCHER, me(s).id, me(s).color,
                            new Vec3(bx - 5 + i * 1.4, 0, bz - (i < 5 ? 2 : 5)), UnitEntity.Role.ARMY);
                    m.order(SoldierEntity.Mode.HOLD, m.position());
                    soldiers.add(m.getId());
                }
                for (int k = 0; k < KINDS.size(); k++)
                    for (int j = 0; j < 2; j++) {
                        DeadZombie z = spawn(s.overworld(), KINDS.get(k), bx - 6 + k * 3.5 + j * 1.5, bz + 9 + j * 2, k + j, 180, false);
                        if (z instanceof GiantZombie || z instanceof PlagueZombie) z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(z.getMaxHealth() * 3);
                        z.setHealth(z.getMaxHealth());
                        dead.add(z.getId());
                    }
            });
            float[][] fight = {{18, 180}, {12, 210}, {10, 150}, {14, 240}, {8, 190}, {16, 170}};
            for (int t = 0; t < fight.length; t++) {
                for (int w = 0; w < 3; w++) {
                    context.waitTicks(10);
                    // (the dead go for the nearest soldier, as a wave's do)
                    server.runOnServer(s -> {
                        for (int id : dead) {
                            if (!(s.overworld().getEntity(id) instanceof DeadZombie z) || z instanceof RamZombie) continue;
                            SoldierEntity near = null;
                            for (int sid : soldiers)
                                if (s.overworld().getEntity(sid) instanceof SoldierEntity m && m.isAlive() && (near == null || m.distanceTo(z) < near.distanceTo(z))) near = m;
                            if (near != null) z.setTarget(near);
                        }
                    });
                }
                look(context, bx, bz + 3, fight[t][0], fight[t][1]);
                context.takeScreenshot("dead_fight_" + t);
            }
            server.runOnServer(s -> {
                for (net.minecraft.world.entity.LivingEntity e : s.overworld().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        new AABB(bx - 40, -64, bz - 40, bx + 40, 320, bz + 40), e -> e instanceof DeadZombie || e instanceof SoldierEntity)) e.discard();
            });

            // 6. the first night: one of each at the head of the wave
            server.runCommand("time set 13000");
            server.runOnServer(s -> {
                StrategyData d = StrategyData.get(s);
                d.wave = 0;
                Waves.start(s.overworld(), d);
            });
            context.waitTicks(20);
            int[] seen = new int[4];
            server.runOnServer(s -> {
                for (Mob m : s.overworld().getEntitiesOfClass(Mob.class, new AABB(cx - 150, -64, cz - 150, cx + 150, 320, cz + 150), x -> x.entityTags().contains(Waves.TAG))) {
                    if (m instanceof PlagueZombie) seen[0]++;
                    if (m instanceof RamZombie) seen[1]++;
                    if (m instanceof CrawlerZombie) seen[2]++;
                    if (m instanceof GiantZombie) seen[3]++;
                }
                log("the first night's wave: plague {}, ram {}, crawler {}, giant {}", seen[0], seen[1], seen[2], seen[3]);
            });
            for (int k = 0; k < 4; k++) if (seen[k] == 0) throw new AssertionError("no " + NAMES[k] + " on the first night");
            // the wave on its way, from above and by one of the giants
            double[] at = new double[2];
            server.runOnServer(s -> {
                for (GiantZombie g : s.overworld().getEntitiesOfClass(GiantZombie.class, new AABB(cx - 150, -64, cz - 150, cx + 150, 320, cz + 150))) {
                    at[0] = g.getX();
                    at[1] = g.getZ();
                }
            });
            context.waitTicks(20 * 6);
            look(context, at[0], at[1], 16, 0);
            context.takeScreenshot("dead_wave_giant");
            context.waitTicks(20 * 6);
            look(context, cx, cz, 60, 0);
            context.takeScreenshot("dead_wave_night");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        }
    }
}
