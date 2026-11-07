package com.notenexus.rag.review.service;

import com.notenexus.rag.common.enums.ReviewDocumentStatus;
import com.notenexus.rag.common.exception.BusinessException;
import com.notenexus.rag.document.model.entity.DocumentEntity;
import com.notenexus.rag.engine.storage.ObjectStorageService;
import com.notenexus.rag.group.mapper.GroupMembershipMapper;
import com.notenexus.rag.group.model.entity.Group;
import com.notenexus.rag.ingestion.mapper.DocumentChunkMapper;
import com.notenexus.rag.ingestion.model.entity.DocumentChunkEntity;
import com.notenexus.rag.review.mapper.ReviewDocumentMapper;
import com.notenexus.rag.review.markdown.MarkdownReviewDocumentBuilder;
import com.notenexus.rag.review.model.entity.ReviewDocumentEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 复习文档生成服务：异步执行「汇总资料 → 大模型整理 → 组装 Markdown → 存对象存储」全流程。
 *
 * <p>由 {@link ReviewDocumentService#create(Long)} 发布事件触发，在事务提交后异步执行，
 * 全程使用短事务更新状态，避免长时间持有数据库连接。</p>
 *
 * <p>状态机：{@code PENDING → GENERATING → COMPLETED | FAILED}，
 * 通过条件更新（仅当状态仍为 PENDING 时）保证同一条记录不会被重复生成。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class ReviewGenerationService {

    /** 切片拼接分隔符，供大模型识别资料片段边界 */
    private static final String CHUNK_SEPARATOR = "\n\n=== \n\n";
    /** 失败原因最大保存长度 */
    private static final int MAX_FAILURE_REASON_LENGTH = 4000;
    /** Markdown 源码最大保存长度 */
    private static final int MAX_MARKDOWN_SOURCE_LENGTH = 4_000_000;
    /** 复习文档对象键日期格式 */
    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 复习文档 MIME 类型 */
    private static final String MARKDOWN_CONTENT_TYPE = "text/markdown;charset=UTF-8";
    /** 文件名中的非法字符 */
    private static final String ILLEGAL_FILE_NAME_CHARS = "[\\\\/:*?\"<>|\\r\\n]";

    private final ReviewDocumentMapper reviewDocumentMapper;
    private final ReviewDocumentService reviewDocumentService;
    private final DocumentChunkMapper documentChunkMapper;
    private final GroupMembershipMapper groupMembershipMapper;
    private final ReviewContentSynthesizer synthesizer;
    private final MarkdownReviewDocumentBuilder markdownDocumentBuilder;
    private final ObjectStorageService objectStorageService;

    public ReviewGenerationService(
            ReviewDocumentMapper reviewDocumentMapper,
            ReviewDocumentService reviewDocumentService,
            DocumentChunkMapper documentChunkMapper,
            GroupMembershipMapper groupMembershipMapper,
            ReviewContentSynthesizer synthesizer,
            MarkdownReviewDocumentBuilder markdownDocumentBuilder,
            ObjectStorageService objectStorageService
    ) {
        this.reviewDocumentMapper = reviewDocumentMapper;
        this.reviewDocumentService = reviewDocumentService;
        this.documentChunkMapper = documentChunkMapper;
        this.groupMembershipMapper = groupMembershipMapper;
        this.synthesizer = synthesizer;
        this.markdownDocumentBuilder = markdownDocumentBuilder;
        this.objectStorageService = objectStorageService;
    }

    /**
     * 监听生成请求事件，事务提交后异步执行生成。
     *
     * @param event 生成请求事件
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleGenerationRequested(ReviewGenerationRequestedEvent event) {
        generate(event.reviewId(), event.groupId());
    }

    /**
     * 执行复习文档生成。
     *
     * @param reviewId 复习文档 ID
     * @param groupId  学习小组 ID
     */
    public void generate(Long reviewId, Long groupId) {
        ReviewDocumentEntity entity = reviewDocumentMapper.selectById(reviewId);
        if (entity == null) {
            log.warn("复习文档不存在，跳过生成: reviewId={}", reviewId);
            return;
        }
        if (!markGenerating(reviewId)) {
            log.info("复习文档已被其它任务处理，跳过: reviewId={}", reviewId);
            return;
        }

        long startMillis = System.currentTimeMillis();
        try {
            Group group = groupMembershipMapper.selectById(groupId);
            String groupName = group == null ? "学习小组" : group.getGroupName();

            List<DocumentEntity> documents = reviewDocumentService.listReadyDocuments(groupId);
            if (documents.isEmpty()) {
                throw new BusinessException("该小组没有已就绪的学习资料");
            }

            // 阶段一：逐份资料提炼笔记
            List<ReviewContentSynthesizer.SourceNotes> notes = new ArrayList<>();
            List<String> documentNames = new ArrayList<>();
            int totalChunks = 0;
            for (DocumentEntity document : documents) {
                List<DocumentChunkEntity> chunks = selectChunks(document.getId());
                if (chunks.isEmpty()) {
                    log.warn("资料无可用切片，跳过: documentId={}, fileName={}",
                            document.getId(), document.getFileName());
                    continue;
                }
                String plainText = joinChunks(chunks);
                totalChunks += chunks.size();
                documentNames.add(document.getFileName());
                log.info("生成复习笔记: documentId={}, fileName={}, chunks={}, chars={}",
                        document.getId(), document.getFileName(), chunks.size(), plainText.length());
                String note = synthesizer.buildDocumentNotes(document.getFileName(), plainText);
                if (!note.isBlank()) {
                    notes.add(new ReviewContentSynthesizer.SourceNotes(document.getFileName(), note));
                }
            }
            if (notes.isEmpty()) {
                throw new BusinessException("资料内容为空，无法生成复习讲义");
            }

            // 阶段二：合并为统一结构讲义
            String bodyMarkdown = synthesizer.buildFinalReview(groupName, notes);

            // 阶段三：组装 Markdown 文档（标题 + 资料头信息 + 六章正文）
            String document = markdownDocumentBuilder.build(
                    entity.getTitle(), groupName, documentNames, bodyMarkdown);
            byte[] contentBytes = document.getBytes(StandardCharsets.UTF_8);

            // 阶段四：上传对象存储
            String fileName = buildFileName(groupName, reviewId);
            String bucket = objectStorageService.getDefaultBucket();
            String objectKey = "review/" + groupId + "/" + reviewId + "-"
                    + LocalDate.now().format(FILE_DATE_FORMATTER) + ".md";
            objectStorageService.putObject(bucket, objectKey,
                    new ByteArrayInputStream(contentBytes), contentBytes.length, MARKDOWN_CONTENT_TYPE);

            // 阶段五：回写结果
            markCompleted(reviewId, fileName, contentBytes.length, bucket, objectKey,
                    document, documentNames.size(), totalChunks);
            log.info("复习文档生成完成: reviewId={}, markdownSize={}字节, 资料数={}, 切片数={}, 耗时={}ms",
                    reviewId, contentBytes.length, documentNames.size(), totalChunks,
                    System.currentTimeMillis() - startMillis);
        } catch (Exception exception) {
            log.error("复习文档生成失败: reviewId={}, groupId={}", reviewId, groupId, exception);
            markFailed(reviewId, exception);
        }
    }

    /** 条件更新为 GENERATING，返回是否抢到执行权 */
    private boolean markGenerating(Long reviewId) {
        LambdaUpdateWrapper<ReviewDocumentEntity> wrapper = new LambdaUpdateWrapper<ReviewDocumentEntity>()
                .eq(ReviewDocumentEntity::getId, reviewId)
                .eq(ReviewDocumentEntity::getStatus, ReviewDocumentStatus.PENDING.name())
                .eq(ReviewDocumentEntity::getDeleted, false)
                .set(ReviewDocumentEntity::getStatus, ReviewDocumentStatus.GENERATING.name())
                .set(ReviewDocumentEntity::getStartedAt, LocalDateTime.now());
        return reviewDocumentMapper.update(null, wrapper) > 0;
    }

    /** 回写生成成功结果 */
    private void markCompleted(Long reviewId, String fileName, long fileSize, String bucket,
                               String objectKey, String markdownSource, int documentCount, int chunkCount) {
        LambdaUpdateWrapper<ReviewDocumentEntity> wrapper = new LambdaUpdateWrapper<ReviewDocumentEntity>()
                .eq(ReviewDocumentEntity::getId, reviewId)
                .set(ReviewDocumentEntity::getStatus, ReviewDocumentStatus.COMPLETED.name())
                .set(ReviewDocumentEntity::getFileName, fileName)
                .set(ReviewDocumentEntity::getFileSize, fileSize)
                .set(ReviewDocumentEntity::getStorageBucket, bucket)
                .set(ReviewDocumentEntity::getStorageObjectKey, objectKey)
                .set(ReviewDocumentEntity::getMarkdownSource,
                        truncate(markdownSource, MAX_MARKDOWN_SOURCE_LENGTH))
                .set(ReviewDocumentEntity::getSourceDocumentCount, documentCount)
                .set(ReviewDocumentEntity::getChunkCount, chunkCount)
                .set(ReviewDocumentEntity::getCompletedAt, LocalDateTime.now())
                .set(ReviewDocumentEntity::getFailureReason, null);
        reviewDocumentMapper.update(null, wrapper);
    }

    /** 回写生成失败原因 */
    private void markFailed(Long reviewId, Exception exception) {
        String reason = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        LambdaUpdateWrapper<ReviewDocumentEntity> wrapper = new LambdaUpdateWrapper<ReviewDocumentEntity>()
                .eq(ReviewDocumentEntity::getId, reviewId)
                .set(ReviewDocumentEntity::getStatus, ReviewDocumentStatus.FAILED.name())
                .set(ReviewDocumentEntity::getFailureReason, truncate(reason, MAX_FAILURE_REASON_LENGTH))
                .set(ReviewDocumentEntity::getCompletedAt, LocalDateTime.now());
        reviewDocumentMapper.update(null, wrapper);
    }

    /** 查询资料的全部切片，按切片序号升序 */
    private List<DocumentChunkEntity> selectChunks(Long documentId) {
        LambdaQueryWrapper<DocumentChunkEntity> wrapper = new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, documentId)
                .orderByAsc(DocumentChunkEntity::getChunkIndex);
        return documentChunkMapper.selectList(wrapper);
    }

    /** 按顺序拼接切片文本 */
    private String joinChunks(List<DocumentChunkEntity> chunks) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < chunks.size(); index++) {
            if (index > 0) {
                builder.append(CHUNK_SEPARATOR);
            }
            builder.append(chunks.get(index).getChunkText());
        }
        return builder.toString();
    }

    /** 构建复习文档文件名（去掉文件系统非法字符） */
    private String buildFileName(String groupName, Long reviewId) {
        String safeGroupName = groupName == null ? "" : groupName.replaceAll(ILLEGAL_FILE_NAME_CHARS, "_");
        return safeGroupName + "-复习资料-" + reviewId + ".md";
    }

    /** 截断超长文本 */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
