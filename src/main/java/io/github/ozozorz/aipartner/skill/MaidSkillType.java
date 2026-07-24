package io.github.ozozorz.aipartner.skill;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 女仆掌握的基础技能目录。
 *
 * <p>技能描述“会做什么”，工作配置只负责组合技能，不再创建一次性的任务类型。</p>
 */
public enum MaidSkillType {
    NAVIGATE("navigate"),
    PICK_UP_ITEM("pick-up-item"),
    PERSONAL_CRAFTING_2X2("personal-crafting-2x2"),
    WORKBENCH_CRAFTING_3X3("workbench-crafting-3x3"),
    BREAK_BLOCK_BY_HAND("break-block-by-hand"),
    CHOP_WITH_AXE("chop-with-axe"),
    MINE_WITH_PICKAXE("mine-with-pickaxe"),
    DIG_WITH_SHOVEL("dig-with-shovel"),
    TILL_WITH_HOE("till-with-hoe"),
    HARVEST_CROP("harvest-crop"),
    PLANT_CROP("plant-crop"),
    PLACE_BLOCK("place-block"),
    OPEN_CONTAINER("open-container"),
    TAKE_FROM_CONTAINER("take-from-container"),
    STORE_IN_CONTAINER("store-in-container"),
    REMEMBER_CONTAINER_CONTENTS("remember-container-contents"),
    SMELT_IN_FURNACE("smelt-in-furnace"),
    USE_BLAST_FURNACE("use-blast-furnace"),
    USE_SMOKER("use-smoker"),
    SHEAR("shear"),
    MILK("milk"),
    FEED_ANIMAL("feed-animal"),
    FEED_OWNER("feed-owner"),
    COLLECT_HONEY("collect-honey"),
    FISH("fish"),
    EXTINGUISH_FIRE("extinguish-fire"),
    MELEE_COMBAT("melee-combat"),
    RANGED_COMBAT("ranged-combat"),
    SHIELD_BLOCK("shield-block");

    private final String serializedName;

    MaidSkillType(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public String translationKey() {
        return "skill.ai-partner." + serializedName;
    }

    /**
     * 解析命令和存档中稳定的技能名称。
     */
    public static Optional<MaidSkillType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(skill -> skill.serializedName.equals(normalized))
                .findFirst();
    }
}
