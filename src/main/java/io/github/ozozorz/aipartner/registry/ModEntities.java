package io.github.ozozorz.aipartner.registry;

import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * 集中注册 AI Partner 使用的实体类型与默认属性。
 */
public final class ModEntities {
    public static final EntityType<AiPartnerEntity> AI_PARTNER = registerEntityType();

    private ModEntities() {
    }

    /**
     * 注册实体属性。实体类型本身在静态初始化阶段完成注册。
     */
    public static void register() {
        FabricDefaultAttributeRegistry.register(AI_PARTNER, AiPartnerEntity.createAttributes());
        AiPartnerMod.LOGGER.info("Registered AI Partner entity type");
    }

    private static EntityType<AiPartnerEntity> registerEntityType() {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, AiPartnerMod.id("ai_partner"));
        EntityType<AiPartnerEntity> type = EntityType.Builder
                .of(AiPartnerEntity::new, MobCategory.MISC)
                .sized(0.6F, 1.8F)
                .eyeHeight(1.62F)
                .clientTrackingRange(10)
                .build(key);
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type);
    }
}

