package io.github.ozozorz.aipartner.registry;

import io.github.ozozorz.aipartner.AiPartnerMod;
import java.util.Optional;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * 女仆 Brain 使用的短期记忆。它们都不带 Codec，因此不会把瞬时运行状态写入存档。
 */
public final class ModMemoryModules {
    public static final MemoryModuleType<Unit> FOLLOW_OWNER = register("follow_owner");
    public static final MemoryModuleType<Unit> STAY_IN_PLACE = register("stay_in_place");
    public static final MemoryModuleType<Unit> PAUSED = register("paused");
    public static final MemoryModuleType<Unit> TASK_CONTROLLED = register("task_controlled");
    public static final MemoryModuleType<Unit> AMBIENT_MOVEMENT = register("ambient_movement");
    public static final MemoryModuleType<Unit> SCHEDULE_WORK = register("schedule_work");
    public static final MemoryModuleType<Unit> SCHEDULE_REST = register("schedule_rest");
    public static final MemoryModuleType<GlobalPos> ACTIVITY_TARGET = register("activity_target");

    private ModMemoryModules() {
    }

    /**
     * 触发类初始化；实际注册由静态字段完成。
     */
    public static void register() {
        // 静态字段已完成注册。
    }

    private static <T> MemoryModuleType<T> register(String path) {
        return Registry.register(
                BuiltInRegistries.MEMORY_MODULE_TYPE,
                AiPartnerMod.id(path),
                new MemoryModuleType<>(Optional.empty())
        );
    }
}
