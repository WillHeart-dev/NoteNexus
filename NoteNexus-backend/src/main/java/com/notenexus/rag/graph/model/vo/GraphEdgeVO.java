package com.notenexus.rag.graph.model.vo;

/**
 * 知识图谱关系视图对象。
 *
 * @param id         关系 ID
 * @param sourceId   起点节点 ID
 * @param targetId   终点节点 ID
 * @param sourceName 起点知识点名称
 * @param targetName 终点知识点名称
 * @param relation   关系类型
 * @param weight     关系权重
 * @param evidence   原文依据
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record GraphEdgeVO(
        Long id,
        Long sourceId,
        Long targetId,
        String sourceName,
        String targetName,
        String relation,
        Double weight,
        String evidence
) {
}
