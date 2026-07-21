package io.github.ozozorz.aipartner.service;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.ozozorz.aipartner.AiPartnerMod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 保存主人到女仆 UUID 集合及当前选中女仆，实体的驯服主人 UUID 仍是所有权事实来源。
 */
public final class MaidOwnershipState extends SavedData {
    private static final Codec<OwnerEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("owner").forGetter(OwnerEntry::owner),
            Codec.STRING.listOf().optionalFieldOf("maids", List.of()).forGetter(OwnerEntry::maids),
            Codec.STRING.optionalFieldOf("selected").forGetter(OwnerEntry::selected)
    ).apply(instance, OwnerEntry::new));

    public static final Codec<MaidOwnershipState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ENTRY_CODEC.listOf().optionalFieldOf("owners", List.of()).forGetter(MaidOwnershipState::serializedEntries)
    ).apply(instance, MaidOwnershipState::new));

    public static final SavedDataType<MaidOwnershipState> TYPE = new SavedDataType<>(
            AiPartnerMod.id("maid_ownership"),
            MaidOwnershipState::new,
            CODEC,
            DataFixTypes.LEVEL
    );

    private final Map<UUID, LinkedHashSet<UUID>> ownedMaids = new LinkedHashMap<>();
    private final Map<UUID, UUID> selectedMaids = new LinkedHashMap<>();

    public MaidOwnershipState() {
        setDirty();
    }

    private MaidOwnershipState(List<OwnerEntry> entries) {
        for (OwnerEntry entry : entries) {
            try {
                UUID owner = UUID.fromString(entry.owner());
                LinkedHashSet<UUID> maids = new LinkedHashSet<>();
                for (String maid : entry.maids()) {
                    maids.add(UUID.fromString(maid));
                }
                if (!maids.isEmpty()) {
                    ownedMaids.put(owner, maids);
                }
                entry.selected().map(UUID::fromString)
                        .filter(maids::contains)
                        .ifPresent(selected -> selectedMaids.put(owner, selected));
            } catch (IllegalArgumentException ignored) {
                // 损坏的单个索引条目被忽略，实体自身的主人 UUID 不受影响。
            }
        }
    }

    public static MaidOwnershipState get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Set<UUID> ownedBy(UUID owner) {
        return Set.copyOf(ownedMaids.getOrDefault(owner, new LinkedHashSet<>()));
    }

    public int ownedCount(UUID owner) {
        return ownedMaids.getOrDefault(owner, new LinkedHashSet<>()).size();
    }

    public Optional<UUID> selected(UUID owner) {
        return Optional.ofNullable(selectedMaids.get(owner));
    }

    public void register(UUID owner, UUID maid) {
        boolean added = ownedMaids.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(maid);
        if (!selectedMaids.containsKey(owner)) {
            selectedMaids.put(owner, maid);
            added = true;
        }
        if (added) {
            setDirty();
        }
    }

    public void unregister(UUID owner, UUID maid) {
        LinkedHashSet<UUID> maids = ownedMaids.get(owner);
        if (maids == null || !maids.remove(maid)) {
            return;
        }
        if (maids.isEmpty()) {
            ownedMaids.remove(owner);
            selectedMaids.remove(owner);
        } else if (maid.equals(selectedMaids.get(owner))) {
            selectedMaids.put(owner, maids.getFirst());
        }
        setDirty();
    }

    public boolean select(UUID owner, UUID maid) {
        if (!ownedMaids.getOrDefault(owner, new LinkedHashSet<>()).contains(maid)) {
            return false;
        }
        selectedMaids.put(owner, maid);
        setDirty();
        return true;
    }

    private List<OwnerEntry> serializedEntries() {
        List<OwnerEntry> entries = new ArrayList<>();
        ownedMaids.forEach((owner, maids) -> entries.add(new OwnerEntry(
                owner.toString(),
                maids.stream().map(UUID::toString).toList(),
                Optional.ofNullable(selectedMaids.get(owner)).map(UUID::toString)
        )));
        return entries;
    }

    private record OwnerEntry(String owner, List<String> maids, Optional<String> selected) {
    }
}
