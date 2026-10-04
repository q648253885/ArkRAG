package com.arkrag.server.mcp;

import com.arkrag.core.embedding.EmbeddingService;
import com.arkrag.core.exception.ArkRagException;
import com.arkrag.core.model.SearchHit;
import com.arkrag.core.search.SearchService;
import com.arkrag.core.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具面（契约 §5.2）：rag_search / rag_list_kbs。
 * 同一份 SyncToolSpecification 同时装配给 SSE 与 Streamable 两个服务器实例，
 * 保证两种传输的工具行为完全一致（单一真相源）。
 * 工具失败 → CallToolResult(isError=true) + 人话提示（模型可读、可行动）。
 */
public final class McpTools {

    private static final Logger LOG = LoggerFactory.getLogger(McpTools.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private McpTools() {
    }

    /** 构建全部工具规范（SSE 与 Streamable 共用同一列表实例内容）。 */
    public static List<McpServerFeatures.SyncToolSpecification> all(
            SearchService searchService, KnowledgeBaseService kbService, EmbeddingService embeddingService,
            com.arkrag.core.ask.AskService askService) {
        return List.of(ragSearch(searchService), ragListKbs(kbService, embeddingService), ragAsk(askService));
    }

    /** rag_search：知识库语义检索。 */
    private static McpServerFeatures.SyncToolSpecification ragSearch(SearchService searchService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "检索问题（自然语言，1..2000 字符）"),
                        "kb_ids", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "限定知识库 id 列表；缺省检索全部"),
                        "top_k", Map.of("type", "integer", "description", "返回条数 1..50，默认 5")),
                "required", List.of("query"));
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_search")
                .description("在用户的本地知识库（PDF/Word/Markdown 等文档）中做语义检索，"
                        + "返回最相关的文本片段与出处（知识库/文档/块序号/相似度）。"
                        + "回答事实性问题、引用用户资料时优先使用本工具。")
                .inputSchema(schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> safeCall(() -> {
                    JsonNode args = JSON.readTree(JSON.writeValueAsString(request.arguments()));
                    String query = args.path("query").asText("");
                    List<String> kbIds = new ArrayList<>();
                    args.path("kb_ids").forEach(n -> kbIds.add(n.asText()));
                    Integer topK = args.has("top_k") ? args.path("top_k").asInt() : null;
                    long t0 = System.currentTimeMillis();
                    List<SearchHit> hits = searchService.search(query,
                            kbIds.isEmpty() ? null : kbIds, topK, null);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("hits", hits.stream().map(McpTools::hitMap).toList());
                    out.put("took_ms", System.currentTimeMillis() - t0);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out))
                            .build();
                }))
                .build();
    }

    /** rag_list_kbs：列出全部知识库。 */
    private static McpServerFeatures.SyncToolSpecification ragListKbs(
            KnowledgeBaseService kbService, EmbeddingService embeddingService) {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_list_kbs")
                .description("列出可用的本地知识库（名称、描述、文档数、块数）。检索前可用它确认目标知识库 id。")
                .inputSchema(schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> safeCall(() -> {
                    List<Map<String, Object>> kbs = new ArrayList<>();
                    for (var kb : kbService.listAll()) {
                        long[] stats = kbService.stats(kb.getId());
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("id", kb.getId());
                        o.put("name", kb.getName());
                        o.put("description", kb.getDescription());
                        o.put("document_count", stats[0]);
                        o.put("chunk_count", stats[1]);
                        kbs.add(o);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("embedding_configured", embeddingService.isConfigured());
                    out.put("knowledge_bases", kbs);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out))
                            .build();
                }))
                .build();
    }

    /** rag_ask：知识库问答（检索 + Chat 生成带引用答案，v1.1）。 */
    private static McpServerFeatures.SyncToolSpecification ragAsk(com.arkrag.core.ask.AskService askService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "问题（自然语言，1..2000 字符）"),
                        "kb_ids", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "限定知识库 id 列表；缺省检索全部"),
                        "top_k", Map.of("type", "integer", "description", "召回条数 1..50，默认 5")),
                "required", List.of("query"));
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_ask")
                .description("基于用户本地知识库回答问题：先语义检索相关资料，再由 Chat 模型生成带 [n] 引用编号的答案。"
                        + "需要基于用户文档给出结论性回答（而非仅列出片段）时优先使用本工具；"
                        + "服务端需已配置 Chat 模型（未配置时返回配置指引）。")
                .inputSchema(schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> safeCall(() -> {
                    JsonNode args = JSON.readTree(JSON.writeValueAsString(request.arguments()));
                    String query = args.path("query").asText("");
                    List<String> kbIds = new ArrayList<>();
                    args.path("kb_ids").forEach(n -> kbIds.add(n.asText()));
                    Integer topK = args.has("top_k") ? args.path("top_k").asInt() : null;
                    var result = askService.ask(query, kbIds.isEmpty() ? null : kbIds, topK);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("answer", result.answer());
                    out.put("citations", com.arkrag.core.ask.AskService.citationsMap(result.citations()));
                    out.put("took_ms", result.tookMs());
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out))
                            .build();
                }))
                .build();
    }

    private static Map<String, Object> hitMap(SearchHit h) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("kb_id", h.kbId());
        o.put("kb_name", h.kbName());
        o.put("doc_id", h.docId());
        o.put("doc_name", h.docName());
        o.put("chunk_index", h.chunkIndex());
        o.put("text", h.text());
        o.put("score", Math.round(h.score() * 1000.0) / 1000.0);
        return o;
    }

    /** 统一把业务异常转为 isError 结果（模型可读）；意外异常记日志返回通用文案。 */
    private static McpSchema.CallToolResult safeCall(ThrowingSupplier<McpSchema.CallToolResult> body) {
        try {
            return body.get();
        } catch (ArkRagException e) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent(humanize(e))
                    .build();
        } catch (Exception e) {
            LOG.error("MCP 工具执行失败", e);
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("工具执行失败：" + e.getMessage())
                    .build();
        }
    }

    /** 错误码 → 可行动的人话（契约 §5.2）。 */
    static String humanize(ArkRagException e) {
        return switch (e.code()) {
            case EMBEDDING_NOT_CONFIGURED -> "检索失败：ArkRAG 服务未配置 Embedding 模型。"
                    + "请在控制台「模型配置」页（或 application.yml 的 arkrag.embedding）配置后重试。";
            case CHAT_NOT_CONFIGURED -> "问答失败：ArkRAG 服务未配置 Chat 模型。"
                    + "请在控制台「模型配置」页配置 chat（base-url / api-key / model）后重试；"
                    + "或改用 rag_search 仅检索相关片段。";
            case EMBEDDING_DIMENSION_MISMATCH -> "检索失败：" + e.getMessage();
            case VALIDATION -> "参数错误：" + e.getMessage();
            case KB_NOT_FOUND -> "知识库不存在：" + e.getMessage();
            default -> "检索失败：" + e.getMessage();
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
