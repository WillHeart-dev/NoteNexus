package com.notenexus.rag.exercise.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notenexus.rag.exercise.model.entity.ExerciseAttemptEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 刷题作答记录 Mapper，继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力。
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Mapper
public interface ExerciseAttemptMapper extends BaseMapper<ExerciseAttemptEntity> {
}
