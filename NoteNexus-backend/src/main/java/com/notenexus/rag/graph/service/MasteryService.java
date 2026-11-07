package com.notenexus.rag.graph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.notenexus.rag.exercise.mapper.ExerciseAttemptMapper;
import com.notenexus.rag.exercise.mapper.ExerciseQuestionMapper;
import com.notenexus.rag.exercise.model.entity.ExerciseAttemptEntity;
import com.notenexus.rag.exercise.model.entity.ExerciseQuestionEntity;
import com.notenexus.rag.graph.model.entity.KnowledgeGraphNodeEntity;
import com.notenexus.rag.graph.model.vo.MasteryVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识点掌握度评估服务。
 *
 * <p>把知识点与习题库打通：习题标签或题干命中知识点名称即视为关联题目，
 * 再用当前用户对这些题目的最近一次作答结果计算正确率，得到掌握度档位。
 * 由于数据量在群组级别可控（单组题目通常数百道），这里采用内存匹配，
 * 不引入额外的倒排索引，避免为统计功能增加写入链路的复杂度。</p>
 *
 * <p>档位规则：无关联题目 / 未作答 → {@code UNTOUCHED}；正确率 &lt; 0.6 → {@code WEAK}；
 * 0.6 ~ 0.85 → {@code MEDIUM}；≥ 0.85 → {@code STRONG}。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Service
public class MasteryService {

    /** 单组参与匹配的题目上限，防止异常数据拖慢统计 */
    private static final int MAX_QUESTIONS = 2000;
    /** 判定为薄弱的知识点正确率阈值 */
    private static final double WEAK_THRESHOLD = 0.6;
    /** 判定为已掌握的知识点正确率阈值 */
    private static final double STRONG_THRESHOLD = 0.85;

    private final ExerciseQuestionMapper questionMapper;
    private final ExerciseAttemptMapper attemptMapper;

    public MasteryService(ExerciseQuestionMapper questionMapper, ExerciseAttemptMapper attemptMapper) {
        this.questionMapper = questionMapper;
        this.attemptMapper = attemptMapper;
    }

    /**
     * 计算每个知识点的掌握度。
     *
     * @param nodes   知识点列表
     * @param groupId 群组 ID
     * @param userId  当前用户 ID
     * @return 节点 ID 到掌握度的映射
     */
    public Map<Long, MasteryVO> compute(List<KnowledgeGraphNodeEntity> nodes, Long groupId, Long userId) {
        if (nodes == null || nodes.isEmpty()) {
            return Map.of();
        }

        List<ExerciseQuestionEntity> questions = questionMapper.selectList(
                new LambdaQueryWrapper<ExerciseQuestionEntity>()
                        .eq(ExerciseQuestionEntity::getGroupId, groupId)
                        .eq(ExerciseQuestionEntity::getDeleted, false)
                        .orderByDesc(ExerciseQuestionEntity::getId)
                        .last("LIMIT " + MAX_QUESTIONS));

        Map<Long, Boolean> latestResult = latestAttempts(groupId, userId);

        Map<Long, MasteryVO> result = new HashMap<>();
        for (KnowledgeGraphNodeEntity node : nodes) {
            result.put(node.getId(), evaluate(node, questions, latestResult));
        }
        return result;
    }

    /** 统计单个知识点关联题目的作答情况 */
    private MasteryVO evaluate(KnowledgeGraphNodeEntity node,
                               List<ExerciseQuestionEntity> questions,
                               Map<Long, Boolean> latestResult) {
        String name = node.getName();
        if (!StringUtils.hasText(name)) {
            return MasteryVO.untouched();
        }

        int matched = 0;
        int answered = 0;
        int correct = 0;
        for (ExerciseQuestionEntity question : questions) {
            if (!matches(name, question)) {
                continue;
            }
            matched++;
            Boolean isCorrect = latestResult.get(question.getId());
            if (isCorrect != null) {
                answered++;
                if (isCorrect) {
                    correct++;
                }
            }
        }

        if (answered == 0) {
            return new MasteryVO("UNTOUCHED", null, matched);
        }

        double accuracy = Math.round((double) correct / answered * 1000d) / 1000d;
        String level;
        if (accuracy >= STRONG_THRESHOLD) {
            level = "STRONG";
        } else if (accuracy >= WEAK_THRESHOLD) {
            level = "MEDIUM";
        } else {
            level = "WEAK";
        }
        return new MasteryVO(level, accuracy, matched);
    }

    /** 判断一道题是否属于该知识点：标签命中优先，其次题干命中 */
    private static boolean matches(String nodeName, ExerciseQuestionEntity question) {
        String tags = question.getTags();
        if (StringUtils.hasText(tags)) {
            for (String tag : tags.split(",")) {
                if (GraphExtractionService.normalizeKey(tag).equals(GraphExtractionService.normalizeKey(nodeName))) {
                    return true;
                }
            }
        }
        String stem = question.getStem();
        return StringUtils.hasText(stem) && stem.contains(nodeName);
    }

    /** 查询当前用户在该群组内每道题的最近一次作答结果 */
    private Map<Long, Boolean> latestAttempts(Long groupId, Long userId) {
        List<ExerciseAttemptEntity> attempts = attemptMapper.selectList(
                new LambdaQueryWrapper<ExerciseAttemptEntity>()
                        .eq(ExerciseAttemptEntity::getGroupId, groupId)
                        .eq(ExerciseAttemptEntity::getUserId, userId)
                        .orderByDesc(ExerciseAttemptEntity::getId));

        Map<Long, Boolean> latest = new HashMap<>();
        List<ExerciseAttemptEntity> ordered = new ArrayList<>(attempts);
        for (ExerciseAttemptEntity attempt : ordered) {
            latest.putIfAbsent(attempt.getQuestionId(), Boolean.TRUE.equals(attempt.getIsCorrect()));
        }
        return latest;
    }
}
