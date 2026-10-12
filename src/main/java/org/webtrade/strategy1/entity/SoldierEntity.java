package org.webtrade.strategy1.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyManager;
import org.webtrade.strategy1.game.UnitType;
import org.webtrade.strategy1.registry.ModContent;

/**
 * A soldier of a realm, in its colours. A player's soldiers take orders (hold, go there, attack that, man a tower or
 * a wall, board a ship, break a building); a garrison's guard their province's centre; archers posted on towers and
 * walls stand there and shoot; a raiding party marches on a province to take it.
 */
public class SoldierEntity extends UnitEntity {

    public enum Mode {FOLLOW, HOLD, MOVE, GUARD, RAID, POSTED, POST, BOARD, BREAK}

    private static final EntityDataAccessor<Integer> DATA_UNIT = SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SKIN = SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);

    public Mode mode = Mode.HOLD;
    /** Where they hold, march to, or guard. */
    public Vec3 anchor = Vec3.ZERO;
    /** The province guarded or raided. */
    public long province;
    /** The structure manned, to be manned or broken; the ship to board (entity id). */
    public int structure = -1, ship = -1;
    private int reload;
    private int repath;

    public SoldierEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 2.0).add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_UNIT, 0);
        builder.define(DATA_SKIN, 0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new SoldierGoals.Melee(this));
        goalSelector.addGoal(1, new SoldierGoals.Shoot(this));
        goalSelector.addGoal(2, new SoldierGoals.Orders(this));
        goalSelector.addGoal(3, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new SoldierGoals.FindEnemy(this));
    }

    public UnitType unit() {
        return UnitType.of(entityData.get(DATA_UNIT));
    }

    public int skin() {
        return entityData.get(DATA_SKIN);
    }

    // ------------------------------------------------------------------ raising them

    /** A new soldier at a place, of a realm (or of the bandits, faction 0). */
    public static SoldierEntity spawn(ServerLevel level, UnitType unit, int faction, int color, Vec3 at, Role role) {
        SoldierEntity s = ModContent.SOLDIER.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (s == null) return null;
        s.setup(unit, faction, color, role);
        Vec3 p = at.y > level.getMinY() + 1 && at.y != 0 ? at : surface(level, at.x, at.z);
        s.snapTo(p.x, p.y, p.z, level.getRandom().nextFloat() * 360, 0);
        s.anchor = p;
        level.addFreshEntity(s);
        return s;
    }

    /** The top of the ground at a column (not on a tree's crown or trunk). */
    public static Vec3 surface(ServerLevel level, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        return new Vec3(bx + 0.5, y, bz + 0.5);
    }

    private void setup(UnitType unit, int faction, int color, Role role) {
        this.role = role;
        setRealm(faction, color);
        entityData.set(DATA_UNIT, unit.ordinal());
        entityData.set(DATA_SKIN, random.nextInt(9));
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(unit.health);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(unit.speed);
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(unit.damage * 0.5);
        setHealth(getMaxHealth());
        dress(unit, color);
        setCustomName(faction == Faction.NEUTRAL ? Component.translatable("strategy1.unit.bandit").withColor(color)
                : Component.translatable(unit.key()).withColor(color));
        setCustomNameVisible(false);
    }

    private static ItemStack dyed(Item item, int rgb) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
        return s;
    }

    /** The realm's colours on a leather tabard; helmets and mail by the kind of soldier. */
    private void dress(UnitType unit, int color) {
        setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, color));
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(unit.weapon()));
        switch (unit) {
            case MILITIA, ARCHER -> {
                setItemSlot(EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, color));
                setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, 0x4A3A2A));
            }
            case SWORDSMAN -> {
                setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, color));
                setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            }
            case KNIGHT -> {
                setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            }
            case GUARD -> {
                setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
                setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
                setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
                setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0);
    }

    @Override
    public boolean hostileTo(LivingEntity e) {
        // (swordsmen can't reach a ship at sea)
        if (e instanceof ShipEntity && !unit().ranged) return false;
        return super.hostileTo(e);
    }

    // ------------------------------------------------------------------ orders

    public void order(Mode mode, Vec3 at) {
        if (isPassenger() && mode != Mode.HOLD) return;
        this.mode = mode;
        if (at != null) anchor = at;
        setTarget(null);
        getNavigation().stop();
        repath = 0;
    }

    /** Its place in a loose formation round a point (so a squad does not stand in one block). */
    Vec3 slot(Vec3 around, float facing) {
        int i = Math.floorMod(getId(), 9);
        if (i == 0) return around;
        double a = Math.toRadians(facing + 180) + (i - 1) * (Math.PI / 4);
        double r = 2.2 + (i % 2);
        return around.add(-Math.sin(a) * r, 0, Math.cos(a) * r);
    }

    /** Walks towards a point, a stretch at a time over long ways. */
    void walkTo(Vec3 to, double speed) {
        if (--repath > 0 && getNavigation().isInProgress()) return;
        repath = 15;
        Vec3 goal = to;
        double d = position().distanceTo(to);
        if (d > 36 && level() instanceof ServerLevel sl) {
            Vec3 dir = to.subtract(position()).normalize().scale(30);
            goal = surface(sl, getX() + dir.x, getZ() + dir.z);
        }
        getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
    }

    void shoot(LivingEntity target) {
        if (reload > 0 || !(level() instanceof ServerLevel sl)) return;
        reload = (mode == Mode.POSTED ? 28 : 35) + random.nextInt(15);
        ItemStack arrowStack = new ItemStack(Items.ARROW);
        Arrow arrow = new Arrow(sl, this, arrowStack, getMainHandItem());
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        double dx = target.getX() - getX(), dy = target.getY(0.3333) - arrow.getY(), dz = target.getZ() - getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        Projectile.spawnProjectileUsingShoot(arrow, sl, arrowStack, dx, dy + flat * 0.2, dz, 1.7F, 4F);
        playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (random.nextFloat() * 0.4F + 0.8F));
        swing(InteractionHand.MAIN_HAND);
    }

    /** A blow at a building (it's slow work without siege engines). */
    void strike() {
        if (reload > 0) return;
        reload = 20;
        swing(InteractionHand.MAIN_HAND);
        StrategyManager.strikeStructure(this, structure, (float) (1.5 + unit().damage * 0.4));
    }

    @Override
    public void aiStep() {
        updateSwingTime();
        super.aiStep();
    }

    @Override
    public void tick() {
        super.tick();
        if (reload > 0) reload--;
        if (!(level() instanceof ServerLevel)) return;
        if (role == Role.ARMY && isAlive()) StrategyManager.armyTick(this);
        if (mode == Mode.POSTED) {
            // at their post: they stand where they were put
            setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
            if (position().distanceToSqr(anchor) > 0.6) snapTo(anchor.x, anchor.y, anchor.z, getYRot(), getXRot());
        }
        if (role != Role.ARMY && tickCount % 40 == 0 && !StrategyManager.stillWanted(this)) discard();
    }

    @Override
    public boolean isPushable() {
        return mode != Mode.POSTED && super.isPushable();
    }

    /** The block below a soldier on a palisade's top is a log; it shouldn't count as falling off. */
    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, net.minecraft.core.BlockPos pos) {
        if (mode != Mode.POSTED) super.checkFallDamage(y, onGround, state, pos);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putInt("Unit", unit().ordinal());
        out.putInt("Skin", skin());
        out.putInt("Mode", mode == Mode.POSTED || mode == Mode.BOARD || mode == Mode.POST || mode == Mode.BREAK ? Mode.HOLD.ordinal() : mode.ordinal());
        out.putDouble("AX", anchor.x);
        out.putDouble("AY", anchor.y);
        out.putDouble("AZ", anchor.z);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        entityData.set(DATA_UNIT, in.getIntOr("Unit", 0));
        entityData.set(DATA_SKIN, in.getIntOr("Skin", 0));
        mode = Mode.values()[Math.floorMod(in.getIntOr("Mode", 1), Mode.values().length)];
        if (mode == Mode.FOLLOW) mode = Mode.HOLD;
        anchor = new Vec3(in.getDoubleOr("AX", getX()), in.getDoubleOr("AY", getY()), in.getDoubleOr("AZ", getZ()));
    }
}
