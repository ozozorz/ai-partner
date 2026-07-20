package io.github.ozozorz.aipartner.evaluation;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 加载并验证随模组打包的版本化离线评测数据集。
 */
public final class OfflineEvaluationDataset {
    public static final String VERSION = "1.0";
    public static final String RESOURCE_PATH = "/assets/ai-partner/evaluation/offline_instructions_v1.jsonl";
    private static final Set<String> SPLITS = Set.of("dev", "test");
    private static final Set<String> CATEGORIES = Set.of(
            "explicit_executable",
            "colloquial_synonym",
            "composite",
            "missing_ambiguous",
            "unsupported_infeasible",
            "boundary_safety"
    );
    private static final Gson GSON = new Gson();

    private OfflineEvaluationDataset() {
    }

    /**
     * 完整读取资源、检查唯一 ID 与金标一致性，并返回原始文本的冻结哈希。
     */
    public static Loaded load() {
        String raw = readResource();
        List<OfflineEvaluationCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int lineNumber = 0;
        for (String line : raw.lines().toList()) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                OfflineEvaluationCase evaluationCase = GSON.fromJson(line, OfflineEvaluationCase.class);
                validate(evaluationCase, ids, lineNumber);
                cases.add(evaluationCase);
            } catch (JsonParseException exception) {
                throw new IllegalStateException("Invalid offline dataset JSON at line " + lineNumber, exception);
            }
        }
        validateFrozenDistribution(cases);
        return new Loaded(VERSION, sha256(raw), raw, List.copyOf(cases));
    }

    private static void validate(
            OfflineEvaluationCase evaluationCase,
            Set<String> ids,
            int lineNumber
    ) {
        if (evaluationCase == null
                || evaluationCase.id() == null
                || evaluationCase.id().isBlank()
                || !ids.add(evaluationCase.id())) {
            throw new IllegalStateException("Missing or duplicate dataset id at line " + lineNumber);
        }
        if (!SPLITS.contains(evaluationCase.split())
                || !CATEGORIES.contains(evaluationCase.category())
                || evaluationCase.instruction() == null
                || evaluationCase.instruction().isBlank()
                || evaluationCase.goldDialogueAct() == null) {
            throw new IllegalStateException("Incomplete offline gold label at line " + lineNumber);
        }
        boolean proposesJob = evaluationCase.goldDialogueAct() == io.github.ozozorz.aipartner.llm.DialogueAct.PROPOSE_JOB;
        if (proposesJob != (evaluationCase.goldJobType() != null)) {
            throw new IllegalStateException("Dialogue act and job label disagree at line " + lineNumber);
        }
        if (evaluationCase.shouldClarify()
                != (evaluationCase.goldDialogueAct() == io.github.ozozorz.aipartner.llm.DialogueAct.ASK_CLARIFICATION)
                || evaluationCase.shouldReject()
                != (evaluationCase.goldDialogueAct() == io.github.ozozorz.aipartner.llm.DialogueAct.REJECT_UNSUPPORTED)) {
            throw new IllegalStateException("Clarification/rejection flags disagree at line " + lineNumber);
        }
    }

    private static void validateFrozenDistribution(List<OfflineEvaluationCase> cases) {
        if (cases.size() != 72) {
            throw new IllegalStateException("Offline dataset v1 must contain exactly 72 cases");
        }
        Map<String, Long> categoryCounts = cases.stream().collect(Collectors.groupingBy(
                OfflineEvaluationCase::category,
                Collectors.counting()
        ));
        for (String category : CATEGORIES) {
            if (categoryCounts.getOrDefault(category, 0L) != 12L) {
                throw new IllegalStateException("Offline dataset category must contain 12 cases: " + category);
            }
        }
        for (String category : CATEGORIES) {
            for (String split : SPLITS) {
                long count = cases.stream()
                        .filter(value -> value.category().equals(category) && value.split().equals(split))
                        .count();
                if (count != 6L) {
                    throw new IllegalStateException(
                            "Offline dataset category/split must contain 6 cases: " + category + "/" + split
                    );
                }
            }
        }
    }

    private static String readResource() {
        try (InputStream input = OfflineEvaluationDataset.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled offline dataset " + RESOURCE_PATH);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read bundled offline dataset", exception);
        }
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 已验证的数据、版本和内容哈希。
     */
    public record Loaded(String version, String sha256, String rawJsonl, List<OfflineEvaluationCase> cases) {
    }
}
