package io.github.ozozorz.aipartner.entity;

import io.github.ozozorz.aipartner.mixin.FishingHookAccessor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 女仆专用的真实钓鱼浮标。
 *
 * <p>实体直接继承原版 {@link FishingHook}，因此咬钩计时、开放水域判断和钓鱼战利品条件
 * 与原版保持一致；Mixin 只把“持竿者”校验从玩家切换为女仆，并避免占用玩家自己的浮标槽。</p>
 */
public final class MaidFishingHookEntity extends FishingHook {
    private int lootLuck;

    public MaidFishingHookEntity(EntityType<? extends FishingHook> type, Level level) {
        super(type, level);
    }

    /** 创建从女仆眼前飞向目标水面的原版兼容浮标。 */
    public void initializeCast(AiPartnerEntity owner, ServerLevel level, ItemStack rod, BlockPos waterPosition) {
        setOwner(owner);
        lootLuck = Math.max(0, EnchantmentHelper.getFishingLuckBonus(level, rod, owner));
        int lureSpeed = Math.max(0, (int) (EnchantmentHelper.getFishingTimeReduction(level, rod, owner) * 20.0F));
        FishingHookAccessor accessor = (FishingHookAccessor) (Object) this;
        accessor.aiPartner$setLuck(lootLuck);
        accessor.aiPartner$setLureSpeed(lureSpeed);

        Vec3 origin = new Vec3(owner.getX(), owner.getEyeY() - 0.15, owner.getZ());
        Vec3 target = Vec3.atCenterOf(waterPosition).add(0.0, 0.35, 0.0);
        snapTo(origin.x, origin.y, origin.z, owner.getYRot(), owner.getXRot());
        Vec3 delta = target.subtract(origin);
        shoot(delta.x, delta.y + delta.horizontalDistance() * 0.12, delta.z, 0.65F, 0.0F);
    }

    /** 供原版 shouldStopFishing 注入调用，验证真正执行钓鱼工作的女仆。 */
    public boolean shouldStopMaidFishing() {
        AiPartnerEntity owner = getMaidOwner();
        boolean invalid = owner == null
                || !owner.canInteractWithLevel()
                || owner.level() != level()
                || !owner.getMainHandItem().is(Items.FISHING_ROD)
                || distanceToSqr(owner) > 1024.0;
        if (invalid && !level().isClientSide()) {
            discard();
        }
        return invalid;
    }

    /** 当前是否处于原版允许收竿获得战利品的咬钩窗口。 */
    public boolean isBiting() {
        return ((FishingHookAccessor) (Object) this).aiPartner$getNibble() > 0;
    }

    /** 浮标落地或钩中实体时，本次工作抛竿失败并应重新寻找时机。 */
    public boolean failedCast() {
        return isRemoved() || onGround() || horizontalCollision || getHookedIn() != null;
    }

    /**
     * 收竿时使用原版 fishing 战利品表，并把物品和经验实体拉向女仆。
     *
     * <p>THIS_ENTITY 仍然是 FishingHook 子类，因而原版开放水域战利品谓词会正确生效。</p>
     */
    @Override
    public int retrieve(ItemStack rod) {
        AiPartnerEntity owner = getMaidOwner();
        if (!(level() instanceof ServerLevel level) || owner == null || !isBiting()) {
            discard();
            return 0;
        }
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, position())
                .withParameter(LootContextParams.TOOL, rod)
                .withParameter(LootContextParams.THIS_ENTITY, this)
                .withLuck(lootLuck + owner.getLuck())
                .create(LootContextParamSets.FISHING);
        LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
        List<ItemStack> loot = lootTable.getRandomItems(params);
        for (ItemStack stack : loot) {
            ItemEntity item = new ItemEntity(level, getX(), getY(), getZ(), stack);
            Vec3 towardOwner = owner.position().subtract(position());
            item.setDeltaMovement(
                    towardOwner.x * 0.1,
                    towardOwner.y * 0.1 + Math.sqrt(Math.sqrt(towardOwner.lengthSqr())) * 0.08,
                    towardOwner.z * 0.1
            );
            level.addFreshEntity(item);
        }
        ExperienceOrb.award(level, owner.position().add(0.0, 0.5, 0.0), random.nextInt(6) + 1);
        rod.hurtAndBreak(1, owner, InteractionHand.MAIN_HAND);
        level.playSound(
                null,
                owner.getX(),
                owner.getY(),
                owner.getZ(),
                SoundEvents.FISHING_BOBBER_RETRIEVE,
                SoundSource.NEUTRAL,
                1.0F,
                1.0F
        );
        discard();
        return loot.size();
    }

    public @Nullable AiPartnerEntity getMaidOwner() {
        return getOwner() instanceof AiPartnerEntity partner ? partner : null;
    }

    /**
     * 原版浮标要求返回 Player；这里返回女仆的主人只用于原版客户端生命周期，
     * 真实距离与持竿校验由 {@link #shouldStopMaidFishing()} 完成。
     */
    @Override
    public @Nullable Player getPlayerOwner() {
        if (getMaidOwner() != null && getMaidOwner().getOwner() instanceof Player player) {
            return player;
        }
        return super.getPlayerOwner();
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return entity != getOwner() && super.canHitEntity(entity);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
