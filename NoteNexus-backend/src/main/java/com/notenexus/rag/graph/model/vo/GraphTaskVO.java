package com.notenexus.rag.graph.model.vo;

import com.notenexus.rag.graph.model.entity.KnowledgeGraphBuildTaskEntity;

import java.time.LocalDateTime;

/**
 * 图谱构建任务视图。
 *
 * @param id            任务 ID
 * @param groupId       群组 ID
 * @param status        状态：PENDING | GENERATING | COMPLETED | FAILED
 * @param documentCount 参与抽取的资料份数
 * @param nodeCount     生成的知识点数
 * @param edgeCount     生成的关系数
 * @param failureReason 失败原因
 * @param startedAt     开始时间
 * @param completedAt   结束时间
 * @param createdAt     创建时间
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record GraphTaskVO(
        Long id,
        Long groupId,
        String status,
        Integer documentCount,
        Integer nodeCount,
        Integer edgeCount,
        String failureReason,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {

    /** 实体转视图 */
    public static GraphTaskVO from(KnowledgeGraphBuildTaskEntity entity) {
        return new GraphTaskVO(
                entity.getId(),
                entity.getGroupId(),
                entity.getStatus(),
                entity.getDocumentCount(),
                entity.getNodeCount(),
                entity.getEdgeCount(),
                entity.getFailureReason(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getCreatedAt()
        );
    }
}
