package com.notenexus.rag.exercise.model.vo;

/**
 * 提交答案后的判定结果。
 *
 * @param questionId 习题 ID
 * @param correct    本次作答是否正确
 * @param answer     标准答案
 * @param analysis   解析
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record SubmitAnswerVO(
        Long questionId,
        boolean correct,
        String answer,
        String analysis
) {
}
