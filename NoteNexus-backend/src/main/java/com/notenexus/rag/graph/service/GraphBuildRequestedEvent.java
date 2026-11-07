package com.notenexus.rag.graph.service;

/**
 * 知识图谱构建请求事件。
 *
 * <p>在构建任务落库事务提交后发布，由异步监听器接收并执行抽取流程，
 * 避免逐份资料调用大模型阻塞 HTTP 请求。</p>
 *
 * @param taskId  构建任务 ID
 * @param groupId 群组 ID
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record GraphBuildRequestedEvent(Long taskId, Long groupId) {
}
