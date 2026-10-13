package org.webtrade.strategy1.game;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.entity.SoldierEntity;
import org.webtrade.strategy1.entity.UnitEntity;
import org.webtrade.strategy1.mixin.MobAccess;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The siege of the dead: every night a wave comes out of the dark to the realm's keep — more of them each night, and
 * more the more land the realm holds; worse kinds as the nights go on (shield-bearers whose shield must be broken
 * first, bombers that run for the walls and blow them open, skeletons, spiders, creepers, witches, ravagers, and a
 * great brute every fifth night). They fight the realm's people, batter its walls
 * and buildings in their way and take the provinces they stand in. The realm's far lands are besieged unseen: those
 * with too few defenders fall; halfway through the night more of them come from another side. At dawn the dead burn
 * and the rest are gone. A day and a night are five minutes each (the world's clock runs faster for it).
 */
public final class Waves {

    public static final String TAG = "s1wave";
    private static final Set<Mob> WAVE = new HashSet<>();
    private static boolean night, reinforced;
    private static int dawnTimer = -1;
    /** The clock's pace last set (NaN: not yet, this world). */
    private static float pace = Float.NaN;

    /** The siege's day and night, in seconds; the clock's dusk and dawn. */
    public static final int DAY_SECONDS = 300, NIGHT_SECONDS = 300;
    private static final long DUSK = 13000, DAWN = 23000;

    private Waves() {
    }

    public static void clear() {
        WAVE.clear();
        night = false;
        reinforced = false;
        dawnTimer = -1;
        pace = Float.NaN;
    }

    /** How fast the clock runs, by day or by night, for each to last its five minutes. */
    private static float rate(boolean dark) {
        return dark ? (DAWN - DUSK) / (NIGHT_SECONDS * 20F) : (24000 - (DAWN - DUSK)) / (DAY_SECONDS * 20F);
    }

    /** The world's clock set to the siege's pace (the sky and the sun go with it). */
    private static void pace(ServerLevel level, boolean dark) {
        float want = rate(dark);
        if (want == pace) return;
        level.dimensionType().defaultClock().ifPresent(c -> level.getServer().clockManager().setRate(c, want));
        pace = want;
    }

    public static int alive() {
        WAVE.removeIf(m -> m.isRemoved() || !m.isAlive());
        return WAVE.size();
    }

    public static boolean isWave(LivingEntity e) {
        return e instanceof Mob m && WAVE.contains(m);
    }

    /** Seconds to the night (positive), or into it (negative). */
    public static int clock(ServerLevel level) {
        long t = level.getOverworldClockTime() % 24000;
        boolean dark = t >= DUSK && t < DAWN;
        long left = dark ? DAWN - t : t < DUSK ? DUSK - t : 24000 - t + DUSK;
        int s = (int) (left / (20 * rate(dark)));
        return dark ? -s : s;
    }

    /** Once a second (in the siege mode). */
    public static void second(ServerLevel level, StrategyData data) {
        long t = level.getOverworldClockTime() % 24000;
        boolean dark = t >= DUSK && t < DAWN;
        pace(level, dark);
        if (dark && !night) start(level, data);
        // halfway through the night: more of them, from another side
        if (dark && night && !reinforced && t >= (DUSK + DAWN) / 2) {
            reinforced = true;
            reinforce(level, data);
        }
        if (!dark && night) {
            night = false;
            dawnTimer = 45;
            // (the sun takes them all, helmets or not)
            for (Mob m : WAVE) if (m.isAlive()) m.igniteForSeconds(10);
            StrategyManager.announce(Component.translatable("strategy1.wave.dawn", data.wave).withStyle(ChatFormatting.GOLD), true);
            for (ServerPlayer p : level.players()) StrategyManager.title(p, Component.translatable("strategy1.wave.dawn_title").withStyle(ChatFormatting.GOLD),
                    Component.translatable("strategy1.wave.survived", data.wave));
        }
        if (dawnTimer > 0 && --dawnTimer == 0) {
            for (Mob m : WAVE) m.discard();
            WAVE.clear();
        }
        alive();
        // the dead take the provinces they stand in, while nobody defends them
        for (Mob m : WAVE) {
            Province p = data.province(org.webtrade.strategy1.hex.Hex.at(m.getX(), m.getZ()));
            if (p == null || p.neutral() || p.hex.distToCenter(m.getX(), m.getZ()) > Rules.CAPTURE_RADIUS) continue;
            Faction f = data.faction(p.owner);
            if (f == null || !f.player()) continue;
            StrategyManager.deadAt(level, p);
        }
    }

    /** Night falls: the wave of the night comes, at the keep, and the far lands are besieged. */
    public static void start(ServerLevel level, StrategyData data) {
        night = true;
        reinforced = false;
        dawnTimer = -1;
        data.wave++;
        data.touch();
        int n = data.wave;
        for (Faction f : data.factions.values()) {
            if (!f.player() || !f.alive) continue;
            Structure keep = Structures.nearest(f.id, StructureType.KEEP, Vec3.ZERO);
            if (keep == null) continue;
            Vec3 c = keep.center();
            RandomSource r = level.getRandom();
            int made = come(level, c, count(data, f, n), n, r.nextDouble() * Math.PI * 2, Math.PI * 2);
            // the boss of every fifth night
            if (n % 5 == 0) {
                double a = r.nextDouble() * Math.PI * 2;
                Vec3 at = SoldierEntity.surface(level, c.x + Math.cos(a) * 70, c.z + Math.sin(a) * 70);
                Mob boss = spawn(level, EntityTypes.ZOMBIE, at, c, n);
                if (boss != null) {
                    boss.getAttribute(Attributes.SCALE).setBaseValue(2.4);
                    boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80 + n * 20);
                    boss.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(8 + n);
                    boss.setHealth(boss.getMaxHealth());
                    boss.setCustomName(Component.translatable("strategy1.wave.brute").withStyle(ChatFormatting.DARK_RED));
                    boss.setCustomNameVisible(true);
                    boss.addTag(TAG + "_brute");
                }
            }
            besiegeFarLands(level, data, f, n);
            for (ServerPlayer p : level.players()) {
                if (data.factionIdOf(p.getUUID()) != f.id) continue;
                StrategyManager.title(p, Component.translatable("strategy1.wave.title", n).withStyle(ChatFormatting.DARK_RED),
                        Component.translatable("strategy1.wave.subtitle", made));
                level.playSound(null, p.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.5F, 0.8F);
            }
            // (the first nights: what the new kinds of the dead are, and how to meet them)
            if (n <= 2) StrategyManager.tell(f, Component.translatable("strategy1.wave.kinds").withStyle(ChatFormatting.YELLOW));
        }
    }

    /** How many of the dead come at a realm on a night: more each night, and more the more land it holds. */
    private static int count(StrategyData data, Faction f, int n) {
        return Math.min(120, 5 + n * 4 + data.owned(f.id).size());
    }

    /**
     * The dead come out of the dark round the keep (within an arc of so many radians about a bearing, a ring 55..85
     * blocks out); how many came.
     */
    private static int come(ServerLevel level, Vec3 c, int count, int n, double bearing, double arc) {
        RandomSource r = level.getRandom();
        int made = 0;
        for (int i = 0; i < count * 3 && made < count; i++) {
            double a = bearing + (r.nextDouble() - 0.5) * arc, d = 55 + r.nextDouble() * 30;
            Vec3 at = SoldierEntity.surface(level, c.x + Math.cos(a) * d, c.z + Math.sin(a) * d);
            if (!level.hasChunkAt(BlockPos.containing(at)) || !level.getFluidState(BlockPos.containing(at).below()).isEmpty()) continue;
            if (spawn(level, kind(n, made, r), at, c, n) != null) made++;
        }
        return made;
    }

    /** Halfway through the night: half as many again, all from one side, at each realm's keep. */
    static void reinforce(ServerLevel level, StrategyData data) {
        int n = data.wave;
        for (Faction f : data.factions.values()) {
            if (!f.player() || !f.alive) continue;
            Structure keep = Structures.nearest(f.id, StructureType.KEEP, Vec3.ZERO);
            if (keep == null) continue;
            int made = come(level, keep.center(), count(data, f, n) / 2, n, level.getRandom().nextDouble() * Math.PI * 2, Math.PI / 2);
            if (made == 0) continue;
            for (ServerPlayer p : level.players()) {
                if (data.factionIdOf(p.getUUID()) != f.id) continue;
                p.sendOverlayMessage(Component.translatable("strategy1.wave.more", made).withStyle(ChatFormatting.DARK_RED));
                level.playSound(null, p.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.2F, 0.6F);
            }
        }
    }

    /** What comes, the further the nights go. */
    private static EntityType<? extends Mob> kind(int n, int i, RandomSource r) {
        List<EntityType<? extends Mob>> pool = new ArrayList<>();
        pool.add(EntityTypes.ZOMBIE);
        pool.add(EntityTypes.ZOMBIE);
        pool.add(EntityTypes.HUSK);
        if (n >= 2) pool.add(EntityTypes.SKELETON);
        if (n >= 2) pool.add(EntityTypes.ZOMBIE_VILLAGER);
        if (n >= 3) pool.add(EntityTypes.SPIDER);
        if (n >= 4) pool.add(EntityTypes.CREEPER);
        if (n >= 5) pool.add(EntityTypes.STRAY);
        if (n >= 6) pool.add(EntityTypes.VINDICATOR);
        if (n >= 7) pool.add(EntityTypes.WITCH);
        if (n >= 8) pool.add(EntityTypes.WITHER_SKELETON);
        if (n >= 10 && i % 12 == 0) return EntityTypes.RAVAGER;
        if (i % 6 == 2) return org.webtrade.strategy1.registry.ModContent.SHIELD_ZOMBIE;
        if (n >= 2 && i % 7 == 4) return org.webtrade.strategy1.registry.ModContent.BOMBER_ZOMBIE;
        return pool.get(r.nextInt(pool.size()));
    }

    private static Mob spawn(ServerLevel level, EntityType<? extends Mob> type, Vec3 at, Vec3 keep, int n) {
        Mob m = type.create(level, EntitySpawnReason.EVENT);
        if (m == null) return null;
        m.snapTo(at.x, at.y, at.z, level.getRandom().nextFloat() * 360, 0);
        m.setPersistenceRequired();
        m.addTag(TAG);
        // the dead are tougher every night: more of them to kill, harder blows, a little quicker; helmets from the third
        // night, more armour later (none of it dropped)
        var hp = m.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * (1.15 + 0.1 * n));
            m.setHealth(m.getMaxHealth());
        }
        var hit = m.getAttribute(Attributes.ATTACK_DAMAGE);
        if (hit != null) hit.setBaseValue(hit.getBaseValue() * (1.1 + 0.08 * n));
        var speed = m.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * Math.min(1.3, 1 + 0.03 * n));
        if (n >= 3 && m.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(n >= 6 ? Items.IRON_HELMET : Items.CHAINMAIL_HELMET));
        if (n >= 5 && level.getRandom().nextInt(3) == 0) m.setItemSlot(EquipmentSlot.CHEST, new ItemStack(n >= 8 ? Items.IRON_CHESTPLATE : Items.CHAINMAIL_CHESTPLATE));
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST}) m.setDropChance(slot, 0);
        var follow = m.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null) follow.setBaseValue(48);
        if (type == EntityTypes.SKELETON || type == EntityTypes.STRAY) m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        if (type == EntityTypes.VINDICATOR) m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        if (type == EntityTypes.WITHER_SKELETON) m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
        if (m instanceof org.webtrade.strategy1.entity.ShieldZombie sz) sz.arm(n);
        if (m instanceof org.webtrade.strategy1.entity.BomberZombie bz) {
            // (a bomber has his own errand: the walls)
            bz.arm(n);
            level.addFreshEntity(m);
            WAVE.add(m);
            return m;
        }
        MobAccess acc = (MobAccess) m;
        acc.strategy1$targets().addGoal(1, new NearestAttackableTargetGoal<>(m, UnitEntity.class, 10, true, false,
                (e, l) -> e instanceof UnitEntity u && u.faction() != Faction.NEUTRAL));
        if (m instanceof PathfinderMob pm) acc.strategy1$goals().addGoal(4, new March(pm, keep));
        level.addFreshEntity(m);
        WAVE.add(m);
        return m;
    }

    /** The far lands (not loaded, unseen): each of the realm's provinces with too few defenders for the night falls. */
    private static void besiegeFarLands(ServerLevel level, StrategyData data, Faction f, int n) {
        RandomSource r = level.getRandom();
        for (Province p : new ArrayList<>(data.owned(f.id))) {
            if (p.key() == f.capital) continue;
            if (level.hasChunkAt(BlockPos.containing(p.hex.centerX(), 64, p.hex.centerZ()))) continue;
            int towers = 0;
            for (Structure s : data.structures.values()) if (s.owner == f.id && s.province() == p.key() && s.type.manned() && s.built()) towers += 1 + s.archers;
            double attack = n * (0.8 + r.nextDouble() * 0.6), defence = p.garrison * 1.5 + towers * 2 + 1;
            if (attack > defence) StrategyManager.lostToTheDead(level, p);
            else p.garrison = Math.max(0, p.garrison - (int) (attack / 3));
        }
    }

    /** The dead march on the keep, battering what stands in their way; creepers blow it open. */
    static final class March extends Goal {
        private final PathfinderMob mob;
        private final Vec3 keep;
        private int repath, hit;

        March(PathfinderMob mob, Vec3 keep) {
            this.mob = mob;
            this.keep = keep;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return mob.getTarget() == null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (!(mob.level() instanceof ServerLevel level)) return;
            // a wall or a building of the realm's in the way: break it
            if (++hit >= 20) {
                hit = 0;
                Structure s = Structures.touching(mob.getBoundingBox().inflate(1.2), Faction.NEUTRAL);
                if (s != null) {
                    mob.getNavigation().stop();
                    mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    if (mob instanceof Creeper) {
                        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, mob.getX(), mob.getY() + 1, mob.getZ(), 1, 0, 0, 0, 0);
                        level.playSound(null, mob.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2F, 1F);
                        Structures.impact(level, mob.position().add(0, 1, 0), Faction.NEUTRAL, 160, 4);
                        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(4), x -> x instanceof UnitEntity))
                            e.hurtServer(level, mob.damageSources().explosion(mob, mob), 12);
                        mob.discard();
                        return;
                    }
                    float dmg = (float) (mob.getAttributeValue(Attributes.ATTACK_DAMAGE) + (mob.getAttributeValue(Attributes.SCALE) > 1.5 ? 20 : 2));
                    Structures.strike(mob, s.id, dmg);
                    return;
                }
            }
            if (--repath > 0 && mob.getNavigation().isInProgress()) return;
            repath = 30;
            Vec3 goal = keep;
            if (mob.position().distanceTo(keep) > 30) {
                Vec3 dir = keep.subtract(mob.position()).normalize().scale(24);
                goal = SoldierEntity.surface(level, mob.getX() + dir.x, mob.getZ() + dir.z);
            }
            mob.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0);
        }
    }
}
