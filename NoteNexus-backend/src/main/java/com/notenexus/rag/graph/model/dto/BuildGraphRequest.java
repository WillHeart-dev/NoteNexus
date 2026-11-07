package com.notenexus.rag.graph.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 图谱构建请求。
 *
 * @param groupId 学习小组 ID
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record BuildGraphRequest(
        @NotNull(message = "群组 ID 不能为空")
        Long groupId
) {
}
