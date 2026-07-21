package io.github.ozozorz.aipartner.work.complex;

/**
 * 把自然树判定的保守阈值与世界遍历分离，便于在不启动游戏世界时回归安全边界。
 */
public final class NaturalTreePolicy {
    public static final int MIN_LOGS = 3;
    public static final int MAX_LOGS = 64;
    public static final int MIN_HEIGHT = 3;
    public static final int MIN_LEAVES = 8;
    public static final int MIN_TOP_LEAVES = 4;

    private NaturalTreePolicy() {
    }

    public static boolean accepts(Summary summary) {
        return summary.logCount() >= MIN_LOGS
                && summary.logCount() <= MAX_LOGS
                && summary.height() >= MIN_HEIGHT
                && summary.rootCount() > 0
                && summary.leafCount() >= Math.max(MIN_LEAVES, summary.logCount() / 2)
                && summary.topLeafCount() >= MIN_TOP_LEAVES
                && !summary.hasBlockEntity()
                && !summary.hasPlayerWoodComponent();
    }

    /** 自然树扫描得到的有限统计，不包含可变世界对象。 */
    public record Summary(
            int logCount,
            int leafCount,
            int topLeafCount,
            int height,
            int rootCount,
            boolean hasBlockEntity,
            boolean hasPlayerWoodComponent
    ) {
    }
}
