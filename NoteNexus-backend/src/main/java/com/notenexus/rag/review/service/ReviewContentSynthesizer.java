package com.notenexus.rag.review.service;

import com.notenexus.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 复习内容合成器：调用大模型把小组资料整理成统一结构的复习讲义。
 *
 * <p>采用 Map-Reduce 两阶段策略：</p>
 * <ol>
 *   <li><b>Map</b>：逐份资料（必要时分批）提炼结构化笔记，提示词见 {@code prompts/review/map.st}</li>
 *   <li><b>Reduce</b>：把各份笔记合并为六章统一结构的讲义，提示词见 {@code prompts/review/reduce.st}</li>
 * </ol>
 *
 * <p>当笔记总量超过单次上下文预算时，先按资料分组做中间汇总，再做终稿汇总，
 * 保证长资料也能稳定产出。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class ReviewContentSynthesizer {

    /** 单次 Map 调用允许的最大正文字符数 */
    private static final int MAP_CHUNK_CHARS = 9000;
    /** 单次 Reduce 调用允许的最大笔记字符数 */
    private static final int REDUCE_BATCH_CHARS = 24000;
    /** 终稿汇总允许的最大字符数（超出则截断尾部） */
    private static final int FINAL_MAX_CHARS = 40000;

    private final ChatClient chatClient;
    private final PromptTemplate mapPromptTemplate;
    private final PromptTemplate reducePromptTemplate;

    public ReviewContentSynthesizer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.mapPromptTemplate = PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/review/map.st"))
                .build();
        this.reducePromptTemplate = PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/review/reduce.st"))
                .build();
    }

    /**
     * 为单份资料生成结构化笔记。
     *
     * @param documentName 资料文件名
     * @param plainText    资料全文（由切片按顺序拼接）
     * @return Markdown 笔记
     */
    public String buildDocumentNotes(String documentName, String plainText) {
        if (!StringUtils.hasText(plainText)) {
            return "";
        }
        List<String> batches = splitByChars(plainText, MAP_CHUNK_CHARS);
        List<String> notes = new ArrayList<>();
        for (int index = 0; index < batches.size(); index++) {
            String batchName = batches.size() > 1
                    ? documentName + "（第 " + (index + 1) + "/" + batches.size() + " 部分）"
                    : documentName;
            String prompt = mapPromptTemplate.render(Map.of(
                    "documentName", batchName,
                    "content", batches.get(index)
            ));
            log.debug("复习笔记 Map 调用: document={}, batch={}/{}, chars={}",
                    documentName, index + 1, batches.size(), batches.get(index).length());
            notes.add(callModel(prompt));
        }
        return String.join("\n\n", notes);
    }

    /**
     * 把各份资料的笔记合并为一份统一结构的复习讲义。
     *
     * @param groupName 小组名称
     * @param notes     各份资料的笔记（文件名 + 内容）
     * @return Markdown 讲义
     */
    public String buildFinalReview(String groupName, List<SourceNotes> notes) {
        if (notes.isEmpty()) {
            throw new BusinessException("没有可用于生成复习讲义的资料");
        }
        String allNotes = formatNotes(notes);
        if (allNotes.length() <= REDUCE_BATCH_CHARS) {
            return reduce(groupName, notes.size(), allNotes);
        }

        // 超长时先分组做中间汇总，再终稿汇总
        log.info("复习笔记总量 {} 字符，超过单次预算，启用分组中间汇总", allNotes.length());
        List<String> partials = new ArrayList<>();
        for (List<SourceNotes> batch : partitionBySize(notes, REDUCE_BATCH_CHARS)) {
            partials.add(reduce(groupName + "（分册草稿）", batch.size(), formatNotes(batch)));
        }
        String merged = String.join("\n\n", partials);
        return reduce(groupName, notes.size(), truncate(merged, FINAL_MAX_CHARS));
    }

    /** 执行一次终稿/中间汇总调用 */
    private String reduce(String groupName, int documentCount, String notesText) {
        String prompt = reducePromptTemplate.render(Map.of(
                "groupName", groupName,
                "documentCount", documentCount,
                "notes", notesText
        ));
        log.debug("复习讲义 Reduce 调用: group={}, 资料数={}, 笔记字符数={}",
                groupName, documentCount, notesText.length());
        return callModel(prompt);
    }

    /** 调用大模型并校验返回内容 */
    private String callModel(String prompt) {
        try {
            String content = chatClient.prompt().user(prompt).call().content();
            if (!StringUtils.hasText(content)) {
                throw new BusinessException("大模型返回内容为空，复习资料生成失败");
            }
            return content.strip();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("复习资料大模型调用失败", exception);
            throw new BusinessException("复习资料大模型调用失败：" + exception.getMessage(), exception);
        }
    }

    /** 把笔记拼接成带来源标注的文本 */
    private String formatNotes(List<SourceNotes> notes) {
        StringBuilder builder = new StringBuilder();
        for (SourceNotes note : notes) {
            builder.append("### 资料：").append(note.documentName()).append("\n\n")
                    .append(note.notes()).append("\n\n");
        }
        return builder.toString();
    }

    /** 按字符预算把笔记分组，保证每组的文本长度不超过预算 */
    private List<List<SourceNotes>> partitionBySize(List<SourceNotes> notes, int maxChars) {
        List<List<SourceNotes>> batches = new ArrayList<>();
        List<SourceNotes> current = new ArrayList<>();
        int currentSize = 0;
        for (SourceNotes note : notes) {
            int size = note.notes().length() + note.documentName().length() + 32;
            if (!current.isEmpty() && currentSize + size > maxChars) {
                batches.add(current);
                current = new ArrayList<>();
                currentSize = 0;
            }
            current.add(note);
            currentSize += size;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /** 按字符预算切分长文本，尽量在换行处断开 */
    private List<String> splitByChars(String text, int maxChars) {
        List<String> batches = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int lastBreak = text.lastIndexOf('\n', end);
                if (lastBreak > start + maxChars / 2) {
                    end = lastBreak;
                }
            }
            batches.add(text.substring(start, end));
            start = end;
        }
        if (batches.isEmpty()) {
            batches.add("");
        }
        return batches;
    }

    /** 截断文本，避免超过上下文预算 */
    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    /**
     * 单份资料的笔记。
     *
     * @param documentName 资料文件名
     * @param notes        结构化笔记
     */
    public record SourceNotes(String documentName, String notes) {
    }
}
