package com.notenexus.rag.exercise.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 习题实体，映射 {@code exercise_questions} 表。
 *
 * <p>习题由 AI 从群组资料切片中抽取生成，也支持后续人工录入。
 * 每条习题保留来源资料与原文依据片段，保证可追溯——这是与通用题库的核心差异。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Data
@TableName("exercise_questions")
public class ExerciseQuestionEntity {

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属群组 ID */
    private Long groupId;

    /** 来源资料 ID，为空表示人工录入 */
    private Long sourceDocumentId;

    /** 来源切片 ID，用于回溯原文 */
    private Long sourceChunkId;

    /** 题型：SINGLE_CHOICE | MULTIPLE_CHOICE | TRUE_FALSE | SHORT_ANSWER */
    private String questionType;

    /** 题干 */
    private String stem;

    /** 选项 JSON 数组，单选题与多选题使用，简答题为空 */
    private String optionsJson;

    /** 标准答案 */
    private String answer;

    /** 解析 */
    private String analysis;

    /** 难度：EASY | MEDIUM | HARD */
    private String difficulty;

    /** 标签，逗号分隔 */
    private String tags;

    /** 资料原文依据片段，用于溯源 */
    private String sourceSnippet;

    /** 软删除标记 */
    private Boolean deleted;

    /** 创建者用户 ID */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
