package com.notenexus.rag.graph.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱抽取器：调用大模型从单份资料中抽取知识点与关系。
 *
 * <p>与习题抽取同源的设计约束是"有据可查"——每条关系都要求附带原文摘录
 * （{@code evidence}），模型只能在给定原文范围内建立关系，不得引入外部知识。</p>
 *
 * <p>模型输出为 JSON 对象（{@code nodes} + {@code edges}），这里做结构校验：
 * 名称为空、重要度越界、关系类型非法的条目会被丢弃或纠正，不影响其余条目。</p>
 *
 * @author NoteNexus Team
 * @since 1.0.0
 */
@Slf4j
@Service
public class GraphExtractionService {

    /** 单次送入模型的最大正文字符数 */
    private static final int MAX_CONTENT_CHARS = 10000;
    /** 单份资料允许的最大关系数 */
    private static final int MAX_EDGES_PER_DOCUMENT = 24;
    /** 合法的关系类型 */
    private static final Set<String> VALID_RELATIONS =
            new LinkedHashSet<>(List.of("PREREQUISITE_OF", "PART_OF", "RELATED_TO"));

    private final ChatClient chatClient;
    private final PromptTemplate extractPromptTemplate;
    private final ObjectMapper objectMapper;

    public GraphExtractionService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.extractPromptTemplate = PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/graph/extract.st"))
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 从一份资料原文中抽取知识点与关系。
     *
     * @param documentName 资料文件名，用于提示词与溯源
     * @param content      资料原文
     * @return 抽取结果，可能为空
     */
    public ExtractedGraph extract(String documentName, String content) {
        if (!StringUtils.hasText(content)) {
            return ExtractedGraph.empty();
        }

        String prompt = extractPromptTemplate.render(Map.of(
                "documentName", StringUtils.hasText(documentName) ? documentName : "未命名资料",
                "maxEdges", String.valueOf(MAX_EDGES_PER_DOCUMENT),
                "content", truncate(content, MAX_CONTENT_CHARS)
        ));

        String raw;
        try {
            raw = chatClient.prompt().user(prompt).call().content();
        } catch (Exception exception) {
            log.warn("知识图谱抽取调用失败: document={}, 错误={}", documentName, exception.getMessage());
            return ExtractedGraph.empty();
        }
        return parse(raw);
    }

    /**
     * 解析模型返回的 JSON 对象。
     *
     * @param raw 模型原始输出
     * @return 抽取结果
     */
    private ExtractedGraph parse(String raw) {
        String json = stripCodeFence(raw);
        if (!StringUtils.hasText(json)) {
            return ExtractedGraph.empty();
        }
        // 容错：模型偶尔会在 JSON 前后加说明文字，截取首个 { 到最后一个 }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return ExtractedGraph.empty();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json.substring(start, end + 1));
        } catch (Exception exception) {
            log.warn("知识图谱抽取结果无法解析为 JSON，长度={}, 错误={}", json.length(), exception.getMessage());
            return ExtractedGraph.empty();
        }

        List<ExtractedNode> nodes = parseNodes(root.path("nodes"));
        List<ExtractedEdge> edges = parseEdges(root.path("edges"));
        return new ExtractedGraph(nodes, edges);
    }

    /** 解析节点数组 */
    private List<ExtractedNode> parseNodes(JsonNode array) {
        List<ExtractedNode> nodes = new ArrayList<>();
        if (!array.isArray()) {
            return nodes;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : array) {
            String name = text(item.path("name"));
            if (!StringUtils.hasText(name) || name.length() < 2 || name.length() > 60) {
                continue;
            }
            String key = normalizeKey(name);
            if (!seen.add(key)) {
                continue;
            }
            String summary = text(item.path("summary"));
            int importance = clamp(item.path("importance").asInt(5), 1, 10);
            nodes.add(new ExtractedNode(name, key, truncate(summary, 400), importance));
        }
        return nodes;
    }

    /** 解析关系数组 */
    private List<ExtractedEdge> parseEdges(JsonNode array) {
        List<ExtractedEdge> edges = new ArrayList<>();
        if (!array.isArray()) {
            return edges;
        }
        for (JsonNode item : array) {
            String source = text(item.path("source"));
            String target = text(item.path("target"));
            if (!StringUtils.hasText(source) || !StringUtils.hasText(target)) {
                continue;
            }
            if (normalizeKey(source).equals(normalizeKey(target))) {
                continue;
            }
            String relation = text(item.path("relation")).toUpperCase();
            if (!VALID_RELATIONS.contains(relation)) {
                relation = "RELATED_TO";
            }
            String evidence = truncate(text(item.path("evidence")), 500);
            edges.add(new ExtractedEdge(normalizeKey(source), normalizeKey(target), relation, evidence));
        }
        return edges;
    }

    /**
     * 概念名归一化：去空白、去常见标点、转小写。
     *
     * <p>多份资料对同一概念的写法常有差异（全半角括号、空格、书名号），
     * 归一化后即可合并为同一节点。</p>
     *
     * @param name 原始概念名
     * @return 归一化键
     */
    public static String normalizeKey(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase()
                .replaceAll("[\\s\\u3000]", "")
                .replaceAll("[()（）\\[\\]【】《》\"'“”‘’·,，。.、;；:：!！?？/\\\\|]", "");
    }

    /** 去掉模型可能包裹的 ``` 代码围栏 */
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 单份资料的抽取结果。
     *
     * @param nodes 知识点列表
     * @param edges 关系列表（以归一化键引用两端）
     */
    public record ExtractedGraph(List<ExtractedNode> nodes, List<ExtractedEdge> edges) {

        /** 空结果 */
        public static ExtractedGraph empty() {
            return new ExtractedGraph(List.of(), List.of());
        }
    }

    /**
     * 抽取出的知识点。
     *
     * @param name       知识点名称
     * @param key        归一化键
     * @param summary    释义
     * @param importance 重要度 1-10
     */
    public record ExtractedNode(String name, String key, String summary, int importance) {
    }

    /**
     * 抽取出的关系。
     *
     * @param sourceKey 起点归一化键
     * @param targetKey 终点归一化键
     * @param relation  关系类型
     * @param evidence  原文依据
     */
    public record ExtractedEdge(String sourceKey, String targetKey, String relation, String evidence) {
    }
}
