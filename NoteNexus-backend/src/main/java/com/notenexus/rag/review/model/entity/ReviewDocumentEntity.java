package com.notenexus.rag.review.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 复习文档实体，对应 review_documents 表。
 *
 * <p>记录由 AI 汇总小组学习资料后生成的复习 Markdown 文档元数据，
 * 包括生成状态、参与汇总的资料快照、对象存储位置与 Markdown 源码。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Data
@TableName("review_documents")
public class ReviewDocumentEntity {

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属学习小组 ID */
    private Long groupId;

    /** 复习文档标题 */
    private String title;

    /**
     * 生成状态。
     * <ul>
     *   <li>PENDING -- 已创建，等待异步生成</li>
     *   <li>GENERATING -- 正在汇总资料并生成 Markdown 文档</li>
     *   <li>COMPLETED -- 生成成功，可下载</li>
     *   <li>FAILED -- 生成失败</li>
     * </ul>
     */
    private String status;

    /** 参与汇总的资料篇数 */
    private Integer sourceDocumentCount;

    /** 参与汇总的资料文件名快照（JSON 数组字符串） */
    private String sourceDocumentNames;

    /** 参与汇总的切片总数 */
    private Integer chunkCount;

    /** 生成的 Markdown 文件名 */
    private String fileName;

    /** Markdown 文件大小（字节） */
    private Long fileSize;

    /** 对象存储 Bucket 名称 */
    private String storageBucket;

    /** 对象存储 Object Key */
    private String storageObjectKey;

    /** 生成的 Markdown 源码，便于追溯与二次编辑 */
    private String markdownSource;

    /** 失败原因，仅 status=FAILED 时有值 */
    private String failureReason;

    /** 触发生成的用户 ID */
    private Long createdBy;

    /** 开始生成时间 */
    private LocalDateTime startedAt;

    /** 生成完成时间 */
    private LocalDateTime completedAt;

    /** 软删除标记 */
    private Boolean deleted;

    /** 记录创建时间 */
    private LocalDateTime createdAt;

    /** 记录最后更新时间 */
    private LocalDateTime updatedAt;
}
