package com.notenexus.rag.exercise.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notenexus.rag.exercise.model.entity.ExerciseQuestionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 习题 Mapper，继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力。
 *
 * <p>查询统一使用 {@code LambdaQueryWrapper} 按群组、题型、难度过滤，无需额外 XML。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Mapper
public interface ExerciseQuestionMapper extends BaseMapper<ExerciseQuestionEntity> {
}
