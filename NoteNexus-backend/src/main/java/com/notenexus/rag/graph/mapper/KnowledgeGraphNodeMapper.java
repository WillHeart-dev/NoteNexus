package com.notenexus.rag.graph.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notenexus.rag.graph.model.entity.KnowledgeGraphNodeEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识图谱节点 Mapper。
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Mapper
public interface KnowledgeGraphNodeMapper extends BaseMapper<KnowledgeGraphNodeEntity> {
}
