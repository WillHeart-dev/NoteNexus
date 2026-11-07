package com.notenexus.rag.exercise.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notenexus.rag.auth.CurrentUserService;
import com.notenexus.rag.common.exception.BusinessException;
import com.notenexus.rag.document.mapper.DocumentMapper;
import com.notenexus.rag.document.model.entity.DocumentEntity;
import com.notenexus.rag.exercise.mapper.ExerciseAttemptMapper;
import com.notenexus.rag.exercise.mapper.ExerciseQuestionMapper;
import com.notenexus.rag.exercise.model.dto.ExerciseQuery;
import com.notenexus.rag.exercise.model.dto.GenerateExerciseRequest;
import com.notenexus.rag.exercise.model.dto.SubmitAnswerRequest;
import com.notenexus.rag.exercise.model.entity.ExerciseAttemptEntity;
import com.notenexus.rag.exercise.model.entity.ExerciseQuestionEntity;
import com.notenexus.rag.exercise.model.vo.ExerciseQuestionVO;
import com.notenexus.rag.exercise.model.vo.ExerciseStatsVO;
import com.notenexus.rag.exercise.model.vo.SubmitAnswerVO;
import com.notenexus.rag.group.service.GroupMembershipService;
import com.notenexus.rag.ingestion.mapper.DocumentChunkMapper;
import com.notenexus.rag.ingestion.model.entity.DocumentChunkEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 习题库服务。
 *
 * <p>负责三件事：</p>
 * <ol>
 *   <li><b>生成</b>：从群组内已就绪资料中抽取习题并入库，题目保留来源与原文依据</li>
 *   <li><b>刷题</b>：按题型/难度筛选、只看错题、提交答案并即时判定</li>
 *   <li><b>统计</b>：统计题量、已答数与正确率</li>
 * </ol>
 *
 * <p>权限：所有操作都要求调用者是目标群组成员，复用
 * {@link GroupMembershipService#requireGroupReadable(Long)} 统一校验。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class ExerciseService {

    /** 资料就绪状态 */
    private static final String DOCUMENT_STATUS_READY = "READY";
    /** 单份资料参与抽取的最大切片数，避免超长上下文 */
    private static final int MAX_CHUNKS_PER_DOCUMENT = 40;
    /** 习题列表单次返回上限 */
    private static final int MAX_LIST_SIZE = 500;

    private final ExerciseQuestionMapper questionMapper;
    private final ExerciseAttemptMapper attemptMapper;
    private final ExerciseExtractionService extractionService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final GroupMembershipService groupMembershipService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public ExerciseService(ExerciseQuestionMapper questionMapper,
                           ExerciseAttemptMapper attemptMapper,
                           ExerciseExtractionService extractionService,
                           DocumentMapper documentMapper,
                           DocumentChunkMapper documentChunkMapper,
                           GroupMembershipService groupMembershipService,
                           CurrentUserService currentUserService,
                           ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.attemptMapper = attemptMapper;
        this.extractionService = extractionService;
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.groupMembershipService = groupMembershipService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    /**
     * 从群组资料中抽取习题并入库。
     *
     * @param request 生成请求
     * @return 实际入库的题目数量
     */
    @Transactional
    public int generate(GenerateExerciseRequest request) {
        CurrentUserService.CurrentUser currentUser =
                groupMembershipService.requireGroupReadable(request.groupId());

        List<DocumentEntity> documents = resolveTargetDocuments(request);
        if (documents.isEmpty()) {
            throw new BusinessException("该小组还没有可用于出题的已就绪资料");
        }

        int questionCount = request.questionCount() == null
                ? GenerateExerciseRequest.DEFAULT_COUNT
                : request.questionCount();
        // 多份资料时按份数均分题量，至少每份 1 题
        int perDocument = Math.max(1, questionCount / documents.size());

        int inserted = 0;
        for (DocumentEntity document : documents) {
            String content = joinChunks(document.getId());
            if (!StringUtils.hasText(content)) {
                continue;
            }
            List<ExerciseExtractionService.ExtractedQuestion> extracted = extractionService.extract(
                    document.getFileName(), content, perDocument, request.questionType());

            for (ExerciseExtractionService.ExtractedQuestion item : extracted) {
                ExerciseQuestionEntity entity = toEntity(item, document, request.groupId(), currentUser.userId());
                questionMapper.insert(entity);
                inserted++;
            }
        }

        log.info("习题抽取完成: groupId={}, 资料数={}, 入库题量={}",
                request.groupId(), documents.size(), inserted);
        return inserted;
    }

    /**
     * 按条件查询习题列表，并标记当前用户的作答状态。
     *
     * @param query 查询条件
     * @return 习题列表
     */
    public List<ExerciseQuestionVO> list(ExerciseQuery query) {
        CurrentUserService.CurrentUser currentUser =
                groupMembershipService.requireGroupReadable(query.groupId());

        LambdaQueryWrapper<ExerciseQuestionEntity> wrapper =
                new LambdaQueryWrapper<ExerciseQuestionEntity>()
                        .eq(ExerciseQuestionEntity::getGroupId, query.groupId())
                        .eq(ExerciseQuestionEntity::getDeleted, false)
                        .orderByDesc(ExerciseQuestionEntity::getId)
                        .last("LIMIT " + MAX_LIST_SIZE);

        if (StringUtils.hasText(query.questionType())) {
            wrapper.eq(ExerciseQuestionEntity::getQuestionType, query.questionType());
        }
        if (StringUtils.hasText(query.difficulty())) {
            wrapper.eq(ExerciseQuestionEntity::getDifficulty, query.difficulty());
        }
        if (StringUtils.hasText(query.keyword())) {
            wrapper.like(ExerciseQuestionEntity::getStem, query.keyword().trim());
        }

        List<ExerciseQuestionEntity> questions = questionMapper.selectList(wrapper);
        if (questions.isEmpty()) {
            return List.of();
        }
        // 客观题在前、主观题在后：选择题 → 判断题 → 简答题，同类内按 ID 倒序
        questions.sort(java.util.Comparator
                .comparingInt((ExerciseQuestionEntity q) -> typeOrder(q.getQuestionType()))
                .thenComparing(ExerciseQuestionEntity::getId, java.util.Comparator.reverseOrder()));

        Map<Long, ExerciseAttemptEntity> latestAttempts =
                findLatestAttempts(currentUser.userId(), questions);

        List<ExerciseQuestionVO> result = new ArrayList<>(questions.size());
        for (ExerciseQuestionEntity question : questions) {
            ExerciseAttemptEntity attempt = latestAttempts.get(question.getId());
            // 只看错题：过滤掉未作答与最近一次答对的题
            if (Boolean.TRUE.equals(query.onlyWrong())
                    && (attempt == null || Boolean.TRUE.equals(attempt.getIsCorrect()))) {
                continue;
            }
            result.add(toVo(question, attempt, resolveDocumentName(question)));
        }
        return result;
    }

    /**
     * 提交一次作答并即时判定。
     *
     * <p>判定规则按题型区分：选择题采用大小写无关的精确匹配（多选按字母集合比较），
     * 判断题与简答题采用去空白后的包含匹配，容忍模型答案的自然表述差异。</p>
     *
     * @param request 作答请求
     * @return 判定结果与解析
     */
    @Transactional
    public SubmitAnswerVO submit(SubmitAnswerRequest request) {
        ExerciseQuestionEntity question = questionMapper.selectById(request.questionId());
        if (question == null || Boolean.TRUE.equals(question.getDeleted())) {
            throw new BusinessException("习题不存在");
        }
        CurrentUserService.CurrentUser currentUser =
                groupMembershipService.requireGroupReadable(question.getGroupId());

        boolean correct = judge(question, request.userAnswer());

        ExerciseAttemptEntity attempt = new ExerciseAttemptEntity();
        attempt.setGroupId(question.getGroupId());
        attempt.setQuestionId(question.getId());
        attempt.setUserId(currentUser.userId());
        attempt.setUserAnswer(request.userAnswer().trim());
        attempt.setIsCorrect(correct);
        attempt.setCreatedAt(LocalDateTime.now());
        attemptMapper.insert(attempt);

        return new SubmitAnswerVO(question.getId(), correct, question.getAnswer(), question.getAnalysis());
    }

    /**
     * 统计习题库概览。
     *
     * @param groupId 群组 ID
     * @return 统计结果
     */
    public ExerciseStatsVO stats(Long groupId) {
        CurrentUserService.CurrentUser currentUser = groupMembershipService.requireGroupReadable(groupId);

        long total = questionMapper.selectCount(new LambdaQueryWrapper<ExerciseQuestionEntity>()
                .eq(ExerciseQuestionEntity::getGroupId, groupId)
                .eq(ExerciseQuestionEntity::getDeleted, false));

        List<ExerciseAttemptEntity> attempts = attemptMapper.selectList(
                new LambdaQueryWrapper<ExerciseAttemptEntity>()
                        .eq(ExerciseAttemptEntity::getGroupId, groupId)
                        .eq(ExerciseAttemptEntity::getUserId, currentUser.userId())
                        .orderByDesc(ExerciseAttemptEntity::getId));

        // 每题只取最近一次作答
        Map<Long, Boolean> latest = new HashMap<>();
        for (ExerciseAttemptEntity attempt : attempts) {
            latest.putIfAbsent(attempt.getQuestionId(), Boolean.TRUE.equals(attempt.getIsCorrect()));
        }

        long answered = latest.size();
        long correct = latest.values().stream().filter(Boolean::booleanValue).count();
        double accuracy = answered == 0 ? 0.0 : (double) correct / answered;

        return new ExerciseStatsVO(total, answered, correct, accuracy);
    }

    /**
     * 删除一道习题（仅群主）。
     *
     * @param questionId 习题 ID
     */
    @Transactional
    public void delete(Long questionId) {
        ExerciseQuestionEntity question = questionMapper.selectById(questionId);
        if (question == null || Boolean.TRUE.equals(question.getDeleted())) {
            throw new BusinessException("习题不存在");
        }
        groupMembershipService.requireGroupOwner(question.getGroupId());
        question.setDeleted(true);
        questionMapper.updateById(question);
    }

    /** 确定本次出题使用的资料范围 */
    private List<DocumentEntity> resolveTargetDocuments(GenerateExerciseRequest request) {
        if (request.documentId() != null) {
            DocumentEntity document = documentMapper.selectByIdAndGroupId(
                    request.documentId(), request.groupId());
            if (document == null || !DOCUMENT_STATUS_READY.equals(document.getStatus())) {
                throw new BusinessException("指定的资料不存在或尚未处理完成");
            }
            return List.of(document);
        }
        return documentMapper.selectList(new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getGroupId, request.groupId())
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

    /** 批量查询当前用户对这批题目的最近一次作答 */
    private Map<Long, ExerciseAttemptEntity> findLatestAttempts(Long userId,
                                                               List<ExerciseQuestionEntity> questions) {
        List<Long> questionIds = questions.stream().map(ExerciseQuestionEntity::getId).toList();
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        List<ExerciseAttemptEntity> attempts = attemptMapper.selectList(
                new LambdaQueryWrapper<ExerciseAttemptEntity>()
                        .eq(ExerciseAttemptEntity::getUserId, userId)
                        .in(ExerciseAttemptEntity::getQuestionId, questionIds)
                        .orderByDesc(ExerciseAttemptEntity::getId));

        Map<Long, ExerciseAttemptEntity> latest = new HashMap<>();
        for (ExerciseAttemptEntity attempt : attempts) {
            latest.putIfAbsent(attempt.getQuestionId(), attempt);
        }
        return latest;
    }

    /** 解析习题来源资料的文件名 */
    private String resolveDocumentName(ExerciseQuestionEntity question) {
        if (question.getSourceDocumentId() == null) {
            return null;
        }
        DocumentEntity document = documentMapper.selectById(question.getSourceDocumentId());
        return document == null ? null : document.getFileName();
    }

    /** 抽取结果转实体 */
    private ExerciseQuestionEntity toEntity(ExerciseExtractionService.ExtractedQuestion item,
                                            DocumentEntity document,
                                            Long groupId,
                                            Long userId) {
        ExerciseQuestionEntity entity = new ExerciseQuestionEntity();
        entity.setGroupId(groupId);
        entity.setSourceDocumentId(document.getId());
        entity.setSourceChunkId(null);
        entity.setQuestionType(item.questionType());
        entity.setStem(item.stem());
        entity.setOptionsJson(item.optionsJson(objectMapper));
        entity.setAnswer(item.answer());
        entity.setAnalysis(item.analysis());
        entity.setDifficulty(item.difficulty());
        entity.setTags(item.tagsText());
        entity.setSourceSnippet(item.sourceSnippet());
        entity.setDeleted(false);
        entity.setCreatedBy(userId);
        return entity;
    }

    /** 实体转视图对象 */
    private ExerciseQuestionVO toVo(ExerciseQuestionEntity entity,
                                    ExerciseAttemptEntity attempt,
                                    String documentName) {
        return new ExerciseQuestionVO(
                entity.getId(),
                entity.getGroupId(),
                entity.getQuestionType(),
                entity.getStem(),
                parseOptions(entity.getOptionsJson()),
                entity.getAnswer(),
                entity.getAnalysis(),
                entity.getDifficulty(),
                splitTags(entity.getTags()),
                entity.getSourceSnippet(),
                documentName,
                attempt != null,
                attempt == null ? null : Boolean.TRUE.equals(attempt.getIsCorrect()),
                entity.getCreatedAt()
        );
    }

    /** 解析选项 JSON */
    private List<String> parseOptions(String optionsJson) {
        if (!StringUtils.hasText(optionsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("习题选项 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 拆分标签 */
    private static List<String> splitTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags.split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 判题逻辑。
     * <p>选择题比较字母集合，判断题与简答题做归一化后的包含匹配，
     * 避免因为模型答案里的修饰词（如"答案是 B"）导致误判。</p>
     */
    private boolean judge(ExerciseQuestionEntity question, String userAnswer) {
        String expected = normalize(question.getAnswer());
        String actual = normalize(userAnswer);
        if (expected.isEmpty() || actual.isEmpty()) {
            return false;
        }

        String type = question.getQuestionType();
        if ("SINGLE_CHOICE".equals(type) || "MULTIPLE_CHOICE".equals(type)) {
            return extractLetters(expected).equals(extractLetters(actual));
        }
        return expected.equals(actual) || expected.contains(actual) || actual.contains(expected);
    }

    /**
     * 题型排序权重：客观题在前，主观题在后。
     *
     * @param questionType 题型
     * @return 排序权重，越小越靠前
     */
    private static int typeOrder(String questionType) {
        if (questionType == null) {
            return 3;
        }
        return switch (questionType) {
            case "SINGLE_CHOICE" -> 0;
            case "MULTIPLE_CHOICE" -> 1;
            case "TRUE_FALSE" -> 2;
            default -> 3;
        };
    }

    /** 归一化：去空白、去标点、转小写 */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s,，。.;；:：、\"'“”]", "").toLowerCase();
    }

    /** 提取答案中的字母集合（用于选择题比对） */
    private static String extractLetters(String text) {
        StringBuilder builder = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                builder.append(c);
            }
        }
        char[] letters = builder.toString().toCharArray();
        java.util.Arrays.sort(letters);
        return new String(letters);
    }
}
