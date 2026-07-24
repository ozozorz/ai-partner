package io.github.ozozorz.aipartner.work.rule;

import io.github.ozozorz.aipartner.work.MaidWorkContext;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import io.github.ozozorz.aipartner.work.MaidWorkRule;
import io.github.ozozorz.aipartner.work.WorkActionResult;
import io.github.ozozorz.aipartner.work.WorkTarget;
import io.github.ozozorz.aipartner.work.supply.WorkSupplyRequirement;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 构造低亮度插火把和区域灭火两类环境维护规则。
 */
public final class EnvironmentWorkRules {
    private static final int MAX_TORCH_BLOCK_LIGHT = 7;
    private static final int MIN_TORCH_SPACING = 5;

    private EnvironmentWorkRules() {
    }

    public static Collection<MaidWorkRule> create() {
        return List.of(new TorchRule(), new FirefighterRule());
    }

    /** 在合法、无碰撞且与已有火把保持间距的低亮度地面放置普通火把。 */
    private static final class TorchRule extends BlockRule {
        private static final WorkSupplyRequirement TORCH_SUPPLY = new WorkSupplyRequirement(
                "torch_bearer_torch",
                partner -> partner.getInventory().getItems().stream().anyMatch(stack -> stack.is(Items.TORCH)),
                List.of(Items.TORCH),
                false
        );

        private TorchRule() {
            super(MaidWorkMode.TORCH_BEARER);
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            return state.isAir()
                    && context.level().getMaxLocalRawBrightness(position) <= MAX_TORCH_BLOCK_LIGHT
                    && Blocks.TORCH.defaultBlockState().canSurvive(context.level(), position)
                    && noNearbyTorch(context, position);
        }

        @Override
        public Optional<WorkSupplyRequirement> supplyRequirement(
                MaidWorkContext context,
                WorkTarget target
        ) {
            return Optional.of(TORCH_SUPPLY);
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            return context.skills().placeBlock().placeHeld(
                    context.level(),
                    target.fallbackPosition(),
                    Blocks.TORCH.defaultBlockState(),
                    stack -> stack.is(Items.TORCH)
            ) ? WorkActionResult.SUCCESS : WorkActionResult.RETRY;
        }

        private static boolean noNearbyTorch(MaidWorkContext context, BlockPos position) {
            return BlockPos.withinManhattanStream(
                            position,
                            MIN_TORCH_SPACING,
                            2,
                            MIN_TORCH_SPACING
                    )
                    .noneMatch(candidate -> context.level().getBlockState(candidate).is(Blocks.TORCH)
                            || context.level().getBlockState(candidate).is(Blocks.WALL_TORCH));
        }
    }

    /** 优先移除区域内火方块；无方块目标时熄灭最近的燃烧生物。 */
    private static final class FirefighterRule extends BlockRule {
        private FirefighterRule() {
            super(MaidWorkMode.FIREFIGHTER);
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
        }

        @Override
        public Optional<WorkTarget> findEntityTarget(MaidWorkContext context) {
            double diameter = context.boundary().radius() * 2.0 + 1.0;
            AABB box = AABB.ofSize(
                    Vec3.atCenterOf(context.boundary().position()),
                    diameter,
                    16.0,
                    diameter
            );
            return context.level().getEntitiesOfClass(
                            LivingEntity.class,
                            box,
                            entity -> entity.isAlive()
                                    && entity.isOnFire()
                                    && context.isLegal(entity.blockPosition())
                    ).stream()
                    .min(Comparator.comparingDouble(context.partner()::distanceToSqr))
                    .map(WorkTarget::entity);
        }

        @Override
        public boolean prioritizesEntityTargets() {
            return true;
        }

        @Override
        public boolean isStillValid(MaidWorkContext context, WorkTarget target) {
            if (!target.isEntity()) {
                return matchesBlock(
                        context,
                        target.fallbackPosition(),
                        context.level().getBlockState(target.fallbackPosition())
                );
            }
            return target.resolveEntity(context.level())
                    .filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast)
                    .filter(LivingEntity::isAlive)
                    .filter(LivingEntity::isOnFire)
                    .isPresent();
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            if (!target.isEntity()) {
                return context.skills().breakBlock().removeWithoutTool(
                        context.level(),
                        target.fallbackPosition()
                ) ? WorkActionResult.SUCCESS : WorkActionResult.RETRY;
            }
            LivingEntity entity = target.resolveEntity(context.level())
                    .filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast)
                    .orElse(null);
            return entity != null && context.skills().interactEntity().extinguish(entity)
                    ? WorkActionResult.SUCCESS
                    : WorkActionResult.RETRY;
        }
    }

    private abstract static class BlockRule implements MaidWorkRule {
        private final MaidWorkMode mode;

        private BlockRule(MaidWorkMode mode) {
            this.mode = mode;
        }

        @Override
        public final MaidWorkMode mode() {
            return mode;
        }

        @Override
        public boolean isStillValid(MaidWorkContext context, WorkTarget target) {
            if (target.isEntity()) {
                return false;
            }
            return matchesBlock(
                    context,
                    target.fallbackPosition(),
                    context.level().getBlockState(target.fallbackPosition())
            );
        }
    }
}
