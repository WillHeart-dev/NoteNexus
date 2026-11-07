package com.notenexus.rag.graph.controller;

import com.notenexus.rag.common.api.ApiResponse;
import com.notenexus.rag.graph.model.dto.BuildGraphRequest;
import com.notenexus.rag.graph.model.vo.GraphTaskVO;
import com.notenexus.rag.graph.model.vo.GraphViewVO;
import com.notenexus.rag.graph.model.vo.LearningPathVO;
import com.notenexus.rag.graph.service.KnowledgeGraphBuildService;
import com.notenexus.rag.graph.service.KnowledgeGraphQueryService;
import com.notenexus.rag.graph.service.LearningPathService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识图谱与学习路径 REST 控制器。
 *
 * <p>提供三类能力：</p>
 * <ul>
 *   <li>构建：从群组资料异步抽取知识点与关系</li>
 *   <li>图谱：读取节点、关系与个人掌握度</li>
 *   <li>路径：按前置关系分阶段输出学习顺序</li>
 * </ul>
 *
 * <p>权限：所有接口都要求调用者是目标群组成员，
 * 由各服务内部统一调用 {@code requireGroupReadable} 校验。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/graph")
public class KnowledgeGraphController {

    private final KnowledgeGraphBuildService buildService;
    private final KnowledgeGraphQueryService queryService;
    private final LearningPathService learningPathService;

    public KnowledgeGraphController(KnowledgeGraphBuildService buildService,
                                    KnowledgeGraphQueryService queryService,
                                    LearningPathService learningPathService) {
        this.buildService = buildService;
        this.queryService = queryService;
        this.learningPathService = learningPathService;
    }

    /**
     * 发起图谱构建。
     *
     * @param request 构建请求
     * @return 新建任务（状态 PENDING）
     */
    @PostMapping("/build")
    public ApiResponse<GraphTaskVO> build(@Valid @RequestBody BuildGraphRequest request) {
        return ApiResponse.success(buildService.createTask(request.groupId()));
    }

    /**
     * 查询构建任务状态。
     *
     * @param taskId 任务 ID
     * @return 任务视图
     */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<GraphTaskVO> task(@PathVariable Long taskId) {
        return ApiResponse.success(buildService.getTask(taskId));
    }

    /**
     * 查询群组最近一次构建任务。
     *
     * @param groupId 群组 ID
     * @return 最近一次任务，从未构建过时 data 为 null
     */
    @GetMapping("/tasks/latest")
    public ApiResponse<GraphTaskVO> latestTask(@RequestParam Long groupId) {
        return ApiResponse.success(buildService.getLatestTask(groupId));
    }

    /**
     * 查询知识图谱。
     *
     * @param groupId 群组 ID
     * @return 图谱视图
     */
    @GetMapping
    public ApiResponse<GraphViewVO> graph(@RequestParam Long groupId) {
        return ApiResponse.success(queryService.getGraph(groupId));
    }

    /**
     * 查询学习路径。
     *
     * @param groupId 群组 ID
     * @return 学习路径视图
     */
    @GetMapping("/learning-path")
    public ApiResponse<LearningPathVO> learningPath(@RequestParam Long groupId) {
        return ApiResponse.success(learningPathService.build(groupId));
    }
}
