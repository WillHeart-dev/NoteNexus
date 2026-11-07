package com.notenexus.rag.graph.service;

import com.notenexus.rag.graph.model.entity.KnowledgeGraphEdgeEntity;
import com.notenexus.rag.graph.model.entity.KnowledgeGraphNodeEntity;
import com.notenexus.rag.graph.model.vo.LearningPathNodeVO;
import com.notenexus.rag.graph.model.vo.LearningPathStageVO;
import com.notenexus.rag.graph.model.vo.LearningPathVO;
import com.notenexus.rag.graph.model.vo.MasteryVO;
import com.notenexus.rag.group.service.GroupMembershipService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学习路径规划服务：把知识图谱转换成"先学什么、后学什么"的分阶段路径。
 *
 * <p>算法分三步：</p>
 * <ol>
 *   <li><b>分层</b>：以 {@code PREREQUISITE_OF} 边为约束做 Kahn 拓扑排序，
 *       每轮取出"前置已全部满足"的知识点作为同一阶段，天然形成由浅入深的梯次。
 *       同时把 {@code PART_OF} 反向视为弱前置（先整体、后局部），
 *       这样即使资料只给出层级结构、没有明确的先修表述，路径也不会退化成单个阶段。</li>
 *   <li><b>断环</b>：资料之间常有互相引用的概念，容易形成环。检测到剩余节点无法解锁时，
 *       取剩余节点中入度最小者强行解锁并标记 {@code cycleDetected}，
 *       保证路径始终可生成，同时把"教材本身存在循环依赖"这个事实暴露给用户。</li>
 *   <li><b>个性化排序</b>：同一阶段内按掌握度排序（薄弱优先、已掌握靠后），
 *       掌握度相同时按重要度降序，使"该先补哪个"有明确依据。</li>
 * </ol>
 *
 * <p>没有任何关系的孤立知识点单独归入最后一阶段，避免与"入门基础"混淆。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Service
public class LearningPathService {

    /** 前置关系类型 */
    private static final String RELATION_PREREQUISITE = "PREREQUISITE_OF";
    /** 包含关系类型 */
    private static final String RELATION_PART_OF = "PART_OF";
    /** 薄弱点清单最大条数 */
    private static final int MAX_WEAK_POINTS = 6;
    /** 未检测知识点的入榜重要度门槛 */
    private static final int UNTOUCHED_IMPORTANCE_THRESHOLD = 7;
    /** 阶段标题 */
    private static final String[] STAGE_TITLES = {
            "入门基础", "核心概念", "进阶机制", "综合应用", "拓展深化"
    };

    private final KnowledgeGraphQueryService queryService;
    private final MasteryService masteryService;
    private final GroupMembershipService groupMembershipService;

    public LearningPathService(KnowledgeGraphQueryService queryService,
                               MasteryService masteryService,
                               GroupMembershipService groupMembershipService) {
        this.queryService = queryService;
        this.masteryService = masteryService;
        this.groupMembershipService = groupMembershipService;
    }

    /**
     * 生成当前用户在指定群组的学习路径。
     *
     * @param groupId 群组 ID
     * @return 学习路径视图，图谱为空时阶段列表为空
     */
    public LearningPathVO build(Long groupId) {
        var currentUser = groupMembershipService.requireGroupReadable(groupId);

        List<KnowledgeGraphNodeEntity> nodes = queryService.listNodes(groupId);
        if (nodes.isEmpty()) {
            return new LearningPathVO(groupId, 0, 0, 0, 0, false, List.of(), List.of(), LocalDateTime.now());
        }
        List<KnowledgeGraphEdgeEntity> edges = queryService.listEdges(groupId);
        Map<Long, MasteryVO> mastery = masteryService.compute(nodes, groupId, currentUser.userId());

        Map<Long, KnowledgeGraphNodeEntity> nodeById = new HashMap<>(nodes.size());
        for (KnowledgeGraphNodeEntity node : nodes) {
            nodeById.put(node.getId(), node);
        }

        // 前置关系：prereqs[target] = 直接前置集合（强约束，用于展示"前置个数"）
        // levelingPrereqs 在强约束基础上叠加"先整体后局部"的结构约束，仅用于分层
        Map<Long, Set<Long>> prereqs = new HashMap<>();
        Map<Long, Set<Long>> levelingPrereqs = new HashMap<>();
        Set<Long> incident = new HashSet<>();
        for (KnowledgeGraphEdgeEntity edge : edges) {
            Long source = edge.getSourceNodeId();
            Long target = edge.getTargetNodeId();
            if (source == null || target == null || source.equals(target)) {
                continue;
            }
            if (!nodeById.containsKey(source) || !nodeById.containsKey(target)) {
                continue;
            }
            incident.add(source);
            incident.add(target);
            if (RELATION_PREREQUISITE.equals(edge.getRelationType())) {
                prereqs.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
                levelingPrereqs.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
            } else if (RELATION_PART_OF.equals(edge.getRelationType())) {
                // source 是 target 的组成部分：认知顺序上先看整体再学局部，
                // 因此把 target 记为 source 的弱前置，避免所有节点都堆在第一阶段
                levelingPrereqs.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
            }
        }

        List<Long> connected = new ArrayList<>();
        List<Long> isolated = new ArrayList<>();
        for (KnowledgeGraphNodeEntity node : nodes) {
            if (incident.contains(node.getId())) {
                connected.add(node.getId());
            } else {
                isolated.add(node.getId());
            }
        }

        // ── Kahn 分层 ──
        boolean cycleDetected = false;
        List<List<Long>> levels = new ArrayList<>();
        Set<Long> remaining = new LinkedHashSet<>(connected);
        while (!remaining.isEmpty()) {
            List<Long> current = new ArrayList<>();
            for (Long id : remaining) {
                if (!hasUnresolvedPrerequisite(id, levelingPrereqs, remaining)) {
                    current.add(id);
                }
            }
            if (current.isEmpty()) {
                // 剩余节点互相依赖形成环：取入度最小者强行解锁，保证路径可生成
                cycleDetected = true;
                Long forced = remaining.stream()
                        .min(Comparator.comparingInt(id ->
                                levelingPrereqs.getOrDefault(id, Set.of()).size()))
                        .orElse(remaining.iterator().next());
                current = List.of(forced);
            }
            levels.add(current);
            remaining.removeAll(current);
        }

        List<LearningPathStageVO> stages = new ArrayList<>(levels.size() + 1);
        int level = 1;
        for (List<Long> ids : levels) {
            stages.add(buildStage(level++, ids, nodeById, prereqs, mastery));
        }
        if (!isolated.isEmpty()) {
            stages.add(buildStage(stages.size() + 1, isolated, nodeById, prereqs, mastery,
                    "独立知识点", "与其他内容暂无关联，可按需查阅"));
        }

        int mastered = 0;
        int weak = 0;
        List<LearningPathNodeVO> weakCandidates = new ArrayList<>();
        for (KnowledgeGraphNodeEntity node : nodes) {
            MasteryVO state = mastery.getOrDefault(node.getId(), MasteryVO.untouched());
            if ("STRONG".equals(state.level())) {
                mastered++;
            }
            if ("WEAK".equals(state.level())) {
                weak++;
                weakCandidates.add(toPathNode(node, state, prereqs));
            } else if ("UNTOUCHED".equals(state.level())
                    && node.getImportance() != null
                    && node.getImportance() >= UNTOUCHED_IMPORTANCE_THRESHOLD) {
                weakCandidates.add(toPathNode(node, state, prereqs));
            }
        }
        // 薄弱优先，其次正确率低的优先，再次重要度高的优先
        weakCandidates.sort(Comparator
                .comparingInt((LearningPathNodeVO item) -> masteryRank(item.mastery()))
                .thenComparing(item -> item.accuracy() == null ? 1.0 : item.accuracy())
                .thenComparing(LearningPathNodeVO::importance, Comparator.reverseOrder()));
        List<LearningPathNodeVO> weakPoints = weakCandidates.size() > MAX_WEAK_POINTS
                ? new ArrayList<>(weakCandidates.subList(0, MAX_WEAK_POINTS))
                : weakCandidates;

        return new LearningPathVO(
                groupId,
                nodes.size(),
                edges.size(),
                mastered,
                weak,
                cycleDetected,
                stages,
                weakPoints,
                LocalDateTime.now()
        );
    }

