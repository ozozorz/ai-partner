package io.github.ozozorz.aipartner.control;

import io.github.ozozorz.aipartner.combat.CombatPolicy;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
import io.github.ozozorz.aipartner.life.ActivityLocationType;
import io.github.ozozorz.aipartner.parser.RuleJobParser;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Limited Chinese/English parser used by local dialogue. It emits typed intents
 * that share the same validation and execution path as commands and menus.
 */
public final class LocalMaidIntentParser {
    private static final Pattern RADIUS = Pattern.compile(
            "(?:\u534a\u5f84|\u8303\u56f4|radius)\\s*(?:\u662f|\u4e3a|to|=)?\\s*(\\d{1,2})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RENAME = Pattern.compile(
            "(?:\u53eb\u4f60|\u540d\u5b57\u6539\u6210|\u6539\u540d\u4e3a|rename(?:\\s+to)?)\\s*[\uff1a:]?\\s*(.{1,32})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Map<MaidWorkMode, String[]> WORK_KEYWORDS = createWorkKeywords();

    private LocalMaidIntentParser() {
    }

    /** Converts a natural-language message into an intent, clarification, or safe rejection. */
    public static MaidControlInterpretation parse(String message) {
        String original = message == null ? "" : message.strip();
        String normalized = original.toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return MaidControlInterpretation.clarify("\u8bf7\u544a\u8bc9\u6211\u4f60\u5e0c\u671b\u6211\u505a\u4ec0\u4e48\u3002");
        }

        JobSpec task = RuleJobParser.parse(normalized).orElse(null);
        if (task != null) {
            return MaidControlInterpretation.propose(new MaidControlIntent.RunTask(task));
        }
        if (containsAny(normalized, "\u56de\u5bb6", "\u56de\u53bb\u4f11\u606f", "return home", "go home")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.ReturnHome());
        }
        MaidControlInterpretation query = parseQuery(normalized);
        if (query != null) {
            return query;
        }
        MaidControlInterpretation schedule = parseSchedule(normalized);
        if (schedule != null) {
            return schedule;
        }
        MaidControlInterpretation combat = parseCombat(normalized);
        if (combat != null) {
            return combat;
        }
        MaidControlInterpretation location = parseLocation(normalized);
        if (location != null) {
            return location;
        }

