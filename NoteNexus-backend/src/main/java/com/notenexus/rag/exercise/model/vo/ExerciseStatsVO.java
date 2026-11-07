package com.notenexus.rag.exercise.model.vo;

/**
 * 习题库统计概览。
 *
 * @param totalQuestions 习题总数
 * @param answeredCount  当前用户已作答的不同题目数
 * @param correctCount   当前用户最近一次作答正确的题目数
 * @param accuracy       正确率（0~1），未作答时为 0
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record ExerciseStatsVO(
        long totalQuestions,
        long answeredCount,
        long correctCount,
        double accuracy
) {
}
