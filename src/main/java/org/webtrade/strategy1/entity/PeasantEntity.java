package org.webtrade.strategy1.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.build.Structure;
import org.webtrade.strategy1.build.StructureType;
import org.webtrade.strategy1.build.Structures;
import org.webtrade.strategy1.build.Trees;
import org.webtrade.strategy1.build.Workforce;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.Res;
import org.webtrade.strategy1.registry.ModContent;

import java.util.EnumSet;

/**
 * A peasant of a realm. Idle, they wait by the keep; given work, they go and do it and carry what they got to the
 * keep or the nearest stockpile: a woodcutter fells the trees round his hut (the whole tree comes down, a sapling
 * goes in), a farmer reaps the wheat of his field, quarrymen and miners hew stone, iron and gold. Soldiers are
 * raised from them.
 */
public class PeasantEntity extends UnitEntity {

    public enum Task {IDLE, TO_WORK, WORK, TO_STORE}

    private static final EntityDataAccessor<Integer> DATA_SKIN = SynchedEntityData.defineId(PeasantEntity.class, EntityDataSerializers.INT);

    /** The building they work at (structure id), or -1. */
    public int job = -1;
    public Task task = Task.IDLE;
    private int timer, repath, stuck;
    private boolean fleeing;
    /** What they carry: how much of which resource. */
    private int carry;
    private Res carried = Res.WOOD;
    private BlockPos target;
    public Vec3 home = Vec3.ZERO;

