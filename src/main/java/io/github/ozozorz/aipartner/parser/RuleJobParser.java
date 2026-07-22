package io.github.ozozorz.aipartner.parser;

import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.job.AllowedTargets;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 有界的确定性任务解析器，也是模型端点未就绪时的安全单意图降级路径。
 */
public final class RuleJobParser {
    private static final Pattern ARABIC_NUMBER = Pattern.compile("(?<![\\d.])(\\d{1,2})(?![\\d.])");
    private static final Pattern ARABIC_QUANTITY = Pattern.compile("(?<![\\d.])(\\d{1,2})\\s*(?:个|根|块)");
    private static final Pattern RADIUS_WITH_BLOCK_UNIT = Pattern.compile(
            "(?<![\\d.])(\\d{1,2})\\s*格(?:范围)?(?:内|以内)?"
    );
    private static final Pattern RADIUS_AFTER_LABEL = Pattern.compile(
            "(?:半径|范围)\\s*(?:是|为)?\\s*(\\d{1,2})(?![\\d.])"
    );
    private RuleJobParser() {
    }

    /**
     * 将有限中英文表达映射到白名单任务，无法确定时返回空结果。
     */
    public static Optional<JobSpec> parse(String message) {
        String normalized = message.strip().toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "取消", "停下", "停止任务", "cancel", "stop task")) {
            return Optional.of(JobSpec.basic(JobType.CANCEL));
        }
        if (containsAny(normalized, "待在这里", "原地待命", "别动", "stay", "wait here")) {
            return Optional.of(JobSpec.basic(JobType.STAY));
        }
        if (containsAny(normalized, "跟着我", "跟随我", "跟我来", "follow", "come with me")) {
            return Optional.of(JobSpec.basic(JobType.FOLLOW));
        }
        if (isCollectAndDepositInstruction(normalized)) {
            String target = parseLogTarget(normalized);
            int quantity = parseQuantity(normalized);
            if (target != null && quantity > 0) {
                return Optional.of(new JobSpec(
                        JobType.COLLECT_AND_DEPOSIT,
                        target,
                        quantity,
                        parseRadius(normalized, AllowedTargets.DEFAULT_COLLECT_RADIUS)
                ));
            }
        }
        if (containsAny(normalized, "放进箱子", "存进箱子", "存入箱子", "deposit", "put in the chest")) {
            String target = parseLogTarget(normalized);
            int quantity = parseQuantity(normalized);
            if (target != null && quantity > 0) {
                return Optional.of(new JobSpec(
                        JobType.DEPOSIT_ITEM,
                        target,
                        quantity,
                        parseRadius(
                                normalized,
                                io.github.ozozorz.aipartner.job.ContainerTargets.DEFAULT_DEPOSIT_RADIUS
                        )
                ));
            }
        }
        if (containsAny(normalized, "收集", "砍", "collect", "chop", "get me")) {
            String target = parseLogTarget(normalized);
            int quantity = parseQuantity(normalized);
            if (target != null && quantity > 0) {
                return Optional.of(new JobSpec(
                        JobType.COLLECT_BLOCK,
                        target,
                        quantity,
                        parseRadius(normalized, AllowedTargets.DEFAULT_COLLECT_RADIUS)
                ));
            }
        }
        return Optional.empty();
    }

    private static boolean isCollectAndDepositInstruction(String message) {
        boolean requestsCollection = containsAny(message, "收集", "砍", "collect", "chop", "get me");
        boolean requestsDeposit = containsAny(
                message,
                "放进箱子",
                "存进箱子",
                "存入箱子",
                "deposit",
                "put in the chest"
        );
        return requestsCollection && requestsDeposit;
    }

    private static String parseLogTarget(String message) {
        if (containsAny(message, "橡木", "oak")) {
            return "minecraft:oak_log";
        }
        if (containsAny(message, "白桦", "birch")) {
            return "minecraft:birch_log";
        }
        if (containsAny(message, "云杉", "spruce")) {
            return "minecraft:spruce_log";
        }
        return null;
    }

    private static int parseQuantity(String message) {
        Matcher explicitQuantity = ARABIC_QUANTITY.matcher(message);
        if (explicitQuantity.find()) {
            return Integer.parseInt(explicitQuantity.group(1));
        }
        Matcher matcher = ARABIC_NUMBER.matcher(message);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        String[] chineseNumbers = {
                "零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
                "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十"
        };
        for (int value = chineseNumbers.length - 1; value >= 1; value--) {
            if (message.contains(chineseNumbers[value])) {
                return value;
            }
        }
        return 0;
    }

    private static int parseRadius(String message, int defaultRadius) {
        Matcher withBlockUnit = RADIUS_WITH_BLOCK_UNIT.matcher(message);
        if (withBlockUnit.find()) {
            return Integer.parseInt(withBlockUnit.group(1));
        }
        Matcher afterLabel = RADIUS_AFTER_LABEL.matcher(message);
        return afterLabel.find() ? Integer.parseInt(afterLabel.group(1)) : defaultRadius;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
