package io.github.ozozorz.aipartner.core.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证个人制作与工作台制作严格遵守原版网格尺寸边界。 */
class CraftItemActionTest {
    @Test
    void personalGridAcceptsOnlyTwoByTwoRecipes() {
        CraftItemAction.CraftingGrid grid = CraftItemAction.CraftingGrid.PERSONAL_2X2;
        assertTrue(grid.fitsShaped(2, 2));
        assertTrue(grid.fitsShapeless(4));
        assertFalse(grid.fitsShaped(3, 1));
        assertFalse(grid.fitsShapeless(5));
    }

    @Test
    void workbenchGridAcceptsThreeByThreeRecipes() {
        CraftItemAction.CraftingGrid grid = CraftItemAction.CraftingGrid.WORKBENCH_3X3;
        assertTrue(grid.fitsShaped(3, 3));
        assertTrue(grid.fitsShapeless(9));
        assertFalse(grid.fitsShaped(4, 1));
        assertFalse(grid.fitsShapeless(10));
    }

    @Test
    void craftingGridRejectsEmptyOrInvalidDimensions() {
        CraftItemAction.CraftingGrid grid = CraftItemAction.CraftingGrid.WORKBENCH_3X3;
        assertFalse(grid.fitsShaped(0, 2));
        assertFalse(grid.fitsShaped(2, 0));
        assertFalse(grid.fitsShapeless(0));
    }
}
