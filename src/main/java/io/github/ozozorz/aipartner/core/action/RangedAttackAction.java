package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;
import java.util.OptionalInt;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 使用真实箭实体、箭种、弹药附魔和弓耐久执行一次女仆远程攻击。
 */
public final class RangedAttackAction {
    private final AiPartnerEntity partner;
    private final InventoryAction inventory;

    public RangedAttackAction(AiPartnerEntity partner, InventoryAction inventory) {
        this.partner = Objects.requireNonNull(partner, "partner");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public boolean shoot(ServerLevel level, LivingEntity target, float power) {
        ItemStack bow = partner.getMainHandItem();
        if (!(bow.getItem() instanceof BowItem)) {
            return false;
        }
        OptionalInt ammoSlot = inventory.findSlot(stack -> stack.typeHolder().is(ItemTags.ARROWS));
        if (ammoSlot.isEmpty()) {
            return false;
        }
        ItemStack storedAmmo = partner.getInventory().getItem(ammoSlot.getAsInt());
        int ammoUse = EnchantmentHelper.processAmmoUse(level, bow, storedAmmo, 1);
        if (ammoUse > storedAmmo.getCount()) {
            return false;
        }
        ItemStack firedAmmo;
        if (ammoUse == 0) {
            firedAmmo = storedAmmo.copyWithCount(1);
            firedAmmo.set(DataComponents.INTANGIBLE_PROJECTILE, Unit.INSTANCE);
        } else {
            firedAmmo = inventory.take(ammoSlot.getAsInt(), ammoUse);
        }
        if (firedAmmo.isEmpty()) {
            return false;
        }

        AbstractArrow arrow = ProjectileUtil.getMobArrow(partner, firedAmmo, power, bow);
        double dx = target.getX() - partner.getX();
        double dy = target.getY(0.3333333333333333) - arrow.getY();
        double dz = target.getZ() - partner.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        Projectile.spawnProjectileUsingShoot(
                arrow,
                level,
                firedAmmo,
                dx,
                dy + horizontalDistance * 0.2F,
                dz,
                1.6F,
                14 - level.getDifficulty().getId() * 4
        );
        EnchantmentHelper.onProjectileSpawned(level, bow, arrow, ignored -> {
        });
        bow.hurtAndBreak(1, partner, InteractionHand.MAIN_HAND);
        partner.playSound(
                SoundEvents.ARROW_SHOOT,
                1.0F,
                1.0F / (partner.getRandom().nextFloat() * 0.4F + 0.8F)
        );
        return true;
    }
}
