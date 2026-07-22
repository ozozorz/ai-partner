package io.github.ozozorz.aipartner.growth;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 为喂食、工作和合法击杀分别实施服务端冷却与每日上限，并写入关系/成长数据。
 */
public final class MaidGrowthController {
    private static final int WORK_DAILY_LIMIT = 32;
    private static final int COMBAT_DAILY_LIMIT = 12;
    private static final int FOOD_DAILY_LIMIT = 10;
    private static final int WORK_REWARD_COOLDOWN_TICKS = 10;
    private static final int COMBAT_REWARD_COOLDOWN_TICKS = 20;

    private final AiPartnerEntity partner;
    private final MaidGrowthData data;
    private long rewardDay = Long.MIN_VALUE;
    private long lastWorkReward = Long.MIN_VALUE;
    private long lastCombatReward = Long.MIN_VALUE;
    private long lastFoodReward = Long.MIN_VALUE;
    private int workRewardsToday;
    private int combatRewardsToday;
    private int foodRewardsToday;

    public MaidGrowthController(AiPartnerEntity partner, MaidGrowthData data) {
        this.partner = partner;
        this.data = data;
    }

    public void rewardWork(MaidWorkMode mode) {
        long now = partner.level().getGameTime();
        rollDay(now);
        if (workRewardsToday >= WORK_DAILY_LIMIT || !cooldownElapsed(now, lastWorkReward, WORK_REWARD_COOLDOWN_TICKS)) {
            return;
        }
        workRewardsToday++;
        lastWorkReward = now;
        boolean firstCompletion = data.markFirstWorkCompletion(mode.ordinal());
        int baseExperience = switch (mode) {
            case LUMBERJACK, MINER, SMELTER, FISHER -> 2;
            default -> 1;
        };
        data.addExperience(baseExperience + (firstCompletion ? 5 : 0));
        data.addAffection(1);
        partner.syncGrowthData();
    }

    public void rewardCombatKill() {
        long now = partner.level().getGameTime();
        rollDay(now);
        if (combatRewardsToday >= COMBAT_DAILY_LIMIT
                || !cooldownElapsed(now, lastCombatReward, COMBAT_REWARD_COOLDOWN_TICKS)) {
            return;
        }
        combatRewardsToday++;
        lastCombatReward = now;
        data.addExperience(4);
        data.addAffection(2);
        partner.syncGrowthData();
    }

    public void rewardFood(int amount, int cooldownTicks) {
        long now = partner.level().getGameTime();
        rollDay(now);
        if (amount <= 0
                || foodRewardsToday >= FOOD_DAILY_LIMIT
                || !cooldownElapsed(now, lastFoodReward, Math.max(0, cooldownTicks))) {
            return;
        }
        foodRewardsToday++;
        lastFoodReward = now;
        data.addAffection(amount);
        partner.syncGrowthData();
    }

    public void save(ValueOutput output) {
        output.putLong("GrowthRewardDay", rewardDay);
        output.putLong("LastWorkGrowthReward", lastWorkReward);
        output.putLong("LastCombatGrowthReward", lastCombatReward);
        output.putLong("LastFoodGrowthReward", lastFoodReward);
        output.putInt("WorkGrowthRewardsToday", workRewardsToday);
        output.putInt("CombatGrowthRewardsToday", combatRewardsToday);
        output.putInt("FoodGrowthRewardsToday", foodRewardsToday);
    }

    public void load(ValueInput input) {
        rewardDay = input.getLongOr("GrowthRewardDay", Long.MIN_VALUE);
        lastWorkReward = input.getLongOr("LastWorkGrowthReward", Long.MIN_VALUE);
        lastCombatReward = input.getLongOr("LastCombatGrowthReward", Long.MIN_VALUE);
        lastFoodReward = input.getLongOr(
                "LastFoodGrowthReward",
                input.getLongOr("LastFoodAffectionGameTime", Long.MIN_VALUE)
        );
        workRewardsToday = Math.max(0, input.getIntOr("WorkGrowthRewardsToday", 0));
        combatRewardsToday = Math.max(0, input.getIntOr("CombatGrowthRewardsToday", 0));
        foodRewardsToday = Math.max(0, input.getIntOr("FoodGrowthRewardsToday", 0));
    }

    private void rollDay(long gameTime) {
        long currentDay = Math.floorDiv(gameTime, 24000L);
        if (rewardDay == currentDay) {
            return;
        }
        rewardDay = currentDay;
        workRewardsToday = 0;
        combatRewardsToday = 0;
        foodRewardsToday = 0;
    }

    private static boolean cooldownElapsed(long now, long previous, int cooldownTicks) {
        return previous == Long.MIN_VALUE || now - previous >= cooldownTicks;
    }
}
