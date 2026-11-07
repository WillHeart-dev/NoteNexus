package com.notenexus.rag.exercise.model.dto;

/**
 * 习题查询条件。
 *
 * @param groupId      群组 ID，必填
 * @param questionType 按题型筛选，可为空
 * @param difficulty   按难度筛选，可为空
 * @param onlyWrong    只看当前用户答错过的题
 * @param keyword      按题干关键词模糊搜索，可为空
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record ExerciseQuery(
        Long groupId,
        String questionType,
        String difficulty,
        Boolean onlyWrong,
        String keyword
) {
}
