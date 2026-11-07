package com.notenexus.rag.review.model.vo;

import com.notenexus.rag.review.model.entity.ReviewDocumentEntity;

import java.time.LocalDateTime;

/**
 * 复习文档列表项视图对象。
 *
 * @param reviewId            复习文档 ID
 * @param groupId             所属学习小组 ID
 * @param title               复习文档标题
 * @param status              生成状态：PENDING / GENERATING / COMPLETED / FAILED
 * @param sourceDocumentCount 参与汇总的资料篇数
 * @param chunkCount          参与汇总的切片总数
 * @param fileName            Markdown 文件名（生成完成后有值）
 * @param fileSize            Markdown 文件大小（字节）
 * @param failureReason       失败原因
 * @param createdBy           触发人用户 ID
 * @param createdAt           创建时间
 * @param startedAt           开始生成时间
 * @param completedAt         完成时间
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record ReviewDocumentVO(
        Long reviewId,
        Long groupId,
        String title,
        String status,
        Integer sourceDocumentCount,
        Integer chunkCount,
        String fileName,
        Long fileSize,
        String failureReason,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {

    /**
     * 由实体转换为视图对象，不暴露 LaTeX 源码等大字段。
     *
     * @param entity 复习文档实体
     * @return 视图对象
     */
    public static ReviewDocumentVO from(ReviewDocumentEntity entity) {
        return new ReviewDocumentVO(
                entity.getId(),
                entity.getGroupId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getSourceDocumentCount(),
                entity.getChunkCount(),
                entity.getFileName(),
                entity.getFileSize(),
                entity.getFailureReason(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt()
        );
    }
}
