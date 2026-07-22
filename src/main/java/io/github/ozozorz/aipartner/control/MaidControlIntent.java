package io.github.ozozorz.aipartner.control;

import io.github.ozozorz.aipartner.combat.CombatPolicy;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
import io.github.ozozorz.aipartner.life.ActivityLocationType;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import java.util.Objects;

/**
 * 命令、UI、本地解析器和 LLM 共用的类型化高层意图；不允许携带任意命令或世界坐标。
 */
public sealed interface MaidControlIntent {
    record RunTask(JobSpec job) implements MaidControlIntent {
        public RunTask {
            Objects.requireNonNull(job, "job");
        }
    }

    record SetWorkMode(MaidWorkMode mode) implements MaidControlIntent {
        public SetWorkMode {
            Objects.requireNonNull(mode, "mode");
        }
    }

    record SetSchedule(ScheduleType schedule) implements MaidControlIntent {
        public SetSchedule {
            Objects.requireNonNull(schedule, "schedule");
        }
    }

    record SetCombatPolicy(CombatPolicy policy) implements MaidControlIntent {
        public SetCombatPolicy {
            Objects.requireNonNull(policy, "policy");
        }
    }

    record ReturnHome() implements MaidControlIntent {
    }

    record ConfigureLocation(ActivityLocationType location, boolean clear) implements MaidControlIntent {
        public ConfigureLocation {
            Objects.requireNonNull(location, "location");
        }
    }

    record SetHomeBound(boolean enabled) implements MaidControlIntent {
    }

    record SetRadius(int radius) implements MaidControlIntent {
    }

    record Rename(String name) implements MaidControlIntent {
        public Rename {
            name = Objects.requireNonNull(name, "name").strip();
        }
    }

    record QueryStatus() implements MaidControlIntent {
    }

    record QueryInventory() implements MaidControlIntent {
    }

    record RetrieveInventory() implements MaidControlIntent {
    }
}
