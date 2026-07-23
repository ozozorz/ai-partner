package io.github.ozozorz.aipartner.registry;

import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.entity.ai.sensing.MaidStateSensor;
import io.github.ozozorz.aipartner.entity.ai.sensing.MaidThreatSensor;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;

/**
 * 注册女仆专用的状态与威胁传感器。
 */
public final class ModSensorTypes {
    public static final SensorType<MaidThreatSensor> MAID_THREAT = register("maid_threat", MaidThreatSensor::new);
    public static final SensorType<MaidStateSensor> MAID_STATE = register("maid_state", MaidStateSensor::new);

    private ModSensorTypes() {
    }

    /**
     * 触发类初始化；实际注册由静态字段完成。
     */
    public static void register() {
        // 静态字段已完成注册。
    }

    private static <S extends Sensor<?>> SensorType<S> register(String path, Supplier<S> factory) {
        return Registry.register(
                BuiltInRegistries.SENSOR_TYPE,
                AiPartnerMod.id(path),
                new SensorType<>(factory)
        );
    }
}
