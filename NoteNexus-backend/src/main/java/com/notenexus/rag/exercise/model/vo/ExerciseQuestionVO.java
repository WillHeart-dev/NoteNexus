package com.notenexus.rag.exercise.model.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 习题视图对象。
 *
 * @param id             习题 ID
 * @param groupId        所属群组 ID
 * @param questionType   题型
 * @param stem           题干
 * @param options        选项列表，简答题为空列表
 * @param answer         标准答案
 * @param analysis       解析
 * @param difficulty     难度
 * @param tags           标签列表
 * @param sourceSnippet  资料原文依据片段
 * @param sourceDocumentName 来源资料文件名，人工录入时为 null
 * @param answered       当前用户是否已答过此题
 * @param lastCorrect    当前用户最近一次作答是否正确，未作答为 null
 * @param createdAt      创建时间
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record ExerciseQuestionVO(
        Long id,
        Long groupId,
        String questionType,
        String stem,
        List<String> options,
        String answer,
        String analysis,
        String difficulty,
        List<String> tags,
        String sourceSnippet,
        String sourceDocumentName,
        boolean answered,
        Boolean lastCorrect,
        LocalDateTime createdAt
) {
}
