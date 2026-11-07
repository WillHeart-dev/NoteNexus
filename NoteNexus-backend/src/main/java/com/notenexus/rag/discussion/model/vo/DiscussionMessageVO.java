package com.notenexus.rag.discussion.model.vo;

import java.time.LocalDateTime;

/**
 * 群组讨论消息视图对象。
 *
 * @param id              消息 ID
 * @param groupId         所属群组 ID
 * @param userId          发送者用户 ID
 * @param displayName     发送者显示名
 * @param content         消息正文
 * @param replyToId       引用回复的消息 ID
 * @param replyToContent  被引用消息的摘要（截取前 80 字符），无引用时为 null
 * @param replyToName     被引用消息的发送者显示名，无引用时为 null
 * @param mine            是否为当前登录用户发送
 * @param createdAt       发送时间
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record DiscussionMessageVO(
        Long id,
        Long groupId,
        Long userId,
        String displayName,
        String content,
        Long replyToId,
        String replyToContent,
        String replyToName,
        boolean mine,
        LocalDateTime createdAt
) {
}
