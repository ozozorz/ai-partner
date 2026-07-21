package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * 统一执行剪毛、挤奶、喂食和灭火等原版实体交互。
 */
public final class InteractEntityAction {
    private final AiPartnerEntity partner;
    private final InventoryAction inventory;

    public InteractEntityAction(AiPartnerEntity partner, InventoryAction inventory) {
        this.partner = Objects.requireNonNull(partner, "partner");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public boolean shear(ServerLevel level, LivingEntity target) {
        if (!(target instanceof Shearable shearable)
                || !shearable.readyForShearing()
                || !partner.getMainHandItem().is(Items.SHEARS)) {
            return false;
        }
        ItemStack shears = partner.getMainHandItem();
        shearable.shear(level, SoundSource.NEUTRAL, shears);
        shears.hurtAndBreak(1, partner, InteractionHand.MAIN_HAND);
        partner.swing(InteractionHand.MAIN_HAND);
        target.gameEvent(GameEvent.SHEAR, partner);
        return true;
    }

    public boolean milk(ServerLevel level, AbstractCow cow) {
        if (cow.isBaby() || !inventory.canAdd(new ItemStack(Items.MILK_BUCKET))) {
            return false;
        }
        ItemStack bucket = inventory.takeOne(Items.BUCKET);
        if (bucket.isEmpty()) {
            return false;
        }
        if (!inventory.add(new ItemStack(Items.MILK_BUCKET))) {
            inventory.add(bucket);
            return false;
        }
        level.playSound(null, cow, SoundEvents.COW_MILK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        partner.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    public boolean feedOwner(ServerLevel level, ServerPlayer owner, int foodSlot) {
        ItemStack food = inventory.take(foodSlot, 1);
        if (food.isEmpty()) {
            return false;
        }
        ItemStack remainder = food.finishUsingItem(level, owner);
        if (!remainder.isEmpty() && !inventory.add(remainder)) {
            owner.spawnAtLocation(level, remainder);
        }
        partner.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    public boolean feedAnimal(ServerLevel level, Animal animal, int foodSlot, ServerPlayer owner) {
        ItemStack food = inventory.take(foodSlot, 1);
        if (food.isEmpty() || !animal.isFood(food) || !animal.canFallInLove() || animal.isBaby()) {
            inventory.add(food);
            return false;
        }
        animal.setInLove(owner);
        partner.swing(InteractionHand.MAIN_HAND);
        animal.gameEvent(GameEvent.EAT, partner);
        return true;
    }

    public boolean extinguish(LivingEntity target) {
        if (!target.isOnFire()) {
            return false;
        }
        target.clearFire();
        partner.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
