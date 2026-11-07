package com.notenexus.rag.review.service;

/**
 * 复习文档生成请求事件。
 *
 * <p>在复习文档记录落库事务提交后发布，由异步监听器接收并执行生成流程，
 * 避免长耗时的大模型汇总与 LaTeX 编译阻塞 HTTP 请求。</p>
 *
 * @param reviewId 复习文档 ID
 * @param groupId  所属学习小组 ID
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record ReviewGenerationRequestedEvent(Long reviewId, Long groupId) {
}
