package com.notenexus.rag.graph.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notenexus.rag.graph.model.entity.KnowledgeGraphEdgeEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识图谱关系 Mapper。
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Mapper
public interface KnowledgeGraphEdgeMapper extends BaseMapper<KnowledgeGraphEdgeEntity> {
}
