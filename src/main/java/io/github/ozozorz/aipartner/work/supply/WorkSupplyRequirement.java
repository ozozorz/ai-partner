package io.github.ozozorz.aipartner.work.supply;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.world.item.Item;

/**
 * 工作规则声明的最小物资契约；规则只描述可用条件和候选产物，不感知配方与工作台状态机。
 */
public record WorkSupplyRequirement(
        String key,
        Predicate<AiPartnerEntity> available,
        List<Item> craftTargets,
        boolean allowsFallback
) {
    public WorkSupplyRequirement {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(available, "available");
        craftTargets = List.copyOf(craftTargets);
        if (key.isBlank() || craftTargets.isEmpty()) {
            throw new IllegalArgumentException("Work supply requirement needs a key and at least one craft target");
        }
    }

    public boolean isSatisfied(AiPartnerEntity partner) {
        return available.test(partner);
    }
}
