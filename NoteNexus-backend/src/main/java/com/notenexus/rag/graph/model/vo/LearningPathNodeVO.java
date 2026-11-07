package com.notenexus.rag.graph.model.vo;

/**
 * 学习路径中的单个知识点。
 *
 * @param id                节点 ID
 * @param name              知识点名称
 * @param summary           释义
 * @param importance        重要度
 * @param mastery           掌握度档位
 * @param accuracy          正确率，未作答为 null
 * @param questionCount     关联习题数
 * @param prerequisiteCount 前置知识点数量
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record LearningPathNodeVO(
        Long id,
        String name,
        String summary,
        Integer importance,
        String mastery,
        Double accuracy,
        Integer questionCount,
        Integer prerequisiteCount
) {
}
