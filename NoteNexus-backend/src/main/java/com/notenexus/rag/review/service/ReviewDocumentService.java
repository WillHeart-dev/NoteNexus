package com.notenexus.rag.review.service;

import com.notenexus.rag.auth.CurrentUserService;
import com.notenexus.rag.common.enums.ReviewDocumentStatus;
import com.notenexus.rag.common.exception.BusinessException;
import com.notenexus.rag.document.mapper.DocumentMapper;
import com.notenexus.rag.document.model.entity.DocumentEntity;
import com.notenexus.rag.engine.storage.ObjectStorageService;
import com.notenexus.rag.group.mapper.GroupMembershipMapper;
import com.notenexus.rag.group.model.entity.Group;
import com.notenexus.rag.group.service.GroupMembershipService;
import com.notenexus.rag.review.mapper.ReviewDocumentMapper;
import com.notenexus.rag.review.model.entity.ReviewDocumentEntity;
import com.notenexus.rag.review.model.vo.ReviewDocumentVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 复习文档服务：负责复习文档的创建、查询、下载与删除。
 *
 * <p>创建时只落库并发布异步生成事件，真正的资料汇总、大模型整理与 Markdown 组装
 * 由 {@link ReviewGenerationService} 在事务提交后异步完成，避免阻塞请求。</p>
 *
 * <p>权限约定：小组成员（含 OWNER 与 MEMBER）均可生成、下载、删除复习文档，
 * 均需通过 {@link GroupMembershipService#requireGroupReadable(Long)} 校验。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class ReviewDocumentService {

    /** 文档就绪状态 */
    private static final String DOCUMENT_STATUS_READY = "READY";

    private final ReviewDocumentMapper reviewDocumentMapper;
    private final DocumentMapper documentMapper;
    private final GroupMembershipMapper groupMembershipMapper;
    private final GroupMembershipService groupMembershipService;
    private final CurrentUserService currentUserService;
    private final ObjectStorageService objectStorageService;
    private final ApplicationEventPublisher eventPublisher;

    public ReviewDocumentService(
            ReviewDocumentMapper reviewDocumentMapper,
            DocumentMapper documentMapper,
            GroupMembershipMapper groupMembershipMapper,
            GroupMembershipService groupMembershipService,
            CurrentUserService currentUserService,
            ObjectStorageService objectStorageService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.reviewDocumentMapper = reviewDocumentMapper;
        this.documentMapper = documentMapper;
        this.groupMembershipMapper = groupMembershipMapper;
        this.groupMembershipService = groupMembershipService;
        this.currentUserService = currentUserService;
        this.objectStorageService = objectStorageService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 为指定小组创建一份复习文档，并触发异步生成。
     *
     * @param groupId 学习小组 ID
     * @return 新建的复习文档（状态 PENDING）
     */
    @Transactional
    public ReviewDocumentVO create(Long groupId) {
        requireGroupId(groupId);
        CurrentUserService.CurrentUser currentUser = currentUserService.requireBusinessUser();
        groupMembershipService.requireGroupReadable(groupId);

        List<DocumentEntity> readyDocuments = listReadyDocuments(groupId);
        if (readyDocuments.isEmpty()) {
            throw new BusinessException("该小组还没有已就绪的学习资料，请先上传资料并等待处理完成");
        }

        ReviewDocumentEntity entity = new ReviewDocumentEntity();
        entity.setGroupId(groupId);
        entity.setTitle(resolveTitle(groupId));
        entity.setStatus(ReviewDocumentStatus.PENDING.name());
        entity.setSourceDocumentCount(readyDocuments.size());
        entity.setSourceDocumentNames(toDocumentNamesJson(readyDocuments));
        entity.setChunkCount(0);
        entity.setFileName("");
        entity.setFileSize(0L);
        entity.setStorageBucket("");
        entity.setStorageObjectKey("");
        entity.setCreatedBy(currentUser.userId());
        entity.setDeleted(false);
        reviewDocumentMapper.insert(entity);

        log.info("复习文档已创建: reviewId={}, groupId={}, 资料数={}, 触发人={}",
                entity.getId(), groupId, readyDocuments.size(), currentUser.userId());
        eventPublisher.publishEvent(new ReviewGenerationRequestedEvent(entity.getId(), groupId));
        return ReviewDocumentVO.from(entity);
    }

    /**
     * 查询指定小组的复习文档列表（按创建时间倒序）。
     *
     * @param groupId 学习小组 ID
     * @return 复习文档列表
     */
    public List<ReviewDocumentVO> list(Long groupId) {
        requireGroupId(groupId);
        currentUserService.requireBusinessUser();
        groupMembershipService.requireGroupReadable(groupId);
        return selectByGroup(groupId).stream().map(ReviewDocumentVO::from).toList();
    }

    /**
     * 查询单份复习文档（供前端轮询生成进度）。
     *
     * @param reviewId 复习文档 ID
     * @return 复习文档视图对象
     */
    public ReviewDocumentVO get(Long reviewId) {
        ReviewDocumentEntity entity = requireReadable(reviewId);
        return ReviewDocumentVO.from(entity);
    }

    /**
     * 删除复习文档：软删除记录并清理对象存储中的 Markdown 文件。
     *
     * @param reviewId 复习文档 ID
     */
    @Transactional
    public void delete(Long reviewId) {
        ReviewDocumentEntity entity = requireReadable(reviewId);
        entity.setDeleted(true);
        reviewDocumentMapper.updateById(entity);
        if (StringUtils.hasText(entity.getStorageBucket())
                && StringUtils.hasText(entity.getStorageObjectKey())) {
            try {
                objectStorageService.deleteObject(entity.getStorageBucket(), entity.getStorageObjectKey());
            } catch (Exception exception) {
                log.warn("复习文档 Markdown 清理失败（记录已软删除）: reviewId={}, objectKey={}",
                        reviewId, entity.getStorageObjectKey(), exception);
            }
        }
        log.info("复习文档已删除: reviewId={}", reviewId);
    }

    /**
     * 获取复习文档下载数据，需小组成员权限且生成已完成。
     *
     * @param reviewId 复习文档 ID
     * @return 下载数据（流、文件名、大小）
     */
    public ReviewDownloadPayload download(Long reviewId) {
        ReviewDocumentEntity entity = requireReadable(reviewId);
        if (!ReviewDocumentStatus.COMPLETED.name().equals(entity.getStatus())) {
            throw new BusinessException("复习文档尚未生成完成，暂不可下载");
        }
        if (!StringUtils.hasText(entity.getStorageBucket())
                || !StringUtils.hasText(entity.getStorageObjectKey())) {
            throw new BusinessException("复习文档存储信息缺失");
        }
        InputStream inputStream = objectStorageService.getObject(
                entity.getStorageBucket(), entity.getStorageObjectKey());
        return new ReviewDownloadPayload(inputStream, entity.getFileName(), entity.getFileSize());
    }

    /**
     * 查询小组下全部已就绪且未删除的资料，按上传先后排序。
     *
     * @param groupId 学习小组 ID
     * @return 资料列表
     */
    public List<DocumentEntity> listReadyDocuments(Long groupId) {
        LambdaQueryWrapper<DocumentEntity> wrapper = new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getGroupId, groupId)
                .eq(DocumentEntity::getStatus, DOCUMENT_STATUS_READY)
                .eq(DocumentEntity::getDeleted, false)
                .orderByAsc(DocumentEntity::getId);
        return documentMapper.selectList(wrapper);
    }

    /** 查询小组下的复习文档，过滤软删除记录 */
    private List<ReviewDocumentEntity> selectByGroup(Long groupId) {
        LambdaQueryWrapper<ReviewDocumentEntity> wrapper = new LambdaQueryWrapper<ReviewDocumentEntity>()
                .eq(ReviewDocumentEntity::getGroupId, groupId)
                .eq(ReviewDocumentEntity::getDeleted, false)
                .orderByDesc(ReviewDocumentEntity::getId);
        return reviewDocumentMapper.selectList(wrapper);
    }

    /** 校验复习文档存在、未删除且当前用户可读 */
    private ReviewDocumentEntity requireReadable(Long reviewId) {
        if (reviewId == null || reviewId <= 0) {
            throw new BusinessException("复习文档ID非法");
        }
        ReviewDocumentEntity entity = reviewDocumentMapper.selectById(reviewId);
        if (entity == null || Boolean.TRUE.equals(entity.getDeleted())) {
            throw new BusinessException("复习文档不存在或已删除");
        }
        currentUserService.requireBusinessUser();
        groupMembershipService.requireGroupReadable(entity.getGroupId());
        return entity;
    }

    /** 生成复习文档标题：小组名 + 复习资料 */
    private String resolveTitle(Long groupId) {
        Group group = groupMembershipMapper.selectById(groupId);
        String groupName = group == null ? "学习小组" : group.getGroupName();
        return groupName + " · 复习资料";
    }

    /** 将资料文件名序列化为 JSON 数组字符串 */
    private String toDocumentNamesJson(List<DocumentEntity> documents) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < documents.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(escapeJson(documents.get(index).getFileName())).append('"');
        }
        return builder.append(']').toString();
    }

    /** JSON 字符串转义 */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 校验 groupId 合法性 */
    private void requireGroupId(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new BusinessException("groupId 非法");
        }
    }

    /**
     * 复习文档下载载荷。
     *
     * @param inputStream Markdown 输入流
     * @param fileName    文件名
     * @param fileSize    文件大小
     */
    public record ReviewDownloadPayload(InputStream inputStream, String fileName, Long fileSize) {
    }
}
