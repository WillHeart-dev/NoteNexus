package com.notenexus.rag.exercise.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交一次作答的请求。
 *
 * @param questionId 习题 ID，必填
 * @param userAnswer 用户作答内容，必填
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record SubmitAnswerRequest(
        @NotNull(message = "习题 ID 不能为空")
        Long questionId,

        @NotBlank(message = "答案不能为空")
        String userAnswer
) {
}
