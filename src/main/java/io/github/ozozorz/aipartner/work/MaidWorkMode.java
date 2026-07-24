package io.github.ozozorz.aipartner.work;

import io.github.ozozorz.aipartner.skill.MaidSkillType;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * WORK 模式内可选择的持续工作配置。
 *
 * <p>每个配置只是基础技能的组合与循环规则，不再表示一次性任务或任务类型。</p>
 */
public enum MaidWorkMode {
    NONE("none"),
    FARMER("farmer", MaidSkillType.NAVIGATE, MaidSkillType.HARVEST_CROP, MaidSkillType.PLANT_CROP),
    SUGAR_CANE("sugar-cane", MaidSkillType.NAVIGATE, MaidSkillType.BREAK_BLOCK_BY_HAND, MaidSkillType.PLACE_BLOCK),
    MELON("melon", MaidSkillType.NAVIGATE, MaidSkillType.BREAK_BLOCK_BY_HAND),
    COCOA("cocoa", MaidSkillType.NAVIGATE, MaidSkillType.HARVEST_CROP, MaidSkillType.PLANT_CROP),
    FORAGER("forager", MaidSkillType.NAVIGATE, MaidSkillType.BREAK_BLOCK_BY_HAND),
    SNOW_CLEARER("snow-clearer", MaidSkillType.NAVIGATE, MaidSkillType.DIG_WITH_SHOVEL),
    BEEKEEPER("beekeeper", MaidSkillType.NAVIGATE, MaidSkillType.COLLECT_HONEY),
    SHEARER("shearer", MaidSkillType.NAVIGATE, MaidSkillType.SHEAR),
    MILKER("milker", MaidSkillType.NAVIGATE, MaidSkillType.MILK),
    CAREGIVER("caregiver", MaidSkillType.NAVIGATE, MaidSkillType.FEED_OWNER),
    BREEDER("breeder", MaidSkillType.NAVIGATE, MaidSkillType.FEED_ANIMAL),
    TORCH_BEARER("torch-bearer", MaidSkillType.NAVIGATE, MaidSkillType.PLACE_BLOCK),
    FIREFIGHTER("firefighter", MaidSkillType.NAVIGATE, MaidSkillType.EXTINGUISH_FIRE),
    LUMBERJACK(
            "lumberjack",
            MaidSkillType.NAVIGATE,
            MaidSkillType.BREAK_BLOCK_BY_HAND,
            MaidSkillType.CHOP_WITH_AXE
    ),
    MINER("miner", MaidSkillType.NAVIGATE, MaidSkillType.MINE_WITH_PICKAXE),
    SMELTER(
            "smelter",
            MaidSkillType.NAVIGATE,
            MaidSkillType.SMELT_IN_FURNACE,
            MaidSkillType.OPEN_CONTAINER,
            MaidSkillType.TAKE_FROM_CONTAINER,
            MaidSkillType.STORE_IN_CONTAINER,
            MaidSkillType.REMEMBER_CONTAINER_CONTENTS
    ),
    FISHER("fisher", MaidSkillType.NAVIGATE, MaidSkillType.FISH, MaidSkillType.PICK_UP_ITEM);

    private static final int MENU_BUTTON_BASE = 100;

    private final String serializedName;
    private final Set<MaidSkillType> requiredSkills;

    MaidWorkMode(String serializedName, MaidSkillType... requiredSkills) {
        this.serializedName = serializedName;
        EnumSet<MaidSkillType> set = requiredSkills.length == 0
                ? EnumSet.noneOf(MaidSkillType.class)
                : EnumSet.copyOf(Arrays.asList(requiredSkills));
        this.requiredSkills = Collections.unmodifiableSet(set);
    }

    public String serializedName() {
        return serializedName;
    }

    public Set<MaidSkillType> requiredSkills() {
        return requiredSkills;
    }

    public MaidWorkMode next() {
        MaidWorkMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }

    /** 每个具体工作使用独立且有界的菜单按钮编号，不接受客户端提交任意枚举序号。 */
    public int menuButtonId() {
        return MENU_BUTTON_BASE + ordinal();
    }

    public static Optional<MaidWorkMode> fromMenuButtonId(int buttonId) {
        int ordinal = buttonId - MENU_BUTTON_BASE;
        MaidWorkMode[] modes = values();
        return ordinal >= 0 && ordinal < modes.length ? Optional.of(modes[ordinal]) : Optional.empty();
    }

    public static Optional<MaidWorkMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(mode -> mode.serializedName.equals(normalized))
                .findFirst();
    }

    public static MaidWorkMode fromSavedName(String value) {
        return parse(value).orElse(NONE);
    }
}
