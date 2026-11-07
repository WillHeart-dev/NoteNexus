package com.notenexus.rag.review.markdown;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 复习讲义 Markdown 文档组装器。
 *
 * <p>把大模型产出的正文（受控 Markdown，固定六章）与文档头信息拼装成一份
 * 可直接用 Typora / Obsidian / VS Code 打开阅读的 {@code .md} 文件：</p>
 *
 * <ol>
 *   <li>一级标题使用讲义标题</li>
 *   <li>引用块给出学习小组、资料来源数与生成时间，并罗列参与汇总的资料文件名</li>
 *   <li>正文保持模型输出的六章结构（{@code ## 一、复习导览} 起）</li>
 * </ol>
 *
 * <p>不再经过 LaTeX 排版与 PDF 编译，避免依赖外部 TeX 进程，生成结果为纯文本，
 * 便于学生二次编辑、复制到笔记软件或导入其它工具。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Component
public class MarkdownReviewDocumentBuilder {

    /** 文档头时间格式 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** Markdown 代码围栏标记 */
    private static final String CODE_FENCE = "```";
    /** 默认小组名 */
    private static final String DEFAULT_GROUP_NAME = "学习小组";

    /**
     * 组装完整的 Markdown 复习讲义。
     *
     * @param title         讲义标题
     * @param groupName     学习小组名称
     * @param documentNames 参与汇总的资料文件名
     * @param bodyMarkdown  大模型产出的六章正文
     * @return 完整 Markdown 文本
     */
    public String build(String title, String groupName, List<String> documentNames, String bodyMarkdown) {
        List<String> names = documentNames == null ? List.of() : documentNames;
        StringBuilder builder = new StringBuilder(1024 + (bodyMarkdown == null ? 0 : bodyMarkdown.length()));

        builder.append("# ").append(resolveTitle(title)).append("\n\n");
        builder.append("> **学习小组**：").append(resolveGroupName(groupName))
                .append("　·　**资料来源**：").append(names.size()).append(" 份")
                .append("　·　**生成时间**：").append(LocalDateTime.now().format(TIME_FORMATTER))
                .append('\n');
        builder.append(">\n");
        builder.append("> 本讲义由 NoteNexus 汇总小组学习资料自动整理，内容全部来自下列资料，")
                .append("请对照原文复习；标注「来源：文件名」处可回查原资料。\n");
        if (!names.isEmpty()) {
            builder.append(">\n");
            builder.append("> 资料来源：").append(String.join("、", names)).append('\n');
        }
        builder.append("\n---\n\n");
        builder.append(sanitizeBody(bodyMarkdown));
        builder.append('\n');
        return builder.toString();
    }

    /**
     * 清洗正文：去掉模型偶尔包裹的代码围栏，统一换行符。
     *
     * @param body 模型原始输出
     * @return 可直接拼入文档的 Markdown 正文
     */
    private String sanitizeBody(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String text = body.strip();
        if (isFenced(text)) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1, text.length() - CODE_FENCE.length()).strip();
            }
        }
        return text.replace("\r\n", "\n");
    }

    /** 判断文本是否被单个 ``` 围栏整体包裹（而非正文内自带代码块） */
    private boolean isFenced(String text) {
        if (!text.startsWith(CODE_FENCE) || !text.endsWith(CODE_FENCE)
                || text.length() <= CODE_FENCE.length() * 2) {
            return false;
        }
        String firstLine = text.substring(0, text.indexOf('\n') > 0 ? text.indexOf('\n') : text.length());
        // 首行只能是 ``` 或 ```markdown / ```md 这类语言标记
        String language = firstLine.substring(CODE_FENCE.length()).trim();
        return language.isEmpty() || "markdown".equalsIgnoreCase(language) || "md".equalsIgnoreCase(language);
    }

    /** 标题缺失时回退 */
    private String resolveTitle(String title) {
        return StringUtils.hasText(title) ? title.strip() : "复习资料";
    }

    /** 小组名缺失时回退 */
    private String resolveGroupName(String groupName) {
        return StringUtils.hasText(groupName) ? groupName.strip() : DEFAULT_GROUP_NAME;
    }
}
