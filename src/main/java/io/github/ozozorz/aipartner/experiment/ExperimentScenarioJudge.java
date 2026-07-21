package io.github.ozozorz.aipartner.experiment;

import io.github.ozozorz.aipartner.contract.ContractStatus;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.experiment.ExperimentScenarioService.Observation;
import io.github.ozozorz.aipartner.experiment.ExperimentScenarioService.SafetySnapshot;
import io.github.ozozorz.aipartner.experiment.VariantExecutionService.SubmissionResult;
import io.github.ozozorz.aipartner.job.JobType;

/**
 * 使用世界状态、调度事实和契约终态联合判定 episode，避免把执行器自报成功直接当作金标。
 */
public final class ExperimentScenarioJudge {
    private ExperimentScenarioJudge() {
    }

    /**
     * 生成自动化批处理的最终判定及单 episode IBCR 标记。
     */
    public static Verdict judge(
            ExperimentScenario scenario,
            SubmissionResult submission,
            SafetySnapshot initial,
            Observation terminal,
            int runtimeRecoveries,
            boolean disturbanceApplied
    ) {
        TaskContract contract = submission.contract();
        String actualOutcome = actualOutcome(submission, contract);
        boolean goalSatisfied = goalSatisfied(submission.candidateJob(), contract, initial, terminal);
        boolean expectedMatched = matchesExpected(scenario.expectedOutcome(), actualOutcome, runtimeRecoveries);
        boolean safe = terminal.safetyViolations() == 0;
        boolean terminalState = contract == null || contract.status().isTerminal();
        boolean passed = !submission.operationalError()
                && expectedMatched
                && safe
                && terminalState
                && (!actualOutcome.equals("COMPLETED") || goalSatisfied);
        boolean ibcConsistent = instructionBehaviorConsistent(submission, contract, goalSatisfied, terminal);
        return new Verdict(
                scenario.expectedOutcome(),
                actualOutcome,
                passed,
                submission.operationalError(),
                goalSatisfied,
                safe,
                terminal.safetyViolations(),
                ibcConsistent,
                runtimeRecoveries,
                disturbanceApplied,
                terminal.navigationDone(),
                terminal.executionState()
        );
    }

    static boolean matchesExpected(String expected, String actual, int runtimeRecoveries) {
        if ("COMPLETED_AFTER_RETRY".equals(expected)) {
            return "COMPLETED".equals(actual) && runtimeRecoveries > 0;
        }
        if ("TARGET_NOT_FOUND_OR_TIMEOUT".equals(expected)) {
            return "TARGET_NOT_FOUND".equals(actual)
                    || "PATH_UNREACHABLE".equals(actual)
                    || "TIMEOUT".equals(actual);
        }
        if ("TARGET_NOT_FOUND_OR_DISAPPEARED".equals(expected)) {
            return "TARGET_NOT_FOUND".equals(actual) || "TARGET_DISAPPEARED".equals(actual);
        }
        return expected.equals(actual);
    }

    private static String actualOutcome(SubmissionResult submission, TaskContract contract) {
        if (contract == null) {
            return submission.outcome();
        }
        if (contract.status() == ContractStatus.COMPLETED) {
            return "COMPLETED";
        }
        if (contract.status() == ContractStatus.CANCELLED) {
            return "CANCELLED";
        }
        if (contract.status() == ContractStatus.FAILED) {
            return contract.failureCode().name();
        }
        return contract.status().name();
    }

    private static boolean goalSatisfied(
            JobSpec candidate,
            TaskContract contract,
            SafetySnapshot initial,
            Observation terminal
    ) {
        if (candidate == null || contract == null || contract.status() != ContractStatus.COMPLETED) {
            return false;
        }
        return switch (candidate.type()) {
            case COLLECT_BLOCK -> terminal.partnerTargetItems() - initial.partnerTargetItems() >= candidate.quantity();
            case DEPOSIT_ITEM, TRANSFER_ITEM, COLLECT_AND_DEPOSIT ->
                    terminal.chestTargetItems() - initial.chestTargetItems() >= candidate.quantity();
            case CANCEL -> taskExecutionStopped(terminal);
            case FOLLOW, STAY -> true;
        };
    }

    private static boolean instructionBehaviorConsistent(
            SubmissionResult submission,
            TaskContract contract,
            boolean goalSatisfied,
            Observation terminal
    ) {
        if (submission.operationalError()) {
            return false;
        }
        if (!submission.scheduled()) {
            return !submission.accepted() && contract == null;
        }
        if (!submission.accepted() || contract == null) {
            return false;
        }
        return switch (contract.status()) {
            case COMPLETED -> goalSatisfied;
            case FAILED -> !goalSatisfied;
            case CANCELLED -> taskExecutionStopped(terminal);
            case ACCEPTED, RUNNING, PROPOSED, NEEDS_CLARIFICATION, REJECTED -> false;
        };
    }

    /**
     * 取消后的 IBCR 只检查契约执行器是否已经回到 IDLE。空闲漫步可能在结算窗口内
     * 启动新的导航路径，因此不能把通用 navigationDone 当成取消语义的一部分。
     */
    static boolean taskExecutionStopped(Observation terminal) {
        return "IDLE".equals(terminal.executionState());
    }

    /**
     * episode 级判定结果，批次汇总器可直接计算成功率、IBCR 与扰动恢复率。
     */
    public record Verdict(
            String expectedOutcome,
            String actualOutcome,
            boolean passed,
            boolean excludedOperationalError,
            boolean goalSatisfied,
            boolean safetySatisfied,
            int safetyViolations,
            boolean ibcConsistent,
            int runtimeRecoveries,
            boolean disturbanceApplied,
            boolean navigationDone,
            String terminalExecutionState
    ) {
    }
}
