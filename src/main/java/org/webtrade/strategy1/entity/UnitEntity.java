package org.webtrade.strategy1.entity;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.game.StrategyData;
import org.webtrade.strategy1.game.StrategyManager;

import java.util.UUID;

/**
 * Anything of a realm's that fights: soldiers, siege engines, ships. Each is in its realm's colour, knows who leads
 * it, fights whoever its realm is at war with, takes no harm from its own side, and goes faster on a path.
 */
public abstract class UnitEntity extends PathfinderMob {

    public enum Role {ARMY, GARRISON, RAID, POST}

    private static final EntityDataAccessor<Integer> DATA_FACTION = SynchedEntityData.defineId(UnitEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_COLOR = SynchedEntityData.defineId(UnitEntity.class, EntityDataSerializers.INT);
    private static final net.minecraft.resources.Identifier PATH_SPEED = Strategy1.id("path_speed");

    public Role role = Role.ARMY;
    /** The player leading it (an army's unit). */
    public UUID commander;

    protected UnitEntity(EntityType<? extends UnitEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FACTION, 0);
        builder.define(DATA_COLOR, 0x777777);
    }

    public int faction() {
        return entityData.get(DATA_FACTION);
    }

    public int color() {
        return entityData.get(DATA_COLOR);
    }

    protected void setRealm(int faction, int color) {
        entityData.set(DATA_FACTION, faction);
        entityData.set(DATA_COLOR, color);
    }

    protected static StrategyData data() {
        return StrategyManager.data();
    }

    // ------------------------------------------------------------------ friend or foe

    /** Is this one to be fought? */
    public boolean hostileTo(LivingEntity e) {
        StrategyData d = data();
        if (d == null || e == this || !e.isAlive()) return false;
        if (e instanceof UnitEntity u) return d.hostile(faction(), u.faction());
        if (e instanceof ServerPlayer p) {
            if (p.isCreative() || p.isSpectator()) return false;
            return d.hostile(faction(), d.factionIdOf(p.getUUID()));
        }
        if (e instanceof Enemy) return role != Role.RAID && faction() != Faction.NEUTRAL;
        return false;
    }

    /** Is this one on our side (no harm from them)? */
    public boolean friend(Entity e) {
        StrategyData d = data();
        if (d == null || e == null) return false;
        if (e instanceof UnitEntity u) return u.faction() == faction() || !d.hostile(faction(), u.faction());
        if (e instanceof Player p) return d.factionIdOf(p.getUUID()) == faction();
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (friend(source.getEntity())) return false;
        return super.hurtServer(level, source, amount);
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (level() instanceof ServerLevel) StrategyManager.unitDied(this, source);
    }

    public ServerPlayer commanderPlayer() {
        if (commander == null || !(level() instanceof ServerLevel sl)) return null;
        return sl.getServer().getPlayerList().getPlayer(commander);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level() instanceof ServerLevel && tickCount % 10 == 0) {
            var speed = getAttribute(Attributes.MOVEMENT_SPEED);
            boolean onPath = level().getBlockState(getOnPos()).is(Blocks.DIRT_PATH) || level().getBlockState(blockPosition()).is(Blocks.DIRT_PATH);
            if (speed != null) {
                if (onPath) speed.addOrUpdateTransientModifier(new AttributeModifier(PATH_SPEED, 0.4, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                else speed.removeModifier(PATH_SPEED);
            }
        }
    }

    // ------------------------------------------------------------------ the rest

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return role == Role.ARMY && super.shouldBeSaved();
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected void dropEquipment(ServerLevel level) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putInt("Faction", faction());
        out.putInt("Color", color());
        if (commander != null) out.store("Commander", UUIDUtil.CODEC, commander);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        setRealm(in.getIntOr("Faction", 0), in.getIntOr("Color", 0x777777));
        commander = in.read("Commander", UUIDUtil.CODEC).orElse(null);
        role = Role.ARMY;
    }
}
