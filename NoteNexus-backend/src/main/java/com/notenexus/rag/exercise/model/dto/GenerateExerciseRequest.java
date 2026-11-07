package com.notenexus.rag.exercise.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 从群组资料中抽取习题的请求。
 *
 * @param groupId        目标群组 ID，必填
 * @param documentId     指定只从某份资料抽取，为空则覆盖群组内全部就绪资料
 * @param questionCount  期望生成的题目数量，范围 1~30，默认 10
 * @param questionType   期望题型，为空则由模型按内容自行判断
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record GenerateExerciseRequest(
        @NotNull(message = "群组 ID 不能为空")
        Long groupId,

        Long documentId,

        @Min(value = 1, message = "题目数量至少为 1")
        @Max(value = 30, message = "单次最多生成 30 道题")
        Integer questionCount,

        String questionType
) {
    /** 默认生成题目数量 */
    public static final int DEFAULT_COUNT = 10;
}
