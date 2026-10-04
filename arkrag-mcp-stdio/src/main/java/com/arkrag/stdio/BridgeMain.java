package com.arkrag.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * ArkRAG MCP stdio 桥（06 设计：契约 §5.2/§5.3）。
 *
 * 设计要点：initialize / tools/list **不依赖服务在线**（静态工具清单）——
 * ArkWork 启动即拉起桥并握手，若桥因服务离线而握手失败，工具永远进不了模型。
 * 只有 tools/call 实时代理到服务，失败映射为 isError + 修复指引。
 *
 * 桥是纯代理：不持有索引、不读写数据目录，避免与 server 双进程写同一索引。
 * 日志必须走 stderr（stdout 是 JSON-RPC 通道，任何 stdout 输出都会破坏协议帧）。
 */
public final class BridgeMain {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeMain.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private BridgeMain() {
    }

    public static void main(String[] args) {
        ArkRagClient client;
        try {
            client = new ArkRagClient();
        } catch (Exception e) {
            LOG.error("stdio 桥启动失败：{}", e.getMessage());
            System.exit(2);
            return;
        }
        // 预解析 token 配置错误（如 TOKEN_FILE 不存在）提前暴露，给出明确退出码
        LOG.info("ArkRAG stdio 桥启动：target={}", client.baseUrl());

        McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper);

        McpSchema.Tool searchTool = McpSchema.Tool.builder()
                .name("rag_search")
                .description("在用户的本地知识库（PDF/Word/Markdown 等文档）中做语义检索，"
                        + "返回最相关的文本片段与出处（知识库/文档/块序号/相似度）。"
                        + "回答事实性问题、引用用户资料时优先使用本工具。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "检索问题（自然语言，1..2000 字符）"),
                                "kb_ids", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "限定知识库 id 列表；缺省检索全部"),
                                "top_k", Map.of("type", "integer",
                                        "description", "返回条数 1..50，默认 5")),
                        "required", List.of("query")))
                .build();

        McpSchema.Tool listTool = McpSchema.Tool.builder()
                .name("rag_list_kbs")
                .description("列出可用的本地知识库（名称、描述、文档数、块数）。检索前可用它确认目标知识库 id。")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();

        McpSchema.Tool askTool = McpSchema.Tool.builder()
                .name("rag_ask")
                .description("基于用户本地知识库回答问题：先语义检索相关资料，再由 Chat 模型生成带 [n] 引用编号的答案。"
                        + "需要基于用户文档给出结论性回答（而非仅列出片段）时优先使用本工具；"
                        + "服务端需已配置 Chat 模型（未配置时返回配置指引）。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "问题（自然语言，1..2000 字符）"),
                                "kb_ids", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "限定知识库 id 列表；缺省检索全部"),
                                "top_k", Map.of("type", "integer",
                                        "description", "召回条数 1..50，默认 5")),
                        "required", List.of("query")))
                .build();

        List<McpServerFeatures.SyncToolSpecification> specs = List.of(
                McpServerFeatures.SyncToolSpecification.builder()
                        .tool(searchTool)
                        .callHandler(handler(client, "search"))
                        .build(),
                McpServerFeatures.SyncToolSpecification.builder()
                        .tool(listTool)
                        .callHandler(handler(client, "list"))
                        .build(),
                McpServerFeatures.SyncToolSpecification.builder()
                        .tool(askTool)
                        .callHandler(handler(client, "ask"))
                        .build());

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("arkrag", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(specs)
                .build();

        // 进程生命周期跟随 stdio：transport 线程关闭时结束进程
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        LOG.info("stdio 桥就绪（等待 MCP 客户端 initialize）");
    }

    /** 工具处理器：代理到服务 HTTP，失败 → isError + 人话。 */
    private static BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler(
            ArkRagClient client, String kind) {
        return (exchange, request) -> {
            try {
                JsonNode result = "search".equals(kind)
                        ? client.search(buildSearchBody(request.arguments()))
                        : "ask".equals(kind)
                                ? client.ask(buildAskBody(request.arguments()))
                                : client.listKbs();
                return McpSchema.CallToolResult.builder()
                        .addTextContent(pretty(result))
                        .build();
            } catch (ArkRagClient.BridgeException e) {
                return error(e.getMessage());
            } catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) {
                return error(ArkRagClient.unreachable(e).getMessage());
            } catch (Exception e) {
                LOG.error("工具调用失败", e);
                return error("工具调用失败：" + e.getMessage());
            }
        };
    }

    /** 组装 /api/v1/search 请求体（校验最简约束，完整校验由服务端负责）。 */
    private static String buildSearchBody(Map<String, Object> args) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        String query = String.valueOf(args.getOrDefault("query", ""));
        if (query.isBlank()) {
            throw new ArkRagClient.BridgeException("参数错误：query 不能为空");
        }
        body.put("query", query);
        Object kbIds = args.get("kb_ids");
        if (kbIds != null) {
            body.put("kbIds", kbIds);
        }
        Object topK = args.get("top_k");
        if (topK != null) {
            body.put("topK", topK);
        }
        return JSON.writeValueAsString(body);
    }

    /** 组装 /api/v1/ask 请求体（校验最简约束，完整校验由服务端负责）。 */
    private static String buildAskBody(Map<String, Object> args) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        String query = String.valueOf(args.getOrDefault("query", ""));
        if (query.isBlank()) {
            throw new ArkRagClient.BridgeException("参数错误：query 不能为空");
        }
        body.put("query", query);
        Object kbIds = args.get("kb_ids");
        if (kbIds != null) {
            body.put("kbIds", kbIds);
        }
        Object topK = args.get("top_k");
        if (topK != null) {
            body.put("topK", topK);
        }
        return JSON.writeValueAsString(body);
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
    }

    private static String pretty(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }
}
