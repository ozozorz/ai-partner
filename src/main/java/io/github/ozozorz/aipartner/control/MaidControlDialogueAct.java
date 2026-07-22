package io.github.ozozorz.aipartner.control;

/** 模型或离线解析器可返回的有限对话行为。 */
public enum MaidControlDialogueAct {
    PROPOSE_INTENT,
    ASK_CLARIFICATION,
    REJECT_UNSUPPORTED,
    SOCIAL_REPLY
}
