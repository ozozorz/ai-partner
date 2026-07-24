package io.github.ozozorz.aipartner.registry;

import io.github.ozozorz.aipartner.AiPartnerMod;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

/**
 * 注册女仆生成蛋及其创造模式物品栏入口。
 */
public final class ModItems {
    public static final Item AI_PARTNER_SPAWN_EGG = registerSpawnEgg();

    private ModItems() {
    }

    public static void register() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS)
                .register(output -> output.accept(AI_PARTNER_SPAWN_EGG));
        AiPartnerMod.LOGGER.info("Registered AI Partner spawn egg");
    }

    private static Item registerSpawnEgg() {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, AiPartnerMod.id("ai_partner_spawn_egg"));
        Item item = new SpawnEggItem(new Item.Properties()
                .setId(key)
                .spawnEgg(ModEntities.AI_PARTNER));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }
}
