package org.webtrade.strategy1.entity;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * One of the dead behind a great shield: the shield takes the blows until it breaks, and only then the body. Arrows
 * hardly mark it; swords hack at it; a ballista's bolt or a catapult's stone smashes it. Fire and blasts go round it.
 */
public class ShieldZombie extends Zombie {

    private static final EntityDataAccessor<Integer> DATA_SHIELD = SynchedEntityData.defineId(ShieldZombie.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SHIELD_MAX = SynchedEntityData.defineId(ShieldZombie.class, EntityDataSerializers.INT);
    /** The tag a tower's ballista bolt carries. */
    public static final String BOLT = "s1bolt";

    public ShieldZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SHIELD, 0);
        builder.define(DATA_SHIELD_MAX, 0);
    }

    /** Ready for the night: a shield as strong as the night is late. */
    public void arm(int night) {
        int s = 30 + night * 6;
        entityData.set(DATA_SHIELD, s);
        entityData.set(DATA_SHIELD_MAX, s);
        setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        for (EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0);
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * 0.85);
    }

    public int shield() {
        return entityData.get(DATA_SHIELD);
    }

    public int shieldMax() {
        return entityData.get(DATA_SHIELD_MAX);
    }

    /** How hard a blow comes down on the shield. */
    private static double against(DamageSource source) {
        Entity direct = source.getDirectEntity();
        if (direct != null && direct.entityTags().contains(BOLT)) return 4.0;
        if (direct instanceof BoulderEntity) return 3.0;
        if (direct instanceof AbstractArrow) return 0.15;
        return 0.6;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        int s = shield();
        if (s > 0 && !source.is(DamageTypeTags.IS_FIRE) && !source.is(DamageTypeTags.IS_EXPLOSION) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            int left = Math.max(0, s - (int) Math.ceil(amount * against(source)));
            entityData.set(DATA_SHIELD, left);
            if (left == 0) {
                setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                level.playSound(null, blockPosition(), SoundEvents.SHIELD_BREAK.value(), SoundSource.HOSTILE, 1.2F, 0.9F);
                level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Items.SHIELD), getX(), getY() + 1, getZ(), 14, 0.3, 0.4, 0.3, 0.12);
            } else level.playSound(null, blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 0.9F, 0.8F + random.nextFloat() * 0.3F);
            if (source.getDirectEntity() instanceof AbstractArrow arrow) arrow.discard();
            return false;
        }
        return super.hurtServer(level, source, amount);
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
        out.putInt("Shield", shield());
        out.putInt("ShieldMax", shieldMax());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        entityData.set(DATA_SHIELD, in.getIntOr("Shield", 0));
        entityData.set(DATA_SHIELD_MAX, in.getIntOr("ShieldMax", 0));
    }
}