    public PeasantEntity(EntityType<? extends PeasantEntity> type, Level level) {
        super(type, level);
        role = Role.WORKER;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 12.0).add(Attributes.MOVEMENT_SPEED, 0.28).add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, 0);
    }

    public int skin() {
        return entityData.get(DATA_SKIN);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new Work());
        goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    public static PeasantEntity spawn(ServerLevel level, Faction f, Vec3 at) {
        PeasantEntity p = ModContent.PEASANT.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (p == null) return null;
        p.setRealm(f.id, f.color);
        p.commander = f.ruler;
        p.entityData.set(DATA_SKIN, level.getRandom().nextInt(9));
        Vec3 s = SoldierEntity.surface(level, at.x, at.z);
        p.snapTo(s.x, s.y, s.z, level.getRandom().nextFloat() * 360, 0);
        p.home = s;
        p.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, 0x7A5A3A));
        p.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, 0x4A3A2A));
        for (EquipmentSlot slot : EquipmentSlot.values()) p.setDropChance(slot, 0);
        p.setCustomName(Component.translatable("strategy1.unit.peasant").withColor(f.color));
        level.addFreshEntity(p);
        return p;
    }

    private static ItemStack dyed(net.minecraft.world.item.Item item, int rgb) {
        ItemStack s = new ItemStack(item);
        s.set(net.minecraft.core.component.DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(rgb));
        return s;
    }

    public String describe() {
        return task + " target " + target + " carry " + carry + " at " + blockPosition().toShortString() + " nav " + getNavigation().isInProgress();
    }

    public boolean idle() {
        return job < 0;
    }

    /** Sent to work at a building (or let go, -1). */
    public void assign(int structure) {
        if (target != null) Trees.release(target);
        target = null;
        job = structure;
        task = structure < 0 ? Task.IDLE : Task.TO_WORK;
        carry = 0;
        timer = 0;
        hold(null);
    }

    public Structure workplace() {
        return job < 0 || data() == null ? null : data().structures.get(job);
    }

    private void hold(net.minecraft.world.item.Item item) {
        setItemSlot(EquipmentSlot.MAINHAND, item == null ? ItemStack.EMPTY : new ItemStack(item));
    }

    private void walk(Vec3 to, double speed) {
        if (--repath > 0 && getNavigation().isInProgress()) return;
        repath = 20;
        Vec3 goal = to;
        if (position().distanceTo(to) > 36 && level() instanceof ServerLevel sl) {
            Vec3 dir = to.subtract(position()).normalize().scale(30);
            goal = SoldierEntity.surface(sl, getX() + dir.x, getZ() + dir.z);
        }
        getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
    }

    private static net.minecraft.world.item.Item tool(StructureType t) {
        return switch (t) {
            case WOODCUTTER -> Items.IRON_AXE;
            case FARM -> Items.IRON_HOE;
            case QUARRY -> Items.STONE_PICKAXE;
            case IRON_MINE -> Items.IRON_PICKAXE;
            case GOLD_MINE -> Items.GOLDEN_PICKAXE;
            default -> null;
        };
    }

    private static net.minecraft.world.item.Item load(Res r) {
        return switch (r) {
            case FOOD -> Items.WHEAT;
            case WOOD -> Items.OAK_LOG;
            case STONE -> Items.COBBLESTONE;
            case IRON -> Items.RAW_IRON;
            case GOLD -> Items.RAW_GOLD;
        };
    }

    // ------------------------------------------------------------------ the day's work

    private final class Work extends Goal {
        Work() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return level() instanceof ServerLevel;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            ServerLevel level = (ServerLevel) level();
            // the dead about: everyone runs for the keep
            if (tickCount % 10 == 0) fleeing = !level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, getBoundingBox().inflate(12), m -> m.isAlive()).isEmpty();
            if (fleeing) {
                walk(home, 1.4);
                return;
            }
            Structure st = workplace();
            if (job >= 0 && (st == null || !st.built() || st.owner != faction())) {
                assign(-1);
                st = null;
            }
            if (st == null) {
                // idle by the keep
                if (position().distanceTo(home) > 8) walk(home, 1.0);
                else if (random.nextInt(120) == 0) {
                    Vec3 w = home.add(random.nextGaussian() * 4, 0, random.nextGaussian() * 4);
                    getNavigation().moveTo(w.x, w.y, w.z, 0.7);
                }
                return;
            }
            switch (task) {
                case IDLE, TO_WORK -> goToWork(level, st);
                case WORK -> work(level, st);
                case TO_STORE -> store(level);
            }
        }
    }

    private void goToWork(ServerLevel level, Structure st) {
        task = Task.TO_WORK;
        hold(tool(st.type));
        if (target == null) {
            target = switch (st.type) {
                case WOODCUTTER -> Trees.nearest(level, BlockPos.containing(st.center()), 28);
                case FARM -> Workforce.crop(level, st, random);
                default -> st.spots.isEmpty() ? BlockPos.containing(st.center()) : BlockPos.containing(st.spots.get(0));
            };
            if (target == null) {
                // (no trees left near: they wait by the hut)
                if (position().distanceTo(st.center()) > 5) walk(st.center(), 1.0);
                return;
            }
        }
        Vec3 t = Vec3.atBottomCenterOf(target);
        double flat = Math.hypot(t.x - getX(), t.z - getZ()), up = Math.abs(t.y - getY());
        if (flat > (st.type == StructureType.WOODCUTTER ? 2.6 : 2.2) || up > 3) {
            walk(t, 1.0);
            if (getNavigation().isDone() && flat < 4.5 && up <= 4) {
                startWork(st);
                return;
            }
            // (a tree he can't get to: he leaves it, and nobody goes for it again)
            if (++stuck > 300) {
                stuck = 0;
                target = null;
            }
            return;
        }
        stuck = 0;
        startWork(st);
    }

    private void startWork(Structure st) {
        getNavigation().stop();
        task = Task.WORK;
        timer = switch (st.type) {
            case WOODCUTTER -> 90;
            case FARM -> 40;
            default -> 120;
        };
    }

    private void work(ServerLevel level, Structure st) {
        if (target == null) {
            task = Task.TO_WORK;
            return;
        }
        getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.8, target.getZ() + 0.5);
        if (timer % 12 == 0) {
            swing(InteractionHand.MAIN_HAND, true);
            BlockState at = level.getBlockState(target);
            if (!at.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, at), target.getX() + 0.5, target.getY() + 0.8,
                    target.getZ() + 0.5, 4, 0.2, 0.2, 0.2, 0.05);
            level.playSound(null, target, st.type == StructureType.WOODCUTTER ? SoundEvents.WOOD_HIT : st.type == StructureType.FARM
                    ? SoundEvents.CROP_BREAK : SoundEvents.STONE_HIT, SoundSource.BLOCKS, 0.6F, 0.9F + random.nextFloat() * 0.2F);
        }
        if (--timer > 0) return;
        switch (st.type) {
            case WOODCUTTER -> {
                int logs = Trees.fell(level, target);
                carry = Math.max(logs > 0 ? 3 : 0, Math.min(10, logs));
                carried = Res.WOOD;
                target = null;
                if (carry == 0) {
                    task = Task.TO_WORK;
                    return;
                }
            }
            case FARM -> {
                BlockState s = level.getBlockState(target);
                if (s.getBlock() instanceof CropBlock) level.setBlock(target, s.setValue(CropBlock.AGE, 0), 3);
                carried = Res.FOOD;
                carry += 2;
                target = null;
                if (carry < 6) {
                    task = Task.TO_WORK;
                    return;
                }
            }
            case QUARRY -> {
                carried = Res.STONE;
                carry = Workforce.richness(st, Res.STONE) ? 6 : 4;
            }
            case IRON_MINE -> {
                carried = Res.IRON;
                carry = Workforce.richness(st, Res.IRON) ? 3 : 2;
            }
            case GOLD_MINE -> {
                carried = Res.GOLD;
                carry = Workforce.richness(st, Res.GOLD) ? 2 : 1;
            }
            default -> {
                task = Task.TO_WORK;
                return;
            }
        }
        task = Task.TO_STORE;
        hold(load(carried));
    }

    private void store(ServerLevel level) {
        Structure store = Structures.nearestStore(faction(), position());
        Vec3 to = store == null ? home : Structures.door(store);
        if (position().distanceTo(to) > 3) {
            walk(to, 1.0);
            return;
        }
        Faction f = data().faction(faction());
        if (f != null) {
            f.res[carried.ordinal()] += carry;
            Workforce.DELIVERED[carried.ordinal()] += carry;
            data().touch();
        }
        level.playSound(null, blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.5F, 1.2F);
        carry = 0;
        task = Task.TO_WORK;
        target = null;
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel && isAlive()) Workforce.tick(this);
    }

    @Override
    public boolean hostileTo(net.minecraft.world.entity.LivingEntity e) {
        return false;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (target != null) Trees.release(target);
        super.remove(reason);
    }

    @Override
    public boolean shouldBeSaved() {
        return super.shouldBeSaved() || role == Role.WORKER && !isRemoved() && !isPassenger();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putInt("Job", job);
        out.putInt("Skin", skin());
        out.putDouble("HX", home.x);
        out.putDouble("HY", home.y);
        out.putDouble("HZ", home.z);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        role = Role.WORKER;
        job = in.getIntOr("Job", -1);
        task = job < 0 ? Task.IDLE : Task.TO_WORK;
        entityData.set(DATA_SKIN, in.getIntOr("Skin", 0));
        home = new Vec3(in.getDoubleOr("HX", getX()), in.getDoubleOr("HY", getY()), in.getDoubleOr("HZ", getZ()));
    }
}
