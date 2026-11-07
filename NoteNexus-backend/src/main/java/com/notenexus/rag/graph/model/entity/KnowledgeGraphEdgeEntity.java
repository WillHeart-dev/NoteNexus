package com.notenexus.rag.graph.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识图谱关系实体，映射 {@code knowledge_graph_edges} 表。
 *
 * <p>关系方向语义：{@code PREREQUISITE_OF} 表示 source 是 target 的先修知识；
 * {@code PART_OF} 表示 source 是 target 的组成部分；{@code RELATED_TO} 表示同域相关。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@TableName("knowledge_graph_edges")
@Data
public class KnowledgeGraphEdgeEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属群组 ID */
    private Long groupId;
    /** 起点知识点 ID */
    private Long sourceNodeId;
    /** 终点知识点 ID */
    private Long targetNodeId;
    /** 关系类型 */
    private String relationType;
    /** 关系权重，多份资料共同印证时累加 */
    private Double weight;
    /** 关系依据的原文摘录 */
    private String evidence;
    /** 创建时间 */
    private LocalDateTime createdAt;
}
