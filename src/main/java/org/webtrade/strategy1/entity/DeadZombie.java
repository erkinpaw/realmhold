package org.webtrade.strategy1.entity;

import net.minecraft.core.Holder;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * One of the siege's own dead with a look of its own (the plague-bearer, the ram-bearer, the crawler, the giant): a
 * skin of three (a zombie's, a husk's, a drowned one's), what it carries drawn on it by the client. Made ready for its
 * night when it comes (or on its first tick, if it was summoned some other way).
 */
public abstract class DeadZombie extends Zombie {

    private static final EntityDataAccessor<Integer> DATA_SKIN = SynchedEntityData.defineId(DeadZombie.class, EntityDataSerializers.INT);
    /** The skins: a zombie's, a husk's, a drowned one's. */
    public static final int SKINS = 3;
    private boolean armed;

    protected DeadZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, 0);
    }

    public int skin() {
        return entityData.get(DATA_SKIN);
    }

    public void setSkin(int skin) {
        entityData.set(DATA_SKIN, Math.floorMod(skin, SKINS));
    }

    /** Ready for the night: its skin, its strength (before the night's own toughening is laid on). */
    public void arm(int night) {
        armed = true;
        entityData.set(DATA_SKIN, random.nextInt(SKINS));
        for (EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0);
    }

    /** An attribute set (its base), and the health filled up to it. */
    protected void base(Holder<Attribute> attribute, double value) {
        AttributeInstance a = getAttribute(attribute);
        if (a != null) a.setBaseValue(value);
        setHealth(getMaxHealth());
    }

    /** An attribute's base multiplied. */
    protected void times(Holder<Attribute> attribute, double k) {
        AttributeInstance a = getAttribute(attribute);
        if (a != null) a.setBaseValue(a.getBaseValue() * k);
    }

    @Override
    public void tick() {
        if (!armed && !level().isClientSide()) arm(1);
        super.tick();
    }

    @Override
    protected boolean convertsInWater() {
        return false;
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putInt("Skin", skin());
        out.putBoolean("Armed", armed);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        entityData.set(DATA_SKIN, in.getIntOr("Skin", 0));
        armed = in.getBooleanOr("Armed", false);
    }
}
