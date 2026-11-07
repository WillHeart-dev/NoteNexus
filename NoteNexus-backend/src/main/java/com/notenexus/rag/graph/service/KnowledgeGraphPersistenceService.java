package com.notenexus.rag.graph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.notenexus.rag.graph.mapper.KnowledgeGraphEdgeMapper;
import com.notenexus.rag.graph.mapper.KnowledgeGraphNodeMapper;
import com.notenexus.rag.graph.model.entity.KnowledgeGraphEdgeEntity;
import com.notenexus.rag.graph.model.entity.KnowledgeGraphNodeEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱持久化服务：在单个事务内整体替换某个群组的图谱。
 *
 * <p>拆成独立 Bean 是为了让 {@code @Transactional} 真正生效——构建服务里的
 * 抽取流程是长耗时的模型调用，不能放在事务里；而落库阶段需要原子性，
 * 否则中途失败会留下半张图。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class KnowledgeGraphPersistenceService {

    private final KnowledgeGraphNodeMapper nodeMapper;
    private final KnowledgeGraphEdgeMapper edgeMapper;

    public KnowledgeGraphPersistenceService(KnowledgeGraphNodeMapper nodeMapper,
                                            KnowledgeGraphEdgeMapper edgeMapper) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
    }

    /**
     * 用新的知识点与关系替换群组现有图谱。
     *
     * @param groupId 群组 ID
     * @param nodes   知识点列表
     * @param edges   关系列表（以归一化键引用两端）
     * @return {@code [节点数, 关系数]}
     */
    @Transactional
    public int[] replaceGraph(Long groupId, List<NodeData> nodes, List<EdgeData> edges) {
        // 先删边再删点：虽然外键是 ON DELETE CASCADE，显式删除语义更清晰
        edgeMapper.delete(new LambdaQueryWrapper<KnowledgeGraphEdgeEntity>()
                .eq(KnowledgeGraphEdgeEntity::getGroupId, groupId));
        nodeMapper.delete(new LambdaQueryWrapper<KnowledgeGraphNodeEntity>()
                .eq(KnowledgeGraphNodeEntity::getGroupId, groupId));

        Map<String, Long> idByKey = new HashMap<>(nodes.size());
        for (NodeData data : nodes) {
            KnowledgeGraphNodeEntity entity = new KnowledgeGraphNodeEntity();
            entity.setGroupId(groupId);
            entity.setNodeKey(data.key());
            entity.setName(data.name());
            entity.setSummary(data.summary() == null ? "" : data.summary());
            entity.setImportance(data.importance());
            entity.setDocumentCount(data.documentCount());
            entity.setSourceDocuments(data.sourceDocuments() == null ? "" : data.sourceDocuments());
            nodeMapper.insert(entity);
            idByKey.put(data.key(), entity.getId());
        }

        int edgeCount = 0;
        for (EdgeData data : edges) {
            Long sourceId = idByKey.get(data.sourceKey());
            Long targetId = idByKey.get(data.targetKey());
            if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
                continue;
            }
            KnowledgeGraphEdgeEntity entity = new KnowledgeGraphEdgeEntity();
            entity.setGroupId(groupId);
            entity.setSourceNodeId(sourceId);
            entity.setTargetNodeId(targetId);
            entity.setRelationType(data.relation());
            entity.setWeight(data.weight());
            entity.setEvidence(data.evidence() == null ? "" : data.evidence());
            edgeMapper.insert(entity);
            edgeCount++;
        }

        log.info("图谱落库完成: groupId={}, 知识点={}, 关系={}", groupId, nodes.size(), edgeCount);
        return new int[]{nodes.size(), edgeCount};
    }

    /**
     * 待入库的知识点。
     *
     * @param key              归一化键
     * @param name             名称
     * @param summary          释义
     * @param importance       重要度
     * @param documentCount    来源资料份数
     * @param sourceDocuments  来源资料名，逗号分隔
     */
    public record NodeData(
            String key,
            String name,
            String summary,
            int importance,
            int documentCount,
            String sourceDocuments
    ) {
    }

    /**
     * 待入库的关系。
     *
     * @param sourceKey 起点归一化键
     * @param targetKey 终点归一化键
     * @param relation  关系类型
     * @param evidence  原文依据
     * @param weight    权重
     */
    public record EdgeData(
            String sourceKey,
            String targetKey,
            String relation,
            String evidence,
            double weight
    ) {
    }
}
