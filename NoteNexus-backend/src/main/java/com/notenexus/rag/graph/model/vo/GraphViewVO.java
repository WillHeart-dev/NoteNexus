package com.notenexus.rag.graph.model.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识图谱整体视图。
 *
 * @param groupId     群组 ID
 * @param nodeCount   知识点数量
 * @param edgeCount   关系数量
 * @param lastBuiltAt 最近一次构建完成时间，未构建过为 null
 * @param nodes       节点列表
 * @param edges       关系列表
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record GraphViewVO(
        Long groupId,
        Integer nodeCount,
        Integer edgeCount,
        LocalDateTime lastBuiltAt,
        List<GraphNodeVO> nodes,
        List<GraphEdgeVO> edges
) {
}
