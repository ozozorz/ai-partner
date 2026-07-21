package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 为有限任务和持续工作提供同一套掉落物发现、排序和原版导航入口。
 * 实际入包仍由 {@link AiPartnerEntity#pickUpItem} 处理，动作层不会凭空生成物品。
 */
public final class PickupItemAction {
    private final AiPartnerEntity partner;

    public PickupItemAction(AiPartnerEntity partner) {
        this.partner = Objects.requireNonNull(partner, "partner");
    }

    /** 在动作发生点附近选择当前女仆能够合法拾取的最近掉落实体。 */
    public Optional<ItemEntity> findNearest(
            ServerLevel level,
            BlockPos center,
            double radius,
            Predicate<ItemEntity> selector
    ) {
        double diameter = radius * 2.0;
        AABB bounds = AABB.ofSize(Vec3.atCenterOf(center), diameter, diameter, diameter);
        return level.getEntitiesOfClass(
                        ItemEntity.class,
                        bounds,
                        entity -> entity.isAlive() && !entity.getItem().isEmpty() && selector.test(entity)
                ).stream()
                .min(Comparator.comparingDouble(partner::distanceToSqr));
    }

    /** 让原版导航持续追踪会受弹跳和水流影响的掉落实体。 */
    public boolean moveTo(ItemEntity entity, double speed) {
        return partner.getNavigation().moveTo(entity, speed);
    }
}
