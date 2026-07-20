package io.github.ozozorz.aipartner.entity.navigation;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * 为女仆开启门、通过门和漂浮能力的地面导航器。
 *
 * <p>该设计借鉴女仆类模组把导航能力放在核心行为层的做法，任务执行器仍只提交目标点。</p>
 */
public final class AiPartnerPathNavigation extends GroundPathNavigation {
    public AiPartnerPathNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        nodeEvaluator = new WalkNodeEvaluator();
        nodeEvaluator.setCanOpenDoors(true);
        nodeEvaluator.setCanPassDoors(true);
        nodeEvaluator.setCanFloat(true);
        return new PathFinder(nodeEvaluator, maxVisitedNodes);
    }
}
