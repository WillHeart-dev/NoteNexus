package com.notenexus.rag.discussion.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 群组讨论消息实体，映射 {@code group_discussion_messages} 表。
 *
 * <p>群组成员在组内讨论区发送的消息，支持引用回复。
 * 讨论内容不参与 RAG 检索，仅作为协作信息流存在。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Data
@TableName("group_discussion_messages")
public class GroupDiscussionMessageEntity {

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属群组 ID */
    private Long groupId;

    /** 发送者用户 ID */
    private Long userId;

    /** 消息正文，支持 Markdown */
    private String content;

    /** 引用回复的消息 ID，为空表示普通消息 */
    private Long replyToId;

    /** 状态：ACTIVE | DELETED */
    private String status;

    /** 软删除标记 */
    private Boolean deleted;

    /** 发送时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
