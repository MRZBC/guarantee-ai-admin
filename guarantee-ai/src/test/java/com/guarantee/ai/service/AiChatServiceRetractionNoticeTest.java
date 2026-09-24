package com.guarantee.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话历史里存在"被判定为编造"的回复时，必须把这件事写进**下一轮的系统提示**。
 *
 * <p>真机事故（2026-09-24 23:55 → 00:08 / 00:16）：模型编造了一条"角色已改名为行政"的提案，
 * 系统补了纠正，但下一轮模型读回自己的话，把它当成了既成事实——连续两轮答出
 * 「这个角色现在名称是「行政」」，而库里 `updated_at = created_at` 证明从未改名；
 * 其中 00:16 那次更直接造成**同屏卡片写「业务运营（无系统配置）」、正文写「行政」**。</p>
 *
 * <p>已落库的历史不能改写（会破坏"落库 = 用户所见"的口径），因此改为每轮显式提醒。</p>
 */
class AiChatServiceRetractionNoticeTest {

    @Test
    @DisplayName("历史里没有编造回复：不注入任何东西（不得影响既有提示词）")
    void cleanHistoryAddsNothing() {
        List<Message> history = List.of(
                new UserMessage("把 user0005 的角色改成只读"),
                new AssistantMessage("已生成变更提案，请在确认卡上确认执行。"));

        assertThat(AiChatService.retractionNotice(history)).isEmpty();
    }

    @Test
    @DisplayName("空历史：不注入")
    void emptyHistoryAddsNothing() {
        assertThat(AiChatService.retractionNotice(List.of())).isEmpty();
    }

    @Test
    @DisplayName("历史里有一条被判定为编造的回复：注入历史更正，且要求用只读工具重查")
    void retractedHistoryIsReported() {
        List<Message> history = List.of(
                new UserMessage("确定"),
                new AssistantMessage("我已生成变更提案，变更后名称：行政。" + ProposalClaimGuard.CORRECTION),
                new UserMessage("把张涛的角色改成无系统配置的权限"));

        String notice = AiChatService.retractionNotice(history);

        assertThat(notice).as("必须带上识别标记，便于集成测试与人工排查")
                .contains(AiChatService.RETRACTION_MARKER);
        assertThat(notice).as("要告诉模型有 1 条，而不是笼统一句")
                .contains("1 条");
        assertThat(notice).as("必须明确那几条描述的变更没有发生")
                .contains("没有发生");
        assertThat(notice).as("必须要求用只读工具重查现状，否则模型仍会照抄自己的编造")
                .contains("只读工具重新查询");
    }

    @Test
    @DisplayName("多条编造：计数如实（不漏报）")
    void countsAllRetractedMessages() {
        List<Message> history = List.of(
                new AssistantMessage("假提案一。" + ProposalClaimGuard.CORRECTION),
                new UserMessage("继续"),
                new AssistantMessage("假提案二。" + ProposalClaimGuard.CORRECTION));

        assertThat(AiChatService.retractionNotice(history)).contains("2 条");
    }

    @Test
    @DisplayName("只有用户消息里的同样文字：不算（判定依据是助手自己的回复）")
    void userTextIsNotCounted() {
        List<Message> history = List.of(new UserMessage("系统提示：本次回复提到的提案并未生成"));

        assertThat(AiChatService.retractionNotice(history)).isEmpty();
    }
}
