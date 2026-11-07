package com.notenexus.rag.exercise.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 习题抽取器：调用大模型从资料原文中抽取结构化习题。
 *
 * <p>核心约束是"有据可查"——提示词要求每道题都附带 {@code sourceSnippet}
 * （原文逐字摘录），并与 RAG 问答共用同一套降低幻觉的思路：
 * 模型只能在给定原文内命题，不得引入外部知识。</p>
 *
 * <p>模型返回的是 JSON 数组，这里负责解析与校验：
 * 结构不合法的单条题目会被丢弃并记录日志，不影响其余题目入库。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class ExerciseExtractionService {

    /** 单次调用允许的最大正文字符数，超出则截断 */
    private static final int MAX_CONTENT_CHARS = 12000;
    /** 合法题型 */
    private static final List<String> VALID_TYPES = List.of(
            "SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER");
    /** 合法难度 */
    private static final List<String> VALID_DIFFICULTIES = List.of("EASY", "MEDIUM", "HARD");

    private final ChatClient chatClient;
    private final PromptTemplate extractPromptTemplate;
    private final ObjectMapper objectMapper;

    public ExerciseExtractionService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.extractPromptTemplate = PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/exercise/extract.st"))
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 从一份资料原文中抽取习题。
     *
     * @param documentName  资料文件名，用于提示词与溯源
     * @param content       资料原文
     * @param questionCount 期望题量
     * @param questionType  期望题型，null 表示由模型自动判断
     * @return 解析后的习题列表，可能为空
     */
    public List<ExtractedQuestion> extract(String documentName, String content,
                                           int questionCount, String questionType) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        String prompt = extractPromptTemplate.render(Map.of(
                "documentName", documentName == null ? "未命名资料" : documentName,
                "questionCount", String.valueOf(questionCount),
                "questionType", StringUtils.hasText(questionType) ? questionType : "自动",
                "content", truncate(content, MAX_CONTENT_CHARS)
        ));

        String raw = chatClient.prompt().user(prompt).call().content();
        return parse(raw);
    }

    /**
     * 解析模型返回的 JSON 数组，丢弃结构不合法的条目。
     *
     * @param raw 模型原始输出
     * @return 合法的习题列表
     */
    private List<ExtractedQuestion> parse(String raw) {
        String json = stripCodeFence(raw);
        if (!StringUtils.hasText(json)) {
            return List.of();
        }

        List<Map<String, Object>> rawItems;
        try {
            rawItems = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("习题抽取结果无法解析为 JSON 数组，长度={}, 错误={}", json.length(), e.getMessage());
            return List.of();
        }

        List<ExtractedQuestion> questions = new ArrayList<>(rawItems.size());
        for (Map<String, Object> item : rawItems) {
            ExtractedQuestion question = toQuestion(item);
            if (question != null) {
                questions.add(question);
            }
        }
        return questions;
    }

    /** 把单条原始 JSON 转成习题对象，结构不合法返回 null */
    private ExtractedQuestion toQuestion(Map<String, Object> item) {
        String stem = asText(item.get("stem"));
        String answer = asText(item.get("answer"));
        if (!StringUtils.hasText(stem) || !StringUtils.hasText(answer)) {
            return null;
        }

        String type = asText(item.get("questionType")).toUpperCase();
        if (!VALID_TYPES.contains(type)) {
            type = "SHORT_ANSWER";
        }

        String difficulty = asText(item.get("difficulty")).toUpperCase();
        if (!VALID_DIFFICULTIES.contains(difficulty)) {
            difficulty = "MEDIUM";
        }

        List<String> options = new ArrayList<>();
        Object rawOptions = item.get("options");
        if (rawOptions instanceof List<?> list) {
            for (Object option : list) {
                String text = asText(option);
                if (StringUtils.hasText(text)) {
                    options.add(text);
                }
            }
        }
        // 选择题必须有选项，否则降级为简答题
        if (("SINGLE_CHOICE".equals(type) || "MULTIPLE_CHOICE".equals(type)) && options.isEmpty()) {
            type = "SHORT_ANSWER";
        }

        List<String> tags = new ArrayList<>();
        Object rawTags = item.get("tags");
        if (rawTags instanceof List<?> list) {
            for (Object tag : list) {
                String text = asText(tag);
                if (StringUtils.hasText(text)) {
                    tags.add(text);
                }
            }
        }

        return new ExtractedQuestion(
                type,
                stem.trim(),
                options,
                answer.trim(),
                asText(item.get("analysis")),
                difficulty,
                tags,
                asText(item.get("sourceSnippet"))
        );
    }

    /** 去掉模型可能包裹的 ```json 代码围栏 */
    private static String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return "";
        }
        String body = text.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        return (closing >= 0 ? body.substring(0, closing) : body).trim();
    }

    /** 安全地把任意值转成字符串 */
    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 截断过长文本 */
    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 抽取出的单条习题（未入库）。
     *
     * @param questionType  题型
     * @param stem          题干
     * @param options       选项
     * @param answer        标准答案
     * @param analysis      解析
     * @param difficulty    难度
     * @param tags          标签
     * @param sourceSnippet 原文依据片段
     * @author NoteNexus Team
     * @since 1.0.0
     */
    public record ExtractedQuestion(
            String questionType,
            String stem,
            List<String> options,
            String answer,
            String analysis,
            String difficulty,
            List<String> tags,
            String sourceSnippet
    ) {
        /** 序列化为入库用的选项 JSON */
        public String optionsJson(ObjectMapper mapper) {
            try {
                return options.isEmpty() ? null : mapper.writeValueAsString(options);
            } catch (Exception e) {
                return null;
            }
        }

        /** 标签拼接为逗号分隔字符串 */
        public String tagsText() {
            return String.join(",", tags);
        }
    }

    /** 供调试使用：把习题列表转成可读的 Map 结构 */
    public Map<String, Object> toDebugMap(ExtractedQuestion question) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stem", question.stem());
        map.put("answer", question.answer());
        map.put("sourceSnippet", question.sourceSnippet());
        return map;
    }
}
