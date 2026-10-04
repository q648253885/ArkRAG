package com.arkrag.server.mcp;

import com.arkrag.core.embedding.EmbeddingService;
import com.arkrag.core.search.SearchService;
import com.arkrag.core.service.KnowledgeBaseService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP over HTTP 装配（契约 §5.2）：
 *  - /mcp        Streamable HTTP（spec 2025-06-18，新客户端）
 *  - /mcp/sse    SSE（旧客户端兼容）+ /mcp/message（SSE 消息回传端点）
 * 两个传输各自构建 McpSyncServer 实例，但共享同一份工具规范 —— 行为一致、单一真相源。
 * 鉴权由 AuthFilter 统一覆盖（/mcp* 路径），支持 Bearer 与 X-Api-Key。
 */
@Configuration
public class McpHttpConfig {

    @Bean
    public McpJsonMapper mcpJsonMapper() {
        return new JacksonMcpJsonMapper(JsonMapper.builder().build());
    }

    @Bean
    public HttpServletSseServerTransportProvider sseTransport(McpJsonMapper jsonMapper) {
        return HttpServletSseServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .baseUrl("")
                .sseEndpoint("/mcp/sse")
                .messageEndpoint("/mcp/message")
                .build();
    }

    @Bean
    public HttpServletStreamableServerTransportProvider streamableTransport(McpJsonMapper jsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean
    public McpSyncServer sseMcpServer(HttpServletSseServerTransportProvider transport,
                                      SearchService searchService, KnowledgeBaseService kbService,
                                      EmbeddingService embeddingService,
                                      com.arkrag.core.ask.AskService askService) {
        return McpServer.sync(transport)
                .serverInfo("arkrag", "1.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(McpTools.all(searchService, kbService, embeddingService, askService))
                .build();
    }

    @Bean
    public McpSyncServer streamableMcpServer(HttpServletStreamableServerTransportProvider transport,
                                             SearchService searchService, KnowledgeBaseService kbService,
                                             EmbeddingService embeddingService,
                                             com.arkrag.core.ask.AskService askService) {
        return McpServer.sync(transport)
                .serverInfo("arkrag", "1.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(McpTools.all(searchService, kbService, embeddingService, askService))
                .build();
    }

    /** SSE 传输的 servlet 注册：同时覆盖 SSE 流端点与消息端点。 */
    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> sseServlet(
            HttpServletSseServerTransportProvider sseTransport) {
        return new ServletRegistrationBean<>(sseTransport, "/mcp/sse", "/mcp/message");
    }

    /** Streamable 传输的 servlet 注册。 */
    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> streamableServlet(
            HttpServletStreamableServerTransportProvider streamableTransport) {
        return new ServletRegistrationBean<>(streamableTransport, "/mcp");
    }
}
