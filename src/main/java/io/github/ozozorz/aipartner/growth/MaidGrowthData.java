package io.github.ozozorz.aipartner.growth;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 分离保存关系好感度与通过工作、战斗或经验球获得的成长经验。
 */
public final class MaidGrowthData {
    public static final int MAX_AFFECTION = 1000;

    private int affection;
    private int experience;
    private long completedWorkMask;

    public int affection() {
        return affection;
    }

    public int experience() {
        return experience;
    }

    public int level() {
        return MaidGrowthProgression.levelForExperience(experience);
    }

    public int addAffection(int amount) {
        affection = Math.clamp(affection + Math.max(0, amount), 0, MAX_AFFECTION);
        return affection;
    }

    public int addExperience(int amount) {
        if (amount > 0) {
            experience = (int) Math.clamp((long) experience + amount, 0L, Integer.MAX_VALUE);
        }
        return experience;
    }

    /** 首次完成某一工作模式时返回 true，用于一次性成长奖励。 */
    public boolean markFirstWorkCompletion(int workOrdinal) {
        if (workOrdinal < 0 || workOrdinal >= Long.SIZE) {
            return false;
        }
        long bit = 1L << workOrdinal;
        boolean first = (completedWorkMask & bit) == 0L;
        completedWorkMask |= bit;
        return first;
    }

    public void save(ValueOutput output) {
        output.putInt("MaidAffection", affection);
        output.putInt("MaidGrowthExperience", experience);
        output.putLong("MaidCompletedWorkMask", completedWorkMask);
    }

    public void load(ValueInput input) {
        affection = Math.clamp(input.getIntOr("MaidAffection", 0), 0, MAX_AFFECTION);
        experience = Math.max(0, input.getIntOr("MaidGrowthExperience", 0));
        completedWorkMask = input.getLongOr("MaidCompletedWorkMask", 0L);
    }
}
