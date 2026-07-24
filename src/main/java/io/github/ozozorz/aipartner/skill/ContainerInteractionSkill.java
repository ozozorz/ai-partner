package io.github.ozozorz.aipartner.skill;

import io.github.ozozorz.aipartner.core.action.TransferItemAction;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 实现打开容器、读取内容、取物、存物和更新容器记忆这一组相互依赖的技能。
 */
public final class ContainerInteractionSkill {
    private final AiPartnerEntity partner;
    private final TransferItemAction transfer;
    private final MaidContainerMemory memory;

    public ContainerInteractionSkill(
            AiPartnerEntity partner,
            TransferItemAction transfer,
            MaidContainerMemory memory
    ) {
        this.partner = Objects.requireNonNull(partner, "partner");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    /**
     * 打开一个已加载且主人有权使用的容器；会话关闭时同步关盖并刷新记忆。
     */
    public Optional<Session> open(ServerLevel level, BlockPos position) {
        ServerPlayer owner = partner.getOwner() instanceof ServerPlayer player ? player : null;
        if (owner == null || partner.distanceToSqr(position.getCenter()) > 16.0) {
            return Optional.empty();
        }
        Container container = resolve(level, position, owner).orElse(null);
        if (container == null) {
            return Optional.empty();
        }
        partner.beginContainerUse(position, container);
        memory.remember(level, position, container);
        return Optional.of(new Session(level, position.immutable(), container));
    }

    private static Optional<Container> resolve(ServerLevel level, BlockPos position, ServerPlayer owner) {
        if (level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getZ())
        ) == null) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(position);
        if (state.getBlock() instanceof ChestBlock chest) {
            Container combined = ChestBlock.getContainer(chest, state, level, position, false);
            return combined == null ? Optional.empty() : Optional.of(combined);
        }
        if (level.getBlockEntity(position) instanceof BaseContainerBlockEntity blockEntity
                && blockEntity.canOpen(owner)) {
            return Optional.of(blockEntity);
        }
        return level.getBlockEntity(position) instanceof Container container
                ? Optional.of(container)
                : Optional.empty();
    }

    /**
     * 一个短生命周期容器会话，保证打开和关闭动作成对出现。
     */
    public final class Session implements AutoCloseable {
        private final ServerLevel level;
        private final BlockPos position;
        private final Container container;
        private boolean closed;

        private Session(ServerLevel level, BlockPos position, Container container) {
            this.level = level;
            this.position = position;
            this.container = container;
        }

        public int take(Item item, int maximum) {
            int moved = transfer.moveMatching(container, partner.getInventory(), item, maximum);
            memory.remember(level, position, container);
            return moved;
        }

        public int store(Item item, int maximum) {
            int moved = transfer.moveMatching(partner.getInventory(), container, item, maximum);
            memory.remember(level, position, container);
            return moved;
        }

        public int store(ItemStack stack) {
            int moved = transfer.insert(container, stack);
            memory.remember(level, position, container);
            return moved;
        }

        public Container container() {
            return container;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            memory.remember(level, position, container);
            partner.endContainerUse(position, container);
        }
    }
}
