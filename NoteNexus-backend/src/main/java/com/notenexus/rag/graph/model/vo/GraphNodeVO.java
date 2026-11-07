package com.notenexus.rag.graph.model.vo;

import java.util.List;

/**
 * 知识图谱节点视图对象。
 *
 * @param id            节点 ID
 * @param name          知识点名称
 * @param summary       释义
 * @param importance    重要度 1-10
 * @param documentCount 支撑资料份数
 * @param documents     来源资料名列表
 * @param mastery       掌握度：UNTOUCHED | WEAK | MEDIUM | STRONG
 * @param accuracy      最近一次作答正确率，未作答为 null
 * @param questionCount 关联习题数
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record GraphNodeVO(
        Long id,
        String name,
        String summary,
        Integer importance,
        Integer documentCount,
        List<String> documents,
        String mastery,
        Double accuracy,
        Integer questionCount
) {
}
