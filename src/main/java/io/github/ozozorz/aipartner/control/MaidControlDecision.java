package io.github.ozozorz.aipartner.control;

import net.minecraft.network.chat.Component;

/** 服务端应用类型化意图后的结果；消息只来源于服务器翻译键和权威状态。 */
public record MaidControlDecision(boolean accepted, Component message) {
    public static MaidControlDecision accepted(Component message) {
        return new MaidControlDecision(true, message);
    }

    public static MaidControlDecision rejected(Component message) {
        return new MaidControlDecision(false, message);
    }
}
