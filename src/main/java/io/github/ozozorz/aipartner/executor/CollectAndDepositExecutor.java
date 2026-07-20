package io.github.ozozorz.aipartner.executor;

import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.server.level.ServerLevel;

/**
 * `COLLECT_AND_DEPOSIT` 的固定两阶段编排器；模型不能改变阶段顺序或插入任意动作。
 */
public final class CollectAndDepositExecutor {
    private final AiPartnerEntity partner;
    private final CollectBlockExecutor collectExecutor;
    private final DepositItemExecutor depositExecutor;
    private final TaskExecutionListener collectListener = new PhaseListener(Phase.COLLECTING);
    private final TaskExecutionListener depositListener = new PhaseListener(Phase.DEPOSITING);
    private Phase phase = Phase.IDLE;
    private TaskContract contract;
    private long deadlineGameTime;

    public CollectAndDepositExecutor(
            AiPartnerEntity partner,
            CollectBlockExecutor collectExecutor,
            DepositItemExecutor depositExecutor
    ) {
        this.partner = partner;
        this.collectExecutor = collectExecutor;
        this.depositExecutor = depositExecutor;
    }

    /**
     * 从采集阶段启动新的组合契约，并共享父契约的总超时预算。
     */
    public void start(TaskContract taskContract) {
        stop();
        contract = taskContract;
        deadlineGameTime = partner.level().getGameTime() + taskContract.failurePolicy().timeoutSeconds() * 20L;
        transitionTo(Phase.COLLECTING);
        collectExecutor.start(taskContract, collectListener);
        partner.updateCollectProgressBaseline(collectExecutor.initialTargetCount());
    }

    /**
     * 从实体存档恢复到原阶段，避免服务器重启后重复执行已经完成的采集阶段。
     */
    public void restore(
            TaskContract taskContract,
            Phase savedPhase,
            int savedCollectInitialTargetCount,
            int savedDepositMovedCount
    ) {
        stop();
        contract = taskContract;
        deadlineGameTime = partner.level().getGameTime() + taskContract.failurePolicy().timeoutSeconds() * 20L;
        if (savedPhase == Phase.DEPOSITING) {
            transitionTo(Phase.DEPOSITING);
            depositExecutor.restore(taskContract, savedDepositMovedCount, depositListener);
        } else {
            transitionTo(Phase.COLLECTING);
            collectExecutor.restore(taskContract, savedCollectInitialTargetCount, collectListener);
        }
    }

    /**
     * 在父契约总超时约束下推进当前唯一活动阶段。
     */
    public void tick(ServerLevel level) {
        if (!isRunning()) {
            return;
        }
        if (level.getGameTime() >= deadlineGameTime) {
            fail(FailureCode.TIMEOUT);
            return;
        }
        if (phase == Phase.COLLECTING) {
            collectExecutor.tick(level);
        } else if (phase == Phase.DEPOSITING) {
            depositExecutor.tick(level);
        }
    }

    /**
     * 主人查看背包时同步暂停父任务及活动子阶段的超时计时。
     */
    public void pauseForMenuTick() {
        if (!isRunning()) {
            return;
        }
        deadlineGameTime++;
        if (phase == Phase.COLLECTING) {
            collectExecutor.pauseForMenuTick();
        } else if (phase == Phase.DEPOSITING) {
            depositExecutor.pauseForMenuTick();
        }
    }

    /**
     * 清理两阶段临时状态，但不直接改变父契约终态。
     */
    public void stop() {
        collectExecutor.stop();
        depositExecutor.stop();
        phase = Phase.IDLE;
        contract = null;
        deadlineGameTime = 0L;
    }

    public boolean isRunning() {
        return contract != null && (phase == Phase.COLLECTING || phase == Phase.DEPOSITING);
    }

    public Phase phase() {
        return phase;
    }

    private void completeCollectPhase() {
        collectExecutor.stop();
        transitionTo(Phase.DEPOSITING);
        partner.updateDepositProgress(0);
        depositExecutor.start(contract, depositListener);
    }

    private void completeDepositPhase() {
        transitionTo(Phase.COMPLETE);
        partner.completeActiveContract();
    }

    private void fail(FailureCode failureCode) {
        transitionTo(Phase.FAILED);
        partner.failActiveContract(failureCode);
    }

    private void transitionTo(Phase nextPhase) {
        phase = nextPhase;
        partner.onCompositePhaseChanged(nextPhase);
        partner.logRuntimeEvent("composite_phase_" + nextPhase.name().toLowerCase());
    }

    /**
     * 组合任务可持久化的有限阶段集合。
     */
    public enum Phase {
        IDLE,
        COLLECTING,
        DEPOSITING,
        COMPLETE,
        FAILED;

        /**
         * 安全解析旧存档或未知值，未知阶段从采集阶段恢复。
         */
        public static Phase fromName(String value) {
            try {
                return Phase.valueOf(value);
            } catch (IllegalArgumentException | NullPointerException exception) {
                return COLLECTING;
            }
        }
    }

    /**
     * 将子执行器终态映射为固定的下一阶段或父契约失败。
     */
    private final class PhaseListener implements TaskExecutionListener {
        private final Phase expectedPhase;

        private PhaseListener(Phase expectedPhase) {
            this.expectedPhase = expectedPhase;
        }

        @Override
        public void onCompleted() {
            if (phase != expectedPhase || contract == null) {
                return;
            }
            if (expectedPhase == Phase.COLLECTING) {
                completeCollectPhase();
            } else {
                completeDepositPhase();
            }
        }

        @Override
        public void onFailed(FailureCode failureCode) {
            if (phase == expectedPhase && contract != null) {
                fail(failureCode);
            }
        }
    }
}
