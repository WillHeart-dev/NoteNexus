package com.notenexus.rag.common.enums;

/**
 * 知识图谱构建任务状态。
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
public enum GraphBuildStatus {

    /** 已创建，等待执行 */
    PENDING,
    /** 正在抽取与入库 */
    GENERATING,
    /** 构建完成 */
    COMPLETED,
    /** 构建失败 */
    FAILED
}