    /** 判断某节点的前置是否还有未解锁的 */
    private static boolean hasUnresolvedPrerequisite(Long id,
                                                     Map<Long, Set<Long>> prereqs,
                                                     Set<Long> remaining) {
        Set<Long> required = prereqs.get(id);
        if (required == null || required.isEmpty()) {
            return false;
        }
        for (Long prerequisite : required) {
            if (remaining.contains(prerequisite)) {
                return true;
            }
        }
        return false;
    }

    /** 组装一个阶段 */
    private LearningPathStageVO buildStage(int level, List<Long> ids,
                                           Map<Long, KnowledgeGraphNodeEntity> nodeById,
                                           Map<Long, Set<Long>> prereqs,
                                           Map<Long, MasteryVO> mastery) {
        String title = level <= STAGE_TITLES.length ? STAGE_TITLES[level - 1] : "第 " + level + " 阶段";
        String description = level == 1
                ? "没有前置依赖，从这里开始"
                : "完成前一阶段后进入";
        return buildStage(level, ids, nodeById, prereqs, mastery, title, description);
    }

    /** 组装一个阶段（可指定标题与说明） */
    private LearningPathStageVO buildStage(int level, List<Long> ids,
                                           Map<Long, KnowledgeGraphNodeEntity> nodeById,
                                           Map<Long, Set<Long>> prereqs,
                                           Map<Long, MasteryVO> mastery,
                                           String title, String description) {
        List<LearningPathNodeVO> items = new ArrayList<>(ids.size());
        for (Long id : ids) {
            KnowledgeGraphNodeEntity node = nodeById.get(id);
            if (node == null) {
                continue;
            }
            items.add(toPathNode(node, mastery.getOrDefault(id, MasteryVO.untouched()), prereqs));
        }
        // 同阶段内：薄弱 → 未检测 → 中等 → 已掌握，再按重要度降序
        items.sort(Comparator
                .comparingInt((LearningPathNodeVO item) -> masteryRank(item.mastery()))
                .thenComparing(LearningPathNodeVO::importance, Comparator.reverseOrder())
                .thenComparing(LearningPathNodeVO::name));
        return new LearningPathStageVO(level, title, description, items.size(), items);
    }

    /** 节点转路径条目 */
    private static LearningPathNodeVO toPathNode(KnowledgeGraphNodeEntity node,
                                                 MasteryVO state,
                                                 Map<Long, Set<Long>> prereqs) {
        return new LearningPathNodeVO(
                node.getId(),
                node.getName(),
                node.getSummary(),
                node.getImportance(),
                state.level(),
                state.accuracy(),
                state.questionCount(),
                prereqs.getOrDefault(node.getId(), Set.of()).size()
        );
    }

    /** 掌握度排序权重：越小越靠前 */
    private static int masteryRank(String level) {
        if (level == null) {
            return 1;
        }
        return switch (level) {
            case "WEAK" -> 0;
            case "UNTOUCHED" -> 1;
            case "MEDIUM" -> 2;
            case "STRONG" -> 3;
            default -> 1;
        };
    }
}
