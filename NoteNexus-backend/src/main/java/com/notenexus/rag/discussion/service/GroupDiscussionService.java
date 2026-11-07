package com.notenexus.rag.discussion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.notenexus.rag.auth.CurrentUserService;
import com.notenexus.rag.common.exception.BusinessException;
import com.notenexus.rag.discussion.mapper.GroupDiscussionMessageMapper;
import com.notenexus.rag.discussion.model.dto.CreateDiscussionMessageRequest;
import com.notenexus.rag.discussion.model.entity.GroupDiscussionMessageEntity;
import com.notenexus.rag.discussion.model.vo.DiscussionMessageVO;
import com.notenexus.rag.group.service.GroupMembershipService;
import com.notenexus.rag.user.service.UserQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库评论服务。
 *
 * <p>提供知识库评论的发布、分页查询与撤回能力。评论按群组（即知识库）
 * 维度存储，前端入口挂在学习资料模块底部。所有操作都先经过
 * {@link GroupMembershipService#requireGroupReadable(Long)} 校验：
 * 只有群组成员可以查看讨论，只有消息作者可以撤回自己的消息。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Service
public class GroupDiscussionService {

    /** 消息状态：正常 */
    private static final String STATUS_ACTIVE = "ACTIVE";
    /** 消息状态：已撤回 */
    private static final String STATUS_DELETED = "DELETED";
    /** 引用回复摘要的最大字符数 */
    private static final int REPLY_SUMMARY_LENGTH = 80;
    /** 单次查询最大返回条数 */
    private static final int MAX_PAGE_SIZE = 200;

    private final GroupDiscussionMessageMapper messageMapper;
    private final GroupMembershipService groupMembershipService;
    private final UserQueryService userQueryService;

    public GroupDiscussionService(GroupDiscussionMessageMapper messageMapper,
                                  GroupMembershipService groupMembershipService,
                                  UserQueryService userQueryService) {
        this.messageMapper = messageMapper;
        this.groupMembershipService = groupMembershipService;
        this.userQueryService = userQueryService;
    }

    /**
     * 发布一条知识库评论。
     *
     * @param request 发送请求
     * @return 新建的消息视图对象
     */
    @Transactional
    public DiscussionMessageVO send(CreateDiscussionMessageRequest request) {
        CurrentUserService.CurrentUser currentUser =
                groupMembershipService.requireGroupReadable(request.groupId());

        GroupDiscussionMessageEntity entity = new GroupDiscussionMessageEntity();
        entity.setGroupId(request.groupId());
        entity.setUserId(currentUser.userId());
        entity.setContent(request.content().trim());
        entity.setReplyToId(request.replyToId());
        entity.setStatus(STATUS_ACTIVE);
        entity.setDeleted(false);
        messageMapper.insert(entity);

        return findById(entity.getId(), currentUser.userId());
    }

    /**
     * 按时间倒序分页查询知识库评论。
     *
     * @param groupId  群组 ID
     * @param beforeId 游标：只返回 ID 小于该值的消息，用于向前翻页，可为空
     * @param limit    返回条数，上限 {@value #MAX_PAGE_SIZE}
     * @return 消息列表，按时间倒序
     */
    public List<DiscussionMessageVO> list(Long groupId, Long beforeId, Integer limit) {
        CurrentUserService.CurrentUser currentUser = groupMembershipService.requireGroupReadable(groupId);
        int pageSize = normalizeLimit(limit);

        LambdaQueryWrapper<GroupDiscussionMessageEntity> wrapper =
                new LambdaQueryWrapper<GroupDiscussionMessageEntity>()
                        .eq(GroupDiscussionMessageEntity::getGroupId, groupId)
                        .eq(GroupDiscussionMessageEntity::getDeleted, false)
                        .orderByDesc(GroupDiscussionMessageEntity::getId)
                        .last("LIMIT " + pageSize);
        if (beforeId != null && beforeId > 0) {
            wrapper.lt(GroupDiscussionMessageEntity::getId, beforeId);
        }

        List<GroupDiscussionMessageEntity> entities = messageMapper.selectList(wrapper);
        if (entities.isEmpty()) {
            return List.of();
        }
        return toViewObjects(entities, currentUser.userId());
    }

    /**
     * 撤回一条消息。仅消息作者本人可撤回，撤回后内容不再返回。
     *
     * @param messageId 消息 ID
     */
    @Transactional
    public void revoke(Long messageId) {
        GroupDiscussionMessageEntity entity = messageMapper.selectById(messageId);
        if (entity == null || Boolean.TRUE.equals(entity.getDeleted())) {
            throw new BusinessException("消息不存在或已撤回");
        }
        CurrentUserService.CurrentUser currentUser =
                groupMembershipService.requireGroupReadable(entity.getGroupId());
        if (!entity.getUserId().equals(currentUser.userId())) {
            throw new BusinessException("只能撤回自己发送的消息");
        }

        entity.setStatus(STATUS_DELETED);
        entity.setDeleted(true);
        messageMapper.updateById(entity);
    }

    /** 查询单条消息并转为视图对象 */
    private DiscussionMessageVO findById(Long messageId, Long currentUserId) {
        GroupDiscussionMessageEntity entity = messageMapper.selectById(messageId);
        if (entity == null) {
            throw new BusinessException("消息不存在");
        }
        return toViewObjects(List.of(entity), currentUserId).get(0);
    }

    /**
     * 批量转换为视图对象。
     * <p>一次性查出所有涉及的用户名与引用消息，避免逐条查询造成 N+1。</p>
     */
    private List<DiscussionMessageVO> toViewObjects(List<GroupDiscussionMessageEntity> entities,
                                                    Long currentUserId) {
        Map<Long, String> displayNameCache = new HashMap<>();
        List<Long> replyIds = entities.stream()
                .map(GroupDiscussionMessageEntity::getReplyToId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();

        Map<Long, GroupDiscussionMessageEntity> replyCache = new HashMap<>();
        if (!replyIds.isEmpty()) {
            List<GroupDiscussionMessageEntity> replies = messageMapper.selectList(
                    new LambdaQueryWrapper<GroupDiscussionMessageEntity>()
                            .in(GroupDiscussionMessageEntity::getId, replyIds));
            for (GroupDiscussionMessageEntity reply : replies) {
                replyCache.put(reply.getId(), reply);
            }
        }

        List<DiscussionMessageVO> result = new ArrayList<>(entities.size());
        for (GroupDiscussionMessageEntity entity : entities) {
            String displayName = resolveDisplayName(entity.getUserId(), displayNameCache);
            String replyContent = null;
            String replyName = null;
            if (entity.getReplyToId() != null) {
                GroupDiscussionMessageEntity reply = replyCache.get(entity.getReplyToId());
                if (reply != null) {
                    replyContent = abbreviate(reply.getContent(), REPLY_SUMMARY_LENGTH);
                    replyName = resolveDisplayName(reply.getUserId(), displayNameCache);
                }
            }
            result.add(new DiscussionMessageVO(
                    entity.getId(),
                    entity.getGroupId(),
                    entity.getUserId(),
                    displayName,
                    entity.getContent(),
                    entity.getReplyToId(),
                    replyContent,
                    replyName,
                    entity.getUserId().equals(currentUserId),
                    entity.getCreatedAt()
            ));
        }
        return result;
    }

    /** 带缓存地解析用户显示名 */
    private String resolveDisplayName(Long userId, Map<Long, String> cache) {
        return cache.computeIfAbsent(userId, id -> {
            UserQueryService.UserRecord record = userQueryService.findById(id);
            return record == null ? "已注销用户" : record.displayName();
        });
    }

    /** 截断字符串并追加省略号 */
    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= maxLength
                ? singleLine
                : singleLine.substring(0, maxLength) + "…";
    }

    /** 规范化分页大小 */
    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 50;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }
}
