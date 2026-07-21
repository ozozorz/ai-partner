package io.github.ozozorz.aipartner.core.event;

/**
 * 核心女仆运行时对外围系统公开的只读事件。
 */
public sealed interface MaidDomainEvent permits ContractLifecycleEvent, OrderValidationEvent {
}
