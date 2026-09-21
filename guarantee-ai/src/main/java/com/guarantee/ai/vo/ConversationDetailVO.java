package com.guarantee.ai.vo;

import java.util.List;

/** 会话详情：会话元信息 + 全部消息。 */
public record ConversationDetailVO(ConversationVO conversation, List<MessageVO> messages) {
}
