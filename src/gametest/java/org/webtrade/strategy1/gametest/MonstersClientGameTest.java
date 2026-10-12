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
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.client.Rts;
import org.webtrade.strategy1.entity.BomberZombie;
import org.webtrade.strategy1.entity.ShieldZombie;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Mode;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.registry.ModContent;

/**
 * The siege's own dead: a shield-bearer's shield shrugs off arrows and breaks under ballista bolts; a tower told to
 * shoot one does so; a bomber runs for a wall and blows a hole in it. Health and shield bars in the screenshots.
 */
public class MonstersClientGameTest implements FabricClientGameTest {

    private static void log(String fmt, Object... args) {
        Strategy1.LOGGER.info("[monsters] " + fmt, args);
    }

    private static ServerPlayer player(MinecraftServer s) {
        return s.getPlayerList().getPlayers().getFirst();
    }

    private static Faction me(MinecraftServer s) {
        return StrategyData.get(s).factionOf(player(s).getUUID());
    }

    private static Structure build(MinecraftServer s, StructureType t, double x, double z, double x2, double z2) {
        int y = s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        String err = Structures.place(player(s), me(s), t, BlockPos.containing(x, y, z), BlockPos.containing(x2, y, z2), 0);
        log("build {} at {} {}: {}", t, (int) x, (int) z, err == null ? "ok" : err);
        Structure last = null;
        for (Structure st : StrategyData.get(s).structures.values()) if (st.owner == me(s).id && st.type == t) last = st;
        return err == null ? last : null;
    }

    private static <T extends net.minecraft.world.entity.Mob> T spawn(ServerLevel l, net.minecraft.world.entity.EntityType<T> type, double x, double z) {
        T m = type.create(l, EntitySpawnReason.EVENT);
        Vec3 at = SoldierEntity.surface(l, x, z);
        m.snapTo(at.x, at.y, at.z, 0, 0);
        m.setPersistenceRequired();
        if (m instanceof ShieldZombie sz) sz.arm(1);
        if (m instanceof BomberZombie bz) bz.arm(1);
        l.addFreshEntity(m);
        return m;
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

            // 1. the shield against arrows, a sword and a ballista bolt
            server.runOnServer(s -> {
                ServerLevel l = s.overworld();
                ShieldZombie z = spawn(l, ModContent.SHIELD_ZOMBIE, cx + 30, cz + 30);
                int start = z.shield();
                Arrow arrow = new Arrow(l, z.getX(), z.getY() + 3, z.getZ(), new ItemStack(Items.ARROW), null);
                z.hurtServer(l, l.damageSources().arrow(arrow, null), 6);
                int afterArrow = z.shield();
                z.hurtServer(l, l.damageSources().generic(), 6);
                int afterSword = z.shield();
                Arrow bolt = new Arrow(l, z.getX(), z.getY() + 3, z.getZ(), new ItemStack(Items.ARROW), null);
                bolt.addTag(ShieldZombie.BOLT);
                z.hurtServer(l, l.damageSources().arrow(bolt, null), 6);
                int afterBolt = z.shield();
                log("shield {} -> arrow {} -> sword {} -> bolt {}; health {}", start, afterArrow, afterSword, afterBolt, z.getHealth());
                if (!(start - afterArrow <= 1 && afterSword < afterArrow && afterArrow - afterSword < afterSword - afterBolt))
                    throw new AssertionError("the shield should shrug off arrows and break under bolts");
                if (z.getHealth() < z.getMaxHealth()) throw new AssertionError("the body was hurt through the shield");
                z.discard();
            });

            // 2. a tower with a ballista, told to shoot a shield-bearer
            Structure[] tower = new Structure[1];
            server.runOnServer(s -> tower[0] = build(s, StructureType.TOWER_WOOD, cx + 20, cz - 6, cx + 20, cz - 6));
            server.waitFor(s -> tower[0] != null && tower[0].built(), 20 * 60);
            int[] zid = new int[1];
            server.runOnServer(s -> zid[0] = spawn(s.overworld(), ModContent.SHIELD_ZOMBIE, cx + 44, cz - 6).getId());
            context.runOnClient(mc -> {
                Rts.jump(cx + 36, cz - 6);
                Rts.distance = 22;
                Rts.yaw = 90;
            });
            context.waitTicks(60);
            context.takeScreenshot("monsters_shield");
            server.runOnServer(s -> {
                me(s).res[1] += 500;
                me(s).res[2] += 500;
                me(s).res[3] += 500;
                log("ballista: {}", Structures.upgrade(me(s), tower[0].id, 1));
                log("aim: {}", Structures.aim(s.overworld(), tower[0].id, zid[0]));
            });
            int[] broke = {-1};
            for (int t = 0; t < 30; t++) {
                context.waitTicks(20);
                int tt = t;
                server.runOnServer(s -> {
                    if (s.overworld().getEntity(zid[0]) instanceof ShieldZombie z && z.isAlive()) {
                        if (broke[0] < 0 && z.shield() == 0) broke[0] = tt;
                        if (tt % 3 == 0) {
                            var bolts = s.overworld().getEntitiesOfClass(Arrow.class, z.getBoundingBox().inflate(60), x -> x.entityTags().contains(ShieldZombie.BOLT));
                            log("t{}: shield {} health {} at {} bolts {}", tt, z.shield(), (int) z.getHealth(), z.blockPosition().toShortString(),
                                    bolts.stream().map(x -> x.blockPosition().toShortString()).toList());
                        }
                    } else if (broke[0] >= -1) {
                        log("t{}: the shield-bearer is down", tt);
                        broke[0] = broke[0] < 0 ? tt : broke[0];
                    }
                });
            }
            if (broke[0] < 0) throw new AssertionError("the ballista never broke the shield");

            // 3. a bomber and a wall
            Structure[] wall = new Structure[1];
            server.runOnServer(s -> wall[0] = build(s, StructureType.WALL_WOOD, cx - 20, cz + 18, cx - 8, cz + 18));
            server.waitFor(s -> wall[0] != null && wall[0].built(), 20 * 60);
            int[] hp = new int[1];
            server.runOnServer(s -> {
                hp[0] = wall[0].hp;
                spawn(s.overworld(), ModContent.BOMBER_ZOMBIE, cx - 14, cz + 34);
            });
            context.runOnClient(mc -> {
                Rts.jump(cx - 14, cz + 24);
                Rts.distance = 30;
                Rts.yaw = 0;
            });
            boolean[] blown = new boolean[1];
            for (int t = 0; t < 30 && !blown[0]; t++) {
                context.waitTicks(20);
                if (t == 3) context.takeScreenshot("monsters_bomber");
                server.runOnServer(s -> {
                    Structure w = StrategyData.get(s).structures.get(wall[0].id);
                    blown[0] = w == null || w.hp < hp[0];
                });
            }
            context.waitTicks(20);
            context.takeScreenshot("monsters_blown");
            server.runOnServer(s -> {
                Structure w = StrategyData.get(s).structures.get(wall[0].id);
                log("the wall: {} -> {}", hp[0], w == null ? "gone" : w.hp);
            });
            if (!blown[0]) throw new AssertionError("the bomber didn't blow the wall");
            context.runOnClient(mc -> Rts.exit(mc));
            context.waitTicks(10);
        } finally {
            Mode.Pending.mode = null;
        }
    }
}
