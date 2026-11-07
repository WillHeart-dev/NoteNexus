package com.notenexus.rag.discussion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notenexus.rag.discussion.model.entity.GroupDiscussionMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 群组讨论消息 Mapper，继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力。
 *
 * <p>讨论消息的查询统一使用 {@code LambdaQueryWrapper} 构造，
 * 按群组 + 软删除标记 + 时间倒序过滤，无需额外 XML 映射。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Mapper
public interface GroupDiscussionMessageMapper extends BaseMapper<GroupDiscussionMessageEntity> {
}
