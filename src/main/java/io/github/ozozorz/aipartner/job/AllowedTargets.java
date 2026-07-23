package io.github.ozozorz.aipartner.job;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 可收集目标白名单；命令、菜单和本地文本入口均不能绕过此注册表。
 */
public final class AllowedTargets {
    public static final int DEFAULT_COLLECT_RADIUS = 16;
    public static final int MAX_COLLECT_RADIUS = 24;
    private static final int VERTICAL_SEARCH_RADIUS = 8;
    private static final Set<Identifier> ALLOWED_LOGS = Set.of(
            Identifier.withDefaultNamespace("oak_log"),
            Identifier.withDefaultNamespace("birch_log"),
            Identifier.withDefaultNamespace("spruce_log")
    );

    private AllowedTargets() {
    }

    /**
     * 返回命令补全和文档展示使用的稳定目标列表。
     */
    public static List<String> suggestedBlockIds() {
        return ALLOWED_LOGS.stream().map(Identifier::toString).sorted().toList();
    }

    /** 返回通用物流命令可补全的全部已注册物品标识。 */
    public static List<String> suggestedItemIds() {
        return BuiltInRegistries.ITEM.keySet().stream().map(Identifier::toString).sorted().toList();
    }

    /**
     * 解析并验证白名单方块标识。
     */
    public static Optional<Block> resolveCollectibleBlock(String rawId) {
        Identifier id = Identifier.tryParse(rawId);
        if (id == null || !ALLOWED_LOGS.contains(id)) {
            return Optional.empty();
        }
        return BuiltInRegistries.BLOCK.getOptional(id);
    }

    /**
     * 判断指定半径内是否存在合法目标，供契约执行前验证使用。
     */
    public static boolean existsNear(AiPartnerEntity partner, Block block, int radius) {
        return BlockPos.findClosestMatch(
                partner.blockPosition(),
                radius,
                VERTICAL_SEARCH_RADIUS,
                pos -> partner.level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                        && serverLevel.getChunkSource().getChunkNow(
                                SectionPos.blockToSectionCoord(pos.getX()),
                                SectionPos.blockToSectionCoord(pos.getZ())
                        ) != null
                        && partner.level().getBlockState(pos).getBlock() == block
        ).isPresent();
    }

    /**
     * 返回目标方块对应的物品，空气等无物品目标会得到空结果。
     */
    public static Optional<Item> asCollectibleItem(Block block) {
        Item item = block.asItem();
        return new ItemStack(item).isEmpty() ? Optional.empty() : Optional.of(item);
    }

    /** 解析通用物流物品；空气和不存在的资源不会成为可执行目标。 */
    public static Optional<Item> resolveDepositableItem(String rawId) {
        Identifier id = Identifier.tryParse(rawId);
        if (id == null) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(id)
                .filter(item -> !new ItemStack(item).isEmpty());
    }
}
