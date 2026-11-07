package com.notenexus.rag.graph.model.vo;

import java.util.List;

/**
 * 学习路径的一个阶段。
 *
 * @param level       阶段序号，从 1 开始
 * @param title       阶段标题
 * @param description 阶段说明
 * @param count       本阶段知识点数量
 * @param nodes       本阶段知识点（按推荐学习顺序排列）
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record LearningPathStageVO(
        int level,
        String title,
        String description,
        int count,
        List<LearningPathNodeVO> nodes
) {
}
