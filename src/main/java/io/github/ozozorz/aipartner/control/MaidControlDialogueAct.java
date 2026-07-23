package io.github.ozozorz.aipartner.control;

/**
 * 本地自然语言解析器允许产生的有限对话行为。
 */
public enum MaidControlDialogueAct {
    PROPOSE_INTENT,
    ASK_CLARIFICATION,
    REJECT_UNSUPPORTED,
    SOCIAL_REPLY
}
