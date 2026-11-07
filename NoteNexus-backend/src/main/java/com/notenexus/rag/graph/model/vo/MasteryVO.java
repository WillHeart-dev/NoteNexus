package com.notenexus.rag.graph.model.vo;

/**
 * 知识点掌握度视图。
 *
 * @param level         掌握度档位：UNTOUCHED | WEAK | MEDIUM | STRONG
 * @param accuracy      正确率，未作答为 null
 * @param questionCount 关联习题数
 * @author NoteNexus Team
 * @since 1.0.0
 */
public record MasteryVO(String level, Double accuracy, int questionCount) {

    /** 尚未出过相关习题 */
    public static MasteryVO untouched() {
        return new MasteryVO("UNTOUCHED", null, 0);
    }
}
