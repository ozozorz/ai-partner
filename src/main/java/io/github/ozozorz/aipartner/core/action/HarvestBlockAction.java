package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.inventory.EquipmentLease;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * 封装需要保留或重置原方块的收获动作，例如补种作物和采集蜂巢。
 */
public final class HarvestBlockAction {
    private final AiPartnerEntity partner;
    private final InventoryAction inventory;

    public HarvestBlockAction(AiPartnerEntity partner, InventoryAction inventory) {
        this.partner = Objects.requireNonNull(partner, "partner");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    /**
     * 先验证并消耗一份种子，再用旧状态的原版战利品表掉落资源并写入幼苗状态。
     */
    public boolean harvestAndReplant(
            ServerLevel level,
            BlockPos position,
            BlockState expected,
            BlockState replanted,
            Predicate<ItemStack> seedSelector
    ) {
        if (!level.getBlockState(position).equals(expected) || !replanted.canSurvive(level, position)) {
            return false;
        }
        EquipmentLease lease = EquipmentLease.acquire(partner, seedSelector).orElse(null);
        if (lease == null) {
            return false;
        }
        try (lease) {
            if (!level.setBlock(position, replanted, Block.UPDATE_ALL)) {
                return false;
            }
            Block.dropResources(
                    expected,
                    level,
                    position,
                    level.getBlockEntity(position),
                    partner,
                    partner.getMainHandItem()
            );
            partner.getMainHandItem().shrink(1);
            partner.swing(InteractionHand.MAIN_HAND);
            level.gameEvent(partner, GameEvent.BLOCK_CHANGE, position);
            return true;
        }
    }

    /**
     * 仅在营火烟雾保护下采集成熟蜂巢，避免把无玩家来源的蜂群仇恨伪装成原版交互。
     */
    public boolean collectHoney(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        if (!(state.getBlock() instanceof BeehiveBlock hive)
                || state.getValue(BeehiveBlock.HONEY_LEVEL) < BeehiveBlock.MAX_HONEY_LEVELS
                || !CampfireBlock.isSmokeyPos(level, position)) {
            return false;
        }

        if (partner.getMainHandItem().is(Items.SHEARS) && inventory.hasAnySpace()) {
            ItemStack shears = partner.getMainHandItem();
            BeehiveBlock.dropHoneycomb(level, shears, state, level.getBlockEntity(position), partner, position);
            hive.resetHoneyLevel(level, state, position);
            level.playSound(null, position, SoundEvents.BEEHIVE_SHEAR, SoundSource.BLOCKS, 1.0F, 1.0F);
            partner.swing(InteractionHand.MAIN_HAND);
            shears.hurtAndBreak(1, partner, InteractionHand.MAIN_HAND);
            level.gameEvent(partner, GameEvent.SHEAR, position);
            return true;
        }

        if (!inventory.canAdd(new ItemStack(Items.HONEY_BOTTLE))) {
            return false;
        }
        EquipmentLease bottleLease = EquipmentLease.acquire(
                partner,
                stack -> stack.is(Items.GLASS_BOTTLE)
        ).orElse(null);
        if (bottleLease == null) {
            return false;
        }
        try (bottleLease) {
            ItemStack bottle = partner.getMainHandItem();
            bottle.shrink(1);
            if (!inventory.add(new ItemStack(Items.HONEY_BOTTLE))) {
                bottle.grow(1);
                return false;
            }
            hive.resetHoneyLevel(level, state, position);
            level.playSound(null, position, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
            partner.swing(InteractionHand.MAIN_HAND);
            level.gameEvent(partner, GameEvent.FLUID_PICKUP, position);
            return true;
        }
    }
}
