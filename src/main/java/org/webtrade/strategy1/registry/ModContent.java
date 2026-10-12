package org.webtrade.strategy1.registry;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.entity.SoldierEntity;

public final class ModContent {

    public static final EntityType<SoldierEntity> SOLDIER = entity("soldier",
            EntityType.Builder.<SoldierEntity>of(SoldierEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F).eyeHeight(1.62F).noLootTable().clientTrackingRange(10));

    public static final EntityType<org.webtrade.strategy1.entity.SiegeEntity> CATAPULT = entity("catapult",
            EntityType.Builder.<org.webtrade.strategy1.entity.SiegeEntity>of(org.webtrade.strategy1.entity.SiegeEntity::new, MobCategory.MISC)
                    .sized(2.2F, 1.6F).noLootTable().clientTrackingRange(10));
    public static final EntityType<org.webtrade.strategy1.entity.SiegeEntity> TREBUCHET = entity("trebuchet",
            EntityType.Builder.<org.webtrade.strategy1.entity.SiegeEntity>of(org.webtrade.strategy1.entity.SiegeEntity::new, MobCategory.MISC)
                    .sized(2.6F, 3.2F).noLootTable().clientTrackingRange(10));
    public static final EntityType<org.webtrade.strategy1.entity.ShipEntity> TRANSPORT = entity("transport",
            EntityType.Builder.<org.webtrade.strategy1.entity.ShipEntity>of(org.webtrade.strategy1.entity.ShipEntity::new, MobCategory.MISC)
                    .sized(3.0F, 1.3F).noLootTable().clientTrackingRange(12).updateInterval(1));
    public static final EntityType<org.webtrade.strategy1.entity.ShipEntity> WARSHIP = entity("warship",
            EntityType.Builder.<org.webtrade.strategy1.entity.ShipEntity>of(org.webtrade.strategy1.entity.ShipEntity::new, MobCategory.MISC)
                    .sized(3.6F, 1.6F).noLootTable().clientTrackingRange(12).updateInterval(1));
    public static final EntityType<org.webtrade.strategy1.entity.BoulderEntity> BOULDER = entity("boulder",
            EntityType.Builder.<org.webtrade.strategy1.entity.BoulderEntity>of(org.webtrade.strategy1.entity.BoulderEntity::new, MobCategory.MISC)
                    .sized(0.8F, 0.8F).noLootTable().noSave().clientTrackingRange(10).updateInterval(1));

    public static final EntityType<org.webtrade.strategy1.entity.PeasantEntity> PEASANT = entity("peasant",
            EntityType.Builder.<org.webtrade.strategy1.entity.PeasantEntity>of(org.webtrade.strategy1.entity.PeasantEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F).eyeHeight(1.62F).noLootTable().clientTrackingRange(10));

    /** The siege's own dead: the shield-bearer and the bomber. */
    public static final EntityType<org.webtrade.strategy1.entity.ShieldZombie> SHIELD_ZOMBIE = entity("shield_zombie",
            EntityType.Builder.<org.webtrade.strategy1.entity.ShieldZombie>of(org.webtrade.strategy1.entity.ShieldZombie::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F).eyeHeight(1.74F).noLootTable().clientTrackingRange(10));
    public static final EntityType<org.webtrade.strategy1.entity.BomberZombie> BOMBER_ZOMBIE = entity("bomber_zombie",
            EntityType.Builder.<org.webtrade.strategy1.entity.BomberZombie>of(org.webtrade.strategy1.entity.BomberZombie::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F).eyeHeight(1.74F).noLootTable().clientTrackingRange(10));

    private ModContent() {
    }

    public static void register() {
        FabricDefaultAttributeRegistry.register(SOLDIER, SoldierEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(PEASANT, org.webtrade.strategy1.entity.PeasantEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(CATAPULT, org.webtrade.strategy1.entity.SiegeEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(TREBUCHET, org.webtrade.strategy1.entity.SiegeEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(TRANSPORT, org.webtrade.strategy1.entity.ShipEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(WARSHIP, org.webtrade.strategy1.entity.ShipEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(SHIELD_ZOMBIE, net.minecraft.world.entity.monster.zombie.Zombie.createAttributes());
        FabricDefaultAttributeRegistry.register(BOMBER_ZOMBIE, net.minecraft.world.entity.monster.zombie.Zombie.createAttributes());
    }

    private static <T extends net.minecraft.world.entity.Entity> EntityType<T> entity(String name, EntityType.Builder<T> builder) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Strategy1.id(name));
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
    }
}
