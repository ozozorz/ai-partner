package io.github.ozozorz.aipartner.core.event;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Isolated in-process event dispatcher for workflow outcome handling. */
public final class MaidDomainEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaidDomainEvents.class);
    private static final CopyOnWriteArrayList<Consumer<MaidDomainEvent>> LISTENERS = new CopyOnWriteArrayList<>();

    private MaidDomainEvents() {
    }

    /** Registers an observer whose failures cannot interrupt the maid runtime. */
    public static void register(Consumer<MaidDomainEvent> listener) {
        Consumer<MaidDomainEvent> checked = Objects.requireNonNull(listener, "listener");
        if (!LISTENERS.contains(checked)) {
            LISTENERS.add(checked);
        }
    }

    /** Publishes an immutable workflow event to all observers. */
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
