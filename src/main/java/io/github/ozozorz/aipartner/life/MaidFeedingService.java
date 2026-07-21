package io.github.ozozorz.aipartner.life;

import io.github.ozozorz.aipartner.config.MaidGameplayConfig;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;

/**
 * 让主人用任意原版可食用物品喂女仆，并正确处理效果与容器返还。
 */
public final class MaidFeedingService {
    private MaidFeedingService() {
    }

    public static boolean isEdible(ItemStack stack) {
        return stack.get(DataComponents.FOOD) != null && stack.get(DataComponents.CONSUMABLE) != null;
    }

    /**
     * 完成一次服务端喂食；负面食物效果也按原版 Consumable 正常应用。
     */
    public static boolean feed(AiPartnerEntity partner, ServerPlayer player, ItemStack heldStack) {
        FoodProperties food = heldStack.get(DataComponents.FOOD);
        Consumable consumable = heldStack.get(DataComponents.CONSUMABLE);
        if (food == null || consumable == null || heldStack.isEmpty()) {
            return false;
        }

        ItemStack consumed = heldStack.copyWithCount(1);
        ItemStack remainder = consumed.finishUsingItem(partner.level(), partner);
        if (!player.hasInfiniteMaterials()) {
            heldStack.shrink(1);
            if (!remainder.isEmpty()) {
                player.getInventory().placeItemBackInInventory(remainder);
            }
        }
        partner.heal(food.nutrition());
        MaidGameplayConfig config = MaidGameplayConfig.get();
        partner.rewardFoodAffection(
                config.foodAffectionGain(),
                config.foodAffectionCooldownTicks()
        );
        partner.showSpeechBubble(net.minecraft.network.chat.Component.translatable(
                "bubble.ai-partner.thanks_for_food"
        ));
        return true;
    }
}