        Matcher radius = RADIUS.matcher(normalized);
        if (radius.find()) {
            return MaidControlInterpretation.propose(new MaidControlIntent.SetRadius(Integer.parseInt(radius.group(1))));
        }
        if (containsAny(normalized,
                "\u4e0d\u8981\u79bb\u5f00\u6d3b\u52a8\u8303\u56f4",
                "\u9650\u5236\u5728\u5bb6\u9644\u8fd1",
                "\u5f00\u542f\u8303\u56f4\u9650\u5236",
                "home bound on")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.SetHomeBound(true));
        }
        if (containsAny(normalized,
                "\u53ef\u4ee5\u79bb\u5f00\u6d3b\u52a8\u8303\u56f4",
                "\u5173\u95ed\u8303\u56f4\u9650\u5236",
                "\u81ea\u7531\u6d3b\u52a8",
                "home bound off")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.SetHomeBound(false));
        }

        Matcher rename = RENAME.matcher(original);
        if (rename.find()) {
            return MaidControlInterpretation.propose(new MaidControlIntent.Rename(rename.group(1)));
        }

        MaidControlInterpretation work = parseWork(normalized);
        if (work != null) {
            return work;
        }
        if (containsAny(normalized,
                "\u4f60\u597d", "\u65e9\u4e0a\u597d", "\u665a\u4e0a\u597d", "\u8f9b\u82e6\u4e86", "\u8c22\u8c22",
                "hello", "thanks")) {
            return MaidControlInterpretation.social(
                    "\u6211\u5728\uff0c\u6709\u9700\u8981\u5c31\u544a\u8bc9\u6211\u5427\u3002"
            );
        }
        if (containsAny(normalized,
                "\u5efa\u9020", "\u76d6\u623f", "\u5408\u6210\u4efb\u610f", "\u8de8\u7ef4\u5ea6", "\u4f20\u9001\u5230",
                "build", "teleport")) {
            return MaidControlInterpretation.reject();
        }
        return MaidControlInterpretation.clarify(
                "\u6211\u8fd8\u4e0d\u80fd\u786e\u5b9a\u4f60\u7684\u610f\u601d\uff0c"
                        + "\u8bf7\u8bf4\u660e\u5177\u4f53\u5de5\u4f5c\u6216\u4efb\u52a1\u3002"
        );
    }

    private static MaidControlInterpretation parseQuery(String message) {
        if (containsAny(message,
                "\u628a\u80cc\u5305\u7ed9\u6211", "\u628a\u4e1c\u897f\u7ed9\u6211", "\u53d6\u56de\u80cc\u5305",
                "retrieve inventory")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.RetrieveInventory());
        }
        if (containsAny(message,
                "\u80cc\u5305\u91cc\u6709\u4ec0\u4e48", "\u6709\u4ec0\u4e48\u4e1c\u897f", "\u67e5\u770b\u80cc\u5305",
                "inventory")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.QueryInventory());
        }
        if (containsAny(message,
                "\u4f60\u5728\u505a\u4ec0\u4e48", "\u5f53\u524d\u72b6\u6001", "\u73b0\u5728\u600e\u4e48\u6837",
                "status", "what are you doing")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.QueryStatus());
        }
        return null;
    }

    private static MaidControlInterpretation parseSchedule(String message) {
        if (containsAny(message, "\u5168\u5929\u5de5\u4f5c", "\u4e00\u76f4\u5de5\u4f5c", "all day")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.SetSchedule(ScheduleType.ALL_DAY));
        }
        if (containsAny(message, "\u591c\u73ed", "\u665a\u4e0a\u5de5\u4f5c", "\u591c\u91cc\u5de5\u4f5c", "night shift")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.SetSchedule(ScheduleType.NIGHT_SHIFT));
        }
        if (containsAny(message, "\u65e5\u73ed", "\u767d\u5929\u5de5\u4f5c", "day shift")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.SetSchedule(ScheduleType.DAY_SHIFT));
        }
        return null;
    }

    private static MaidControlInterpretation parseCombat(String message) {
        if (containsAny(message, "\u4e0d\u8981\u6253\u67b6", "\u5173\u95ed\u6218\u6597", "\u505c\u6b62\u4fdd\u62a4", "combat off")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.SetCombatPolicy(CombatPolicy.OFF));
        }
        if (containsAny(message, "\u53ea\u4fdd\u62a4\u81ea\u5df1", "\u53ea\u81ea\u536b", "self defense")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.SetCombatPolicy(CombatPolicy.SELF_DEFENSE));
        }
        if (containsAny(message, "\u4fdd\u62a4\u6211", "\u4fdd\u62a4\u4e3b\u4eba", "\u5b88\u62a4\u6211", "defend me", "defend owner")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.SetCombatPolicy(CombatPolicy.DEFEND_OWNER));
        }
        return null;
    }

    private static MaidControlInterpretation parseLocation(String message) {
        boolean clear = containsAny(message, "\u6e05\u9664", "\u53d6\u6d88\u5730\u70b9", "clear");
        if (containsAny(message, "\u5de5\u4f5c\u5730\u70b9", "\u5de5\u4f5c\u533a", "work location")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.ConfigureLocation(ActivityLocationType.WORK, clear));
        }
        if (containsAny(message, "\u4f11\u95f2\u5730\u70b9", "\u4f11\u606f\u5730\u70b9", "leisure location")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.ConfigureLocation(ActivityLocationType.LEISURE, clear));
        }
        if (containsAny(message, "\u7761\u7720\u5730\u70b9", "\u7761\u89c9\u5730\u70b9", "sleep location")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.ConfigureLocation(ActivityLocationType.SLEEP, clear));
        }
        return null;
    }

    private static MaidControlInterpretation parseWork(String message) {
        if (containsAny(message, "\u505c\u6b62\u5de5\u4f5c", "\u5173\u95ed\u5de5\u4f5c", "\u4e0d\u518d\u5de5\u4f5c", "work off")) {
            return MaidControlInterpretation.propose(new MaidControlIntent.SetWorkMode(MaidWorkMode.NONE));
        }
        for (Map.Entry<MaidWorkMode, String[]> entry : WORK_KEYWORDS.entrySet()) {
            if (containsAny(message, entry.getValue())) {
                return MaidControlInterpretation.propose(new MaidControlIntent.SetWorkMode(entry.getKey()));
            }
        }
        return null;
    }

    private static Map<MaidWorkMode, String[]> createWorkKeywords() {
        LinkedHashMap<MaidWorkMode, String[]> keywords = new LinkedHashMap<>();
        keywords.put(MaidWorkMode.SUGAR_CANE, new String[]{"\u7518\u8517", "sugar cane"});
        keywords.put(MaidWorkMode.SNOW_CLEARER, new String[]{"\u9664\u96ea", "\u6e05\u96ea", "clear snow"});
        keywords.put(MaidWorkMode.TORCH_BEARER, new String[]{"\u63d2\u706b\u628a", "\u653e\u706b\u628a", "place torches"});
        keywords.put(MaidWorkMode.FIREFIGHTER, new String[]{"\u706d\u706b", "firefighting"});
        keywords.put(MaidWorkMode.LUMBERJACK, new String[]{"\u780d\u6811", "\u4f10\u6728", "lumberjack"});
        keywords.put(MaidWorkMode.MINER, new String[]{"\u6316\u77ff", "\u91c7\u77ff", "\u77ff\u77f3", "mining"});
        keywords.put(MaidWorkMode.SMELTER, new String[]{"\u7194\u70bc", "\u70e7\u70bc", "\u70e7\u77ff", "smelting"});
        keywords.put(MaidWorkMode.FISHER, new String[]{"\u9493\u9c7c", "fishing"});
        keywords.put(MaidWorkMode.FARMER, new String[]{"\u79cd\u5730", "\u666e\u901a\u4f5c\u7269", "\u5e84\u7a3c", "farming"});
        keywords.put(MaidWorkMode.MELON, new String[]{"\u897f\u74dc", "\u5357\u74dc", "\u74dc\u7c7b", "melons"});
        keywords.put(MaidWorkMode.COCOA, new String[]{"\u53ef\u53ef", "cocoa"});
        keywords.put(MaidWorkMode.FORAGER, new String[]{"\u82b1\u8349", "\u91c7\u82b1", "\u6536\u96c6\u690d\u7269", "foraging"});
        keywords.put(MaidWorkMode.BEEKEEPER, new String[]{"\u91c7\u871c", "\u8702\u871c", "honey"});
        keywords.put(MaidWorkMode.SHEARER, new String[]{"\u526a\u6bdb", "shearing"});
        keywords.put(MaidWorkMode.MILKER, new String[]{"\u6324\u5976", "milking"});
        keywords.put(MaidWorkMode.CAREGIVER, new String[]{"\u5582\u4e3b\u4eba", "\u7ed9\u6211\u5403", "feed owner"});
        keywords.put(MaidWorkMode.BREEDER, new String[]{"\u5582\u52a8\u7269", "\u7e41\u6b96\u52a8\u7269", "breed animals"});
        return Collections.unmodifiableMap(keywords);
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
