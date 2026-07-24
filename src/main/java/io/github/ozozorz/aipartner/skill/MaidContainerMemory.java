package io.github.ozozorz.aipartner.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 保存女仆实际打开过的容器及其最近一次可见内容。
 *
 * <p>记忆有固定上限并采用最近使用淘汰，避免长期探索让实体存档无限增长。</p>
 */
public final class MaidContainerMemory {
    public static final int MAX_REMEMBERED_CONTAINERS = 64;
    private static final String SAVE_KEY = "MaidContainerMemories";

    private final LinkedHashMap<ContainerKey, Observation> observations =
            new LinkedHashMap<>(16, 0.75F, true);

    /**
     * 记录一个当前可访问容器的物品汇总。
     */
    public void remember(ServerLevel level, BlockPos position, Container container) {
        LinkedHashMap<String, Integer> items = new LinkedHashMap<>();
        for (ItemStack stack : container) {
            if (!stack.isEmpty()) {
                items.merge(
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getCount(),
                        Integer::sum
                );
            }
        }
        observations.put(
                new ContainerKey(level.dimension().identifier().toString(), position.asLong()),
                new Observation(level.getGameTime(), Map.copyOf(items))
        );
        trimToLimit();
    }

    public int rememberedContainerCount() {
        return observations.size();
    }

    /**
     * 返回最近观察的容器摘要，供命令界面确认记忆是否生效。
     */
    public List<String> recentSummaries(int maximum) {
        int limit = Math.max(0, maximum);
        return observations.entrySet().stream()
                .toList()
                .reversed()
                .stream()
                .limit(limit)
                .map(entry -> {
                    BlockPos pos = BlockPos.of(entry.getKey().packedPosition());
                    int itemCount = entry.getValue().items().values().stream().mapToInt(Integer::intValue).sum();
                    return entry.getKey().dimension() + " " + pos.toShortString() + " (" + itemCount + ")";
                })
                .toList();
    }

    /**
     * 把容器记忆写入实体存档。
     */
    public void save(ValueOutput output) {
        ValueOutput.ValueOutputList entries = output.childrenList(SAVE_KEY);
        for (Map.Entry<ContainerKey, Observation> memory : observations.entrySet()) {
            ValueOutput entry = entries.addChild();
            entry.putString("Dimension", memory.getKey().dimension());
            entry.putLong("Position", memory.getKey().packedPosition());
            entry.putLong("ObservedAt", memory.getValue().observedAt());
            ValueOutput.ValueOutputList items = entry.childrenList("Items");
            memory.getValue().items().forEach((itemId, count) -> {
                ValueOutput item = items.addChild();
                item.putString("Id", itemId);
                item.putInt("Count", count);
            });
        }
    }

    /**
     * 从存档恢复容器记忆，并丢弃损坏或超过上限的条目。
     */
    public void load(ValueInput input) {
        observations.clear();
        for (ValueInput entry : input.childrenListOrEmpty(SAVE_KEY)) {
            String dimension = entry.getStringOr("Dimension", "");
            long packedPosition = entry.getLongOr("Position", Long.MIN_VALUE);
            if (dimension.isBlank() || packedPosition == Long.MIN_VALUE) {
                continue;
            }
            LinkedHashMap<String, Integer> items = new LinkedHashMap<>();
            for (ValueInput savedItem : entry.childrenListOrEmpty("Items")) {
                String itemId = savedItem.getStringOr("Id", "");
                int count = savedItem.getIntOr("Count", 0);
                if (!itemId.isBlank() && count > 0) {
                    items.merge(itemId, count, Integer::sum);
                }
            }
            observations.put(
                    new ContainerKey(dimension, packedPosition),
                    new Observation(entry.getLongOr("ObservedAt", 0L), Map.copyOf(items))
            );
            trimToLimit();
        }
    }

    private void trimToLimit() {
        while (observations.size() > MAX_REMEMBERED_CONTAINERS) {
            observations.remove(observations.firstEntry().getKey());
        }
    }

    private record ContainerKey(String dimension, long packedPosition) {
    }

    private record Observation(long observedAt, Map<String, Integer> items) {
    }
}
