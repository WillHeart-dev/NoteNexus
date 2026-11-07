package com.notenexus.rag.review.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 创建复习文档请求。
 *
 * @param groupId 目标学习小组 ID，生成该小组下全部就绪资料的复习文档
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record CreateReviewDocumentRequest(
        @NotNull(message = "group_id 不能为空")
        Long groupId
) {
}
