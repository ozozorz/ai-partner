package io.github.ozozorz.aipartner.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.junit.jupiter.api.Test;

/**
 * 验证任务快照的版本、默认值和不可变性。
 */
class MaidTaskSnapshotTest {
    @Test
    void storesTypedValuesAndReturnsFallbacks() {
        MaidTaskSnapshot snapshot = MaidTaskSnapshot.builder(2)
                .putInt("count", 8)
                .putLong("elapsed", 120L)
                .putString("phase", "NAVIGATE")
                .build();

        assertEquals(2, snapshot.version());
        assertEquals(8, snapshot.integer("count", 0));
        assertEquals(120L, snapshot.longValue("elapsed", 0L));
        assertEquals("NAVIGATE", snapshot.string("phase", "IDLE"));
        assertEquals(4, snapshot.integer("missing", 4));
    }

    @Test
    void defensivelyCopiesSourceMaps() {
        Map<String, Integer> integers = new LinkedHashMap<>();
        integers.put("count", 1);
        MaidTaskSnapshot snapshot = new MaidTaskSnapshot(1, integers, Map.of(), Map.of());

        integers.put("count", 99);

        assertEquals(1, snapshot.integer("count", 0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.integers().put("other", 2));
    }

    @Test
    void rejectsInvalidVersionsAndBlankKeys() {
        assertThrows(IllegalArgumentException.class, () -> MaidTaskSnapshot.builder(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> MaidTaskSnapshot.builder(1).putInt(" ", 1)
        );
    }

    @Test
    void roundTripsThroughMinecraftValueStorage() {
        MaidTaskSnapshot original = MaidTaskSnapshot.builder(3)
                .putInt("count", 12)
                .putLong("deadline", 900L)
                .putString("phase", "DEPOSITING")
                .build();
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        original.write(output);
        HolderLookup.Provider lookup = HolderLookup.Provider.create(Stream.empty());
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, lookup, output.buildResult());

        MaidTaskSnapshot restored = MaidTaskSnapshot.read(input);

        assertEquals(original, restored);
    }
}
