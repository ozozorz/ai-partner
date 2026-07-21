package io.github.ozozorz.aipartner.experiment;

import io.github.ozozorz.aipartner.core.event.ContractLifecycleEvent;
import io.github.ozozorz.aipartner.core.event.MaidDomainEvent;
import io.github.ozozorz.aipartner.core.event.MaidDomainEvents;
import io.github.ozozorz.aipartner.core.event.OrderValidationEvent;
import io.github.ozozorz.aipartner.logging.ExperimentLogger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把核心领域事件适配到冻结的 v0.4 实验日志，不让核心反向依赖研究包。
 */
public final class ExperimentEventBridge {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private ExperimentEventBridge() {
    }

    /**
     * 在模组初始化时注册一次实验观察者。
     */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            MaidDomainEvents.register(ExperimentEventBridge::handle);
        }
    }

    private static void handle(MaidDomainEvent event) {
        if (event instanceof ContractLifecycleEvent lifecycle) {
            ExperimentLogger.getInstance().logContractEvent(
                    lifecycle.event(),
                    lifecycle.sourceId(),
                    lifecycle.partner(),
                    lifecycle.actor(),
                    lifecycle.contract(),
                    lifecycle.detail()
            );
        } else if (event instanceof OrderValidationEvent validation) {
            ExperimentLogger.getInstance().logValidationDecision(
                    validation.sourceId(),
                    validation.partner(),
                    validation.actor(),
                    validation.rawInstruction(),
                    validation.candidate(),
                    validation.decision(),
                    validation.outcome()
            );
        }
    }
}
