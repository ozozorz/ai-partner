package io.github.ozozorz.aipartner.core.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 只包含稳定原始值的版本化任务快照，可供不同任务自行解释。
 */
public record MaidTaskSnapshot(
        int version,
        Map<String, Integer> integers,
        Map<String, Long> longs,
        Map<String, String> strings
) {
    private static final int MAX_ENTRIES_PER_TYPE = 64;
    private static final String PREFIX = "ActiveTaskSnapshot";

    public MaidTaskSnapshot {
        if (version < 1) {
            throw new IllegalArgumentException("Task snapshot version must be positive");
        }
        integers = immutableOrderedCopy(integers);
        longs = immutableOrderedCopy(longs);
        strings = immutableOrderedCopy(strings);
    }

    public static Builder builder(int version) {
        return new Builder(version);
    }

    public static MaidTaskSnapshot empty() {
        return builder(1).build();
    }

    public int integer(String key, int fallback) {
        return integers.getOrDefault(key, fallback);
    }

    public long longValue(String key, long fallback) {
        return longs.getOrDefault(key, fallback);
    }

    public String string(String key, String fallback) {
        return strings.getOrDefault(key, fallback);
    }

    /**
     * 使用带数量和键名的稳定平铺格式写入实体存档。
     */
    public void write(ValueOutput output) {
        output.putInt(PREFIX + "Version", version);
        writeIntegers(output);
        writeLongs(output);
        writeStrings(output);
    }

    /**
     * 从实体存档安全读取快照，异常数量会被限制，防止损坏数据造成长循环。
     */
    public static MaidTaskSnapshot read(ValueInput input) {
        Builder builder = builder(Math.max(1, input.getIntOr(PREFIX + "Version", 1)));
        int integerCount = boundedCount(input.getIntOr(PREFIX + "IntegerCount", 0));
        for (int index = 0; index < integerCount; index++) {
            String key = input.getStringOr(PREFIX + "IntegerKey" + index, "");
            if (!key.isBlank()) {
                builder.putInt(key, input.getIntOr(PREFIX + "IntegerValue" + index, 0));
            }
        }
        int longCount = boundedCount(input.getIntOr(PREFIX + "LongCount", 0));
        for (int index = 0; index < longCount; index++) {
            String key = input.getStringOr(PREFIX + "LongKey" + index, "");
            if (!key.isBlank()) {
                builder.putLong(key, input.getLongOr(PREFIX + "LongValue" + index, 0L));
            }
        }
        int stringCount = boundedCount(input.getIntOr(PREFIX + "StringCount", 0));
        for (int index = 0; index < stringCount; index++) {
            String key = input.getStringOr(PREFIX + "StringKey" + index, "");
            if (!key.isBlank()) {
                builder.putString(key, input.getStringOr(PREFIX + "StringValue" + index, ""));
            }
        }
        return builder.build();
    }

    private void writeIntegers(ValueOutput output) {
        output.putInt(PREFIX + "IntegerCount", integers.size());
        int index = 0;
        for (Map.Entry<String, Integer> entry : integers.entrySet()) {
            output.putString(PREFIX + "IntegerKey" + index, entry.getKey());
            output.putInt(PREFIX + "IntegerValue" + index, entry.getValue());
            index++;
        }
    }

    private void writeLongs(ValueOutput output) {
        output.putInt(PREFIX + "LongCount", longs.size());
        int index = 0;
        for (Map.Entry<String, Long> entry : longs.entrySet()) {
            output.putString(PREFIX + "LongKey" + index, entry.getKey());
            output.putLong(PREFIX + "LongValue" + index, entry.getValue());
            index++;
        }
    }

    private void writeStrings(ValueOutput output) {
        output.putInt(PREFIX + "StringCount", strings.size());
        int index = 0;
        for (Map.Entry<String, String> entry : strings.entrySet()) {
            output.putString(PREFIX + "StringKey" + index, entry.getKey());
            output.putString(PREFIX + "StringValue" + index, entry.getValue());
            index++;
        }
    }

    private static int boundedCount(int value) {
        return Math.max(0, Math.min(MAX_ENTRIES_PER_TYPE, value));
    }

    private static <T> Map<String, T> immutableOrderedCopy(Map<String, T> source) {
        Objects.requireNonNull(source, "source");
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(source)));
    }

    /**
     * 构建任务私有快照字段。
     */
    public static final class Builder {
        private final int version;
        private final Map<String, Integer> integers = new LinkedHashMap<>();
        private final Map<String, Long> longs = new LinkedHashMap<>();
        private final Map<String, String> strings = new LinkedHashMap<>();

        private Builder(int version) {
            if (version < 1) {
                throw new IllegalArgumentException("Task snapshot version must be positive");
            }
            this.version = version;
        }

        public Builder putInt(String key, int value) {
            integers.put(requireKey(key), value);
            return this;
        }

        public Builder putLong(String key, long value) {
            longs.put(requireKey(key), value);
            return this;
        }

        public Builder putString(String key, String value) {
            strings.put(requireKey(key), Objects.requireNonNull(value, "value"));
            return this;
        }

        public MaidTaskSnapshot build() {
            return new MaidTaskSnapshot(version, integers, longs, strings);
        }

        private static String requireKey(String key) {
            String checked = Objects.requireNonNull(key, "key").strip();
            if (checked.isEmpty()) {
                throw new IllegalArgumentException("Task snapshot key must not be blank");
            }
            return checked;
        }
    }
}
