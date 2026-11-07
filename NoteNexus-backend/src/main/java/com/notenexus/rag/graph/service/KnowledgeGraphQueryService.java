package com.notenexus.rag.graph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.notenexus.rag.graph.mapper.KnowledgeGraphBuildTaskMapper;
import com.notenexus.rag.graph.mapper.KnowledgeGraphEdgeMapper;
import com.notenexus.rag.graph.mapper.KnowledgeGraphNodeMapper;
import com.notenexus.rag.graph.model.entity.KnowledgeGraphBuildTaskEntity;
import com.notenexus.rag.graph.model.entity.KnowledgeGraphEdgeEntity;
import com.notenexus.rag.graph.model.entity.KnowledgeGraphNodeEntity;
import com.notenexus.rag.graph.model.vo.GraphEdgeVO;
import com.notenexus.rag.graph.model.vo.GraphNodeVO;
import com.notenexus.rag.graph.model.vo.GraphViewVO;
import com.notenexus.rag.graph.model.vo.MasteryVO;
import com.notenexus.rag.group.service.GroupMembershipService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱查询服务：读取图谱并结合当前用户的做题情况标注掌握度。
 *
 * <p>掌握度是图谱的"个人化"入口——同一张图对不同用户会呈现不同的颜色分布，
 * 因此节点视图必然与调用者绑定，不能做全局缓存。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Service
public class KnowledgeGraphQueryService {

    private final KnowledgeGraphNodeMapper nodeMapper;
    private final KnowledgeGraphEdgeMapper edgeMapper;
    private final KnowledgeGraphBuildTaskMapper taskMapper;
    private final MasteryService masteryService;
    private final GroupMembershipService groupMembershipService;

    public KnowledgeGraphQueryService(KnowledgeGraphNodeMapper nodeMapper,
                                      KnowledgeGraphEdgeMapper edgeMapper,
                                      KnowledgeGraphBuildTaskMapper taskMapper,
                                      MasteryService masteryService,
                                      GroupMembershipService groupMembershipService) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.taskMapper = taskMapper;
        this.masteryService = masteryService;
        this.groupMembershipService = groupMembershipService;
    }

    /**
     * 查询群组的知识图谱。
     *
     * @param groupId 群组 ID
     * @return 图谱视图，未构建时节点与关系为空
     */
    public GraphViewVO getGraph(Long groupId) {
        var currentUser = groupMembershipService.requireGroupReadable(groupId);

        List<KnowledgeGraphNodeEntity> nodes = listNodes(groupId);
        List<KnowledgeGraphEdgeEntity> edges = listEdges(groupId);
        Map<Long, MasteryVO> mastery = masteryService.compute(nodes, groupId, currentUser.userId());

        Map<Long, String> nameById = new HashMap<>(nodes.size());
        for (KnowledgeGraphNodeEntity node : nodes) {
            nameById.put(node.getId(), node.getName());
        }

        List<GraphNodeVO> nodeVOs = new ArrayList<>(nodes.size());
        for (KnowledgeGraphNodeEntity node : nodes) {
            MasteryVO state = mastery.getOrDefault(node.getId(), MasteryVO.untouched());
            nodeVOs.add(new GraphNodeVO(
                    node.getId(),
                    node.getName(),
                    node.getSummary(),
                    node.getImportance(),
                    node.getDocumentCount(),
                    splitDocuments(node.getSourceDocuments()),
                    state.level(),
                    state.accuracy(),
                    state.questionCount()
            ));
        }

        List<GraphEdgeVO> edgeVOs = new ArrayList<>(edges.size());
        for (KnowledgeGraphEdgeEntity edge : edges) {
            edgeVOs.add(new GraphEdgeVO(
                    edge.getId(),
                    edge.getSourceNodeId(),
                    edge.getTargetNodeId(),
                    nameById.getOrDefault(edge.getSourceNodeId(), ""),
                    nameById.getOrDefault(edge.getTargetNodeId(), ""),
                    edge.getRelationType(),
                    edge.getWeight(),
                    edge.getEvidence()
            ));
        }

        return new GraphViewVO(groupId, nodeVOs.size(), edgeVOs.size(), lastBuiltAt(groupId), nodeVOs, edgeVOs);
    }

    /** 查询群组全部节点 */
    List<KnowledgeGraphNodeEntity> listNodes(Long groupId) {
        return nodeMapper.selectList(new LambdaQueryWrapper<KnowledgeGraphNodeEntity>()
                .eq(KnowledgeGraphNodeEntity::getGroupId, groupId)
                .orderByAsc(KnowledgeGraphNodeEntity::getId));
    }

    /** 查询群组全部关系 */
    List<KnowledgeGraphEdgeEntity> listEdges(Long groupId) {
        return edgeMapper.selectList(new LambdaQueryWrapper<KnowledgeGraphEdgeEntity>()
                .eq(KnowledgeGraphEdgeEntity::getGroupId, groupId)
                .orderByAsc(KnowledgeGraphEdgeEntity::getId));
    }

    /** 最近一次构建完成时间 */
    private java.time.LocalDateTime lastBuiltAt(Long groupId) {
        List<KnowledgeGraphBuildTaskEntity> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphBuildTaskEntity>()
                        .eq(KnowledgeGraphBuildTaskEntity::getGroupId, groupId)
                        .eq(KnowledgeGraphBuildTaskEntity::getStatus, "COMPLETED")
                        .orderByDesc(KnowledgeGraphBuildTaskEntity::getId)
                        .last("LIMIT 1"));
        return tasks.isEmpty() ? null : tasks.get(0).getCompletedAt();
    }

    /** 拆分来源资料名字符串 */
    private static List<String> splitDocuments(String documents) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(documents)) {
            return result;
        }
        for (String item : documents.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
