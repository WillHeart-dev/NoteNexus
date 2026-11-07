package com.notenexus.rag.common.enums;

/**
 * 复习文档生成状态。
 *
 * <p>状态流转：{@code PENDING → GENERATING → COMPLETED | FAILED}。
 * 与数据库 {@code review_documents.status} 字段对应，以枚举 {@code name()} 存为 VARCHAR。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
public enum ReviewDocumentStatus {

    /** 已创建，等待异步生成 */
    PENDING,

    /** 正在汇总资料并生成 Markdown 文档 */
    GENERATING,

    /** 生成成功，可下载 */
    COMPLETED,

    /** 生成失败，可查看原因并重新生成 */
    FAILED
}
