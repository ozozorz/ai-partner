package io.github.ozozorz.aipartner.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.core.schedule.ScheduleWindows;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 女仆生活玩法的服务端配置。
 */
public record MaidGameplayConfig(
        int maxMaidsPerOwner,
        int defaultActivityRadius,
        int maximumActivityRadius,
        int duskStart,
        int nightStart,
        int dawnStart,
        int sleepHealIntervalTicks,
        float sleepHealAmount,
        int foodAffectionCooldownTicks,
        int foodAffectionGain,
        boolean generalPickupEnabled,
        boolean experiencePickupEnabled,
        boolean builtInVoiceEnabled,
        boolean chatBubblesEnabled
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("ai-partner-gameplay.json");
    private static final MaidGameplayConfig DEFAULT = new MaidGameplayConfig(
            1,
            8,
            32,
            12000,
            14000,
            22000,
            100,
            1.0F,
            1200,
            1,
            true,
            true,
            true,
            true
    );
    private static volatile MaidGameplayConfig instance;

    public static MaidGameplayConfig get() {
        MaidGameplayConfig current = instance;
        if (current != null) {
            return current;
        }
        synchronized (MaidGameplayConfig.class) {
            if (instance == null) {
                instance = load();
            }
            return instance;
        }
    }

    public ScheduleWindows scheduleWindows() {
        return new ScheduleWindows(0, duskStart, nightStart, dawnStart, 24000);
    }

    private static MaidGameplayConfig load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(CONFIG_PATH, GSON.toJson(DEFAULT) + System.lineSeparator(), StandardCharsets.UTF_8);
                return DEFAULT;
            }
            return validate(GSON.fromJson(
                    Files.readString(CONFIG_PATH, StandardCharsets.UTF_8),
                    MaidGameplayConfig.class
            ));
        } catch (IOException | RuntimeException exception) {
            AiPartnerMod.LOGGER.error("Failed to load config/ai-partner-gameplay.json; defaults are used", exception);
            return DEFAULT;
        }
    }

    private static MaidGameplayConfig validate(MaidGameplayConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Missing gameplay configuration");
        }
        if (config.maxMaidsPerOwner < 1 || config.maxMaidsPerOwner > 32) {
            throw new IllegalArgumentException("maxMaidsPerOwner must be between 1 and 32");
        }
        if (config.defaultActivityRadius < 1
                || config.maximumActivityRadius < config.defaultActivityRadius
                || config.maximumActivityRadius > 64) {
            throw new IllegalArgumentException("Invalid activity radius range");
        }
        config.scheduleWindows();
        if (config.sleepHealIntervalTicks < 20
                || config.sleepHealAmount < 0.0F
                || config.foodAffectionCooldownTicks < 0
                || config.foodAffectionGain < 0) {
            throw new IllegalArgumentException("Invalid life-cycle timing or gain configuration");
        }
        return config;
    }
}
