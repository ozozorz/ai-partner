package io.github.ozozorz.aipartner.life;

import io.github.ozozorz.aipartner.config.MaidGameplayConfig;
import io.github.ozozorz.aipartner.core.event.MaidDomainEvents;
import io.github.ozozorz.aipartner.core.event.MaidExperienceEvent;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.growth.MaidGrowthData;
import io.github.ozozorz.aipartner.mixin.ExperienceOrbAccessor;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 提供独立于有限任务的物品、箭和经验拾取，并复用原版经验修补规则。
 */
public final class MaidPickupController {
    private static final double BACKGROUND_PICKUP_RADIUS = 1.5;

    private final AiPartnerEntity partner;
    private final MaidLifeController lifeController;
    private final MaidGrowthData growthData;
    private final MaidGameplayConfig config;

    public MaidPickupController(
            AiPartnerEntity partner,
            MaidLifeController lifeController,
            MaidGrowthData growthData,
            MaidGameplayConfig config
    ) {
        this.partner = partner;
        this.lifeController = lifeController;
        this.growthData = growthData;
        this.config = config;
    }

    public boolean wantsItem(ItemEntity entity) {
        return config.generalPickupEnabled()
                && !partner.isInventoryMenuOpen()
                && lifeController.permitsPickupAt(entity.blockPosition())
                && partner.getInventory().canAddItem(entity.getItem());
    }

    public boolean wantsItem(ItemStack stack) {
        return config.generalPickupEnabled()
                && !partner.isInventoryMenuOpen()
                && lifeController.permitsPickupAt(partner.blockPosition())
                && partner.getInventory().canAddItem(stack);
    }

    /**
     * 原版 Mob 已处理 ItemEntity；这里补充箭实体和经验球。
     */
    public void tick() {
        if (!(partner.level() instanceof ServerLevel level)
                || partner.isInventoryMenuOpen()
                || partner.tickCount % 2 != 0) {
            return;
        }
        if (config.generalPickupEnabled()) {
            pickUpArrows(level);
        }
        if (config.experiencePickupEnabled()) {
            pickUpExperience(level);
        }
    }

    private void pickUpArrows(ServerLevel level) {
        for (AbstractArrow arrow : level.getEntitiesOfClass(
                AbstractArrow.class,
                partner.getBoundingBox().inflate(BACKGROUND_PICKUP_RADIUS),
                candidate -> candidate.pickup == AbstractArrow.Pickup.ALLOWED
                        && candidate.getDeltaMovement().lengthSqr() < 1.0E-4
                        && lifeController.permitsPickupAt(candidate.blockPosition())
        )) {
            ItemStack pickup = arrow.getPickupItemStackOrigin().copy();
            if (pickup.isEmpty()) {
                continue;
            }
            ItemStack remainder = partner.getInventory().addItem(pickup);
            if (remainder.isEmpty()) {
                partner.take(arrow, pickup.getCount());
                arrow.discard();
            }
        }
    }

    private void pickUpExperience(ServerLevel level) {
        for (ExperienceOrb orb : level.getEntitiesOfClass(
                ExperienceOrb.class,
                partner.getBoundingBox().inflate(BACKGROUND_PICKUP_RADIUS),
                candidate -> lifeController.permitsPickupAt(candidate.blockPosition())
        )) {
            int amount = orb.getValue();
            RepairResult result = repairEquipment(level, amount);
            int growthExperience = result.remainingExperience();
            if (growthExperience > 0) {
                growthData.addExperience(growthExperience);
                partner.syncGrowthData();
            }
            partner.take(orb, 1);
            ExperienceOrbAccessor accessor = (ExperienceOrbAccessor) orb;
            int remainingCount = accessor.aiPartner$getCount() - 1;
            if (remainingCount <= 0) {
                orb.discard();
            } else {
                accessor.aiPartner$setCount(remainingCount);
            }
            MaidDomainEvents.publish(new MaidExperienceEvent(
                    partner,
                    amount,
                    result.repairedDurability(),
                    growthExperience
            ));
        }
    }

    private RepairResult repairEquipment(ServerLevel level, int experience) {
        int remaining = experience;
        int repaired = 0;
        while (remaining > 0) {
            Optional<EnchantedItemInUse> selected = EnchantmentHelper.getRandomItemWith(
                    EnchantmentEffectComponents.REPAIR_WITH_XP,
                    partner,
                    ItemStack::isDamaged
            );
            if (selected.isEmpty()) {
                break;
            }
            ItemStack stack = selected.get().itemStack();
            int repairCapacity = EnchantmentHelper.modifyDurabilityToRepairFromXp(level, stack, remaining);
            if (repairCapacity <= 0) {
                break;
            }
            int repair = Math.min(repairCapacity, stack.getDamageValue());
            stack.setDamageValue(stack.getDamageValue() - repair);
            repaired += repair;
            if (repair <= 0) {
                break;
            }
            remaining -= repair * remaining / repairCapacity;
        }
        return new RepairResult(remaining, repaired);
    }

    private record RepairResult(int remainingExperience, int repairedDurability) {
    }
}
