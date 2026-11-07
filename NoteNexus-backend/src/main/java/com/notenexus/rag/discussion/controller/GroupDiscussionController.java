package com.notenexus.rag.discussion.controller;

import com.notenexus.rag.common.api.ApiResponse;
import com.notenexus.rag.discussion.model.dto.CreateDiscussionMessageRequest;
import com.notenexus.rag.discussion.model.vo.DiscussionMessageVO;
import com.notenexus.rag.discussion.service.GroupDiscussionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 群组讨论 REST 控制器。
 *
 * <p>提供知识库评论（按群组存储）的发布、倒序分页查询与撤回接口。
 * 所有接口均校验调用者是目标群组成员，非成员一律拒绝。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/discussions")
public class GroupDiscussionController {

    private final GroupDiscussionService groupDiscussionService;

    public GroupDiscussionController(GroupDiscussionService groupDiscussionService) {
        this.groupDiscussionService = groupDiscussionService;
    }

    /**
     * 查询知识库评论（按时间倒序）。
     *
     * @param groupId  群组 ID
     * @param beforeId 游标，只返回 ID 小于该值的消息，为空则从最新开始
     * @param limit    返回条数，默认 50，上限 200
     * @return 消息列表
     */
    @GetMapping
    public ApiResponse<List<DiscussionMessageVO>> list(@RequestParam Long groupId,
                                                       @RequestParam(required = false) Long beforeId,
                                                       @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(groupDiscussionService.list(groupId, beforeId, limit));
    }

    /**
     * 发布一条知识库评论。
     *
     * @param request 发送请求
     * @return 新建的消息
     */
    @PostMapping
    public ApiResponse<DiscussionMessageVO> send(@Valid @RequestBody CreateDiscussionMessageRequest request) {
        return ApiResponse.success(groupDiscussionService.send(request));
    }

    /**
     * 撤回自己发送的消息。
     *
     * @param messageId 消息 ID
     * @return 空响应
     */
    @DeleteMapping("/{messageId}")
    public ApiResponse<Void> revoke(@PathVariable Long messageId) {
        groupDiscussionService.revoke(messageId);
        return ApiResponse.success(null);
    }
}
