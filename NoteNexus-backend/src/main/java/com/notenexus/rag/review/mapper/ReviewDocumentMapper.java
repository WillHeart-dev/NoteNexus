package com.notenexus.rag.review.mapper;

import com.notenexus.rag.review.model.entity.ReviewDocumentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 复习文档数据访问接口。
 *
 * <p>基于 MyBatis-Plus {@link BaseMapper} 提供基础 CRUD，
 * 条件查询统一使用 {@code LambdaQueryWrapper} 构造。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Mapper
public interface ReviewDocumentMapper extends BaseMapper<ReviewDocumentEntity> {
}
