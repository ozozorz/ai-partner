package io.github.ozozorz.aipartner.registry;

import io.github.ozozorz.aipartner.AiPartnerMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * 数据包可扩展的女仆物品标签。
 */
public final class ModItemTags {
    public static final TagKey<Item> MAID_TAMING_FOODS = TagKey.create(
            Registries.ITEM,
            AiPartnerMod.id("maid_taming_foods")
    );

    private ModItemTags() {
    }
}
