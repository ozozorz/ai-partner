package io.github.ozozorz.aipartner.core.action;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * 使用服务端原版配方完成女仆制作；规划只接受普通有形/无形配方，执行时原子复验并处理剩余物。
 */
public final class CraftItemAction {
    private static final int MAX_PLAN_DEPTH = 7;
    private static final int MAX_INGREDIENT_ALTERNATIVES = 64;

    private final AiPartnerEntity partner;
    public CraftItemAction(AiPartnerEntity partner) {
        this.partner = Objects.requireNonNull(partner, "partner");
    }

    /**
     * 判断当前储物和可递归制作的中间材料能否产生任一目标物品，不改变真实背包。
     */
    public boolean canPlanAny(ServerLevel level, List<Item> targets, CraftingGrid grid) {
        return findNextStep(level, targets, grid).isPresent() || containsAnyTarget(targets);
    }

    /**
     * 每次只执行计划中的一个原版配方，使长依赖链可以跨 tick 复验并安全停止。
     */
    public CraftResult craftNext(ServerLevel level, List<Item> targets, CraftingGrid grid) {
        if (containsAnyTarget(targets)) {
            return new CraftResult(CraftStatus.TARGET_PRESENT, ItemStack.EMPTY);
        }
        PlannedStep step = findNextStep(level, targets, grid).orElse(null);
        if (step == null) {
            return new CraftResult(CraftStatus.NO_PLAN, ItemStack.EMPTY);
        }
        return applyStep(level, step);
    }

    private Optional<PlannedStep> findNextStep(ServerLevel level, List<Item> targets, CraftingGrid grid) {
        if (targets == null || targets.isEmpty()) {
            return Optional.empty();
        }
        RecipeIndex recipeIndex = recipeIndex(level);
        VirtualInventory initial = VirtualInventory.from(partner.getInventory());
        for (Item target : targets) {
            PlanResult result = planItem(
                    target,
                    initial.copy(),
                    grid,
                    recipeIndex,
                    0,
                    Set.of(),
                    level
            ).orElse(null);
            if (result != null && !result.steps().isEmpty()) {
                return Optional.of(result.steps().getFirst());
            }
        }
        return Optional.empty();
    }

