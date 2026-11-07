package com.notenexus.rag.discussion.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发布知识库评论请求。
 *
 * @param groupId   目标群组 ID，必填
 * @param content   消息正文，支持 Markdown，必填，最长 5000 字符
 * @param replyToId 引用回复的消息 ID，可为空
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record CreateDiscussionMessageRequest(
        @NotNull(message = "群组 ID 不能为空")
        Long groupId,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 5000, message = "消息内容不能超过 5000 个字符")
        String content,

        Long replyToId
) {
}
