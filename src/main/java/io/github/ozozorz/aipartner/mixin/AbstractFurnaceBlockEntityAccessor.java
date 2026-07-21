package io.github.ozozorz.aipartner.mixin;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 允许女仆在按原版语义生成熔炼经验后清除对应配方计数，避免重复领取经验。
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceBlockEntityAccessor {
    @Accessor("recipesUsed")
    Reference2IntOpenHashMap<ResourceKey<Recipe<?>>> aiPartner$getRecipesUsed();
}
