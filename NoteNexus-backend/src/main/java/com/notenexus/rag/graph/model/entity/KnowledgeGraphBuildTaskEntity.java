package com.notenexus.rag.graph.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识图谱构建任务实体，映射 {@code knowledge_graph_build_tasks} 表。
 *
 * <p>状态机：{@code PENDING → GENERATING → COMPLETED | FAILED}，
 * 与复习文档生成保持一致，用条件更新抢占执行权，避免同一任务被重复执行。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@TableName("knowledge_graph_build_tasks")
@Data
public class KnowledgeGraphBuildTaskEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属群组 ID */
    private Long groupId;
    /** 发起用户 ID */
    private Long requestedBy;
    /** 任务状态 */
    private String status;
    /** 参与抽取的资料份数 */
    private Integer documentCount;
    /** 生成的知识点数量 */
    private Integer nodeCount;
    /** 生成的关系数量 */
    private Integer edgeCount;
    /** 失败原因 */
    private String failureReason;
    /** 开始时间 */
    private LocalDateTime startedAt;
    /** 结束时间 */
    private LocalDateTime completedAt;
    /** 创建时间 */
    private LocalDateTime createdAt;
}
