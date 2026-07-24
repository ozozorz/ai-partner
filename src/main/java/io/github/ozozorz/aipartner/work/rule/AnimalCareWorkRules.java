package io.github.ozozorz.aipartner.work.rule;

import io.github.ozozorz.aipartner.inventory.EquipmentLease;
import io.github.ozozorz.aipartner.life.MaidFeedingService;
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
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 构造蜂蜜、剪毛、挤奶、喂主人和动物繁殖五类生活生产规则。
 */
public final class AnimalCareWorkRules {
    private static final int MAX_NEARBY_BREEDING_ANIMALS = 16;
    private static final WorkSupplyRequirement BEE_SUPPLY = new WorkSupplyRequirement(
            "beekeeper_container",
            partner -> hasShears(partner)
                    || partner.getInventory().getItems().stream().anyMatch(stack -> stack.is(Items.GLASS_BOTTLE)),
            List.of(Items.SHEARS, Items.GLASS_BOTTLE),
            false
    );
    private static final WorkSupplyRequirement SHEARS_SUPPLY = new WorkSupplyRequirement(
            "shearer_shears",
            AnimalCareWorkRules::hasShears,
            List.of(Items.SHEARS),
            false
    );
    private static final WorkSupplyRequirement BUCKET_SUPPLY = new WorkSupplyRequirement(
            "milker_bucket",
            partner -> partner.getInventory().getItems().stream().anyMatch(stack -> stack.is(Items.BUCKET)),
            List.of(Items.BUCKET),
            false
    );
    private static final Set<Item> UNSAFE_OWNER_FOODS = Set.of(
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.POISONOUS_POTATO,
            Items.PUFFERFISH,
            Items.CHICKEN
    );

    private AnimalCareWorkRules() {
    }

    public static Collection<MaidWorkRule> create() {
        return List.of(
                new BeekeeperRule(),
                new ShearerRule(),
                new MilkerRule(),
                new CaregiverRule(),
                new BreederRule()
        );
    }

    /** 成熟蜂巢必须处于营火烟雾保护下，优先用剪刀，其次使用玻璃瓶。 */
    private static final class BeekeeperRule extends BlockRule {
        private BeekeeperRule() {
            super(MaidWorkMode.BEEKEEPER);
        }

        @Override
        public boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
            return state.getBlock() instanceof BeehiveBlock
                    && state.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS
                    && CampfireBlock.isSmokeyPos(context.level(), position)
                    && context.skills().inventory().hasAnySpace();
        }

