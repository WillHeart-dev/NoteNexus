package com.notenexus.rag.exercise.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷题作答记录实体，映射 {@code exercise_attempts} 表。
 *
 * <p>每次提交答案都会留一条记录，用于统计正确率与重刷错题。
 * 同一道题可以被同一用户多次作答，按时间取最新一次判定掌握情况。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Data
@TableName("exercise_attempts")
public class ExerciseAttemptEntity {

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属群组 ID */
    private Long groupId;

    /** 习题 ID */
    private Long questionId;

    /** 作答用户 ID */
    private Long userId;

    /** 用户作答内容 */
    private String userAnswer;

    /** 是否正确 */
    private Boolean isCorrect;

    /** 作答时间 */
    private LocalDateTime createdAt;
}
