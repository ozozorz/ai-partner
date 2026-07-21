package io.github.ozozorz.aipartner.core.task;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 把 JobType 和稳定任务 ID 映射到执行器工厂，消除实体内的具体任务分支。
 */
public final class MaidTaskRegistry {
    private final Map<JobType, Registration> byJobType = new EnumMap<>(JobType.class);
    private final Map<String, Registration> byTaskId = new LinkedHashMap<>();
    private boolean frozen;

    /**
     * 注册一种有限任务。长期指令和 CANCEL 不应注册为任务。
     */
    public MaidTaskRegistry register(JobType jobType, String taskId, MaidTaskFactory factory) {
        if (frozen) {
            throw new IllegalStateException("Task registry is frozen");
        }
        Objects.requireNonNull(jobType, "jobType");
        String checkedId = Objects.requireNonNull(taskId, "taskId").strip();
        if (checkedId.isEmpty()) {
            throw new IllegalArgumentException("Task ID must not be blank");
        }
        Registration registration = new Registration(jobType, checkedId, Objects.requireNonNull(factory, "factory"));
        if (byJobType.putIfAbsent(jobType, registration) != null) {
            throw new IllegalArgumentException("Duplicate task job type " + jobType);
        }
        if (byTaskId.putIfAbsent(checkedId, registration) != null) {
            byJobType.remove(jobType);
            throw new IllegalArgumentException("Duplicate task ID " + checkedId);
        }
        return this;
    }

    /**
     * 冻结注册表，防止游戏运行期间改变任务语义。
     */
    public MaidTaskRegistry freeze() {
        frozen = true;
        return this;
    }

    public Optional<MaidTask> create(JobType jobType, AiPartnerEntity partner) {
        Registration registration = byJobType.get(jobType);
        return registration == null ? Optional.empty() : Optional.of(registration.factory().create(partner));
    }

    public Optional<MaidTask> create(String taskId, AiPartnerEntity partner) {
        Registration registration = byTaskId.get(taskId);
        return registration == null ? Optional.empty() : Optional.of(registration.factory().create(partner));
    }

    public Optional<String> taskId(JobType jobType) {
        Registration registration = byJobType.get(jobType);
        return registration == null ? Optional.empty() : Optional.of(registration.taskId());
    }

    public Set<JobType> registeredJobTypes() {
        return Collections.unmodifiableSet(byJobType.keySet());
    }

    private record Registration(JobType jobType, String taskId, MaidTaskFactory factory) {
    }
}
