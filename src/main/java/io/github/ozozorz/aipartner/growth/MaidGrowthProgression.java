package io.github.ozozorz.aipartner.growth;

/**
 * v0.8 的数据化成长曲线；所有工作在 1 级即可使用，等级只提供温和数值增益。
 */
public final class MaidGrowthProgression {
    public static final int MAX_LEVEL = 20;
    private static final double BASE_MAX_HEALTH = 20.0;
    private static final double BASE_ATTACK_DAMAGE = 4.0;
    private static final double BASE_MOVEMENT_SPEED = 0.30;

    private MaidGrowthProgression() {
    }

    public static int levelForExperience(int experience) {
        return Math.clamp(1 + (int) Math.sqrt(Math.max(0, experience) / 25.0), 1, MAX_LEVEL);
    }

    public static Effects effectsForLevel(int level) {
        int bounded = Math.clamp(level, 1, MAX_LEVEL);
        int gained = bounded - 1;
        return new Effects(
                BASE_MAX_HEALTH + gained * 0.5,
                BASE_ATTACK_DAMAGE + gained * 0.2,
                BASE_MOVEMENT_SPEED + gained * 0.0025,
                Math.min(0.25, gained * 0.015),
                gained / 5
        );
    }

    public static int adjustCooldown(int baseTicks, int level) {
        double reduction = effectsForLevel(level).workCooldownReduction();
        return Math.max(1, (int) Math.ceil(Math.max(1, baseTicks) * (1.0 - reduction)));
    }

    /** 当前等级投影到原版属性、工作冷却和额外寻路重试的结果。 */
    public record Effects(
            double maxHealth,
            double attackDamage,
            double movementSpeed,
            double workCooldownReduction,
            int extraPathRetries
    ) {
    }
}
