package com.notenexus.rag.exercise.controller;

import com.notenexus.rag.common.api.ApiResponse;
import com.notenexus.rag.exercise.model.dto.ExerciseQuery;
import com.notenexus.rag.exercise.model.dto.GenerateExerciseRequest;
import com.notenexus.rag.exercise.model.dto.SubmitAnswerRequest;
import com.notenexus.rag.exercise.model.vo.ExerciseQuestionVO;
import com.notenexus.rag.exercise.model.vo.ExerciseStatsVO;
import com.notenexus.rag.exercise.model.vo.SubmitAnswerVO;
import com.notenexus.rag.exercise.service.ExerciseService;
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
import java.util.Map;

/**
 * 习题库 REST 控制器。
 *
 * <p>提供三类接口：</p>
 * <ul>
 *   <li>生成：从群组资料中抽取习题入库</li>
 *   <li>刷题：按条件筛选题目、提交答案并即时判定</li>
 *   <li>统计：题量、已答数与正确率</li>
 * </ul>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/exercises")
public class ExerciseController {

    private final ExerciseService exerciseService;

    public ExerciseController(ExerciseService exerciseService) {
        this.exerciseService = exerciseService;
    }

    /**
     * 从群组资料中抽取习题并入库。
     *
     * @param request 生成请求
     * @return 实际入库的题目数量
     */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Integer>> generate(@Valid @RequestBody GenerateExerciseRequest request) {
        int inserted = exerciseService.generate(request);
        return ApiResponse.success(Map.of("inserted", inserted));
    }

    /**
     * 按条件查询习题列表。
     *
     * @param groupId      群组 ID
     * @param questionType 题型筛选，可为空
     * @param difficulty   难度筛选，可为空
     * @param onlyWrong    是否只看错题
     * @param keyword      题干关键词搜索，可为空
     * @return 习题列表
     */
    @GetMapping
    public ApiResponse<List<ExerciseQuestionVO>> list(@RequestParam Long groupId,
                                                      @RequestParam(required = false) String questionType,
                                                      @RequestParam(required = false) String difficulty,
                                                      @RequestParam(required = false) Boolean onlyWrong,
                                                      @RequestParam(required = false) String keyword) {
        ExerciseQuery query = new ExerciseQuery(groupId, questionType, difficulty, onlyWrong, keyword);
        return ApiResponse.success(exerciseService.list(query));
    }

    /**
     * 提交答案并即时判定。
     *
     * @param request 作答请求
     * @return 判定结果
     */
    @PostMapping("/submit")
    public ApiResponse<SubmitAnswerVO> submit(@Valid @RequestBody SubmitAnswerRequest request) {
        return ApiResponse.success(exerciseService.submit(request));
    }

    /**
     * 查询习题库统计概览。
     *
     * @param groupId 群组 ID
     * @return 统计结果
     */
    @GetMapping("/stats")
    public ApiResponse<ExerciseStatsVO> stats(@RequestParam Long groupId) {
        return ApiResponse.success(exerciseService.stats(groupId));
    }

    /**
     * 删除一道习题（仅群主）。
     *
     * @param questionId 习题 ID
     * @return 空响应
     */
    @DeleteMapping("/{questionId}")
    public ApiResponse<Void> delete(@PathVariable Long questionId) {
        exerciseService.delete(questionId);
        return ApiResponse.success(null);
    }
}
