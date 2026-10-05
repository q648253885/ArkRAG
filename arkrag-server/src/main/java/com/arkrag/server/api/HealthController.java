package com.arkrag.server.api;

import com.arkrag.core.config.ArkRagConfig;
import com.arkrag.core.embedding.EmbeddingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /api/v1/health — 免鉴权探活与配置自检（插件"测试连接"、运维排查用）。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final EmbeddingService embeddingService;
    private final ArkRagConfig config;
    private final String version;

    public HealthController(EmbeddingService embeddingService, ArkRagConfig config) {
        this.embeddingService = embeddingService;
        this.config = config;
        // Boot 嵌套 jar 里 getImplementationVersion() 返回 null：回退到构建时传入的版本常量
        this.version = HealthController.class.getPackage().getImplementationVersion() != null
                ? HealthController.class.getPackage().getImplementationVersion() : "1.1.2";
    }

    /**
     * @return 服务状态、版本、Embedding 配置自检、存储类型（契约 §5.1）
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        var embedding = config.embedding();
        return Map.of(
                "status", "UP",
                "version", version,
                "embedding", Map.of(
                        "configured", embeddingService.isConfigured(),
                        "model", embeddingService.isConfigured() ? embeddingService.modelName() : null,
                        "dims", embedding.dims() != null ? embedding.dims() : 0),
                "store", Map.of("type", "inmemory"));
    }
}
