package com.notenexus.rag.graph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.notenexus.rag.common.enums.GraphBuildStatus;
import com.notenexus.rag.common.exception.BusinessException;
import com.notenexus.rag.document.mapper.DocumentMapper;
import com.notenexus.rag.document.model.entity.DocumentEntity;
import com.notenexus.rag.graph.mapper.KnowledgeGraphBuildTaskMapper;
import com.notenexus.rag.graph.model.entity.KnowledgeGraphBuildTaskEntity;
import com.notenexus.rag.graph.model.vo.GraphTaskVO;
import com.notenexus.rag.group.service.GroupMembershipService;
import com.notenexus.rag.ingestion.mapper.DocumentChunkMapper;
import com.notenexus.rag.ingestion.model.entity.DocumentChunkEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱构建服务：异步执行「逐份资料抽取 → 概念归一合并 → 重建图谱」全流程。
 *
 * <p>任务落库后发布事件，在事务提交后异步执行，状态机
 * {@code PENDING → GENERATING → COMPLETED | FAILED}，与复习文档生成保持一致；
 * 通过条件更新抢占执行权，保证同一任务不会被重复执行。</p>
 *
 * <p>重建策略：同一群组每次构建都整体替换旧图。知识图谱描述的是"当前资料集合"
 * 的语义结构，增量合并会让已删除资料的概念残留下来，整体替换更符合直觉。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class KnowledgeGraphBuildService {

    /** 资料就绪状态 */
    private static final String DOCUMENT_STATUS_READY = "READY";
    /** 单份资料参与抽取的最大切片数 */
    private static final int MAX_CHUNKS_PER_DOCUMENT = 30;
    /** 失败原因最大保存长度 */
    private static final int MAX_FAILURE_REASON_LENGTH = 2000;
    /** 来源资料名最大保存长度（列宽 1024） */
    private static final int MAX_SOURCE_DOCUMENTS_LENGTH = 1000;

    private final KnowledgeGraphBuildTaskMapper taskMapper;
    private final KnowledgeGraphPersistenceService persistenceService;
    private final GraphExtractionService extractionService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final GroupMembershipService groupMembershipService;
    private final ApplicationEventPublisher eventPublisher;

    public KnowledgeGraphBuildService(
            KnowledgeGraphBuildTaskMapper taskMapper,
            KnowledgeGraphPersistenceService persistenceService,
            GraphExtractionService extractionService,
            DocumentMapper documentMapper,
            DocumentChunkMapper documentChunkMapper,
            GroupMembershipService groupMembershipService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.taskMapper = taskMapper;
        this.persistenceService = persistenceService;
        this.extractionService = extractionService;
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.groupMembershipService = groupMembershipService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 创建构建任务并触发异步构建。
     *
     * @param groupId 群组 ID
     * @return 新建任务（状态 PENDING）
     */
    @Transactional
    public GraphTaskVO createTask(Long groupId) {
        var currentUser = groupMembershipService.requireGroupReadable(groupId);

        List<DocumentEntity> documents = listReadyDocuments(groupId);
        if (documents.isEmpty()) {
            throw new BusinessException("该小组还没有已就绪的学习资料，请先上传资料并等待处理完成");
        }

        KnowledgeGraphBuildTaskEntity task = new KnowledgeGraphBuildTaskEntity();
        task.setGroupId(groupId);
        task.setRequestedBy(currentUser.userId());
        task.setStatus(GraphBuildStatus.PENDING.name());
        task.setDocumentCount(documents.size());
        task.setNodeCount(0);
        task.setEdgeCount(0);
        task.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        log.info("知识图谱构建任务已创建: taskId={}, groupId={}, 资料数={}",
                task.getId(), groupId, documents.size());
        eventPublisher.publishEvent(new GraphBuildRequestedEvent(task.getId(), groupId));
        return GraphTaskVO.from(task);
    }

    /**
     * 查询构建任务状态。
     *
     * @param taskId 任务 ID
     * @return 任务视图
     */
    public GraphTaskVO getTask(Long taskId) {
        KnowledgeGraphBuildTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("构建任务不存在");
        }
        groupMembershipService.requireGroupReadable(task.getGroupId());
        return GraphTaskVO.from(task);
    }

    /**
     * 查询群组最近一次构建任务，用于页面刷新后恢复进度展示。
     *
     * @param groupId 群组 ID
     * @return 最近一次任务，从未构建过时返回 null
     */
    public GraphTaskVO getLatestTask(Long groupId) {
        groupMembershipService.requireGroupReadable(groupId);
        List<KnowledgeGraphBuildTaskEntity> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphBuildTaskEntity>()
                        .eq(KnowledgeGraphBuildTaskEntity::getGroupId, groupId)
                        .orderByDesc(KnowledgeGraphBuildTaskEntity::getId)
                        .last("LIMIT 1"));
        return tasks.isEmpty() ? null : GraphTaskVO.from(tasks.get(0));
    }

    /**
     * 监听构建请求，事务提交后异步执行。
     *
     * @param event 构建事件
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBuildRequested(GraphBuildRequestedEvent event) {
        build(event.taskId(), event.groupId());
    }

    /**
     * 执行图谱构建。
     *
     * @param taskId  任务 ID
     * @param groupId 群组 ID
     */
    public void build(Long taskId, Long groupId) {
        KnowledgeGraphBuildTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("图谱构建任务不存在，跳过: taskId={}", taskId);
            return;
        }
        if (!markGenerating(taskId)) {
            log.info("图谱构建任务已被其它线程处理，跳过: taskId={}", taskId);
            return;
        }

        long startMillis = System.currentTimeMillis();
        try {
            List<DocumentEntity> documents = listReadyDocuments(groupId);
            if (documents.isEmpty()) {
                throw new BusinessException("该小组没有已就绪的学习资料");
            }

            Map<String, NodeAccumulator> nodeAccumulators = new LinkedHashMap<>();
            List<GraphExtractionService.ExtractedEdge> rawEdges = new ArrayList<>();
            int usedDocuments = 0;

            for (DocumentEntity document : documents) {
                String content = joinChunks(document.getId());
                if (!StringUtils.hasText(content)) {
                    log.warn("资料无可用切片，跳过图谱抽取: documentId={}", document.getId());
                    continue;
                }
                GraphExtractionService.ExtractedGraph extracted =
                        extractionService.extract(document.getFileName(), content);
                if (extracted.nodes().isEmpty()) {
                    log.warn("资料未抽取出知识点: documentId={}, fileName={}",
                            document.getId(), document.getFileName());
                    continue;
                }
                usedDocuments++;
                for (GraphExtractionService.ExtractedNode node : extracted.nodes()) {
                    nodeAccumulators.computeIfAbsent(node.key(),
                                    key -> new NodeAccumulator(node.name()))
                            .merge(node, document.getFileName());
                }
                rawEdges.addAll(extracted.edges());
                log.info("资料图谱抽取完成: fileName={}, 知识点={}, 关系={}",
                        document.getFileName(), extracted.nodes().size(), extracted.edges().size());
            }

            if (nodeAccumulators.isEmpty()) {
                throw new BusinessException("资料内容未能抽取出知识点，请确认资料正文可读");
            }

            List<KnowledgeGraphPersistenceService.NodeData> nodeData = new ArrayList<>();
            for (Map.Entry<String, NodeAccumulator> entry : nodeAccumulators.entrySet()) {
                nodeData.add(entry.getValue().toData(entry.getKey()));
            }
            List<KnowledgeGraphPersistenceService.EdgeData> edgeData =
                    mergeEdges(rawEdges, nodeAccumulators.keySet());

            int[] counts = persistenceService.replaceGraph(groupId, nodeData, edgeData);
            markCompleted(taskId, usedDocuments, counts[0], counts[1]);
            log.info("知识图谱构建完成: taskId={}, groupId={}, 资料={}, 知识点={}, 关系={}, 耗时={}ms",
                    taskId, groupId, usedDocuments, counts[0], counts[1],
                    System.currentTimeMillis() - startMillis);
        } catch (Exception exception) {
            log.error("知识图谱构建失败: taskId={}, groupId={}", taskId, groupId, exception);
            markFailed(taskId, exception);
        }
    }

    /**
     * 合并多份资料抽出的关系：同一对节点同一关系累加权重，保留第一条依据。
     *
     * @param rawEdges 原始关系列表
     * @param nodeKeys 已确认存在的知识点键集合
     * @return 去重后的关系列表
     */
    private List<KnowledgeGraphPersistenceService.EdgeData> mergeEdges(
            List<GraphExtractionService.ExtractedEdge> rawEdges, Set<String> nodeKeys) {
        Map<String, EdgeAccumulator> accumulators = new LinkedHashMap<>();
        for (GraphExtractionService.ExtractedEdge edge : rawEdges) {
            if (!nodeKeys.contains(edge.sourceKey()) || !nodeKeys.contains(edge.targetKey())) {
                // 关系端点在节点合并时被丢弃（例如名称为空），跳过
                continue;
            }
            if (edge.sourceKey().equals(edge.targetKey())) {
                continue;
            }
            String key = edge.sourceKey() + "|" + edge.targetKey() + "|" + edge.relation();
            accumulators.computeIfAbsent(key,
                            ignored -> new EdgeAccumulator(edge.sourceKey(), edge.targetKey(), edge.relation()))
                    .increase(edge.evidence());
        }
        List<KnowledgeGraphPersistenceService.EdgeData> result = new ArrayList<>(accumulators.size());
        for (EdgeAccumulator accumulator : accumulators.values()) {
            result.add(accumulator.toData());
        }
        return result;
    }

    /** 条件更新为 GENERATING，返回是否抢到执行权 */
    private boolean markGenerating(Long taskId) {
        KnowledgeGraphBuildTaskEntity update = new KnowledgeGraphBuildTaskEntity();
        update.setStatus(GraphBuildStatus.GENERATING.name());
        update.setStartedAt(LocalDateTime.now());
        return taskMapper.update(update, new LambdaUpdateWrapper<KnowledgeGraphBuildTaskEntity>()
                .eq(KnowledgeGraphBuildTaskEntity::getId, taskId)
                .eq(KnowledgeGraphBuildTaskEntity::getStatus, GraphBuildStatus.PENDING.name())) > 0;
    }

    /** 回写构建成功结果 */
    private void markCompleted(Long taskId, int documentCount, int nodeCount, int edgeCount) {
        KnowledgeGraphBuildTaskEntity update = new KnowledgeGraphBuildTaskEntity();
        update.setId(taskId);
        update.setStatus(GraphBuildStatus.COMPLETED.name());
        update.setDocumentCount(documentCount);
        update.setNodeCount(nodeCount);
        update.setEdgeCount(edgeCount);
        update.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(update);
    }

    /** 回写构建失败原因 */
    private void markFailed(Long taskId, Exception exception) {
        String reason = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        if (reason.length() > MAX_FAILURE_REASON_LENGTH) {
            reason = reason.substring(0, MAX_FAILURE_REASON_LENGTH);
        }
        KnowledgeGraphBuildTaskEntity update = new KnowledgeGraphBuildTaskEntity();
        update.setId(taskId);
        update.setStatus(GraphBuildStatus.FAILED.name());
        update.setFailureReason(reason);
        update.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(update);
    }

    /** 查询群组内已就绪的资料 */
    private List<DocumentEntity> listReadyDocuments(Long groupId) {
        return documentMapper.selectList(new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getGroupId, groupId)
                .eq(DocumentEntity::getStatus, DOCUMENT_STATUS_READY)
                .eq(DocumentEntity::getDeleted, false)
                .orderByAsc(DocumentEntity::getId));
    }

    /** 按顺序拼接资料切片文本 */
    private String joinChunks(Long documentId) {
        List<DocumentChunkEntity> chunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunkEntity>()
                        .eq(DocumentChunkEntity::getDocumentId, documentId)
                        .orderByAsc(DocumentChunkEntity::getChunkIndex)
                        .last("LIMIT " + MAX_CHUNKS_PER_DOCUMENT));
        StringBuilder builder = new StringBuilder();
        for (DocumentChunkEntity chunk : chunks) {
            if (StringUtils.hasText(chunk.getChunkText())) {
                builder.append(chunk.getChunkText()).append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * 知识点累加器：多份资料命中同一概念时合并释义、重要度与来源。
     */
    private static final class NodeAccumulator {

        private String name;
        private String summary = "";
        private int importance;
        private final Set<String> documents = new LinkedHashSet<>();

        private NodeAccumulator(String name) {
            this.name = name;
        }

        /** 合并一次抽取结果 */
        private void merge(GraphExtractionService.ExtractedNode node, String documentName) {
            if (node.summary().length() > summary.length()) {
                summary = node.summary();
            }
            if (node.importance() > importance) {
                importance = node.importance();
            }
            // 名称取更短的那个，通常更接近术语本身
            if (name.length() > node.name().length()) {
                name = node.name();
            }
            if (StringUtils.hasText(documentName)) {
                documents.add(documentName);
            }
        }

        private KnowledgeGraphPersistenceService.NodeData toData(String key) {
            String joined = String.join(",", documents);
            if (joined.length() > MAX_SOURCE_DOCUMENTS_LENGTH) {
                joined = joined.substring(0, MAX_SOURCE_DOCUMENTS_LENGTH);
            }
            return new KnowledgeGraphPersistenceService.NodeData(
                    key, name, summary, Math.max(1, importance), Math.max(1, documents.size()), joined);
        }
    }

    /**
     * 关系累加器：同一对节点的同一关系在多份资料中出现时权重累加。
     */
    private static final class EdgeAccumulator {

        private final String sourceKey;
        private final String targetKey;
        private final String relation;
        private String evidence = "";
        private int occurrences;

        private EdgeAccumulator(String sourceKey, String targetKey, String relation) {
            this.sourceKey = sourceKey;
            this.targetKey = targetKey;
            this.relation = relation;
        }

        private void increase(String text) {
            occurrences++;
            if (!StringUtils.hasText(evidence) && StringUtils.hasText(text)) {
                evidence = text;
            }
        }

        private KnowledgeGraphPersistenceService.EdgeData toData() {
            return new KnowledgeGraphPersistenceService.EdgeData(
                    sourceKey, targetKey, relation, evidence, (double) occurrences);
        }
    }
}
