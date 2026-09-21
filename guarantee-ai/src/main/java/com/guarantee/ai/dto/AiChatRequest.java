package com.guarantee.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * AI 聊天请求。
 */
@Getter
@Setter
public class AiChatRequest {

    /** 为空表示新建会话。 */
    private Long conversationId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 2000, message = "单条消息长度不能超过 2000")
    private String message;
}
