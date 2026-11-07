package com.notenexus.rag.graph.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识图谱节点实体，映射 {@code knowledge_graph_nodes} 表。
 *
 * <p>一个节点对应一个可独立学习的概念。{@code nodeKey} 是概念名的归一化键
 * （小写、去空白与标点），用于把多份资料中提到的同一概念合并为一个节点。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@TableName("knowledge_graph_nodes")
@Data
public class KnowledgeGraphNodeEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属群组 ID */
    private Long groupId;
    /** 概念归一化键，组内唯一 */
    private String nodeKey;
    /** 知识点名称 */
    private String name;
    /** 知识点释义 */
    private String summary;
    /** 重要度 1-10 */
    private Integer importance;
    /** 支撑该知识点的资料份数 */
    private Integer documentCount;
    /** 来源资料名，逗号分隔 */
    private String sourceDocuments;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