        @Override
        public Optional<WorkSupplyRequirement> supplyRequirement(
                MaidWorkContext context,
                WorkTarget target
        ) {
            return Optional.of(BEE_SUPPLY);
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            EquipmentLease lease = EquipmentLease.acquire(
                    context.partner(),
                    stack -> stack.is(Items.SHEARS)
            ).orElse(null);
            if (lease != null) {
                try (lease) {
                    if (context.skills().harvestBlock().collectHoney(
                            context.level(),
                            target.fallbackPosition()
                    )) {
                        return WorkActionResult.SUCCESS;
                    }
                }
            }
            return context.skills().harvestBlock().collectHoney(context.level(), target.fallbackPosition())
                    ? WorkActionResult.SUCCESS
                    : WorkActionResult.RETRY;
        }
    }

    /** 查找所有实现原版 Shearable 的可剪成年实体并使用真实剪毛战利品表。 */
    private static final class ShearerRule extends EntityRule {
        private ShearerRule() {
            super(MaidWorkMode.SHEARER);
        }

        @Override
        public Optional<WorkTarget> findEntityTarget(MaidWorkContext context) {
            if (!context.skills().inventory().hasAnySpace()) {
                return Optional.empty();
            }
            return nearestLiving(context, entity -> entity instanceof Shearable shearable
                    && shearable.readyForShearing());
        }

        @Override
        public boolean isStillValid(MaidWorkContext context, WorkTarget target) {
            return target.resolveEntity(context.level())
                    .filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast)
                    .filter(LivingEntity::isAlive)
                    .filter(entity -> entity instanceof Shearable shearable && shearable.readyForShearing())
                    .isPresent();
        }

        @Override
        public Optional<WorkSupplyRequirement> supplyRequirement(
                MaidWorkContext context,
                WorkTarget target
        ) {
            return Optional.of(SHEARS_SUPPLY);
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            LivingEntity entity = target.resolveEntity(context.level())
                    .filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast)
                    .orElse(null);
            if (entity == null) {
                return WorkActionResult.RETRY;
            }
            EquipmentLease lease = EquipmentLease.acquire(
                    context.partner(),
                    stack -> stack.is(Items.SHEARS)
            ).orElse(null);
            if (lease == null) {
                return WorkActionResult.BLOCKED;
            }
            try (lease) {
                return context.skills().interactEntity().shear(context.level(), entity)
                        ? WorkActionResult.SUCCESS
                        : WorkActionResult.RETRY;
            }
        }
    }

    /** 对成年原版牛使用一个空桶，并在确认背包容量后写入牛奶桶。 */
    private static final class MilkerRule extends EntityRule {
        private MilkerRule() {
            super(MaidWorkMode.MILKER);
        }

        @Override
        public Optional<WorkTarget> findEntityTarget(MaidWorkContext context) {
            if (!context.skills().inventory().canAdd(new ItemStack(Items.MILK_BUCKET))) {
                return Optional.empty();
            }
            return nearestLiving(context, entity -> entity instanceof AbstractCow cow && !cow.isBaby());
        }

        @Override
        public boolean isStillValid(MaidWorkContext context, WorkTarget target) {
            return target.resolveEntity(context.level())
                    .filter(AbstractCow.class::isInstance)
                    .map(AbstractCow.class::cast)
                    .filter(LivingEntity::isAlive)
                    .filter(cow -> !cow.isBaby())
                    .isPresent();
        }

        @Override
        public Optional<WorkSupplyRequirement> supplyRequirement(
                MaidWorkContext context,
                WorkTarget target
        ) {
            return Optional.of(BUCKET_SUPPLY);
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            AbstractCow cow = target.resolveEntity(context.level())
                    .filter(AbstractCow.class::isInstance)
                    .map(AbstractCow.class::cast)
                    .orElse(null);
            return cow != null && context.skills().interactEntity().milk(context.level(), cow)
                    ? WorkActionResult.SUCCESS
                    : WorkActionResult.RETRY;
        }

        @Override
        public int successCooldownTicks() {
            return 100;
        }
    }

    /** 主人饥饿时选择不带常见负面效果的原版食物并走近喂食。 */
    private static final class CaregiverRule extends EntityRule {
        private CaregiverRule() {
            super(MaidWorkMode.CAREGIVER);
        }

        @Override
        public Optional<WorkTarget> findEntityTarget(MaidWorkContext context) {
            ServerPlayer owner = owner(context);
            return owner != null
                    && owner.getFoodData().needsFood()
                    && findSafeFood(context).isPresent()
                    && context.isLegal(owner.blockPosition())
                    ? Optional.of(WorkTarget.entity(owner))
                    : Optional.empty();
        }

        @Override
        public boolean isStillValid(MaidWorkContext context, WorkTarget target) {
            ServerPlayer owner = owner(context);
            return owner != null
                    && target.entityId() != null
                    && target.entityId().equals(owner.getUUID())
                    && owner.isAlive()
                    && owner.getFoodData().needsFood()
                    && findSafeFood(context).isPresent();
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            ServerPlayer owner = owner(context);
            OptionalInt foodSlot = findSafeFood(context);
            return owner != null
                    && foodSlot.isPresent()
                    && context.skills().interactEntity().feedOwner(
                            context.level(),
                            owner,
                            foodSlot.getAsInt()
                    ) ? WorkActionResult.SUCCESS : WorkActionResult.RETRY;
        }

        private static OptionalInt findSafeFood(MaidWorkContext context) {
            return context.skills().inventory().findSlot(stack -> MaidFeedingService.isEdible(stack)
                    && !UNSAFE_OWNER_FOODS.contains(stack.getItem())
                    && stack.get(DataComponents.FOOD) != null);
        }
    }

    /** 在区域动物数量上限内，给可进入繁殖状态的成年动物消耗对应食物。 */
    private static final class BreederRule extends EntityRule {
        private BreederRule() {
            super(MaidWorkMode.BREEDER);
        }

        @Override
        public Optional<WorkTarget> findEntityTarget(MaidWorkContext context) {
            List<Animal> animals = animalsInBoundary(context);
            if (animals.size() >= MAX_NEARBY_BREEDING_ANIMALS) {
                return Optional.empty();
            }
            return animals.stream()
                    .filter(Animal::isAlive)
                    .filter(animal -> !animal.isBaby() && animal.canFallInLove())
                    .filter(animal -> findFood(context, animal).isPresent())
                    .min(Comparator.comparingDouble(context.partner()::distanceToSqr))
                    .map(WorkTarget::entity);
        }

        @Override
        public boolean isStillValid(MaidWorkContext context, WorkTarget target) {
            Animal animal = target.resolveEntity(context.level())
                    .filter(Animal.class::isInstance)
                    .map(Animal.class::cast)
                    .orElse(null);
            return animal != null
                    && animal.isAlive()
                    && !animal.isBaby()
                    && animal.canFallInLove()
                    && findFood(context, animal).isPresent()
                    && animalsInBoundary(context).size() < MAX_NEARBY_BREEDING_ANIMALS;
        }

        @Override
        public WorkActionResult perform(MaidWorkContext context, WorkTarget target) {
            Animal animal = target.resolveEntity(context.level())
                    .filter(Animal.class::isInstance)
                    .map(Animal.class::cast)
                    .orElse(null);
            ServerPlayer owner = owner(context);
            if (animal == null || owner == null) {
                return WorkActionResult.RETRY;
            }
            OptionalInt foodSlot = findFood(context, animal);
            return foodSlot.isPresent()
                    && context.skills().interactEntity().feedAnimal(
                            context.level(),
                            animal,
                            foodSlot.getAsInt(),
                            owner
                    ) ? WorkActionResult.SUCCESS : WorkActionResult.RETRY;
        }

        private static OptionalInt findFood(MaidWorkContext context, Animal animal) {
            return context.skills().inventory().findSlot(animal::isFood);
        }
    }

    private static boolean hasShears(MaidWorkContext context) {
        return hasShears(context.partner());
    }

    private static boolean hasShears(io.github.ozozorz.aipartner.entity.AiPartnerEntity partner) {
        return partner.getMainHandItem().is(Items.SHEARS)
                || partner.getInventory().getItems().stream().anyMatch(stack -> stack.is(Items.SHEARS));
    }

    private static ServerPlayer owner(MaidWorkContext context) {
        return context.partner().getOwner() instanceof ServerPlayer owner
                && owner.level() == context.level() ? owner : null;
    }

    private static Optional<WorkTarget> nearestLiving(
            MaidWorkContext context,
            java.util.function.Predicate<LivingEntity> predicate
    ) {
        return context.level().getEntitiesOfClass(
                        LivingEntity.class,
                        boundaryBox(context),
                        entity -> context.isLegal(entity.blockPosition()) && predicate.test(entity)
                ).stream()
                .min(Comparator.comparingDouble(context.partner()::distanceToSqr))
                .map(WorkTarget::entity);
    }

    private static List<Animal> animalsInBoundary(MaidWorkContext context) {
        return context.level().getEntitiesOfClass(
                Animal.class,
                boundaryBox(context),
                animal -> context.isLegal(animal.blockPosition())
        );
    }

    private static AABB boundaryBox(MaidWorkContext context) {
        double diameter = context.boundary().radius() * 2.0 + 1.0;
        return AABB.ofSize(Vec3.atCenterOf(context.boundary().position()), diameter, 16.0, diameter);
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

    private abstract static class EntityRule implements MaidWorkRule {
        private final MaidWorkMode mode;

        private EntityRule(MaidWorkMode mode) {
            this.mode = mode;
        }

        @Override
        public final MaidWorkMode mode() {
            return mode;
        }

        @Override
        public final boolean scansBlocks() {
            return false;
        }
    }
}
