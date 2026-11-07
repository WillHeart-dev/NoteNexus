package com.notenexus.rag.review.controller;

import com.notenexus.rag.common.api.ApiResponse;
import com.notenexus.rag.review.model.dto.CreateReviewDocumentRequest;
import com.notenexus.rag.review.model.vo.ReviewDocumentVO;
import com.notenexus.rag.review.service.ReviewDocumentService;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 复习文档 REST 控制器，提供 AI 复习讲义的生成、查询、下载与删除接口。
 *
 * <p>生成采用异步方式：创建接口立即返回 PENDING 状态，
 * 前端轮询查询接口获取进度，完成后调用下载接口获取 Markdown 文档。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/review-documents")
public class ReviewDocumentController {

    /** 下载内容类型：Markdown 文本 */
    private static final MediaType MARKDOWN_MEDIA_TYPE =
            MediaType.parseMediaType("text/markdown;charset=UTF-8");

    private final ReviewDocumentService reviewDocumentService;

    public ReviewDocumentController(ReviewDocumentService reviewDocumentService) {
        this.reviewDocumentService = reviewDocumentService;
    }

    /**
     * 为指定学习小组生成复习文档（异步）。
     *
     * @param request 创建请求，含小组 ID
     * @return 新建的复习文档（状态 PENDING）
     */
    @PostMapping
    public ApiResponse<ReviewDocumentVO> create(@Valid @RequestBody CreateReviewDocumentRequest request) {
        return ApiResponse.success(reviewDocumentService.create(request.groupId()));
    }

    /**
     * 查询学习小组的复习文档列表。
     *
     * @param groupId 学习小组 ID
     * @return 复习文档列表
     */
    @GetMapping
    public ApiResponse<List<ReviewDocumentVO>> list(@RequestParam Long groupId) {
        return ApiResponse.success(reviewDocumentService.list(groupId));
    }

    /**
     * 查询单份复习文档（供前端轮询生成进度）。
     *
     * @param reviewId 复习文档 ID
     * @return 复习文档详情
     */
    @GetMapping("/{reviewId}")
    public ApiResponse<ReviewDocumentVO> get(@PathVariable Long reviewId) {
        return ApiResponse.success(reviewDocumentService.get(reviewId));
    }

    /**
     * 下载复习文档（Markdown）。
     *
     * @param reviewId 复习文档 ID
     * @return Markdown 文本流
     */
    @GetMapping("/{reviewId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long reviewId) {
        ReviewDocumentService.ReviewDownloadPayload payload = reviewDocumentService.download(reviewId);
        String encodedName = URLEncoder.encode(payload.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MARKDOWN_MEDIA_TYPE)
                .contentLength(payload.fileSize() == null ? -1 : payload.fileSize())
                .body(new InputStreamResource(payload.inputStream()));
    }

    /**
     * 删除复习文档。
     *
     * @param reviewId 复习文档 ID
     * @return 空响应
     */
    @DeleteMapping("/{reviewId}")
    public ApiResponse<Void> delete(@PathVariable Long reviewId) {
        reviewDocumentService.delete(reviewId);
        return ApiResponse.success(null);
    }
}
