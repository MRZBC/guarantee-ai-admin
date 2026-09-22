package com.guarantee.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 会话。 */
@Data
public class AiConversation {

    private Long id;
    private String conversationNo;
    private Long userId;
    private String title;
    private String model;
    /** ACTIVE / ARCHIVED */
    private String status;
    private Integer messageCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;

}
