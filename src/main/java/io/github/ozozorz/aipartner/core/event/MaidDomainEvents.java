package io.github.ozozorz.aipartner.core.event;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 轻量领域事件分发器，使实验和日志系统只能观察核心行为。
 */
public final class MaidDomainEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaidDomainEvents.class);
    private static final CopyOnWriteArrayList<Consumer<MaidDomainEvent>> LISTENERS = new CopyOnWriteArrayList<>();

    private MaidDomainEvents() {
    }

    /**
     * 注册只读观察者。监听器异常会被隔离，不能中断女仆任务。
     */
    public static void register(Consumer<MaidDomainEvent> listener) {
        Consumer<MaidDomainEvent> checked = Objects.requireNonNull(listener, "listener");
        if (!LISTENERS.contains(checked)) {
            LISTENERS.add(checked);
        }
    }

    /**
     * 向全部外围观察者发布事件。
     */
    public static void publish(MaidDomainEvent event) {
        for (Consumer<MaidDomainEvent> listener : LISTENERS) {
            try {
                listener.accept(event);
            } catch (RuntimeException exception) {
                LOGGER.error("AI Partner domain event listener failed for {}", event.getClass().getSimpleName(), exception);
            }
        }
    }
}
