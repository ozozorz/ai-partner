package io.github.ozozorz.aipartner.work;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * 工作状态机使用的轻量目标引用；实体目标只保存 UUID，失效后由规则重新搜索。
 */
public record WorkTarget(BlockPos fallbackPosition, @Nullable UUID entityId) {
    public WorkTarget {
        fallbackPosition = fallbackPosition.immutable();
    }

    public static WorkTarget block(BlockPos position) {
        return new WorkTarget(position, null);
    }

    public static WorkTarget entity(Entity entity) {
        return new WorkTarget(entity.blockPosition(), entity.getUUID());
    }

    public boolean isEntity() {
        return entityId != null;
    }

    public Optional<Entity> resolveEntity(ServerLevel level) {
        return entityId == null ? Optional.empty() : Optional.ofNullable(level.getEntity(entityId));
    }

    public BlockPos currentPosition(ServerLevel level) {
        return resolveEntity(level).map(Entity::blockPosition).orElse(fallbackPosition);
    }
}
