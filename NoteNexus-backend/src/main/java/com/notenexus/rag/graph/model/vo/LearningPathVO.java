package com.notenexus.rag.graph.model.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习路径视图。
 *
 * @param groupId       群组 ID
 * @param nodeCount     知识点总数
 * @param edgeCount     关系总数
 * @param masteredCount 已掌握知识点数量
 * @param weakCount     薄弱知识点数量
 * @param cycleDetected 图中是否存在前置关系环（存在时路径做了断环处理）
 * @param stages        分阶段学习路径
 * @param weakPoints    优先补强清单（薄弱 / 未检测的高重要度知识点）
 * @param generatedAt   生成时间
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record LearningPathVO(
        Long groupId,
        Integer nodeCount,
        Integer edgeCount,
        Integer masteredCount,
        Integer weakCount,
        Boolean cycleDetected,
        List<LearningPathStageVO> stages,
        List<LearningPathNodeVO> weakPoints,
        LocalDateTime generatedAt
) {
}