    private Optional<PlanResult> planItem(
            Item target,
            VirtualInventory inventory,
            CraftingGrid grid,
            RecipeIndex recipeIndex,
            int depth,
            Set<Item> visiting,
            ServerLevel level
    ) {
        if (inventory.count(target) > 0) {
            return Optional.of(new PlanResult(inventory, List.of()));
        }
        if (depth >= MAX_PLAN_DEPTH || visiting.contains(target)) {
            return Optional.empty();
        }
        Set<Item> nextVisiting = new HashSet<>(visiting);
        nextVisiting.add(target);

        for (RecipeHolder<CraftingRecipe> holder : recipeIndex.recipesFor(target)) {
            CraftingRecipe recipe = holder.value();
            if (!grid.fits(recipe)) {
                continue;
            }
            VirtualInventory branch = inventory.copy();
            List<PlannedStep> prerequisiteSteps = new ArrayList<>();
            List<Item> chosenIngredients = new ArrayList<>();
            boolean failed = false;

            for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
                Item chosen = branch.findMatching(ingredient).orElse(null);
                if (chosen == null) {
                    IngredientResolution resolution = resolveIngredient(
                            ingredient,
                            branch,
                            grid,
                            recipeIndex,
                            depth + 1,
                            nextVisiting,
                            level
                    ).orElse(null);
                    if (resolution == null) {
                        failed = true;
                        break;
                    }
                    branch = resolution.inventory();
                    prerequisiteSteps.addAll(resolution.steps());
                    chosen = resolution.item();
                }
                if (!branch.consume(chosen, 1)) {
                    failed = true;
                    break;
                }
                chosenIngredients.add(chosen);
            }
            if (failed) {
                continue;
            }

            CraftingInput input = buildInput(recipe, stacksFor(chosenIngredients), grid);
            if (!recipe.matches(input, level)) {
                continue;
            }
            ItemStack output = recipe.assemble(input);
            if (output.isEmpty() || !output.is(target) || !output.isItemEnabled(level.enabledFeatures())) {
                continue;
            }
            branch.add(output);
            for (ItemStack remainder : recipe.getRemainingItems(input)) {
                branch.add(remainder);
            }
            prerequisiteSteps.add(new PlannedStep(holder, List.copyOf(chosenIngredients), grid));
            return Optional.of(new PlanResult(branch, List.copyOf(prerequisiteSteps)));
        }
        return Optional.empty();
    }

    @SuppressWarnings("deprecation")
    private Optional<IngredientResolution> resolveIngredient(
            Ingredient ingredient,
            VirtualInventory inventory,
            CraftingGrid grid,
            RecipeIndex recipeIndex,
            int depth,
            Set<Item> visiting,
            ServerLevel level
    ) {
        List<Holder<Item>> alternatives = ingredient.items().limit(MAX_INGREDIENT_ALTERNATIVES).toList();
        for (Holder<Item> alternative : alternatives) {
            Item item = alternative.value();
            PlanResult result = planItem(
                    item,
                    inventory.copy(),
                    grid,
                    recipeIndex,
                    depth,
                    visiting,
                    level
            ).orElse(null);
            if (result == null) {
                continue;
            }
            Item chosen = result.inventory().findMatching(ingredient).orElse(null);
            if (chosen != null) {
                return Optional.of(new IngredientResolution(chosen, result.inventory(), result.steps()));
            }
        }
        return Optional.empty();
    }

    private CraftResult applyStep(ServerLevel level, PlannedStep step) {
        SimpleContainer shadow = copyStorage();
        List<ItemStack> actualIngredients = new ArrayList<>(step.ingredients().size());
        for (Item ingredient : step.ingredients()) {
            ItemStack removed = removeOne(shadow, ingredient);
            if (removed.isEmpty()) {
                return new CraftResult(CraftStatus.REVALIDATION_FAILED, ItemStack.EMPTY);
            }
            actualIngredients.add(removed);
        }

        CraftingRecipe recipe = step.recipe().value();
        CraftingInput input = buildInput(recipe, actualIngredients, step.grid());
        if (!recipe.matches(input, level)) {
            return new CraftResult(CraftStatus.REVALIDATION_FAILED, ItemStack.EMPTY);
        }
        ItemStack output = recipe.assemble(input);
        if (output.isEmpty() || !output.isItemEnabled(level.enabledFeatures())) {
            return new CraftResult(CraftStatus.REVALIDATION_FAILED, ItemStack.EMPTY);
        }
        NonNullList<ItemStack> remainders = recipe.getRemainingItems(input);
        if (!addFully(shadow, output.copy())) {
            return new CraftResult(CraftStatus.INVENTORY_FULL, ItemStack.EMPTY);
        }
        for (ItemStack remainder : remainders) {
            if (!remainder.isEmpty() && !addFully(shadow, remainder.copy())) {
                return new CraftResult(CraftStatus.INVENTORY_FULL, ItemStack.EMPTY);
            }
        }

        SimpleContainer storage = partner.getInventory();
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            storage.setItem(slot, shadow.getItem(slot).copy());
        }
        output.onCraftedBySystem(level);
        partner.swing(InteractionHand.MAIN_HAND);
        return new CraftResult(CraftStatus.CRAFTED, output.copy());
    }

    private RecipeIndex recipeIndex(ServerLevel level) {
        Map<Item, List<RecipeHolder<CraftingRecipe>>> byOutput = new HashMap<>();
        for (RecipeHolder<?> untyped : level.getServer().getRecipeManager().getRecipes()) {
            if (!(untyped.value() instanceof CraftingRecipe recipe)
                    || (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            RecipeHolder<CraftingRecipe> holder = (RecipeHolder<CraftingRecipe>) untyped;
            ItemStack representative = representativeOutput(recipe);
            if (!representative.isEmpty()) {
                byOutput.computeIfAbsent(representative.getItem(), ignored -> new ArrayList<>()).add(holder);
            }
        }
        Map<Item, List<RecipeHolder<CraftingRecipe>>> frozen = new HashMap<>();
        byOutput.forEach((item, recipes) -> frozen.put(item, List.copyOf(recipes)));
        return new RecipeIndex(Map.copyOf(frozen));
    }

    @SuppressWarnings("deprecation")
    private static ItemStack representativeOutput(CraftingRecipe recipe) {
        List<Item> ingredients = new ArrayList<>();
        for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
            Holder<Item> first = ingredient.items().findFirst().orElse(null);
            if (first == null) {
                return ItemStack.EMPTY;
            }
            ingredients.add(first.value());
        }
        CraftingGrid grid = CraftingGrid.WORKBENCH_3X3;
        if (!grid.fits(recipe)) {
            return ItemStack.EMPTY;
        }
        CraftingInput input = buildInput(recipe, stacksFor(ingredients), grid);
        return recipe.assemble(input);
    }

    private boolean containsAnyTarget(List<Item> targets) {
        for (ItemStack stack : partner.getInventory().getItems()) {
            if (!stack.isEmpty() && targets.contains(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    private SimpleContainer copyStorage() {
        SimpleContainer storage = partner.getInventory();
        SimpleContainer copy = new SimpleContainer(storage.getContainerSize());
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            copy.setItem(slot, storage.getItem(slot).copy());
        }
        return copy;
    }

    private static ItemStack removeOne(SimpleContainer container, Item item) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(item)) {
                continue;
            }
            ItemStack removed = stack.copyWithCount(1);
            stack.shrink(1);
            if (stack.isEmpty()) {
                container.setItem(slot, ItemStack.EMPTY);
            }
            return removed;
        }
        return ItemStack.EMPTY;
    }

    private static boolean addFully(SimpleContainer container, ItemStack stack) {
        return container.addItem(stack).isEmpty();
    }

    private static List<ItemStack> stacksFor(List<Item> items) {
        return items.stream().map(ItemStack::new).toList();
    }

    private static CraftingInput buildInput(
            CraftingRecipe recipe,
            List<ItemStack> ingredients,
            CraftingGrid grid
    ) {
        int width;
        int height;
        if (recipe instanceof ShapedRecipe shaped) {
            width = shaped.getWidth();
            height = shaped.getHeight();
        } else {
            width = Math.min(grid.width(), Math.max(1, ingredients.size()));
            height = Math.max(1, (ingredients.size() + width - 1) / width);
        }
        List<ItemStack> cells = new ArrayList<>(width * height);
        for (int index = 0; index < width * height; index++) {
            cells.add(ItemStack.EMPTY);
        }
        if (recipe instanceof ShapedRecipe) {
            IntList placement = recipe.placementInfo().slotsToIngredientIndex();
            for (int slot = 0; slot < Math.min(cells.size(), placement.size()); slot++) {
                int ingredientIndex = placement.getInt(slot);
                if (ingredientIndex >= 0 && ingredientIndex < ingredients.size()) {
                    cells.set(slot, ingredients.get(ingredientIndex).copyWithCount(1));
                }
            }
        } else {
            for (int slot = 0; slot < ingredients.size(); slot++) {
                cells.set(slot, ingredients.get(slot).copyWithCount(1));
            }
        }
        return CraftingInput.of(width, height, cells);
    }

    /** 女仆可用的原生制作网格边界。 */
    public enum CraftingGrid {
        PERSONAL_2X2(2, 2),
        WORKBENCH_3X3(3, 3);

        private final int width;
        private final int height;

        CraftingGrid(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public boolean fits(CraftingRecipe recipe) {
            if (recipe instanceof ShapedRecipe shaped) {
                return fitsShaped(shaped.getWidth(), shaped.getHeight());
            }
            return recipe instanceof ShapelessRecipe
                    && fitsShapeless(recipe.placementInfo().ingredients().size());
        }

        /** 判断普通有形配方能否放入该制作网格。 */
        public boolean fitsShaped(int recipeWidth, int recipeHeight) {
            return recipeWidth > 0
                    && recipeHeight > 0
                    && recipeWidth <= width
                    && recipeHeight <= height;
        }

        /** 判断普通无形配方的非空原料数能否放入该制作网格。 */
        public boolean fitsShapeless(int ingredientCount) {
            return ingredientCount > 0 && ingredientCount <= width * height;
        }
    }

    /** 单步制作的结果；失败不会改变真实背包。 */
    public record CraftResult(CraftStatus status, ItemStack output) {
    }

    public enum CraftStatus {
        CRAFTED,
        TARGET_PRESENT,
        NO_PLAN,
        INVENTORY_FULL,
        REVALIDATION_FAILED
    }

    private record PlannedStep(
            RecipeHolder<CraftingRecipe> recipe,
            List<Item> ingredients,
            CraftingGrid grid
    ) {
    }

    private record PlanResult(VirtualInventory inventory, List<PlannedStep> steps) {
    }

    private record IngredientResolution(Item item, VirtualInventory inventory, List<PlannedStep> steps) {
    }

    private record RecipeIndex(Map<Item, List<RecipeHolder<CraftingRecipe>>> recipesByOutput) {
        private List<RecipeHolder<CraftingRecipe>> recipesFor(Item output) {
            return recipesByOutput.getOrDefault(output, List.of());
        }
    }

    /** 规划期只保存物品数量，不持有或修改真实 ItemStack。 */
    private static final class VirtualInventory {
        private final LinkedHashMap<Item, Integer> counts;

        private VirtualInventory(LinkedHashMap<Item, Integer> counts) {
            this.counts = counts;
        }

        private static VirtualInventory from(SimpleContainer storage) {
            LinkedHashMap<Item, Integer> counts = new LinkedHashMap<>();
            for (ItemStack stack : storage.getItems()) {
                if (!stack.isEmpty()) {
                    counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
            return new VirtualInventory(counts);
        }

        private VirtualInventory copy() {
            return new VirtualInventory(new LinkedHashMap<>(counts));
        }

        private int count(Item item) {
            return counts.getOrDefault(item, 0);
        }

        private Optional<Item> findMatching(Ingredient ingredient) {
            return counts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0 && ingredient.test(new ItemStack(entry.getKey())))
                    .map(Map.Entry::getKey)
                    .findFirst();
        }

        private boolean consume(Item item, int amount) {
            int available = count(item);
            if (amount <= 0 || available < amount) {
                return false;
            }
            if (available == amount) {
                counts.remove(item);
            } else {
                counts.put(item, available - amount);
            }
            return true;
        }

        private void add(ItemStack stack) {
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
    }
}
